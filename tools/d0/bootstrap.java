import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import org.flowable.engine.impl.cfg.StandaloneProcessEngineConfiguration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/** Explicit operator entry for isolated D0 databases. No migration, role provisioning or external delivery. */
class bootstrap {
    static final List<String> ACTORS = List.of("reporter", "person", "safety", "remediator", "admin");
    static final String SEED_NAME = "D0 isolated acceptance seed v1";
    static String database;
    static String tenantCode;
    static final BCryptPasswordEncoder PASSWORDS = new BCryptPasswordEncoder(12);

    public static void main(String[] args) {
        try {
            if (args.length != 1 || !Set.of("framework", "seed").contains(args[0])) {
                throw new IllegalArgumentException("Usage: java --class-path <backend-test-classpath> tools/d0/bootstrap.java framework|seed");
            }
            String url = required("D0_JDBC_URL");
            database = required("D0_DATABASE");
            checkTarget(url, database, required("D0_BOOTSTRAP_ACK"));
            tenantCode = System.getenv().getOrDefault("D0_TENANT_CODE", "d0seed");
            if (!tenantCode.matches("d0[a-z0-9-]{0,30}")) throw new IllegalArgumentException("D0 tenant code required");
            String user = required("D0_OPERATOR_USERNAME"), password = required("D0_OPERATOR_PASSWORD");
            try (Connection c = DriverManager.getConnection(url, user, password)) {
                verifyDatabase(c);
                if (args[0].equals("framework")) framework(c, url, user, password);
                else seed(c);
            }
            System.out.println("D0_BOOTSTRAP_" + args[0].toUpperCase(Locale.ROOT) + "_OK");
        } catch (Exception failure) {
            // Never print JDBC messages, SQL, parameter values or credentials.
            String sqlState = failure instanceof SQLException sql ? sql.getSQLState() : "n/a";
            System.err.println("D0_BOOTSTRAP_FAILED type=" + failure.getClass().getSimpleName() + " sqlState=" + sqlState);
            System.exit(1);
        }
    }

    static void checkTarget(String url, String db, String ack) {
        if (!db.matches("msf_d0_[a-z0-9_]+_test") || !ack.equals("isolated-d0-only:" + db)
                || !url.matches("jdbc:postgresql://(?:127\\.0\\.0\\.1|localhost|192\\.168\\.1\\.2):[0-9]{1,5}/" + db)) {
            throw new IllegalArgumentException("Explicit isolated D0 database and plain URL required");
        }
    }

    static void verifyDatabase(Connection c) throws SQLException {
        try (var s = c.createStatement(); var r = s.executeQuery("select current_database(), current_setting('server_version_num')::int")) {
            r.next();
            if (!database.equals(r.getString(1)) || r.getInt(2) < 180000 || r.getInt(2) >= 190000) {
                throw new IllegalArgumentException("Unexpected PostgreSQL database/version");
            }
        }
        try (var s = c.createStatement(); var r = s.executeQuery("select exists(select 1 from public.flyway_schema_history_app where version='26' and success)")) {
            r.next(); if (!r.getBoolean(1)) throw new IllegalStateException("Apply approved application migrations through V26 first");
        }
    }

