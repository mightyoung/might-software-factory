package com.mightyoung.softwarefactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.assertThat;

/** Read-only effective ACL checks against the reviewed, code-owned operator manifests. Never executes grants. */
final class D0RuntimePrivilegeAssertions {
    static final Set<String> FLOWABLE = Set.of(
        "act_ge_property","act_ge_bytearray","act_ru_entitylink","act_hi_entitylink","act_ru_identitylink","act_hi_identitylink",
        "act_ru_job","act_ru_timer_job","act_ru_suspended_job","act_ru_deadletter_job","act_ru_history_job","act_ru_external_job",
        "flw_ru_batch","flw_ru_batch_part","act_ru_task","act_hi_taskinst","act_hi_tsk_log","act_ru_variable","act_hi_varinst","act_ru_event_subscr",
        "act_hi_procinst","act_hi_actinst","act_hi_detail","act_hi_comment","act_hi_attachment",
        "act_re_deployment","act_re_model","act_ru_execution","act_re_procdef","act_evt_log","act_procdef_info","act_ru_actinst");
    record Rights(Set<String> table, Map<String,Set<String>> columns) { }

    static void assertSelectedFlowable(JdbcTemplate jdbc) {
        assertThat(jdbc.queryForList("select relname from pg_class where relnamespace='public'::regnamespace and relkind='r' and left(relname,4) in ('act_','flw_')",String.class))
            .as("Pinned Flowable8 common20 + engine7 + history5; IDM and event registry disabled").containsExactlyInAnyOrderElementsOf(FLOWABLE);
        for (String table:FLOWABLE) {
            assertThat(jdbc.queryForObject("select pg_get_userbyid(relowner)=current_user from pg_class where oid=cast(? as regclass)",Boolean.class,"public."+table)).isFalse();
            for (String op:List.of("SELECT","INSERT","UPDATE","DELETE","TRUNCATE","REFERENCES","TRIGGER","MAINTAIN")) {
                assertThat(jdbc.queryForObject("select has_table_privilege(current_user,cast(? as regclass),?)",Boolean.class,"public."+table,op))
                    .as("Engine %s %s",table,op).isEqualTo(Set.of("SELECT","INSERT","UPDATE","DELETE").contains(op));
            }
        }
    }

    static void assertApplication(JdbcTemplate jdbc) {
        assertApplication(jdbc, Map.of(), Map.of());
    }

