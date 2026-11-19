package com.mightyoung.softwarefactory;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** All cases stop before opening any database connection. */
class FixturePostgresTargetTest {
    private static final String DATABASE = "msf_d0_fixture_test";
    private static final String URL = "jdbc:postgresql://192.0.2.10:5432/" + DATABASE;

    @Test
    void acceptsAnExplicitlyAllowlistedDisposableDatabaseWithoutAHistoricalName() {
        var target = FixturePostgresTarget.validateRepositoryInputs(environment());
        assertThat(target).isEqualTo(new FixturePostgresTarget.Target(URL, DATABASE));
    }

    @Test
    void rejectsMissingOrMismatchedDisposableAcknowledgementBeforeNetwork() {
        for (String value : new String[] {"", "I_UNDERSTAND_THIS_IS_A_DISPOSABLE_DB:other_test"}) {
            var environment = environment();
            environment.put("LAN_PG_DEDICATED_TEST_DB_ACK", value);
            assertThatThrownBy(() -> FixturePostgresTarget.preflightRepository(environment))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage(value.isEmpty()
                            ? "Required LAN PostgreSQL test input is missing: LAN_PG_DEDICATED_TEST_DB_ACK"
                            : "Dedicated disposable test database confirmation is missing or does not match the database name");
        }
    }

    @Test
    void rejectsDatabaseNameMismatchAndAppTargetMismatch() {
        var environment = environment();
        environment.put("LAN_PG_TEST_DATABASE_NAME", "other_test");
        assertThatThrownBy(() -> FixturePostgresTarget.preflightRepository(environment))
                .hasMessageContaining("must exactly match");
        var mismatchedAppTarget = environment();
        mismatchedAppTarget.put("LAN_PG_TEST_JDBC_URL", "jdbc:postgresql://192.0.2.10:5432/other_test");
        assertThatThrownBy(() -> FixturePostgresTarget.preflightRepository(mismatchedAppTarget))
                .hasMessageContaining("must match the URL");
    }

    @Test
    void rejectsMissingAllowlistAndIdenticalRolesBeforeNetwork() {
        var environment = environment();
        environment.remove("LAN_PG_TRUSTED_TEST_TARGET_ALLOWLIST");
        assertThatThrownBy(() -> FixturePostgresTarget.preflightRepository(environment))
                .hasMessageContaining("allowlist is missing");
        var sameRoles = environment();
        sameRoles.put("LAN_PG_APP_USERNAME", sameRoles.get("LAN_PG_USERNAME"));
        assertThatThrownBy(() -> FixturePostgresTarget.preflightRepository(sameRoles))
                .hasMessage("Migration and application PostgreSQL roles must be separate");
    }

    @Test
    void rejectsMissingOrOtherDatabaseExclusiveAcknowledgementBeforeNetwork() {
        var environment = environment();
        environment.remove("LAN_PG_EXCLUSIVE_ASYNC_TEST_DB_ACK");
        assertThatThrownBy(() -> FixturePostgresTarget.preflightRepository(environment))
                .hasMessageContaining("exclusive database");
        environment.put("LAN_PG_EXCLUSIVE_ASYNC_TEST_DB_ACK", "I_CONFIRM_EXCLUSIVE_ASYNC_TEST_DB:other_test");
        assertThatThrownBy(() -> FixturePostgresTarget.preflightRepository(environment))
                .hasMessageContaining("exclusive database");
    }

    @Test
    void finalizationRequiresItsOwnMatchingUrlAndWriteAcknowledgementBeforeNetwork() {
        var environment = environment();
        assertThatThrownBy(() -> FixturePostgresTarget.preflightFinalization(environment))
                .hasMessageContaining("Finalization fixtures require");
        environment.put("LAN_PG_FINALIZE_TEST_JDBC_URL", URL);
        environment.put("LAN_PG_FINALIZE_TEST_ACK", "I_CONFIRM_FINALIZE_FIXTURE_WRITES:other_test");
        assertThatThrownBy(() -> FixturePostgresTarget.preflightFinalization(environment))
                .hasMessageContaining("Finalization fixtures require");
        environment.put("LAN_PG_FINALIZE_TEST_ACK", "I_CONFIRM_FINALIZE_FIXTURE_WRITES:" + DATABASE);
        assertThat(FixturePostgresTarget.validateFinalizationInputs(environment).jdbcUrl()).isEqualTo(URL);
        environment.put("LAN_PG_FINALIZE_TEST_JDBC_URL", "jdbc:postgresql://192.0.2.10:5432/other_test");
        assertThatThrownBy(() -> FixturePostgresTarget.preflightFinalization(environment))
                .hasMessageContaining("Finalization fixtures require");
    }

    private static Map<String, String> environment() {
        var environment = new HashMap<String, String>();
        environment.put("LAN_PG_JDBC_URL", URL);
        environment.put("LAN_PG_TEST_JDBC_URL", URL);
        environment.put("LAN_PG_TEST_DATABASE_NAME", DATABASE);
        environment.put("LAN_PG_TRUSTED_TEST_TARGET_ALLOWLIST", URL);
        environment.put("LAN_PG_DEDICATED_TEST_DB_ACK", "I_UNDERSTAND_THIS_IS_A_DISPOSABLE_DB:" + DATABASE);
        environment.put("LAN_PG_EXCLUSIVE_ASYNC_TEST_DB_ACK", "I_CONFIRM_EXCLUSIVE_ASYNC_TEST_DB:" + DATABASE);
        environment.put("LAN_PG_USERNAME", "synthetic_migrator");
        environment.put("LAN_PG_PASSWORD", "synthetic-not-used");
        environment.put("LAN_PG_APP_USERNAME", "synthetic_runtime");
        environment.put("LAN_PG_APP_PASSWORD", "synthetic-not-used");
        return environment;
    }
}