    static void framework(Connection c, String url, String user, String password) throws Exception {
        // Let the pinned engine own its framework schema. Existing schemas are only validated.
        boolean anyEngine;
        try (var s = c.createStatement(); var r = s.executeQuery("select count(*) from information_schema.tables where table_schema='public' and table_name like 'act_%'")) {
            r.next(); anyEngine = r.getInt(1) != 0;
        }
        var cfg = new StandaloneProcessEngineConfiguration();
        cfg.setJdbcUrl(url).setJdbcUsername(user).setJdbcPassword(password).setJdbcDriver("org.postgresql.Driver")
            .setDatabaseSchemaUpdate(anyEngine ? "false" : "true").setAsyncExecutorActivate(false);
        cfg.setDisableIdmEngine(true); cfg.setDisableEventRegistry(true);
        cfg.buildProcessEngine().close();
        c.setAutoCommit(false);
        try {
            boolean session, attributes;
            try (var s = c.createStatement(); var r = s.executeQuery("select to_regclass('public.spring_session') is not null, to_regclass('public.spring_session_attributes') is not null")) {
                r.next(); session = r.getBoolean(1); attributes = r.getBoolean(2);
            }
            if (session != attributes) throw new IllegalStateException("Partial Spring Session schema");
            if (!session) {
                try (var input = bootstrap.class.getResourceAsStream("/org/springframework/session/jdbc/schema-postgresql.sql")) {
                    if (input == null) throw new IllegalStateException("Pinned Session schema missing from classpath");
                    try (var s = c.createStatement()) {
                        for (String sql : new String(input.readAllBytes(), StandardCharsets.UTF_8).split(";")) {
                            if (!sql.isBlank()) s.execute(sql);
                        }
                    }
                }
            }
            c.commit();
        } catch (Exception e) { c.rollback(); throw e; }
    }

    static void seed(Connection c) throws Exception {
        Map<String, String> secrets = new LinkedHashMap<>();
        for (String actor : ACTORS) {
            String value = required("D0_SEED_PASSWORD_" + actor.toUpperCase(Locale.ROOT));
            if (value.getBytes(StandardCharsets.UTF_8).length < 16 || value.getBytes(StandardCharsets.UTF_8).length > 72
                    || !StandardCharsets.UTF_8.newEncoder().canEncode(value)) throw new IllegalArgumentException("Password requires 16-72 UTF-8 bytes");
            secrets.put(actor, value);
        }
        c.setAutoCommit(false);
        c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        try {
            execute(c, "select pg_advisory_xact_lock(1297303111, 1)");
            execute(c, "select set_config('app.tenant_id', ?, true)", id("tenant").toString());
            boolean exists;
            try (var q = c.prepareStatement("select id, name from public.tenant where code=?")) {
                q.setString(1, tenantCode);
                try (var r = q.executeQuery()) {
                    exists = r.next();
                    if (exists && (!id("tenant").equals(r.getObject(1)) || !SEED_NAME.equals(r.getString(2)))) {
                        throw new IllegalStateException("Existing tenant is not owned by D0 seed");
                    }
                }
            }
            if (exists) { verifySeed(c, secrets); c.commit(); return; }
            execute(c, "insert into public.tenant(id,code,name,status,auth_epoch) values (?,?,?,'ACTIVE',1)", id("tenant"), tenantCode, SEED_NAME);
            for (String app : List.of("safety", "platform")) execute(c, "insert into public.tenant_application(tenant_id,application_key,enabled,version) values (?,?,true,1)", id("tenant"), app);
            execute(c, "insert into public.org_unit(tenant_id,id,code,name,active) values (?,?,'D0-ORG','D0 acceptance organization',true)", id("tenant"), id("org"));
            for (String actor : ACTORS) {
                execute(c, "insert into public.account(id,login_key,status,credential_version) values (?,?,'ACTIVE',1)", id(actor+"/account"), tenantCode+"-"+actor);
                execute(c, "insert into public.principal(id,kind,account_id,status) values (?,'HUMAN',?,'ACTIVE')", id(actor+"/principal"), id(actor+"/account"));
                execute(c, "insert into public.credential(account_id,kind,secret_hash,changed_at) values (?,'PASSWORD',?,current_timestamp)", id(actor+"/account"), "{bcrypt}"+PASSWORDS.encode(secrets.get(actor)));
                execute(c, "insert into public.tenant_member(tenant_id,id,account_id,status,auth_epoch,display_name) values (?,?,?,'ACTIVE',1,?)", id("tenant"), id(actor+"/member"), id(actor+"/account"), "D0 "+actor);
                execute(c, "insert into public.member_org(tenant_id,member_id,org_id,kind,valid_from) values (?,?,?,'PRIMARY',timestamptz '2026-01-01 00:00:00+00')", id("tenant"), id(actor+"/member"), id("org"));
                execute(c, "insert into public.\"role\"(tenant_id,id,code,name,active) values (?,?,?,?,true)", id("tenant"), id(actor+"/role"), "D0-"+actor, "D0 "+actor);
                execute(c, "insert into public.scope_rule(tenant_id,id,kind,version_no,definition) values (?,?,'EXPLICIT',1,'{}'::jsonb)", id("tenant"), id(actor+"/scope"));
                execute(c, "insert into public.scope_org(tenant_id,scope_rule_id,org_id) values (?,?,?)", id("tenant"), id(actor+"/scope"), id("org"));
                for (String permission : permissions(actor)) {
                    int added = execute(c, "insert into public.role_permission(tenant_id,role_id,permission_id,scope_rule_id) select ?,?,id,? from public.permission where code=?", id("tenant"), id(actor+"/role"), id(actor+"/scope"), permission);
                    if (added != 1) throw new IllegalStateException("Required permission unavailable");
                }
                execute(c, "insert into public.role_grant(tenant_id,id,role_id,subject_kind,member_id,source_type,source_id,granted_by_member_id,valid_from) values (?,?,?,'MEMBER',?,'D0_ACCEPTANCE',?,?,timestamptz '2026-01-01 00:00:00+00')", id("tenant"), id(actor+"/grant"), id(actor+"/role"), id(actor+"/member"), id(actor+"/source"), id(actor+"/member"));
            }
            for (String stage : List.of("PERSON_CONFIRM", "SAFETY_CONFIRM", "SAFETY_DISPATCH", "SAFETY_VERIFY")) execute(c, "insert into public.finding_stage_assignment(tenant_id,organization_id,stage,assignee_member_id,valid_from) values (?,?,?,?,timestamptz '2026-01-01 00:00:00+00')", id("tenant"), id("org"), stage, id((stage.equals("PERSON_CONFIRM") ? "person" : "safety")+"/member"));
            verifySeed(c, secrets);
            c.commit();
        } catch (Exception e) { c.rollback(); throw e; }
    }

