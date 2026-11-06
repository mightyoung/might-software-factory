package com.mightyoung.softwarefactory.auditops;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** Source contracts only: does not parse/apply SQL or establish actual PostgreSQL trigger behavior. */
class TenantAuditQueryCapabilityMigrationTest {
    private static final UUID CAPABILITY=UUID.fromString("8c987ea3-631b-5512-bd41-ced3e29eee46");

    @Test void theReservedIdentifierIsAnIndependentlyComputedDnsUuidV5() throws Exception {
        UUID namespace=UUID.fromString("6ba7b810-9dad-11d1-80b4-00c04fd430c8");
        var namespaceBytes=ByteBuffer.allocate(16).putLong(namespace.getMostSignificantBits()).putLong(namespace.getLeastSignificantBits()).array();
        MessageDigest sha1=MessageDigest.getInstance("SHA-1");sha1.update(namespaceBytes);
        byte[] hash=sha1.digest("might-software-factory/capability/audit_event.read".getBytes(StandardCharsets.UTF_8));
        hash[6]=(byte)((hash[6]&0x0f)|0x50);hash[8]=(byte)((hash[8]&0x3f)|0x80);
        var bytes=ByteBuffer.wrap(hash);UUID computed=new UUID(bytes.getLong(),bytes.getLong());
        assertThat(computed).isEqualTo(CAPABILITY);assertThat(computed.version()).isEqualTo(5);assertThat(computed.variant()).isEqualTo(2);
        assertThat(source()).contains(CAPABILITY.toString(),"'audit_event.read'","'audit_event'","'read'");
    }

    @Test void theValidatedKindConstraintKeepsAllSixExistingKindsAndAddsOnlyAuditTenant() throws IOException {
        String sql=statements(source());
        var match=Pattern.compile("add constraint scope_rule_kind_check\\s+check\\s*\\(kind\\s+in\\s*\\(([^)]*)\\)\\)[^;]*;",Pattern.CASE_INSENSITIVE).matcher(sql);
        assertThat(match.find()).as("replacement scope kind constraint").isTrue();
        assertThat(captures(match.group(1),"'([^']+)'" )).containsExactly("SELF","DEPT","DEPT_TREE","EXPLICIT","RELATION","IDENTITY_TENANT","AUDIT_TENANT");
        assertThat(match.group()).doesNotContainIgnoringCase("not valid");
    }

    @Test void onlyTheGlobalCapabilityIsSeededAndTheTenantTimeIdIndexIsOrdinary() throws IOException {
        String sql=statements(source()).toLowerCase(Locale.ROOT);
        assertThat(captures(sql,"insert\\s+into\\s+public\\.(\\w+)" )).containsExactly("permission");
        assertThat(sql).doesNotContain("create role","alter role","grant ","row level security","create policy","drop policy","truncate ",
                "update public.permission","delete from public.permission","create index concurrently","create index if not exists");
        assertThat(Pattern.compile("create index audit_event_tenant_time_id_idx\\s+on public\\.audit_event\\s*\\(tenant_id\\s*,\\s*occurred_at desc\\s*,\\s*id desc\\s*\\)",Pattern.CASE_INSENSITIVE).matcher(sql).find()).isTrue();
    }

