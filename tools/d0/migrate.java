import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import org.flywaydb.core.Flyway;

/** Run from repository root, against an explicitly acknowledged isolated PostgreSQL 18 target. */
class migrate {
    public static void main(String[] args) throws Exception {
        if (args.length != 0) throw new IllegalArgumentException("No positional arguments accepted");
        String url = bootstrap.required("D0_JDBC_URL"), db = bootstrap.required("D0_DATABASE");
        bootstrap.checkTarget(url, db, bootstrap.required("D0_BOOTSTRAP_ACK"));
        String migrator = bootstrap.required("D0_MIGRATOR_USERNAME");
        String secret = bootstrap.required("D0_MIGRATOR_PASSWORD");
        String operator = bootstrap.required("D0_OPERATOR_USERNAME");
        String operatorSecret = bootstrap.required("D0_OPERATOR_PASSWORD");
        if (migrator.equals(operator)) throw new IllegalArgumentException("Separate migration and operator identities required");
        try (var c = DriverManager.getConnection(url, migrator, secret);
             var s = c.createStatement();
             var r = s.executeQuery("select current_database(), current_setting('server_version_num')::int, "
                 + "rolsuper or rolbypassrls or rolcreatedb or rolcreaterole or rolreplication "
                 + "from pg_roles where rolname=current_user")) {
            r.next();
            if (!db.equals(r.getString(1)) || r.getInt(2) < 180000 || r.getInt(2) >= 190000 || r.getBoolean(3))
                throw new IllegalArgumentException("Unexpected database/version or privileged migrator");
        }
        // V23 explicitly requires bypass of FORCE RLS. Never elevate the ordinary migrator.
        var current = flyway(url, migrator, secret, "latest").info().current();
        if (current == null || current.getVersion().compareTo(org.flywaydb.core.api.MigrationVersion.fromVersion("23")) < 0) {
            flyway(url, migrator, secret, "22").migrate();
            flyway(url, operator, operatorSecret, "23").migrate();
        }
        try (var c = DriverManager.getConnection(url, operator, operatorSecret); var s = c.createStatement()) {
            // Repeatable ownership repair also covers a previous interruption immediately after V23.
            s.execute(Files.readString(Path.of("backend/src/main/resources/db/operator/V23-superuser-apply.sql")));
        }
        var application = flyway(url, migrator, secret, "latest");
        application.migrate();
        application.validate();
        System.out.println("D0_MIGRATION_VALIDATED database=" + db + " version=" + application.info().current().getVersion());
    }

    static Flyway flyway(String url, String user, String password, String target) {
        return Flyway.configure().dataSource(url, user, password)
            .locations("filesystem:backend/src/main/resources/db/migration")
            .table("flyway_schema_history_app").target(target).load();
    }
}
