package com.mightyoung.softwarefactory;

import com.mightyoung.softwarefactory.authorization.PostgresAuthorizationWriteGuard;
import com.mightyoung.softwarefactory.identity.CiPg18SourceSession;
import com.mightyoung.softwarefactory.identity.SessionSensitiveKeys;
import com.mightyoung.softwarefactory.testing.CiPg18EphemeralTarget;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Fresh isolated PG18 source lane only. Seeds identity prerequisites, never source command results. */
public final class CiPg18SourceFixture implements AutoCloseable {
    private static final List<String> VERSIONS = List.of("2","3","4","5","6","7","8","11","12","13","14","15","16","17","18","18.1","18.2","18.3","19","20","21","22","23","24","25","26","27","28");
    private static final String RUNTIME = "msf_ci_source_runtime";
    private static final String GRANTS = "/ci-pg18-source/runtime-grants.sql";
    private static final Set<String> REQUIRED_FUNCTIONS=Set.of(
            "public.g014_instant_valid(timestamptz)","public.g014_text_valid(text,integer,boolean)",
            "public.g014r_require_context(uuid)","public.g014r_key_header(text,uuid)",
            "public.g014r_natural_key_bytes(uuid,text)","public.g014r_receipt_key_bytes(uuid,uuid,text,uuid)",
            "public.g014r_mutation_key_bytes(text,uuid,uuid,bigint,uuid,uuid)","public.g014r_key_hash(bytea)",
            "public.g014r_require_key(integer,bytea)","public.g014r_lock_natural_key(uuid,text)",
            "public.g014r_require_natural_key(uuid,text)","public.g014r_lock_receipt_key(uuid,uuid,text,uuid)",
            "public.g014r_require_mutation_key(text,uuid,uuid,bigint,uuid,uuid)","public.g014r_require_scenario_keys(uuid,uuid,bigint,uuid)",
            "public.g014r_mutation_key_plan(uuid,uuid,bigint,uuid,uuid[],bigint[],uuid[])",
            "public.g014r_lock_mutation_keys(uuid,uuid,bigint,uuid,uuid[],bigint[],uuid[])",
            "public.g014r_require_identity(uuid,uuid,uuid)","public.g014r_open_command(uuid,uuid,bigint,uuid,uuid,uuid,timestamptz)",
            "public.g014r_validate_root_revision(uuid,uuid,bigint,uuid)","public.g014r_validate_receipt(public.risk_scenario_receipt)",
            "public.g014r_validate_scenario(uuid,uuid,bigint,uuid,boolean)","public.g014r_lock_row(integer,uuid,uuid)");
    private static final Pattern FUNCTION_GRANT=Pattern.compile("grant execute on function (public\\.[a-z0-9_]+\\([a-z0-9_.,\\[\\]]*\\)) to msf_ci_source_runtime;");
    private static final Set<String> PHASES = Set.of("open","target-version","target-marker","empty-schema","migrate","history",
            "framework-schema","source-schema","runtime-role","runtime-rights","seed-identity","seed-foundation","native-graph","closed","unknown");
    private enum Spec {
        SOURCE("msf_ci_risk_source","msf_ci_source_runtime"),
        RECOVERY("msf_ci_risk_recovery","msf_ci_recovery_runtime");
        final String database,runtime;
        Spec(String database,String runtime){this.database=database;this.runtime=runtime;}
    }
    private final Spec spec;
    private final CiPg18EphemeralTarget target;
    private final DataSource admin;
    private final DataSource runtime;
    private final SessionSensitiveKeys keys;
    private Flyway flyway;
    private boolean closed;
    private Identity identity;
    private UUID hazard;
    private CiPg18SourceSession session;
    private final java.util.concurrent.atomic.AtomicInteger borrows = new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicInteger commits = new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicInteger rollbacks = new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicInteger publicationInserts = new java.util.concurrent.atomic.AtomicInteger();
    private final CiPg18SourceDiagnostics diagnostics = new CiPg18SourceDiagnostics();

    public record Identity(UUID tenant, UUID account, UUID actor, UUID member, UUID organization) { }
    public record Metrics(int borrows, int commits, int rollbacks, int publicationInserts) { }

