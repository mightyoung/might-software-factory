import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import org.flowable.engine.impl.cfg.StandaloneProcessEngineConfiguration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/** Test-only app/Flyway bootstrap. Do not use an existing PostgreSQL instance. */
class HostedBootstrap {
    static final String DB = "msf_d2_issue_test";
    static final String MIGRATOR = "msf_ci_au08_migrator", ADMIN = "msf_ci_au08_bootstrap";
    static final String BPMN = "processes/tool-loan-v1.bpmn20.xml";
    static final BCryptPasswordEncoder BCrypt = new BCryptPasswordEncoder(12);
    static String url;
    static UUID marker;

    public static void main(String[] args) {
        try {
            if (args.length != 1 || !Set.of("migrate", "seed", "deploy").contains(args[0]))
                throw new IllegalArgumentException("Explicit AU-08 phase required");
            marker = UUID.fromString(bootstrap.required("AU08_BOOT_MARKER"));
            int port = Integer.parseInt(bootstrap.required("AU08_BOOT_PORT"));
            if (marker.version() != 4 || port < 1024 || port > 65535 || port == 5432)
                throw new IllegalArgumentException("Only the owned ephemeral target is supported");
            url = "jdbc:postgresql://127.0.0.1:" + port + "/" + DB + "?sslmode=disable&gssEncMode=disable";
            String secret = bootstrap.required("AU08_BOOT_MIGRATOR_PASSWORD");
            if (args[0].equals("migrate")) {
                migrations(secret, bootstrap.required("AU08_BOOT_ADMIN_PASSWORD"));
            } else {
                UUID tenant = UUID.fromString(bootstrap.required("AU08_BOOT_TENANT"));
                if (!tenant.equals(derivedTenant())) throw new IllegalStateException("Wrong synthetic tenant");
                if (args[0].equals("seed")) seed(secret, tenant);
                else deploy(secret, tenant);
            }
            System.out.println("AU08_BOOTSTRAP_OK " + args[0]);
        } catch (Exception failure) {
            String state = "NA";
            List<String> classes = new ArrayList<>();
            for (Throwable cause = failure; cause != null && classes.size() < 5; cause = cause.getCause()) {
                classes.add(cause.getClass().getSimpleName());
                if (cause instanceof SQLException sql && sql.getSQLState() != null
                        && sql.getSQLState().matches("[A-Z0-9]{5}")) {
                    state = sql.getSQLState();
                    break;
                }
            }
            System.err.println("AU08_BOOTSTRAP_FAILED " + String.join(".", classes) + " " + state);
            System.exit(1);
        }
    }

    static UUID derivedTenant() throws Exception {
        var digest = java.security.MessageDigest.getInstance("SHA-1");
        var raw = java.nio.ByteBuffer.allocate(16).putLong(marker.getMostSignificantBits()).putLong(marker.getLeastSignificantBits()).array();
        digest.update(raw);
        byte[] value = digest.digest("msf/d2-authz/d1seed".getBytes(StandardCharsets.UTF_8));
        value[6] = (byte) ((value[6] & 0x0f) | 0x50);
        value[8] = (byte) ((value[8] & 0x3f) | 0x80);
        var bytes = java.nio.ByteBuffer.wrap(value);
        return new UUID(bytes.getLong(), bytes.getLong());
    }

    static void verify(Connection c, String role, boolean admin) throws SQLException {
        try (var s = c.createStatement(); var r = s.executeQuery("""
                select current_database(),current_user,session_user,
                       current_setting('msf.ci_instance', true), current_setting('server_version_num')::int,
                       rolsuper,rolbypassrls or rolcreatedb or rolcreaterole or rolreplication or rolinherit,
                       exists(select 1 from pg_auth_members m where m.member=r.oid),
                       (select pg_get_userbyid(d.datdba) from pg_database d where d.datname=current_database())
                  from pg_roles r where rolname=current_user and rolcanlogin
                """)) {
            if (!r.next() || !DB.equals(r.getString(1)) || !role.equals(r.getString(2))
                    || !role.equals(r.getString(3)) || !marker.toString().equals(r.getString(4))
                    || r.getInt(5) < 180000 || r.getInt(5) >= 190000 || r.getBoolean(6) != admin
                    || (!admin && (r.getBoolean(7) || r.getBoolean(8))) || !ADMIN.equals(r.getString(9)))
                throw new IllegalStateException("Unexpected AU-08 migration or bootstrap identity");
        }
    }