    @Test void theExistingIdentityMigrationAndItsFunctionsAreNotRewritten() throws Exception {
        byte[] old=resource("V19__identity_lifecycle_capabilities.sql");
        assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(old)))
                .isEqualTo("7c402d453bfd9f1d830e399a630c94580de860fd3ec82c8b5b1b633b26da40e3");
        String sql=statements(source()).toLowerCase(Locale.ROOT);
        var oldFunctions=captures(new String(old,StandardCharsets.UTF_8).toLowerCase(Locale.ROOT),"create function public\\.(\\w+)\\(");
        var created=captures(sql,"create function public\\.(\\w+)\\(");
        assertThat(created).hasSize(4).doesNotContainAnyElementsOf(oldFunctions);
        assertThat(sql).doesNotContain("create or replace function","drop function","disable trigger","drop trigger");
    }

    @Test void completeVisibilityAndCatalogPreflightRunUnderFixedLocksBeforeInstallingOrSeeding() throws IOException {
        String sql=source();String compact=statements(sql);String preflight=statements(block(sql,"preflight"));
        var locks=captures(compact,"lock table public\\.(\\w+) in (?:access exclusive|share) mode;");
        assertThat(locks).containsExactly("permission","scope_rule","role_permission","scope_org","audit_event");
        assertThat(compact.indexOf("lock table public.audit_event in share mode;")).isLessThan(compact.indexOf("do $preflight$"));
        assertThat(compact.indexOf("end $preflight$;")).isLessThan(compact.indexOf("alter table public.scope_rule drop constraint"));
        assertThat(compact.indexOf("end $identity_baseline$;")).isLessThan(compact.indexOf("alter table public.scope_rule drop constraint"));
        assertThat(compact.indexOf("end $preflight$;")).isLessThan(compact.indexOf("insert into public.permission"));
        for(String table:List.of("permission","scope_rule","role_permission","scope_org"))
            assertThat(preflight).contains("pg_catalog.row_security_active('public."+table+"'::regclass)");
        assertThat(preflight).contains("a.attnum>0 and a.attnotnull and not a.attisdropped",
                "n.conrelid=a.attrelid and n.contype='n'",
                "n.conkey=array[a.attnum]::smallint[] and n.convalidated and n.conenforced",
                "c.contype='c' and c.convalidated and c.conenforced",
                "not actual.convalidated or not actual.conenforced","actual.condeferrable",
                "actual.confdeltype is distinct from 'r'","actual.confrelid is distinct from",
                "is distinct from expected.source_columns","is distinct from expected.target_columns",
                "left join public.permission p on p.id=rp.permission_id","left join public.scope_rule s on s.tenant_id=rp.tenant_id and s.id=rp.scope_rule_id",
                "p.id is null or (rp.scope_rule_id is not null and s.id is null)","p.resource_type is distinct from 'audit_event'",
                "pg_catalog.to_regprocedure(","pg_catalog.pg_trigger","pg_catalog.to_regclass('public.audit_event_tenant_time_id_idx')");
        assertThat(preflight).contains(CAPABILITY.toString(),"s.definition is distinct from '{}'::jsonb",
                "p.code is distinct from 'audit_event.read'","p.action is distinct from 'read'",
                "(p.code is not distinct from 'audit_event.read') is distinct from (s.kind is not distinct from 'AUDIT_TENANT')",
                "(s.kind is not distinct from 'IDENTITY_TENANT')");
        for(String name:List.of("role_permission_permission_id_fkey","role_permission_scope_fkey","role_permission_role_fkey","scope_org_rule_fkey","scope_org_org_fkey"))
            assertThat(preflight).contains("'"+name+"'");
        assertThat(preflight).contains("not c.relrowsecurity or not c.relforcerowsecurity",
                "c.oid='public.permission'::regclass and (c.relrowsecurity or c.relforcerowsecurity)",
                "'public.scope_rule'::regclass,'public.role_permission'::regclass,'public.scope_org'::regclass,'public.audit_event'::regclass",
                "pg_catalog.pg_get_expr(c.conbin,c.conrelid,false)='(definition = ''{}''::jsonb)'");
        assertThat(preflight).doesNotContain("insert into","update public.","delete from","repair","on conflict");
    }

    @Test void reservedPermissionAndAuditScopeParentsCannotBeRenamedConvertedOrMutated() throws IOException {
        String sql=source();String permission=statements(function(sql,"guard_audit_query_permission"));
        assertThat(permission).contains("if tg_op='DELETE' then","old.code='audit_event.read'","new.code='audit_event.read'",
                "old.id='"+CAPABILITY+"'::uuid","new.id='"+CAPABILITY+"'::uuid","new is distinct from old",
                "new.code is distinct from 'audit_event.read'","new.resource_type is distinct from 'audit_event'","new.action is distinct from 'read'");
        String scope=statements(function(sql,"guard_audit_tenant_scope"));
        assertThat(scope).contains("old.kind='AUDIT_TENANT' or new.kind='AUDIT_TENANT'","new.kind is distinct from old.kind",
                "new.tenant_id is distinct from old.tenant_id","new.id is distinct from old.id","new.definition is distinct from '{}'::jsonb");
        assertTrigger(sql,"permission","guard_audit_query_permission",true);
        assertTrigger(sql,"scope_rule","guard_audit_tenant_scope",false);
    }

    @Test void childGuardsRejectMissingParentsAndUseTwoValuedBidirectionalPairing() throws IOException {
        String sql=source();String org=statements(function(sql,"guard_audit_tenant_scope_org"));
        assertThat(org).contains("from public.scope_rule s where s.tenant_id=new.tenant_id and s.id=new.scope_rule_id for key share;",
                "if not found then raise exception","scope_kind is not distinct from 'AUDIT_TENANT'");
        assertThat(org.indexOf("if not found")).isLessThan(org.indexOf("if scope_kind"));
        String pairing=statements(function(sql,"guard_audit_query_role_permission"));
        assertThat(pairing).contains("from public.permission p where p.id=new.permission_id for key share;","if new.scope_rule_id is not null then",
                "from public.scope_rule s where s.tenant_id=new.tenant_id and s.id=new.scope_rule_id for key share;",
                "(permission_code is not distinct from 'audit_event.read') is distinct from (scope_kind is not distinct from 'AUDIT_TENANT')",
                "resource_kind is distinct from 'audit_event' or permission_action is distinct from 'read'");
        assertThat(captures(pairing,"(if not found then raise exception)")).hasSize(2);
        assertThat(pairing.indexOf("permission parent is unavailable")).isLessThan(pairing.indexOf("if new.scope_rule_id"));
        assertThat(pairing.indexOf("scope parent is unavailable")).isLessThan(pairing.indexOf("if (permission_code"));
        // Parent locks prevent a visible non-audit parent being deleted/recreated as audit before FK checks.
        assertThat(captures(org+pairing,"(for key share)")).hasSize(3);
        assertThat(org+pairing).doesNotContain("for update","security definer","insert into","update public.");
        assertTrigger(sql,"scope_org","guard_audit_tenant_scope_org",false);
        assertTrigger(sql,"role_permission","guard_audit_query_role_permission",false);
        for(String guard:List.of("guard_audit_query_permission","guard_audit_tenant_scope","guard_audit_tenant_scope_org","guard_audit_query_role_permission")){
            String declaration=declaration(sql,guard);
            assertThat(declaration).contains("set search_path = pg_catalog, public").doesNotContain("security definer");
        }
    }

    @Test void legacyGuardBodiesAreOnlyCatalogExpectationsAndSeedKeepsEquivalentLegacyIds() throws IOException {
        String sql=source();String old=new String(resource("V19__identity_lifecycle_capabilities.sql"),StandardCharsets.UTF_8);
        String baseline=block(sql,"identity_baseline");
        var matcher=Pattern.compile("\\('([^']+)','(guard_identity_\\w+)',(\\d+),\\$identity_body\\$([\\s\\S]*?)\\$identity_body\\$\\)").matcher(baseline);
        var names=new ArrayList<String>();
        while(matcher.find()){
            String name=matcher.group(2);names.add(name);assertThat(matcher.group(4)).as("unchanged %s expectation",name).isEqualTo(function(old,name));
            assertThat(Integer.parseInt(matcher.group(3))).isEqualTo(name.equals("guard_identity_invitation_permission")?31:23);
        }
        assertThat(names).containsExactly("guard_identity_tenant_scope","guard_identity_tenant_scope_org",
                "guard_identity_invitation_permission","guard_identity_invitation_role_permission");
        assertThat(statements(baseline)).contains("existing_function.prosrc is distinct from expected.function_body","existing_function.prosecdef",
                "existing_function.proconfig is not null","existing_function.provolatile is distinct from 'v'",
                "existing_function.prokind is distinct from 'f'","existing_function.proretset","existing_function.proparallel is distinct from 'u'",
                "existing_function.prorettype is distinct from 'trigger'::regtype",
                "existing_function.prolang is distinct from (select oid from pg_catalog.pg_language where lanname='plpgsql')",
                "t.tgfoid=existing_function.oid and t.tgtype=expected.trigger_type",
                "t.tgenabled in ('O','A')","not t.tgisinternal and t.tgnargs=0 and t.tgqual is null and t.tgattr=''::int2vector");
        String seed=statements(block(sql,"capability_seed"));
        assertThat(seed).contains("if not exists(select 1 from public.permission where code='audit_event.read') then",
                "values('"+CAPABILITY+"','audit_event.read','audit_event','read')");
        assertThat(seed).doesNotContain("update ","delete ","on conflict","role_permission","scope_rule","role_grant");
    }

    private static String source() throws IOException {return new String(resource("V23__tenant_audit_query_capability.sql"),StandardCharsets.UTF_8);}
    private static byte[] resource(String name) throws IOException {
        try(var input=TenantAuditQueryCapabilityMigrationTest.class.getResourceAsStream("/db/migration/"+name)){
            if(input==null)throw new IOException("Missing migration source");return input.readAllBytes();
        }
    }
    private static String statements(String sql){return sql.replaceAll("(?m)--[^\\r\\n]*","").replaceAll("\\s+"," ").trim();}
    private static List<String> captures(String sql,String regex){
        var result=new ArrayList<String>();var matcher=Pattern.compile(regex,Pattern.CASE_INSENSITIVE|Pattern.DOTALL).matcher(sql);
        while(matcher.find())result.add(matcher.group(1));return result;
    }
    private static String block(String sql,String tag){
        var matcher=Pattern.compile("do \\$"+Pattern.quote(tag)+"\\$([\\s\\S]*?)end \\$"+Pattern.quote(tag)+"\\$;",Pattern.CASE_INSENSITIVE).matcher(sql);
        assertThat(matcher.find()).as("DO %s",tag).isTrue();return matcher.group(1);
    }
    private static String function(String sql,String name){
        var matcher=Pattern.compile("create function public\\."+Pattern.quote(name)+"\\([^$]*?as \\$\\$([\\s\\S]*?)\\$\\$;",Pattern.CASE_INSENSITIVE).matcher(sql);
        assertThat(matcher.find()).as("function %s",name).isTrue();return matcher.group(1);
    }
    private static String declaration(String sql,String name){
        var matcher=Pattern.compile("create function public\\."+Pattern.quote(name)+"\\([^$]*?as \\$\\$",Pattern.CASE_INSENSITIVE).matcher(sql);
        assertThat(matcher.find()).as("declaration %s",name).isTrue();return statements(matcher.group());
    }
    private static void assertTrigger(String sql,String table,String function,boolean delete){
        String events=delete?"insert or update or delete":"insert or update";
        assertThat(statements(sql)).contains("create trigger "+function+" before "+events+" on public."+table+" for each row execute function public."+function+"();");
    }

}
