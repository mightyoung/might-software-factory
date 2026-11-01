package com.mightyoung.softwarefactory;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Pure input-validation tests. These tests never open a database connection. */
class LanPostgresDatabaseTargetTest {

    private static final String DATABASE = "might_software_factory_pg18_test";
    private static final String REMOTE_URL = "jdbc:postgresql://192.0.2.10:5432/" + DATABASE;

    @Test
    void requiresTheAllowedTargetAndKeepsMigrationAndRuntimeRolesSeparate() {
        Map<String, String> environment = environment(REMOTE_URL);

        var target = LanPostgresCompatibilityIT.DatabaseTarget.forAppRole(environment);

        assertThat(target.databaseName()).isEqualTo(DATABASE);
        assertThat(target.jdbcUrl()).isEqualTo(REMOTE_URL);
        environment.put("LAN_PG_APP_USERNAME", environment.get("LAN_PG_USERNAME"));
        assertThatThrownBy(() -> LanPostgresCompatibilityIT.DatabaseTarget.forAppRole(environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Migration and application PostgreSQL roles must be separate");
    }

    @Test
    void rejectsTargetNotInTheExternallySuppliedExactUrlAllowlist() {
        Map<String, String> environment = environment(REMOTE_URL);
        environment.put("LAN_PG_JDBC_URL", "jdbc:postgresql://192.0.2.99:5432/" + DATABASE);
        environment.put("LAN_PG_TEST_JDBC_URL", environment.get("LAN_PG_JDBC_URL"));

        assertThatThrownBy(() -> LanPostgresCompatibilityIT.DatabaseTarget.forAppRole(environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("LAN_PG_JDBC_URL target is not in LAN_PG_TRUSTED_TEST_TARGET_ALLOWLIST");
    }

    @Test
    void requiresTheSeparatelySuppliedTargetAllowlist() {
        Map<String, String> environment = environment(REMOTE_URL);
        environment.remove("LAN_PG_TRUSTED_TEST_TARGET_ALLOWLIST");

        assertThatThrownBy(() -> LanPostgresCompatibilityIT.DatabaseTarget.forAppRole(environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Trusted PostgreSQL test target allowlist is missing");
    }

    @Test
    void rejectsDriverOverridesEvenWhenTheWholeUrlIsAllowlisted() {
        for (String suffix : new String[] {
                "?PGHOST=192.0.2.99", "?PGPORT=1", "?PGDBNAME=another_database",
                "?host=192.0.2.99", "?port=1", "?dbname=another_database",
                "?user=another_role", "?password=another_password", "?pass%77ord=encoded_password",
                "?service=another_service", "?options=-c%20role%3Danother_role",
                "?currentSchema=another_schema", "?socketFactory=another_factory",
                "?sslmode=require", "?", "#ignored", "#", "?user=another_role#ignored"
        }) {
            Map<String, String> environment = environment(REMOTE_URL + suffix);

            assertThatThrownBy(() -> LanPostgresCompatibilityIT.DatabaseTarget.forAppRole(environment))
                    .as("URL suffix %s must fail before connecting", suffix)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("LAN_PG_JDBC_URL must not contain query parameters or fragments");
        }
    }

    @Test
    void driverQueryPropertiesOverrideTheApparentTargetAndSeparateCredentialsWithoutConnecting() throws Exception {
        Properties credentials = new Properties();
        credentials.setProperty("user", "expected_role");
        credentials.setProperty("password", "expected_placeholder");
        // PostgreSQL is runtime-scoped; exercise its parser without opening a connection.
        Properties parsed = (Properties) Class.forName("org.postgresql.Driver")
                .getMethod("parseURL", String.class, Properties.class)
                .invoke(null, REMOTE_URL
                        + "?PGHOST=192.0.2.99&PGPORT=1&PGDBNAME=another_database"
                        + "&user=another_role&password=another_placeholder", credentials);

        assertThat(parsed.getProperty("PGHOST")).isEqualTo("192.0.2.99");
        assertThat(parsed.getProperty("PGPORT")).isEqualTo("1");
        assertThat(parsed.getProperty("PGDBNAME")).isEqualTo("another_database");
        assertThat(parsed.getProperty("user")).isEqualTo("another_role");
        assertThat(parsed.getProperty("password")).isEqualTo("another_placeholder");
    }

    @Test
    void missingApplicationSecretFailsBeforeOpeningTheMigrationConnection() {
        Map<String, String> environment = environment(REMOTE_URL);
        environment.remove("LAN_PG_APP_PASSWORD");

        assertThatThrownBy(() -> LanPostgresCompatibilityIT.DatabaseTarget.preflightFromEnvironment(environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Required LAN PostgreSQL test input is missing: LAN_PG_APP_PASSWORD");
    }

    @Test
    void loopbackIsAllowedOnlyForTheApprovedDatabaseWithWrapperPortAndExactUrlAllowlist() {
        String tunnelUrl = "jdbc:postgresql://127.0.0.1:60948/" + DATABASE;
        Map<String, String> environment = environment(tunnelUrl);
        environment.put("PG18_TUNNEL_LOCAL_PORT", "60948");

        assertThat(LanPostgresCompatibilityIT.DatabaseTarget.forAppRole(environment).jdbcUrl())
                .isEqualTo(tunnelUrl);

        environment.remove("PG18_TUNNEL_LOCAL_PORT");
        assertThatThrownBy(() -> LanPostgresCompatibilityIT.DatabaseTarget.forAppRole(environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Loopback PostgreSQL targets require the approved PG18 database, wrapper tunnel-port match, and exact allowlisted URL");

        environment.put("PG18_TUNNEL_LOCAL_PORT", "60949");
        assertThatThrownBy(() -> LanPostgresCompatibilityIT.DatabaseTarget.forAppRole(environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Loopback PostgreSQL targets require the approved PG18 database, wrapper tunnel-port match, and exact allowlisted URL");

        environment.put("PG18_TUNNEL_LOCAL_PORT", "60948");
        environment.put("LAN_PG_TRUSTED_TEST_TARGET_ALLOWLIST", "jdbc:postgresql://127.0.0.1:60949/" + DATABASE);
        assertThatThrownBy(() -> LanPostgresCompatibilityIT.DatabaseTarget.forAppRole(environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("LAN_PG_JDBC_URL target is not in LAN_PG_TRUSTED_TEST_TARGET_ALLOWLIST");

        String otherDatabaseUrl = "jdbc:postgresql://127.0.0.1:60948/not_the_approved_db";
        Map<String, String> otherDatabase = environment(otherDatabaseUrl);
        otherDatabase.put("LAN_PG_TEST_DATABASE_NAME", "not_the_approved_db");
        otherDatabase.put("LAN_PG_DEDICATED_TEST_DB_ACK",
                "I_UNDERSTAND_THIS_IS_A_DISPOSABLE_DB:not_the_approved_db");
        otherDatabase.put("PG18_TUNNEL_LOCAL_PORT", "60948");
        assertThatThrownBy(() -> LanPostgresCompatibilityIT.DatabaseTarget.forAppRole(otherDatabase))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Loopback PostgreSQL targets require the approved PG18 database, wrapper tunnel-port match, and exact allowlisted URL");
    }

    @Test
    void compatibilityProfileKeepsSchemaInitializationDisabled() throws IOException {
        var source = new YamlPropertySourceLoader().load("compatibility-profile",
                new ClassPathResource("application-lan-postgres-compatibility.yml")).getFirst();

        assertThat(source.getProperty("spring.flyway.enabled")).isEqualTo(false);
        assertThat(source.getProperty("flowable.database-schema-update")).isEqualTo(false);
        assertThat(source.getProperty("spring.session.jdbc.initialize-schema")).isEqualTo("never");
        assertThat(source.getProperty("spring.session.jdbc.cleanup-cron")).isEqualTo("-");
        assertThat(source.getProperty("spring.sql.init.mode")).isEqualTo("never");
        assertThat(source.getProperty("spring.modulith.events.jdbc.schema-initialization.enabled")).isEqualTo(false);
    }

    @Test
    void readOnlyRoleGateAllowsNonSuperuserMigratorButRejectsElevatedAndAppDdlRoles() {
        LanPostgresCompatibilityIT.DatabaseTarget.assertRolePreflight(
                rolePreflight("might_software_factory_migrator", false, true),
                DATABASE, "might_software_factory_migrator", true);
        LanPostgresCompatibilityIT.DatabaseTarget.assertRolePreflight(
                rolePreflight("might_software_factory_app", false, false),
                DATABASE, "might_software_factory_app", false);

        assertThatThrownBy(() -> LanPostgresCompatibilityIT.DatabaseTarget.assertRolePreflight(
                rolePreflight("might_software_factory_migrator", true, true),
                DATABASE, "might_software_factory_migrator", true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("non-elevated");
        assertThatThrownBy(() -> LanPostgresCompatibilityIT.DatabaseTarget.assertRolePreflight(
                rolePreflight("might_software_factory_app", false, true),
                DATABASE, "might_software_factory_app", false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Application role requires database CONNECT and public USAGE, with no public CREATE");
    }

    @Test
    void backgroundExecutorRequiresADatabaseSpecificExclusiveUseAcknowledgement() {
        Map<String, String> environment = environment(REMOTE_URL);
        assertThatThrownBy(() -> LanPostgresCompatibilityIT.DatabaseTarget.requireExclusiveAsyncDatabase(environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("explicitly confirmed exclusive database");
        environment.put("LAN_PG_EXCLUSIVE_ASYNC_TEST_DB_ACK", "I_CONFIRM_EXCLUSIVE_ASYNC_TEST_DB:some_other_test");
        assertThatThrownBy(() -> LanPostgresCompatibilityIT.DatabaseTarget.requireExclusiveAsyncDatabase(environment))
                .isInstanceOf(IllegalStateException.class);
        environment.put("LAN_PG_EXCLUSIVE_ASYNC_TEST_DB_ACK", "I_CONFIRM_EXCLUSIVE_ASYNC_TEST_DB:" + DATABASE);
        LanPostgresCompatibilityIT.DatabaseTarget.requireExclusiveAsyncDatabase(environment);
    }

    private static Map<String, String> environment(String url) {
        Map<String, String> environment = new HashMap<>();
        environment.put("LAN_PG_JDBC_URL", url);
        environment.put("LAN_PG_USERNAME", "might_software_factory_migrator");
        environment.put("LAN_PG_PASSWORD", "test-only-placeholder");
        environment.put("LAN_PG_TEST_DATABASE_NAME", DATABASE);
        environment.put("LAN_PG_DEDICATED_TEST_DB_ACK", "I_UNDERSTAND_THIS_IS_A_DISPOSABLE_DB:" + DATABASE);
        environment.put("LAN_PG_TRUSTED_TEST_TARGET_ALLOWLIST", url);
        environment.put("LAN_PG_TEST_JDBC_URL", url);
        environment.put("LAN_PG_APP_USERNAME", "might_software_factory_app");
        environment.put("LAN_PG_APP_PASSWORD", "test-only-app-placeholder");
        return environment;
    }

    private static LanPostgresCompatibilityIT.DatabaseTarget.RolePreflight rolePreflight(
            String role, boolean superuser, boolean schemaCreate) {
        return new LanPostgresCompatibilityIT.DatabaseTarget.RolePreflight(
                DATABASE, role, role, "postgres", "postgres", superuser,
                false, false, false, false, false, false, true, true, schemaCreate);
    }
}