    static List<String> permissions(String actor) {
        return switch (actor) {
            case "reporter" -> List.of("finding.read", "finding.create", "finding.workflow.initialize");
            case "person" -> List.of("finding.read", "finding.confirm.person");
            case "safety" -> List.of("finding.read", "finding.confirm.safety", "finding.dispatch", "finding.verify");
            case "remediator" -> List.of("finding.read", "finding.remediate");
            case "admin" -> List.of("platform.permission.read", "platform.permission.manage");
            default -> throw new IllegalArgumentException("Unknown seed actor");
        };
    }

    static void verifySeed(Connection c, Map<String,String> secrets) throws SQLException {
        // This namespace is a fixed synthetic fixture, not an application provisioner.
        // Refuse extra grants/memberships rather than overlooking authority drift or deleting it.
        for (var entry : Map.ofEntries(
                Map.entry("tenant_application", 2), Map.entry("org_unit", 1),
                Map.entry("tenant_member", 5), Map.entry("member_org", 5),
                Map.entry("role", 5), Map.entry("role_grant", 5), Map.entry("scope_rule", 5),
                Map.entry("scope_org", 5), Map.entry("member_position", 0),
                Map.entry("group_member", 0), Map.entry("finding_stage_assignment", 4)).entrySet()) {
            expectCount(c, "select count(*) from public.\"" + entry.getKey() + "\" where tenant_id=?", entry.getValue(), id("tenant"));
        }
        expectCount(c, "select count(*) from public.tenant where id=? and code=? and name=? and status='ACTIVE'", 1, id("tenant"), tenantCode, SEED_NAME);
        expectCount(c, "select count(*) from public.tenant_application where tenant_id=? and enabled and application_key in ('safety','platform')", 2, id("tenant"));
        expectCount(c, "select count(*) from public.org_unit where tenant_id=? and id=? and active", 1, id("tenant"), id("org"));
        for (String actor : ACTORS) {
            try (var q = c.prepareStatement("select a.login_key,a.status,p.id,p.status,m.id,m.status,cr.secret_hash from public.account a join public.principal p on p.account_id=a.id and p.kind='HUMAN' join public.tenant_member m on m.account_id=a.id and m.tenant_id=? join public.credential cr on cr.account_id=a.id and cr.kind='PASSWORD' where a.id=?")) {
                q.setObject(1,id("tenant")); q.setObject(2,id(actor+"/account"));
                try (var r=q.executeQuery()) {
                    if (!r.next() || !r.getString(1).equals(tenantCode+"-"+actor) || !r.getString(2).equals("ACTIVE") || !r.getObject(3).equals(id(actor+"/principal")) || !r.getString(4).equals("ACTIVE") || !r.getObject(5).equals(id(actor+"/member")) || !r.getString(6).equals("ACTIVE") || !r.getString(7).startsWith("{bcrypt}") || !PASSWORDS.matches(secrets.get(actor),r.getString(7).substring(8))) throw new IllegalStateException("Seed identity/credential differs; no implicit reset permitted");
                }
            }
            try (var q=c.prepareStatement("select p.code from public.role_permission rp join public.permission p on p.id=rp.permission_id where rp.tenant_id=? and rp.role_id=?")) {
                q.setObject(1,id("tenant")); q.setObject(2,id(actor+"/role"));
                Set<String> actual=new HashSet<>();
                try(var r=q.executeQuery()) { while(r.next()) actual.add(r.getString(1)); }
                if (!actual.equals(new HashSet<>(permissions(actor)))) throw new IllegalStateException("Seed permission drift; no implicit regrant permitted");
            }
            expectCount(c, "select count(*) from public.role_grant g join public.\"role\" r on r.tenant_id=g.tenant_id and r.id=g.role_id where g.tenant_id=? and g.id=? and g.role_id=? and g.subject_kind='MEMBER' and g.member_id=? and g.source_type='D0_ACCEPTANCE' and r.active and g.valid_from<=current_timestamp and (g.valid_to is null or g.valid_to>current_timestamp)", 1, id("tenant"), id(actor+"/grant"), id(actor+"/role"), id(actor+"/member"));
            expectCount(c, "select count(*) from public.role_permission rp join public.scope_rule sr on sr.tenant_id=rp.tenant_id and sr.id=rp.scope_rule_id join public.scope_org so on so.tenant_id=sr.tenant_id and so.scope_rule_id=sr.id where rp.tenant_id=? and rp.role_id=? and sr.id=? and sr.kind='EXPLICIT' and so.org_id=?", permissions(actor).size(), id("tenant"), id(actor+"/role"), id(actor+"/scope"), id("org"));
        }
        for (String stage : List.of("PERSON_CONFIRM", "SAFETY_CONFIRM", "SAFETY_DISPATCH", "SAFETY_VERIFY")) {
            expectCount(c, "select count(*) from public.finding_stage_assignment where tenant_id=? and organization_id=? and stage=? and assignee_member_id=? and valid_from<=current_timestamp and (valid_to is null or valid_to>current_timestamp)", 1, id("tenant"), id("org"), stage, id((stage.equals("PERSON_CONFIRM") ? "person" : "safety")+"/member"));
        }
    }

    static void expectCount(Connection c, String sql, int expected, Object... args) throws SQLException {
        try (var q=c.prepareStatement(sql)) {
            for (int i=0;i<args.length;i++) q.setObject(i+1,args[i]);
            try (var r=q.executeQuery()) { r.next(); if(r.getInt(1)!=expected) throw new IllegalStateException("Seed state differs; inspect without resetting existing data"); }
        }
    }
    static UUID id(String key) { return UUID.nameUUIDFromBytes(("msf/d0/"+database+"/"+tenantCode+"/"+key).getBytes(StandardCharsets.UTF_8)); }
    static String required(String key) { String value=System.getenv(key); if(value==null || value.isBlank()) throw new IllegalArgumentException("Missing "+key); return value; }
    static int execute(Connection c, String sql, Object... args) throws SQLException {
        try (var s=c.prepareStatement(sql)) { for(int i=0;i<args.length;i++) s.setObject(i+1,args[i]); boolean rows=s.execute(); return rows ? -1 : s.getUpdateCount(); }
    }
}