    static void migrations(String migratorPassword, String adminPassword) throws Exception {
        try (var migrator = DriverManager.getConnection(url, MIGRATOR, migratorPassword);
             var admin = DriverManager.getConnection(url, ADMIN, adminPassword)) {
            verify(migrator, MIGRATOR, false);
            verify(admin, ADMIN, true);
            if (migrate.flyway(url, MIGRATOR, migratorPassword, "latest").info().current() != null)
                throw new IllegalStateException("AU-08 starts with an empty application history");
            migrate.flyway(url, MIGRATOR, migratorPassword, "22").migrate();
            var v23 = migrate.flyway(url, ADMIN, adminPassword, "23");
            var handoff = v23.migrate();
            if (handoff.migrationsExecuted != 1 || !"23".equals(v23.info().current().getVersion().getVersion()))
                throw new IllegalStateException("AU-08 bootstrap identity may install V23 only");
            try (var s = admin.createStatement()) {
                boolean rows = s.execute(Files.readString(Path.of(
                        "backend/src/main/resources/db/operator/V23-superuser-apply.sql")));
                while (rows || s.getUpdateCount() != -1) {
                    if (rows) try (var r = s.getResultSet()) {
                        if (r.next()) throw new IllegalStateException("Unexpected object owner after V23");
                    }
                    rows = s.getMoreResults();
                }
            }
            var app = migrate.flyway(url, MIGRATOR, migratorPassword, "latest");
            app.migrate();
            app.validate();
            try (var s = migrator.createStatement(); var r = s.executeQuery("""
                select count(*), count(*) filter(where success and installed_by='msf_ci_au08_bootstrap' and version='23'),
                       count(*) filter(where success and installed_by='msf_ci_au08_migrator' and version<>'23')
                  from public.flyway_schema_history_app
                """)) {
                if (!r.next() || r.getInt(1) != 37 || r.getInt(2) != 1 || r.getInt(3) != 36
                        || !"37".equals(app.info().current().getVersion().getVersion()))
                    throw new IllegalStateException("Unexpected application source migration history");
            }
            bootstrap.framework(migrator, url, MIGRATOR, migratorPassword);
        }
    }

    static void seed(String password, UUID tenant) throws Exception {
        try (var c = DriverManager.getConnection(url, MIGRATOR, password)) {
            verify(c, MIGRATOR, false);
            c.setAutoCommit(false);
            try {
                sql(c, "select set_config('app.tenant_id',?,true)", tenant.toString());
                UUID org = id(tenant, "org"), scope = id(tenant, "custody");
                sql(c, "insert into public.tenant(id,code,name,status,auth_epoch) values (?,'d1seed','D1 isolated tooling seed v1','ACTIVE',1)", tenant);
                sql(c, "insert into public.tenant_application(tenant_id,application_key,enabled,version) values (?,'tooling',true,1)", tenant);
                sql(c, "insert into public.org_unit(tenant_id,id,code,name,active) values (?,?,'D1-ORG-A','AU-08 synthetic organization',true)", tenant, org);
                sql(c, "insert into public.tool_custody_scope(tenant_id,id,organization_id,code,name,active) values (?,?,?,'D1-SCOPE-A','AU-08 synthetic custody',true)", tenant, scope, org);
                for (String actor : List.of("reader","keeper")) seedActor(c, tenant, org, scope, actor);
                c.commit();
            } catch (Exception failure) {
                c.rollback();
                throw failure;
            }
        }
    }

