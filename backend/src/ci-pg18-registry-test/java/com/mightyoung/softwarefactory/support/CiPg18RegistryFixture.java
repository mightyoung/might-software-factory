package com.mightyoung.softwarefactory.support;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.datasource.AbstractDataSource;

/** New isolated CI database only; original migrations and no business/identity seed graph. */
final class CiPg18RegistryFixture implements AutoCloseable {
    static final List<String> VERSIONS = List.of("2","3","4","5","6","7","8","11","12","13","14","15","16","17","18","18.1","18.2","18.3","19","20","21","22","23","24","25","26","27","28","29","30","31");
    private static final Set<String> PHASES = Set.of("open","target-version","target-marker","empty-schema","empty-runtime","migrate","history","publication-schema","runtime-role","runtime-rights","native-graph","closed","unknown");
    private static final Set<String> INSERT = Set.of("id","event_type","listener_id","publication_date","serialized_event");
    private final CiPg18RegistryTarget target;
    private Flyway flyway;
    private boolean closed;
    private CiPg18RegistryFixture(CiPg18RegistryTarget target) { this.target = target; }

    static CiPg18RegistryFixture open(CiPg18RegistryTarget target) {
        var fixture = new CiPg18RegistryFixture(target);
        String phase = "open";
        try {
            try (Connection connection = fixture.connect(true)) {
                phase = "empty-schema";
                require(connection, "select not exists(select 1 from pg_catalog.pg_class c join pg_catalog.pg_namespace n on n.oid=c.relnamespace where n.nspname='public')"
                        + " and not exists(select 1 from pg_catalog.pg_proc p join pg_catalog.pg_namespace n on n.oid=p.pronamespace where n.nspname='public')"
                        + " and not exists(select 1 from pg_catalog.pg_type t join pg_catalog.pg_namespace n on n.oid=t.typnamespace where n.nspname='public')"
                        + " and not exists(select 1 from pg_catalog.pg_namespace where nspname not in ('public','pg_catalog','information_schema','pg_toast'))");
                phase = "empty-runtime";
                require(connection, "select not exists(select 1 from pg_catalog.pg_roles where rolname='msf_ci_registry_runtime')");
            }
            phase = "migrate";
            fixture.flyway = Flyway.configure().dataSource(fixture.source(true)).locations("classpath:db/migration")
                    .table("flyway_schema_history_app").baselineOnMigrate(false).outOfOrder(false)
                    .cleanDisabled(true).validateMigrationNaming(true).load();
            if (fixture.flyway.migrate().migrationsExecuted != VERSIONS.size()) throw new IllegalStateException();
            phase = "runtime-role";
            try (Connection connection = fixture.connect(true)) {
                connection.setAutoCommit(false);
                try (Statement statement = connection.createStatement()) {
                    statement.execute("create role msf_ci_registry_runtime login nosuperuser nocreatedb nocreaterole noinherit noreplication nobypassrls password '" + target.runtimePassword() + "'");
                    try (var input = CiPg18RegistryFixture.class.getResourceAsStream("/ci-pg18-registry/runtime-grants.sql")) {
                        if (input == null) throw new IllegalStateException();
                        statement.execute(new String(input.readAllBytes(), StandardCharsets.UTF_8));
                    }
                }
                connection.commit();
            }
            phase = "history"; fixture.verifyHistory();
            phase = "publication-schema"; fixture.verifySchema();
            phase = "runtime-rights"; fixture.verifyRights();
            fixture.writeEvidence();
            return fixture;
        } catch (Throwable failure) { fixture.closed = true; throw failed(phase, failure); }
    }
    DataSource source(boolean admin) {
        return new AbstractDataSource() {
            @Override public Connection getConnection() throws SQLException { return connect(admin); }
            @Override public Connection getConnection(String user, String password) throws SQLException { throw new SQLException("Fixed CI connection identity", "08004"); }
        };
    }
    Connection admin() throws SQLException { return connect(true); }
    private Connection connect(boolean admin) throws SQLException {
        if (closed) throw failed("closed", null);
        Connection connection = null;
        try {
            Properties properties = new Properties();
            properties.setProperty("user", admin ? target.adminUser() : target.runtimeUser());
            properties.setProperty("password", admin ? target.adminPassword() : target.runtimePassword());
            properties.setProperty("connectTimeout", "10"); properties.setProperty("socketTimeout", "15");
            properties.setProperty("ApplicationName", "msf-ci-registry-" + target.instanceId());
            connection = DriverManager.getConnection(target.jdbcUrl(), properties);
            if (!"PostgreSQL".equals(connection.getMetaData().getDatabaseProductName()) || connection.getMetaData().getDatabaseMajorVersion() != 18)
                throw failed("target-version", null);
            try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(
                    "select pg_catalog.current_database(),current_user,session_user,pg_catalog.current_setting('msf.ci_instance',true)")) {
                String user = admin ? target.adminUser() : target.runtimeUser();
                if (!rows.next() || !target.databaseName().equals(rows.getString(1)) || !user.equals(rows.getString(2))
                        || !user.equals(rows.getString(3)) || !target.instanceId().toString().equals(rows.getString(4)) || rows.next())
                    throw failed("target-marker", null);
            }
            return connection;
        } catch (Throwable failure) {
            if (connection != null) try { connection.close(); } catch (Throwable ignored) { }
            if (failure instanceof IllegalStateException safe && "CI registry fixture rejected".equals(safe.getMessage())) throw safe;
            throw new SQLException("CI registry connection rejected", sqlState(failure));
        }
    }
    void verifyHistory() {
        try {
            flyway.validate();
            List<String> actual = new ArrayList<>();
            for (var migration : flyway.info().applied()) {
                if (migration.getVersion() == null || !migration.getState().isApplied() || migration.getChecksum() == null) throw new IllegalStateException();
                actual.add(migration.getVersion().getVersion());
            }
            if (!actual.equals(VERSIONS)) throw new IllegalStateException();
        } catch (Throwable failure) { throw failed("history", failure); }
    }
    void verifySchema() {
        try (Connection connection = admin()) {
            require(connection, "select c.relkind='r' and not c.relrowsecurity and not c.relforcerowsecurity and c.relowner=(select oid from pg_catalog.pg_roles where rolname=current_user) from pg_catalog.pg_class c where c.oid='public.event_publication'::pg_catalog.regclass");
            List<String> actual = new ArrayList<>();
            try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery("select a.attname||':'||pg_catalog.format_type(a.atttypid,a.atttypmod)||':'||a.attnotnull::text from pg_catalog.pg_attribute a where a.attrelid='public.event_publication'::pg_catalog.regclass and a.attnum>0 and not a.attisdropped order by a.attnum")) {
                while (rows.next()) actual.add(rows.getString(1));
            }
            if (!actual.equals(List.of("id:uuid:true","listener_id:text:true","event_type:text:true","serialized_event:text:true","publication_date:timestamp with time zone:true","completion_date:timestamp with time zone:false","status:text:false","completion_attempts:integer:false","last_resubmission_date:timestamp with time zone:false"))) throw new IllegalStateException();
            require(connection, "select count(*)=3 and bool_and(i.indisvalid and i.indisready) from pg_catalog.pg_index i where i.indrelid='public.event_publication'::pg_catalog.regclass");
            require(connection, "select count(*)=1 from pg_catalog.pg_constraint where conrelid='public.event_publication'::pg_catalog.regclass and contype='p' and convalidated and conenforced and pg_catalog.pg_get_constraintdef(oid)='PRIMARY KEY (id)'");
            require(connection, "select count(*)=2 from pg_catalog.pg_indexes where schemaname='public' and tablename='event_publication' and ((indexname='event_publication_serialized_event_hash_idx' and indexdef like '% USING hash (serialized_event)') or (indexname='event_publication_by_completion_date_idx' and indexdef like '% USING btree (completion_date)'))");
        } catch (Throwable failure) { throw failed("publication-schema", failure); }
    }
    void verifyRights() {
        try (Connection connection = admin()) {
            require(connection, "select not rolsuper and not rolcreatedb and not rolcreaterole and not rolinherit and not rolreplication and not rolbypassrls and rolcanlogin and not exists(select 1 from pg_catalog.pg_auth_members m where m.member=r.oid) and not exists(select 1 from pg_catalog.pg_class c where c.relowner=r.oid) and not exists(select 1 from pg_catalog.pg_database d where d.datdba=r.oid) from pg_catalog.pg_roles r where rolname='msf_ci_registry_runtime'");
            require(connection, "select pg_catalog.has_database_privilege('msf_ci_registry_runtime',pg_catalog.current_database(),'CONNECT') and not pg_catalog.has_database_privilege('msf_ci_registry_runtime',pg_catalog.current_database(),'CREATE,TEMP') and pg_catalog.has_schema_privilege('msf_ci_registry_runtime','public','USAGE') and not pg_catalog.has_schema_privilege('msf_ci_registry_runtime','public','CREATE')");
            try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery("select c.oid,c.relkind,c.relname from pg_catalog.pg_class c join pg_catalog.pg_namespace n on n.oid=c.relnamespace where n.nspname='public' and c.relkind in ('r','p','v','m','f','S') order by c.relname")) {
                while (rows.next()) {
                    long oid = rows.getLong(1); String kind = rows.getString(2), relation = rows.getString(3);
                    if (kind.equals("S")) {
                        if (privilege(connection, "select pg_catalog.has_sequence_privilege('msf_ci_registry_runtime',?::oid,'USAGE,SELECT,UPDATE')", oid)) throw new IllegalStateException();
                        continue;
                    }
                    if (privilege(connection, "select pg_catalog.has_table_privilege('msf_ci_registry_runtime',?::oid,'SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER')", oid)) throw new IllegalStateException();
                    try (PreparedStatement query = connection.prepareStatement("select a.attname,pg_catalog.has_column_privilege('msf_ci_registry_runtime',a.attrelid,a.attnum,'SELECT'),pg_catalog.has_column_privilege('msf_ci_registry_runtime',a.attrelid,a.attnum,'INSERT'),pg_catalog.has_column_privilege('msf_ci_registry_runtime',a.attrelid,a.attnum,'UPDATE'),pg_catalog.has_column_privilege('msf_ci_registry_runtime',a.attrelid,a.attnum,'REFERENCES') from pg_catalog.pg_attribute a where a.attrelid=?::oid and a.attnum>0 and not a.attisdropped")) {
                        query.setLong(1, oid);
                        try (ResultSet columns = query.executeQuery()) {
                            while (columns.next()) {
                                boolean publication = relation.equals("event_publication"); String column = columns.getString(1);
                                if (columns.getBoolean(2) != publication || columns.getBoolean(3) != (publication && INSERT.contains(column))
                                        || columns.getBoolean(4) != (publication && column.equals("completion_date")) || columns.getBoolean(5)) throw new IllegalStateException();
                            }
                        }
                    }
                }
            }
        } catch (Throwable failure) { throw failed("runtime-rights", failure); }
    }
    private static boolean privilege(Connection connection, String sql, long oid) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, oid); try (ResultSet rows = statement.executeQuery()) { if (!rows.next()) throw new SQLException("Missing privilege observation"); return rows.getBoolean(1); }
        }
    }
    private static void require(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            if (!rows.next() || !rows.getBoolean(1) || rows.wasNull() || rows.next()) throw new SQLException("Fixed CI registry invariant rejected");
        }
    }
    record Stored(UUID id, String type, String listener, String serialized, boolean completed) {}
    Stored observe(UUID id) {
        try (Connection connection = admin(); PreparedStatement statement = connection.prepareStatement("select id,event_type,listener_id,serialized_event,completion_date is not null from public.event_publication where id=?")) {
            statement.setObject(1, id);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) throw new IllegalStateException("Expected owned publication");
                var value = new Stored(rows.getObject(1,UUID.class),rows.getString(2),rows.getString(3),rows.getString(4),rows.getBoolean(5));
                if (rows.next()) throw new IllegalStateException(); return value;
            }
        } catch (Throwable failure) { throw failed("native-graph", failure); }
    }
    Stored find(String serialized) {
        try (Connection connection = admin(); PreparedStatement statement = connection.prepareStatement("select id from public.event_publication where serialized_event=?")) {
            statement.setString(1, serialized);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) throw new IllegalStateException(); UUID id = rows.getObject(1, UUID.class);
                if (rows.next()) throw new IllegalStateException(); return observe(id);
            }
        } catch (Throwable failure) { throw failed("native-graph", failure); }
    }
    int count(String serialized) {
        try (Connection connection = admin(); PreparedStatement statement = connection.prepareStatement("select count(*) from public.event_publication where serialized_event=?")) {
            statement.setString(1, serialized); try (ResultSet rows = statement.executeQuery()) { rows.next(); return rows.getInt(1); }
        } catch (Throwable failure) { throw failed("native-graph", failure); }
    }
    void malformed(UUID id, String value) {
        try (Connection connection = admin(); PreparedStatement statement = connection.prepareStatement("update public.event_publication set serialized_event=? where id=?")) {
            statement.setString(1, value); statement.setObject(2, id); if (statement.executeUpdate()!=1) throw new IllegalStateException();
        } catch (Throwable failure) { throw failed("native-graph", failure); }
    }
    private void writeEvidence() throws Exception {
        StringBuilder migrations = new StringBuilder();
        for (var migration : flyway.info().applied()) {
            if (!migration.getScript().matches("V[0-9]+(?:_[0-9]+)*__[A-Za-z0-9_]+\\.sql")) throw new IllegalStateException();
            if (!migrations.isEmpty()) migrations.append(',');
            migrations.append("{\"version\":\"").append(migration.getVersion().getVersion()).append("\",\"script\":\"")
                    .append(migration.getScript()).append("\",\"checksum\":").append(migration.getChecksum()).append('}');
        }
        String json = "{\"schemaVersion\":1,\"postgresMajor\":18,\"database\":\"msf_ci_risk_registry\",\"instanceMarkerVerified\":true,\"imageDigest\":\""+target.imageDigest()+"\",\"migrations\":["+migrations+"],\"publicationSchemaVerified\":true,\"runtimeRightsVerified\":true}";
        Files.writeString(Path.of("target","ci-pg18-registry-safe-evidence.json"), json, StandardCharsets.UTF_8);
    }
    static IllegalStateException failed(String phase, Throwable failure) {
        String safePhase = PHASES.contains(phase) ? phase : "unknown";
        String state = sqlState(failure);
        try { Files.writeString(Path.of("target","ci-pg18-registry-safe-failure.json"), "{\"schemaVersion\":1,\"database\":\"msf_ci_risk_registry\",\"phase\":\""+safePhase+"\",\"migrationVersion\":null,\"sqlState\":"+(state==null?"null":"\""+state+"\"")+"}", StandardCharsets.UTF_8); }
        catch (Exception ignored) { }
        return new IllegalStateException("CI registry fixture rejected");
    }
    private static String sqlState(Throwable failure) {
        try { if (failure instanceof SQLException sql && sql.getSQLState()!=null && sql.getSQLState().matches("[0-9A-Z]{5}")) return sql.getSQLState(); }
        catch (Throwable ignored) { } return null;
    }
    @Override public void close() { closed = true; } // Owned container/JVM cleanup removes retained unknown leases.
}