    private CiPg18SourceFixture(CiPg18EphemeralTarget target,Spec spec) {
        this.spec=spec;
        if (target == null || !spec.database.equals(target.databaseName())
                || !"msf_ci_bootstrap".equals(target.adminUser()) || !spec.runtime.equals(target.runtimeUser()))
            throw rejected();
        this.target = target;
        admin = source(true); runtime = source(false);
        if(spec==Spec.RECOVERY){keys=null;return;}
        byte[] key = new byte[32]; new SecureRandom().nextBytes(key);
        keys = new SessionSensitiveKeys("ci-source-k1", Map.of("ci-source-k1", key));
        java.util.Arrays.fill(key, (byte) 0);
    }

    public static CiPg18SourceFixture open(CiPg18EphemeralTarget target) {
        return open(target,Spec.SOURCE);
    }
    public static CiPg18SourceFixture openRecovery(CiPg18EphemeralTarget target){return open(target,Spec.RECOVERY);}
    private static CiPg18SourceFixture open(CiPg18EphemeralTarget target,Spec spec){
        var fixture = new CiPg18SourceFixture(target,spec);
        String phase = "open";
        try {
            phase = "empty-schema";
            try (Connection connection = fixture.connect(true)) {
                require(connection, fixture.runtimeSql("select not exists(select 1 from pg_catalog.pg_class c join pg_catalog.pg_namespace n on n.oid=c.relnamespace where n.nspname='public')"
                        + " and not exists(select 1 from pg_catalog.pg_proc p join pg_catalog.pg_namespace n on n.oid=p.pronamespace where n.nspname='public')"
                        + " and not exists(select 1 from pg_catalog.pg_type t join pg_catalog.pg_namespace n on n.oid=t.typnamespace where n.nspname='public')"
                        + " and not exists(select 1 from pg_catalog.pg_namespace where nspname not in ('public','pg_catalog','information_schema','pg_toast'))"
                        + " and not exists(select 1 from pg_catalog.pg_roles where rolname='msf_ci_source_runtime')"));
            }
            phase = "migrate";
            fixture.flyway = Flyway.configure().dataSource(fixture.admin).locations("classpath:db/migration")
                    .table("flyway_schema_history_app").baselineOnMigrate(false).outOfOrder(false)
                    .cleanDisabled(true).validateMigrationNaming(true).load();
            if (fixture.flyway.migrate().migrationsExecuted != VERSIONS.size()) throw rejected();
            phase = "history"; fixture.verifyHistory();
            // Framework-owned schema comes from the pinned Spring Session dependency, not reconstructed fixture DDL.
            phase = "framework-schema";
            new ResourceDatabasePopulator(new ClassPathResource("org/springframework/session/jdbc/schema-postgresql.sql"))
                    .execute(fixture.admin);
            phase = "runtime-role";
            try (Connection connection = fixture.connect(true)) {
                connection.setAutoCommit(false);
                try (var statement = connection.createStatement()) {
                    statement.execute(fixture.runtimeSql("create role msf_ci_source_runtime login nosuperuser nocreatedb nocreaterole noinherit noreplication nobypassrls password '"
                            + target.runtimePassword() + "'"));
                    statement.execute(fixture.canonicalResource());
                }
                connection.commit();
            }
            phase = "runtime-rights"; fixture.verifyRights();
            phase = "seed-identity"; fixture.seedIdentityAndSession();
            phase = "seed-foundation"; fixture.seedOwnedFoundationPrerequisites();
            phase = "source-schema"; fixture.verifySourceSchema();
            if(spec==Spec.SOURCE)fixture.writeEvidence();
            return fixture;
        } catch (Throwable failure) {
            writeFailure(phase, failure,spec);
            fixture.close();
            if(spec==Spec.RECOVERY)throw new RecoveryBootstrapFailure();
            throw rejected();
        }
    }

