package com.mightyoung.softwarefactory;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdentityAcceptanceTargetTest {

    private static final String DATABASE = "identity_acceptance_test";
    private static final String URL = "jdbc:postgresql://192.0.2.10:5432/" + DATABASE;

    @Test
    void missingOrMismatchedWriteAcknowledgementFailsBeforeTheNetworkPreflight() {
        var environment = environment();
        assertThatThrownBy(() -> IdentityAcceptanceTarget.preflightFromEnvironment(environment))
                .hasMessage("Identity acceptance requires database-specific fixture-write confirmation");
        environment.put("LAN_PG_IDENTITY_ACCEPTANCE_ACK", "I_CONFIRM_IDENTITY_FIXTURE_WRITES:another_database");
        assertThatThrownBy(() -> IdentityAcceptanceTarget.validateInputs(environment))
                .hasMessage("Identity acceptance requires database-specific fixture-write confirmation");
    }

    @Test
    void matchingWriteAcknowledgementRetainsTheExistingTargetAndRoleSeparationGate() {
        var environment = environment();
        environment.put("LAN_PG_IDENTITY_ACCEPTANCE_ACK", "I_CONFIRM_IDENTITY_FIXTURE_WRITES:" + DATABASE);
        assertThat(IdentityAcceptanceTarget.validateInputs(environment).jdbcUrl()).isEqualTo(URL);
        environment.put("LAN_PG_APP_USERNAME", "synthetic_migrator");
        assertThatThrownBy(() -> IdentityAcceptanceTarget.validateInputs(environment))
                .hasMessage("Migration and application PostgreSQL roles must be separate");
    }

    @Test
    void identityProfileUsesRuntimeCredentialsAndDisablesUnrelatedEngines() throws Exception {
        var source = new YamlPropertySourceLoader().load("identity-acceptance",
                new ClassPathResource("application-identity-postgres-acceptance.yml")).getFirst();
        assertThat(source.getProperty("spring.datasource.username")).isEqualTo("${LAN_PG_APP_USERNAME}");
        assertThat(source.getProperty("spring.datasource.hikari.maximum-pool-size")).isEqualTo(1);
        assertThat(source.getProperty("flowable.process.enabled")).isEqualTo(false);
        assertThat(source.getProperty("flowable.idm.enabled")).isEqualTo(false);
        assertThat(source.getProperty("flowable.eventregistry.enabled")).isEqualTo(false);
        assertThat(source.getProperty("spring.modulith.events.externalization.enabled")).isEqualTo(false);
        assertThat(source.getProperty("spring.autoconfigure.exclude[0]"))
                .isEqualTo("org.springframework.modulith.events.jdbc.JdbcEventPublicationAutoConfiguration");
        assertThat(source.getProperty("spring.autoconfigure.exclude[1]"))
                .isEqualTo("org.springframework.modulith.events.config.EventPublicationAutoConfiguration");
    }

    private static Map<String, String> environment() {
        return new HashMap<>(Map.of(
                "LAN_PG_JDBC_URL", URL,
                "LAN_PG_TEST_JDBC_URL", URL,
                "LAN_PG_TEST_DATABASE_NAME", DATABASE,
                "LAN_PG_USERNAME", "synthetic_migrator",
                "LAN_PG_PASSWORD", "synthetic-not-used",
                "LAN_PG_APP_USERNAME", "synthetic_runtime",
                "LAN_PG_APP_PASSWORD", "synthetic-not-used",
                "LAN_PG_TRUSTED_TEST_TARGET_ALLOWLIST", URL,
                "LAN_PG_DEDICATED_TEST_DB_ACK", "I_UNDERSTAND_THIS_IS_A_DISPOSABLE_DB:" + DATABASE));
    }
}