    static void seedActor(Connection c, UUID tenant, UUID org, UUID custody, String actor) throws SQLException {
        UUID account=id(tenant,actor+"/account"), member=id(tenant,actor+"/member");
        UUID role=id(tenant,actor+"/role"), scope=id(tenant,actor+"/scope");
        String secret=bootstrap.required("AU08_BOOT_" + actor.toUpperCase(Locale.ROOT));
        if (!secret.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Invalid private actor credential");
        sql(c, "insert into public.account(id,login_key,status,credential_version) values (?,?,'ACTIVE',1)", account, "d1seed-"+actor);
        sql(c, "insert into public.principal(id,kind,account_id,status) values (?,'HUMAN',?,'ACTIVE')", id(tenant,actor+"/principal"), account);
        sql(c, "insert into public.credential(account_id,kind,secret_hash,changed_at) values (?,'PASSWORD',?,current_timestamp)", account, "{bcrypt}"+BCrypt.encode(secret));
        sql(c, "insert into public.tenant_member(tenant_id,id,account_id,status,auth_epoch,display_name) values (?,?,?,'ACTIVE',1,?)", tenant, member, account, "AU-08 "+actor);
        sql(c, "insert into public.member_org(tenant_id,member_id,org_id,kind,valid_from) values (?,?,?,'PRIMARY',timestamptz '2026-01-01 00:00:00+00')", tenant, member, org);
        sql(c, "insert into public.role(tenant_id,id,code,name,active) values (?,?,?,?,true)", tenant, role, "D1-"+actor, "AU-08 "+actor);
        sql(c, "insert into public.scope_rule(tenant_id,id,kind,version_no,definition) values (?,?,'EXPLICIT',1,'{}'::jsonb)", tenant, scope);
        sql(c, "insert into public.scope_org(tenant_id,scope_rule_id,org_id) values (?,?,?)", tenant, scope, org);
        List<String> permissions = actor.equals("keeper")
                ? List.of("tool.read","tool.create","tool_loan.read","tool_loan.issue","tool_loan.receive")
                : List.of("tool.read","tool_loan.read","tool_loan.create","tool_loan.update");
        for (String permission : permissions)
            if (sql(c, "insert into public.role_permission(tenant_id,role_id,permission_id,scope_rule_id) select ?,?,id,? from public.permission where code=?", tenant,role,scope,permission)!=1)
                throw new IllegalStateException("Missing canonical AU-08 permission");
        sql(c, "insert into public.role_grant(tenant_id,id,role_id,subject_kind,member_id,source_type,source_id,granted_by_member_id,valid_from) values (?,?,?,'MEMBER',?,'D1_ACCEPTANCE',?,?,timestamptz '2026-01-01 00:00:00+00')",
                tenant,id(tenant,actor+"/grant"),role,member,id(tenant,actor+"/source"),member);
        if (actor.equals("keeper"))
            sql(c, "insert into public.tool_custodian(tenant_id,custody_scope_id,member_id,valid_from) values (?,?,?,timestamptz '2026-01-01 00:00:00+00')",tenant,custody,member);
    }

    static StandaloneProcessEngineConfiguration workflowConfiguration(String password) {
        var cfg = new StandaloneProcessEngineConfiguration();
        cfg.setJdbcUrl(url).setJdbcUsername(MIGRATOR).setJdbcPassword(password).setJdbcDriver("org.postgresql.Driver")
                .setDatabaseSchemaUpdate("false").setAsyncExecutorActivate(false);
        cfg.setDisableIdmEngine(true);
        cfg.setDisableEventRegistry(true);
        return cfg;
    }

    static void deploy(String password, UUID tenant) throws Exception {
        try (var c = DriverManager.getConnection(url, MIGRATOR, password)) { verify(c, MIGRATOR, false); }
        var engine = workflowConfiguration(password).buildProcessEngine();
        try {
            var repo = engine.getRepositoryService();
            var query = repo.createProcessDefinitionQuery().processDefinitionTenantId(tenant.toString()).processDefinitionKey("toolLoanV1");
            if (query.count() != 0) throw new IllegalStateException("Unexpected existing AU-08 definition");
            repo.createDeployment().tenantId(tenant.toString()).name("AU-08 synthetic tool loan V1")
                    .addBytes(BPMN, Files.readAllBytes(Path.of("backend/src/main/resources", BPMN))).deploy();
        } finally { engine.close(); }
    }

    static UUID id(UUID tenant, String key) {
        return UUID.nameUUIDFromBytes((tenant + "/" + key).getBytes(StandardCharsets.UTF_8));
    }

    static int sql(Connection c, String query, Object... values) throws SQLException {
        try (var q = c.prepareStatement(query)) {
            for (int i=0;i<values.length;i++) q.setObject(i+1, values[i]);
            return q.execute() ? -1 : q.getUpdateCount();
        }
    }
}