    public DataSource runtimeSource() { ensureOpen(); return runtime; }
    public SessionSensitiveKeys keys() { ensureOpen(); if(keys==null)throw rejected();return keys; }
    public Identity identity() { ensureOpen(); return identity; }
    public CiPg18SourceSession session() { ensureOpen();if(session==null)throw rejected(); return session; }
    public UUID hazard() { ensureOpen(); return hazard; }
    public Metrics metrics() { return new Metrics(borrows.get(),commits.get(),rollbacks.get(),publicationInserts.get()); }
    void beginCase(String name) { diagnostics.begin(name,metrics()); }
    void graphReady() { diagnostics.graphReady(); }
    <T> T invokeIssuer(java.util.function.Supplier<T> command) {
        diagnostics.enter();
        try { T value=command.get();diagnostics.exit(true,null);return value; }
        catch(RuntimeException|Error failure){diagnostics.exit(false,failure);throw failure;}
    }
    void finishCase(Throwable failure) throws Exception {
        diagnostics.finish(failure,metrics());
        Files.writeString(Path.of("target","ci-pg18-source-case-diagnostics.json"),
                tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(diagnostics.snapshot()),StandardCharsets.UTF_8);
    }
    /** Independent observations are permitted after source commands; this connection never performs source writes. */
    public Connection observer() throws SQLException {
        ensureOpen();
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()
                || org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()
                || !org.springframework.transaction.support.TransactionSynchronizationManager.getResourceMap().isEmpty()) throw rejected();
        return connect(true);
    }

    private DataSource source(boolean privileged) {
        return new AbstractDataSource() {
            @Override public Connection getConnection() throws SQLException { return connect(privileged); }
            @Override public Connection getConnection(String user, String password) throws SQLException {
                throw new SQLException("Fixed CI source identity", "08004");
            }
        };
    }
    private Connection connect(boolean privileged) throws SQLException {
        ensureOpen();
        Connection connection = null;
        try {
            var properties = new Properties();
            properties.setProperty("user", privileged ? target.adminUser() : target.runtimeUser());
            properties.setProperty("password", privileged ? target.adminPassword() : target.runtimePassword());
            properties.setProperty("connectTimeout", "10"); properties.setProperty("socketTimeout", "15");
            properties.setProperty("ApplicationName", "msf-ci-source-" + target.instanceId());
            connection = DriverManager.getConnection(target.jdbcUrl(), properties);
            if (!"PostgreSQL".equals(connection.getMetaData().getDatabaseProductName())
                    || connection.getMetaData().getDatabaseMajorVersion() != 18) throw rejected();
            try (var statement = connection.createStatement(); var rows = statement.executeQuery(
                    "select current_database(),current_user,session_user,current_setting('msf.ci_instance',true)")) {
                String user = privileged ? target.adminUser() : target.runtimeUser();
                if (!rows.next() || !target.databaseName().equals(rows.getString(1)) || !user.equals(rows.getString(2))
                        || !user.equals(rows.getString(3)) || !target.instanceId().toString().equals(rows.getString(4)) || rows.next())
                    throw rejected();
            }
            if (privileged) return connection;
            borrows.incrementAndGet();
            return observe(connection);
        } catch (Throwable failure) {
            if (connection != null) try { connection.close(); } catch (Throwable ignored) { }
            throw new SQLException("CI source connection rejected", "08004");
        }
    }

    private Connection observe(Connection physical) {
        return (Connection)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{Connection.class},
                (proxy, method, arguments) -> {
                    var operation=switch(method.getName()) {
                        case "prepareStatement" -> CiPg18SourceDiagnostics.Operation.PREPARE;
                        case "commit" -> CiPg18SourceDiagnostics.Operation.COMMIT;
                        case "rollback" -> CiPg18SourceDiagnostics.Operation.ROLLBACK;
                        case "close" -> CiPg18SourceDiagnostics.Operation.CLOSE;
                        default -> null;
                    };
                    String sql=operation==CiPg18SourceDiagnostics.Operation.PREPARE&&arguments!=null&&arguments.length>0&&arguments[0] instanceof String text?text:null;
                    Object result;
                    try {result=invoke(physical,method,arguments);if(operation!=null)diagnostics.success(operation,sql);}
                    catch(Throwable failure){if(operation!=null)diagnostics.failure(operation,sql,failure);throw failure;}
                    if (method.getName().equals("commit")) commits.incrementAndGet();
                    if (method.getName().equals("rollback") && method.getParameterCount()==0) rollbacks.incrementAndGet();
                    if(result instanceof PreparedStatement statement)return observeStatement(statement,PreparedStatement.class,sql);
                    if(result instanceof java.sql.Statement statement)return observeStatement(statement,java.sql.Statement.class,null);
                    return result;
                });
    }
    private Object observeStatement(java.sql.Statement statement,Class<?> contract,String preparedSql) {
        return java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{contract},(proxy,method,arguments)->{
            var operation=switch(method.getName()) {
                case "executeQuery" -> CiPg18SourceDiagnostics.Operation.EXECUTE_QUERY;
                case "executeUpdate" -> CiPg18SourceDiagnostics.Operation.EXECUTE_UPDATE;
                case "execute" -> CiPg18SourceDiagnostics.Operation.EXECUTE;
                default -> null;
            };
            String sql=preparedSql!=null?preparedSql:arguments!=null&&arguments.length>0&&arguments[0] instanceof String text?text:null;
            try {
                Object value=invoke(statement,method,arguments);
                if(operation!=null)diagnostics.success(operation,sql);
                if(operation==CiPg18SourceDiagnostics.Operation.EXECUTE_UPDATE&&Integer.valueOf(1).equals(value)&&sql!=null
                        &&sql.toUpperCase(java.util.Locale.ROOT).matches("INSERT INTO (PUBLIC\\.)?EVENT_PUBLICATION \\(ID, EVENT_TYPE, LISTENER_ID, PUBLICATION_DATE, SERIALIZED_EVENT\\)\\s+VALUES \\(\\?, \\?, \\?, \\?, \\?\\)\\s*"))publicationInserts.incrementAndGet();
                if(value instanceof ResultSet rows)return observeRows(rows,sql);
                return value;
            }catch(Throwable failure){if(operation!=null)diagnostics.failure(operation,sql,failure);throw failure;}
        });
    }
    private ResultSet observeRows(ResultSet rows,String sql) {
        return (ResultSet)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{ResultSet.class},(proxy,method,arguments)->{
            boolean read=method.getName().startsWith("get")||method.getName().equals("next")||method.getName().equals("wasNull");
            try {
                Object value=invoke(rows,method,arguments);
                if(read)diagnostics.success(CiPg18SourceDiagnostics.Operation.RESULT_READ,sql);
                return value;
            }catch(Throwable failure){if(read)diagnostics.failure(CiPg18SourceDiagnostics.Operation.RESULT_READ,sql,failure);throw failure;}
        });
    }
    private static Object invoke(Object target,java.lang.reflect.Method method,Object[] arguments) throws Throwable {
        try { return method.invoke(target,arguments); }
        catch (java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
    }

    public void verifyHistory() {
        flyway.validate();
        var actual = new ArrayList<String>();
        for (var migration : flyway.info().applied()) {
            if (migration.getVersion() == null || !migration.getState().isApplied() || migration.getChecksum() == null)
                throw rejected();
            actual.add(migration.getVersion().getVersion());
        }
        if (!actual.equals(VERSIONS)) throw rejected();
    }

    public void verifySourceSchema() throws Exception {
        try (Connection connection = connect(true)) {
            String tables = "'risk_scenario','risk_scenario_revision','risk_hazard','risk_hazard_revision','risk_scenario_revision_hazard','risk_scenario_receipt'";
            require(connection,"select count(*)=6 and bool_and(c.relkind='r' and c.relrowsecurity and c.relforcerowsecurity and c.relowner=(select oid from pg_roles where rolname=current_user)) from pg_class c join pg_namespace n on n.oid=c.relnamespace where n.nspname='public' and c.relname in ("+tables+")");
            require(connection,"select count(*)=13 and bool_and(c.convalidated and c.conenforced and c.condeferred) from pg_constraint c join pg_class r on r.oid=c.conrelid join pg_namespace n on n.oid=r.relnamespace where n.nspname='public' and r.relname in ("+tables+") and c.contype='f' and c.condeferrable");
            require(connection,"select count(*)=3 and bool_and(t.tgenabled='O' and t.tgdeferrable and t.tginitdeferred) from pg_trigger t join pg_class c on c.oid=t.tgrelid join pg_namespace n on n.oid=c.relnamespace where n.nspname='public' and not t.tgisinternal and t.tgname in ('g014r_scenario_history_complete','g014r_link_history_complete','g014r_receipt_complete')");
            require(connection,"select not exists(select 1 from pg_constraint c join pg_class r on r.oid=c.conrelid join pg_namespace n on n.oid=r.relnamespace where n.nspname='public' and r.relname in ("+tables+") and (not c.convalidated or not c.conenforced))");
            require(connection,"select state='ENFORCING' and version=1 and activation_event_id is not null from public.session_authority_activation where singleton");
            var publication = new ArrayList<String>();
            try (var statement=connection.createStatement();var rows=statement.executeQuery("select a.attname||':'||format_type(a.atttypid,a.atttypmod)||':'||a.attnotnull::text from pg_attribute a where a.attrelid='public.event_publication'::regclass and a.attnum>0 and not a.attisdropped order by a.attnum")) {
                while(rows.next())publication.add(rows.getString(1));
            }
            if(!publication.equals(List.of("id:uuid:true","listener_id:text:true","event_type:text:true","serialized_event:text:true","publication_date:timestamp with time zone:true","completion_date:timestamp with time zone:false","status:text:false","completion_attempts:integer:false","last_resubmission_date:timestamp with time zone:false")))throw rejected();
        }
    }

    private void writeEvidence() throws Exception {
        var migrations = new ArrayList<Map<String,Object>>();
        for (var migration : flyway.info().applied()) {
            if (!migration.getScript().matches("V[0-9]+__[A-Za-z0-9_]+\\.sql")) throw rejected();
            migrations.add(Map.of("version",migration.getVersion().getVersion(),"script",migration.getScript(),"checksum",migration.getChecksum()));
        }
        var value=Map.of("schemaVersion",1,"postgresMajor",18,"database",target.databaseName(),
                "instanceMarkerVerified",true,"imageDigest",target.imageDigest(),"migrations",migrations,
                "sourceSchemaVerified",true,"runtimeRightsVerified",true);
        Files.writeString(Path.of("target","ci-pg18-source-safe-evidence.json"),tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(value),StandardCharsets.UTF_8);
    }
    static final class RecoveryBootstrapFailure extends IllegalStateException {
        RecoveryBootstrapFailure(){super("CI recovery bootstrap rejected");}
    }
    private static void writeFailure(String phase,Throwable failure,Spec spec) {
        String state=null;
        for(int index=0;index<8&&failure!=null;index++,failure=failure.getCause()) {
            if(failure instanceof SQLException sql&&sql.getSQLState()!=null&&sql.getSQLState().matches("[0-9A-Z]{5}")){state=sql.getSQLState();break;}
        }
        String safe=PHASES.contains(phase)?phase:"unknown";
        if(spec==Spec.RECOVERY)safe=switch(safe){
            case "runtime-role","runtime-rights","source-schema"->"canonical-rights";
            case "seed-identity","seed-foundation"->"open";
            case "native-graph"->"unknown";
            default->safe;
        };
        String file=spec==Spec.RECOVERY?"ci-pg18-recovery-safe-failure.json":"ci-pg18-source-safe-failure.json";
        try {
            Files.writeString(Path.of("target",file),"{\"schemaVersion\":1,\"database\":\""+spec.database+"\",\"phase\":\""+safe+"\",\"migrationVersion\":null,\"sqlState\":"+(state==null?"null":"\""+state+"\"")+"}",StandardCharsets.UTF_8);
        } catch(Exception ignored) { }
    }

    /** Verify effective relation/column privileges, including PUBLIC and role inheritance, across the whole public schema. */
    public void verifyRights() throws Exception {
        Map<String, Map<String, Set<String>>> expected = expectedRights();
        try (Connection connection = connect(true)) {
            requireRuntime(connection, "select not rolsuper and not rolcreatedb and not rolcreaterole and not rolinherit and not rolreplication and not rolbypassrls and rolcanlogin"
                    + " and not exists(select 1 from pg_auth_members m where m.member=r.oid)"
                    + " and not exists(select 1 from pg_class c where c.relowner=r.oid)"
                    + " and not exists(select 1 from pg_database d where d.datdba=r.oid)"
                    + " from pg_roles r where rolname='msf_ci_source_runtime'");
            requireRuntime(connection, "select has_database_privilege('msf_ci_source_runtime',current_database(),'CONNECT')"
                    + " and not has_database_privilege('msf_ci_source_runtime',current_database(),'CREATE,TEMP')"
                    + " and has_schema_privilege('msf_ci_source_runtime','public','USAGE')"
                    + " and not has_schema_privilege('msf_ci_source_runtime','public','CREATE')");
            var observed = new java.util.HashSet<String>();
            try (var statement = connection.createStatement(); var rows = statement.executeQuery(
                    "select c.oid,c.relkind,c.relname from pg_class c join pg_namespace n on n.oid=c.relnamespace where n.nspname='public' and c.relkind in ('r','p','v','m','f','S')")) {
                while (rows.next()) {
                    long oid = rows.getLong(1); String relation = rows.getString(3);
                    observed.add(relation);
                    try (var query = connection.prepareStatement(runtimeSql(rows.getString(2).equals("S")
                            ? "select not has_sequence_privilege('msf_ci_source_runtime',?::oid,'USAGE,SELECT,UPDATE')"
                            : "select not has_table_privilege('msf_ci_source_runtime',?::oid,'SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER')"))) {
                        query.setLong(1, oid); try (var result = query.executeQuery()) { require(result); }
                    }
                    if (rows.getString(2).equals("S")) continue;
                    try (var query = connection.prepareStatement(runtimeSql("select a.attname,has_column_privilege('msf_ci_source_runtime',a.attrelid,a.attnum,'SELECT'),has_column_privilege('msf_ci_source_runtime',a.attrelid,a.attnum,'INSERT'),has_column_privilege('msf_ci_source_runtime',a.attrelid,a.attnum,'UPDATE'),has_column_privilege('msf_ci_source_runtime',a.attrelid,a.attnum,'REFERENCES') from pg_attribute a where a.attrelid=?::oid and a.attnum>0 and not a.attisdropped"))) {
                        query.setLong(1, oid);
                        try (var columns = query.executeQuery()) {
                            while (columns.next()) {
                                for (int index = 0; index < 4; index++) {
                                    String privilege = List.of("select", "insert", "update", "references").get(index);
                                    boolean allowed = expected.getOrDefault(relation, Map.of()).getOrDefault(privilege, Set.of()).contains(columns.getString(1));
                                    if (columns.getBoolean(index + 2) != allowed) throw rejected();
                                }
                            }
                        }
                    }
                }
            }
            if (!observed.containsAll(expected.keySet())) throw rejected();
            verifyFunctionRights(connection);
        }
    }

    private void verifyFunctionRights(Connection connection) throws Exception {
        Set<String> signatures=declaredFunctionGrants(canonicalResource().replace(spec.runtime,RUNTIME));
        var expected=new java.util.HashSet<Long>();
        for(String signature:signatures)try(var statement=connection.prepareStatement("select pg_catalog.to_regprocedure(?)::oid")) {
            statement.setString(1,signature);
            try(var rows=statement.executeQuery()) {
                if(!rows.next()||rows.getObject(1)==null||!expected.add(rows.getLong(1))||rows.next())throw rejected();
            }
        }
        var found=new java.util.HashSet<Long>();
        try(var statement=connection.createStatement();var rows=statement.executeQuery(runtimeSql("""
                select p.oid,p.prosecdef,p.prorettype='pg_catalog.trigger'::regtype,
                       p.proowner=(select oid from pg_roles where rolname='msf_ci_bootstrap'),
                       p.proowner=(select oid from pg_roles where rolname='msf_ci_source_runtime'),
                       has_function_privilege('msf_ci_source_runtime',p.oid,'EXECUTE'),
                       exists(select 1 from aclexplode(coalesce(p.proacl,acldefault('f',p.proowner))) a
                              where a.grantee=0 and a.privilege_type='EXECUTE'),
                       exists(select 1 from aclexplode(coalesce(p.proacl,acldefault('f',p.proowner))) a
                              where a.grantee=(select oid from pg_roles where rolname='msf_ci_source_runtime') and a.privilege_type='EXECUTE'),
                       exists(select 1 from aclexplode(coalesce(p.proacl,acldefault('f',p.proowner))) a
                              where a.grantee=(select oid from pg_roles where rolname='msf_ci_source_runtime') and a.is_grantable)
                  from pg_proc p join pg_namespace n on n.oid=p.pronamespace where n.nspname='public'
                """))) {
            while(rows.next()) {
                boolean allowed=expected.contains(rows.getLong(1));
                if(rows.getBoolean(5)||rows.getBoolean(9)||rows.getBoolean(8)!=allowed)throw rejected();
                if(allowed) {
                    if(rows.getBoolean(2)||rows.getBoolean(3)||!rows.getBoolean(4)||!rows.getBoolean(6)||rows.getBoolean(7))throw rejected();
                    found.add(rows.getLong(1));
                } else if(rows.getBoolean(6)!=rows.getBoolean(7))throw rejected();
            }
        }
        if(!found.equals(expected)||found.size()!=22)throw rejected();
    }

    private void seedOwnedFoundationPrerequisites() throws Exception {
        Map<String, String> values = new HashMap<>();
        for (String name : List.of("work", "hazard", "workCommand", "hazardCommand", "trace", "workAudit", "hazardAudit", "workIdempotency", "hazardIdempotency"))
            values.put(name, UUID.randomUUID().toString());
        hazard = UUID.fromString(values.get("hazard"));
        values.put("tenant", identity.tenant().toString()); values.put("actor", identity.actor().toString());
        values.put("account", identity.account().toString()); values.put("member", identity.member().toString());
        values.put("organization", identity.organization().toString());
        values.put("occurredAt", java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS).toString());
        String sql;
        try (var input = CiPg18SourceFixture.class.getResourceAsStream("/ci-pg18-source/owned-foundation-prerequisites.sql")) {
            if (input == null) throw rejected();
            sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        for (var value : values.entrySet()) sql = sql.replace("@" + value.getKey() + "@", value.getValue());
        if (sql.contains("@")) throw rejected();
        try (Connection connection = connect(true)) {
            connection.setAutoCommit(false);
            try (var statement = connection.createStatement()) { statement.setQueryTimeout(10); statement.execute(sql); }
            connection.commit();
        }
    }

    private void seedIdentityAndSession() {
        identity = new Identity(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        var manager = new JdbcTransactionManager(admin);
        var transaction = new TransactionTemplate(manager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        var guard = new PostgresAuthorizationWriteGuard(admin);
        transaction.executeWithoutResult(status -> {
            guard.forGlobalSecurityChange();
            Connection connection = DataSourceUtils.getConnection(admin);
            try {
                execute(connection, "insert into public.tenant(id,code,name,status,auth_epoch) values(?,?,?,'ACTIVE',1)", identity.tenant(), "ci-source-" + identity.tenant(), "CI source identity");
                scalar(connection, "select set_config('app.tenant_id',?,true)", identity.tenant().toString());
                execute(connection, "insert into public.account(id,login_key,status,credential_version) values(?,?,'ACTIVE',1)", identity.account(), "ci-source-" + identity.account());
                execute(connection, "insert into public.principal(id,kind,account_id,status) values(?,'HUMAN',?,'ACTIVE')", identity.actor(), identity.account());
                execute(connection, "insert into public.tenant_member(tenant_id,id,account_id,status,auth_epoch,display_name) values(?,?,?,'ACTIVE',1,'CI source author')", identity.tenant(), identity.member(), identity.account());
                execute(connection, "insert into public.org_unit(tenant_id,id,code,name,active) values(?,?,?,'CI source organization',true)", identity.tenant(), identity.organization(), "ci-" + identity.organization());
                execute(connection, "insert into public.member_org(tenant_id,member_id,org_id,kind,valid_from) values(?,?,?,'PRIMARY',current_timestamp - interval '1 day')", identity.tenant(), identity.member(), identity.organization());
                execute(connection, "insert into public.tenant_application(tenant_id,application_key,enabled,version) values(?,'safety',true,1)", identity.tenant());
                UUID role = UUID.randomUUID(), scope = UUID.randomUUID();
                execute(connection, "insert into public.\"role\"(tenant_id,id,code,name,active) values(?,?,?,'CI source role',true)", identity.tenant(), role, "ci-" + role);
                execute(connection, "insert into public.scope_rule(tenant_id,id,kind,version_no,definition) values(?,?,'EXPLICIT',1,'{}'::jsonb)", identity.tenant(), scope);
                execute(connection, "insert into public.scope_org(tenant_id,scope_rule_id,org_id) values(?,?,?)", identity.tenant(), scope, identity.organization());
                for (String action : List.of("risk_scenario.create", "risk_scenario.update", "hazard_source.read"))
                    execute(connection, "insert into public.role_permission(tenant_id,role_id,permission_id,scope_rule_id) select ?,?,id,? from public.permission where code=?", identity.tenant(), role, scope, action);
                execute(connection, "insert into public.role_grant(tenant_id,id,role_id,subject_kind,member_id,source_type,source_id,granted_by_member_id,valid_from) values(?,?,?,'MEMBER',?,'CI_SOURCE_ACCEPTANCE',?,?,current_timestamp - interval '1 day')", identity.tenant(), UUID.randomUUID(), role, identity.member(), UUID.randomUUID(), identity.member());
                UUID event = UUID.randomUUID();
                Object at = scalar(connection, "select statement_timestamp()");
                String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest("isolated-ci-source-enforcement-v1".getBytes(StandardCharsets.UTF_8)));
                execute(connection, "insert into public.identity_sensitive_event(id,operation,result,provenance,database_role,trace_id,policy_version,occurred_at) values(?,'ACTIVATE_SESSION_AUTHORITY','SUCCESS','CONTROLLED_ACTIVATION',current_user,?,'session-sensitive-v1',?)", event, UUID.randomUUID(), at);
                execute(connection, "update public.session_authority_activation set state='ENFORCING',version=1,activated_at=?,activated_by_database_role=current_user,activation_event_id=?,activation_evidence_sha256=? where singleton and state='OFF' and version=0", at, event, digest);
                if(spec==Spec.SOURCE)session = CiPg18SourceSession.create(connection, identity.tenant(), identity.account(), identity.actor(), identity.member(), keys);
            } catch (Exception failure) { throw rejected(); }
            finally { DataSourceUtils.releaseConnection(connection, admin); }
        });
    }

    private Map<String, Map<String, Set<String>>> expectedRights() throws Exception {
        var pattern = Pattern.compile("grant (select|insert|update)\\(([a-z0-9_,]+)\\) on public\\.\"([a-z_]+)\" to msf_ci_source_runtime;");
        Map<String, Map<String, Set<String>>> expected = new HashMap<>();
        String text=canonicalResource().replace(spec.runtime,RUNTIME);declaredFunctionGrants(text);
        for (String line : text.lines().toList()) {
            var match = pattern.matcher(line);
            if (match.matches()) expected.computeIfAbsent(match.group(3), ignored -> new HashMap<>())
                    .put(match.group(1), Set.of(match.group(2).split(",")));
            else if(FUNCTION_GRANT.matcher(line).matches()) { /* Independently checked against the fixed signature closure above. */ }
            else if (line.startsWith("grant ") && !line.startsWith("grant connect ") && !line.startsWith("grant usage ")) throw rejected();
        }
        if (expected.size() != (spec==Spec.SOURCE?30:31)) throw rejected();
        return expected;
    }
    static Set<String> declaredFunctionGrants(String text) {
        var found=new java.util.HashSet<String>();
        for(String line:text.lines().toList()) {
            if(!line.stripLeading().toLowerCase(java.util.Locale.ROOT).startsWith("grant execute"))continue;
            var match=FUNCTION_GRANT.matcher(line);
            if(!match.matches()||!REQUIRED_FUNCTIONS.contains(match.group(1))||!found.add(match.group(1)))throw rejected();
        }
        if(!found.equals(REQUIRED_FUNCTIONS))throw rejected();
        return Set.copyOf(found);
    }
    private String runtimeSql(String sql){return sql.replace("msf_ci_source_runtime",spec.runtime);}
    private void requireRuntime(Connection connection,String sql)throws SQLException{require(connection,runtimeSql(sql));}
    private String canonicalResource()throws Exception{
        String sql=resource().replace("msf_ci_risk_source",spec.database).replace("msf_ci_source_runtime",spec.runtime);
        if(spec==Spec.RECOVERY){
            try(var in=CiPg18SourceFixture.class.getResourceAsStream("/ci-pg18-recovery/canonical-extra-grants.sql")){
                if(in==null)throw rejected();sql+="\n"+new String(in.readAllBytes(),StandardCharsets.UTF_8);
            }
        }
        return sql;
    }
    private static String resource() throws Exception {
        try (var input = CiPg18SourceFixture.class.getResourceAsStream(GRANTS)) {
            if (input == null) throw rejected();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
    private static void execute(Connection connection, String sql, Object... arguments) throws SQLException {
        try (PreparedStatement statement = prepared(connection, sql, arguments)) {
            if (statement.executeUpdate() != 1) throw rejected();
        }
    }
    private static Object scalar(Connection connection, String sql, Object... arguments) throws SQLException {
        try (PreparedStatement statement = prepared(connection, sql, arguments); var rows = statement.executeQuery()) {
            if (!rows.next()) throw rejected(); Object value = rows.getObject(1);
            if (rows.next()) throw rejected(); return value;
        }
    }
    private static PreparedStatement prepared(Connection connection, String sql, Object... arguments) throws SQLException {
        var statement = connection.prepareStatement(sql);
        for (int index = 0; index < arguments.length; index++) statement.setObject(index + 1, arguments[index]);
        return statement;
    }
    private static void require(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) { require(rows); }
    }
    private static void require(ResultSet rows) throws SQLException {
        if (!rows.next() || !rows.getBoolean(1) || rows.wasNull() || rows.next()) throw rejected();
    }
    private void ensureOpen() { if (closed) throw rejected(); }
    private static IllegalStateException rejected() { return new IllegalStateException("CI source fixture rejected"); }
    @Override public void close() {
        if (session != null) { session.close(); session = null; }
        closed = true; // The harness exclusively owns container/JVM/volume disposal; unknown leases are retained until then.
    }
}