    static void assertApplication(JdbcTemplate jdbc, Map<String,Rights> additionalTables, Map<String,Set<String>> additionalReadColumns) {
        String delta=resource("/db/operator/d0-runtime-grants.sql");
        String legacy=resource("/db/operator/d0-legacy-runtime-grants.sql");
        Map<String,Rights> expected=loadApplication(delta,legacy);
        for (var entry : additionalTables.entrySet()) {
            if (expected.putIfAbsent(entry.getKey(),entry.getValue()) != null)
                throw new IllegalArgumentException("Additional profile must not override D0: " + entry.getKey());
        }
        for (var entry : additionalReadColumns.entrySet())
            Objects.requireNonNull(expected.get(entry.getKey()),"Unknown read-column table").columns().get("SELECT").addAll(entry.getValue());
        assertThat(jdbc.queryForObject("select rolsuper or rolbypassrls or rolcreaterole or rolcreatedb or rolreplication from pg_roles where rolname=current_user",Boolean.class)).isFalse();
        assertThat(jdbc.queryForObject("select count(*) from pg_auth_members where member=(select oid from pg_roles where rolname=current_user)",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select has_schema_privilege(current_user,'public','CREATE') or has_database_privilege(current_user,current_database(),'CREATE,TEMP')",Boolean.class)).isFalse();
        assertThat(jdbc.queryForObject("select count(*) from pg_class c cross join lateral aclexplode(c.relacl) a where c.relnamespace='public'::regnamespace and a.grantee=(select oid from pg_roles where rolname=current_user) and a.is_grantable",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from pg_attribute col join pg_class c on c.oid=col.attrelid cross join lateral aclexplode(col.attacl) a where c.relnamespace='public'::regnamespace and a.grantee=(select oid from pg_roles where rolname=current_user) and a.is_grantable",Integer.class)).isZero();
        Set<String> seen=new HashSet<>();
        for (String table:jdbc.queryForList("select c.relname from pg_class c where c.relnamespace='public'::regnamespace and c.relkind in ('r','p','v','m','f','S')",String.class)) {
            Rights rights=expected.getOrDefault(table,empty());
            String qualified="public.\""+table.replace("\"","\"\"")+"\"";
            assertThat(jdbc.queryForObject("select pg_get_userbyid(relowner)=current_user from pg_class where oid=cast(? as regclass)",Boolean.class,qualified)).as("No runtime ownership: %s",table).isFalse();
            boolean sequence=Boolean.TRUE.equals(jdbc.queryForObject("select relkind='S' from pg_class where oid=cast(? as regclass)",Boolean.class,qualified));
            if(sequence) {
                assertThat(jdbc.queryForObject("select has_sequence_privilege(current_user,cast(? as regclass),'SELECT,UPDATE,USAGE')",Boolean.class,qualified)).as("No unregistered sequence grants").isFalse();
                continue;
            }
            seen.add(table);
            for(String op:List.of("SELECT","INSERT","UPDATE","DELETE","TRUNCATE","REFERENCES","TRIGGER","MAINTAIN")) {
                assertThat(jdbc.queryForObject("select has_table_privilege(current_user,cast(? as regclass),?)",Boolean.class,qualified,op))
                    .as("D0 table %s %s",table,op).isEqualTo(rights.table().contains(op));
            }
            for(String col:jdbc.queryForList("select attname from pg_attribute where attrelid=cast(? as regclass) and attnum>0 and not attisdropped",String.class,qualified)) {
                for(String op:List.of("SELECT","INSERT","UPDATE","REFERENCES")) {
                    assertThat(jdbc.queryForObject("select has_column_privilege(current_user,cast(? as regclass),?,?)",Boolean.class,qualified,col,op))
                        .as("D0 column %s.%s %s",table,col,op).isEqualTo(rights.table().contains(op)||rights.columns().get(op).contains(col));
                }
            }
        }
        assertThat(seen).containsAll(expected.keySet());
        // Explicit runtime routine grants must be exactly the reviewed ordinary helper signatures.
        var signatures=Pattern.compile("\\('([^']+)'\\)").matcher(delta.split("insert into d0_runtime_functions values",2)[1].split(";",2)[0]);
        Set<String> helpers=new HashSet<>();
        while(signatures.find()) helpers.add(jdbc.queryForObject("select to_regprocedure(?)::text",String.class,signatures.group(1)));
        assertThat(helpers).isNotEmpty().doesNotContainNull();
        Set<String> direct=new HashSet<>(jdbc.queryForList("select p.oid::regprocedure::text from pg_proc p cross join lateral aclexplode(p.proacl) a where p.pronamespace='public'::regnamespace and a.grantee=(select oid from pg_roles where rolname=current_user) and a.privilege_type='EXECUTE'",String.class));
        assertThat(direct).containsExactlyInAnyOrderElementsOf(helpers);
        assertThat(jdbc.queryForObject("select count(*) from pg_proc p cross join lateral aclexplode(p.proacl) a where p.pronamespace='public'::regnamespace and a.grantee=(select oid from pg_roles where rolname=current_user) and (a.is_grantable or p.prosecdef or p.prorettype='pg_catalog.trigger'::regtype)",Integer.class)).isZero();
    }

    static Map<String,Rights> loadApplication(String delta,String legacy) {
        Map<String,Rights> expected=loadLegacy(legacy);
        Map<String,Rights> additional=loadDelta(delta);
        assertThat(additional).hasSize(42);
        expected.putAll(additional);
        Pattern extra=Pattern.compile("grant (insert|update)\\(([^)]+)\\) on public\\.([a-z_]+) to",Pattern.CASE_INSENSITIVE);
        var match=extra.matcher(delta);
        while(match.find()) {
            Rights rights=Objects.requireNonNull(expected.get(match.group(3)),"Unknown identity grant table");
            rights.columns().get(match.group(1).toUpperCase(Locale.ROOT)).addAll(Arrays.asList(match.group(2).split(",")));
        }
        assertThat(delta).contains("grant insert(tenant_id,role_id,permission_id,scope_rule_id),delete on public.role_permission");
        expected.get("role_permission").table().add("DELETE");
        expected.get("role_permission").columns().get("INSERT").addAll(List.of("tenant_id","role_id","permission_id","scope_rule_id"));
        return expected;
    }
    static Map<String,Rights> loadDelta(String sql) {
        Map<String,Rights> map=new LinkedHashMap<>();
        var rows=Pattern.compile("^\\('([a-z_]+)',[0-9]+,ARRAY\\[([^]]*)\\]::text\\[\\],ARRAY\\[([^]]*)\\]::text\\[\\],ARRAY\\[([^]]*)\\]::text\\[\\]\\)",Pattern.MULTILINE).matcher(sql);
        while(rows.find()) { Rights r=empty();r.columns().get("SELECT").addAll(quoted(rows.group(2)));r.columns().get("INSERT").addAll(quoted(rows.group(3)));r.columns().get("UPDATE").addAll(quoted(rows.group(4)));if(map.put(rows.group(1),r)!=null)throw new IllegalArgumentException("Duplicate manifest table"); }
        return map;
    }
    static Map<String,Rights> loadLegacy(String sql) {
        Map<String,Rights> map=new LinkedHashMap<>();
        // Explicit identity base; subsequent exact identity deltas are parsed from the newer manifest.
        for(String t:List.of("account","principal","credential","tenant","tenant_application","tenant_member","org_unit","member_org","position","member_position","user_group","group_member","permission","role","scope_rule","role_permission","role_grant","scope_org")) map.put(t,rights(true,false,false,false,Set.of()));
        map.get("account").columns().get("UPDATE").add("credential_version");
        map.get("credential").columns().get("UPDATE").addAll(List.of("secret_hash","changed_at"));
        var explicit=Pattern.compile("\\('([a-z_]+)',(true|false),(true|false),ARRAY\\[([^]]*)\\](?:::text\\[\\])?\\)").matcher(sql);
        while(explicit.find()) map.put(explicit.group(1),rights(Boolean.parseBoolean(explicit.group(2)),Boolean.parseBoolean(explicit.group(3)),false,false,quoted(explicit.group(4))));
        String append=sql.split("-- Append-only records:",2)[1].split("-- The selected Flowable8",2)[0];
        String names=append.split("unnest\\(ARRAY\\[",2)[1].split("\\]\\)",2)[0];
        for(String t:quoted(names)) map.put(t,rights(true,true,false,false,Set.of()));
        // V6 immutable identity guards make this column safe for FOR SHARE/UPDATE locking.
        // Keep the reviewed delta explicit rather than accepting arbitrary manifest UPDATE statements.
        assertThat(sql).contains("update d0_legacy_runtime set update_columns=ARRAY['tenant_id']\n where table_name in ('file_object','file_link');");
        for(String t:List.of("file_object","file_link")) map.get(t).columns().get("UPDATE").add("tenant_id");
        for(String t:FLOWABLE) map.put(t,rights(true,true,true,true,Set.of()));
        for(String t:List.of("spring_session","spring_session_attributes","event_publication")) map.put(t,rights(true,true,true,true,Set.of()));
        return map;
    }
    static Rights rights(boolean select,boolean insert,boolean update,boolean delete,Set<String> updateColumns) {
        Rights r=empty();if(select)r.table().add("SELECT");if(insert)r.table().add("INSERT");if(update)r.table().add("UPDATE");if(delete)r.table().add("DELETE");r.columns().get("UPDATE").addAll(updateColumns);return r;
    }
    static Rights empty() { Map<String,Set<String>> cols=new HashMap<>();for(String op:List.of("SELECT","INSERT","UPDATE","REFERENCES"))cols.put(op,new HashSet<>());return new Rights(new HashSet<>(),cols); }
    static Set<String> quoted(String input) { Set<String> values=new HashSet<>();var matcher=Pattern.compile("'([a-z_][a-z0-9_]*)'").matcher(input);while(matcher.find())values.add(matcher.group(1));return values; }
    static String resource(String path) {
        try(var stream=D0RuntimePrivilegeAssertions.class.getResourceAsStream(path)) { if(stream==null)throw new IllegalStateException("Missing reviewed ACL manifest");return new String(stream.readAllBytes(),StandardCharsets.UTF_8); }
        catch(IOException e) { throw new IllegalStateException("Cannot read reviewed ACL manifest",e); }
    }
}
