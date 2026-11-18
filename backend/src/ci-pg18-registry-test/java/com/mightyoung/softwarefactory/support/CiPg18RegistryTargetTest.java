package com.mightyoung.softwarefactory.support;

import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class CiPg18RegistryTargetTest {
    @TempDir Path temporary;
    private Path root, receipt, admin, runtime;
    private Map<String, String> environment;
    private String ownership;
    private static final String MARKER = "11111111-2222-4333-8444-555555555555";

    @BeforeEach void prepare() throws Exception {
        root = temporary.toRealPath();
        receipt = root.resolve("ownership");
        admin = root.resolve("admin.password");
        runtime = root.resolve("runtime.password");
        environment = new HashMap<>(Map.of("GITHUB_ACTIONS", "true", "CI", "true",
                "GITHUB_REPOSITORY", "mightyoung/might-software-factory", "GITHUB_RUN_ID", "12345",
                "GITHUB_RUN_ATTEMPT", "1", "RUNNER_TEMP", root.toString()));
        environment.putAll(Map.of("MSF_CI_PG18_ACK", "ephemeral-risk-registry-only",
                "MSF_CI_PG18_URL", "jdbc:postgresql://127.0.0.1:35432/msf_ci_risk_registry",
                "MSF_CI_PG18_CONTAINER_ID", "a".repeat(64), "MSF_CI_PG18_INSTANCE_ID", MARKER,
                "MSF_CI_PG18_IMAGE_DIGEST", "sha256:" + "b".repeat(64),
                "MSF_CI_PG18_OWNERSHIP_FILE", receipt.toString(),
                "MSF_CI_PG18_ADMIN_PASSWORD_FILE", admin.toString(),
                "MSF_CI_PG18_RUNTIME_PASSWORD_FILE", runtime.toString()));
        ownership = "container_id=" + "a".repeat(64) + "\ninstance_id=" + MARKER
                + "\nhost=127.0.0.1\nport=35432\ndatabase=msf_ci_risk_registry\nimage_digest=sha256:"
                + "b".repeat(64) + "\nrepository=mightyoung/might-software-factory\nrun_id=12345\nrun_attempt=1\nengine_major=28\n";
        write(receipt, ownership); write(admin, "c".repeat(64)); write(runtime, "d".repeat(64));
    }

    @Test void acceptsOnlyMatchingPrivateHarnessReceiptAndNeverRendersSecrets() {
        var target = CiPg18RegistryTarget.from(environment);
        assertThat(target.jdbcUrl()).isEqualTo(environment.get("MSF_CI_PG18_URL"));
        assertThat(target.databaseName()).isEqualTo("msf_ci_risk_registry");
        assertThat(target.instanceId()).isEqualTo(UUID.fromString(MARKER));
        assertThat(target.adminUser()).isEqualTo("msf_ci_bootstrap");
        assertThat(target.runtimeUser()).isEqualTo("msf_ci_registry_runtime");
        assertThat(target.toString()).isEqualTo("CiPg18RegistryTarget[private ephemeral target]");
        assertThat(target.toString()).doesNotContain(target.adminPassword(), target.runtimePassword(), target.jdbcUrl());
    }

    @ParameterizedTest
    @ValueSource(strings = {"GITHUB_ACTIONS", "CI", "GITHUB_REPOSITORY", "GITHUB_RUN_ID", "GITHUB_RUN_ATTEMPT",
            "RUNNER_TEMP", "MSF_CI_PG18_ACK", "MSF_CI_PG18_URL", "MSF_CI_PG18_CONTAINER_ID",
            "MSF_CI_PG18_INSTANCE_ID", "MSF_CI_PG18_IMAGE_DIGEST", "MSF_CI_PG18_OWNERSHIP_FILE",
            "MSF_CI_PG18_ADMIN_PASSWORD_FILE", "MSF_CI_PG18_RUNTIME_PASSWORD_FILE"})
    void missingConfigurationFailsRatherThanSkips(String key) {
        environment.remove(key); rejected();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "jdbc:postgresql://192.168.1.2:54318/msf_ci_risk_registry",
            "jdbc:postgresql://localhost:35432/msf_ci_risk_registry",
            "jdbc:postgresql://127.0.0.1:35432/postgres",
            "jdbc:postgresql://127.0.0.1:35432/msf_ci_risk_registry?host=192.168.1.2",
            "jdbc:postgresql://127.0.0.1:35432/msf_ci_risk_registry?user=postgres",
            "jdbc:postgresql://127.0.0.1:35432/msf_ci_risk_registry?PGDBNAME=postgres",
            "jdbc:postgresql://127.0.0.1:35432/msf_ci_risk_registry#ignored",
            "jdbc:postgresql://user@127.0.0.1:35432/msf_ci_risk_registry",
            "jdbc:postgresql://127.0.0.1,192.168.1.2:35432/msf_ci_risk_registry",
            "jdbc:postgresql://127.0.0.1:0/msf_ci_risk_registry",
            "jdbc:postgresql://127.0.0.1:543/msf_ci_risk_registry",
            "jdbc:postgresql://127.0.0.1:65536/msf_ci_risk_registry",
            "jdbc:postgresql://127.0.0.1:035432/msf_ci_risk_registry",
            "jdbc:postgresql://127.0.0.1:35432/msf_ci_risk_registry/"})
    void refusesAlternateTargetsAndConnectionOverrideProperties(String value) {
        environment.put("MSF_CI_PG18_URL", value); rejected();
    }

    @ParameterizedTest
    @ValueSource(strings = {"LAN_PG_URL", "LAN_PG_APP_PASSWORD", "PG18_TUNNEL_LOCAL_PORT", "MSF_CI_PG18_OTHER"})
    void cannotMixLanTunnelOrUnknownConfiguration(String name) {
        environment.put(name, "present"); rejected();
    }

    @ParameterizedTest
    @ValueSource(strings = {"container", "marker", "host", "port", "database", "digest", "repository", "run", "attempt",
            "old-engine", "missing-engine", "duplicate", "unknown", "blank"})
    void receiptMustIdentifyThisOwnedContainerAndRun(String fault) throws Exception {
        String changed = switch (fault) {
            case "container" -> ownership.replace("a".repeat(64), "e".repeat(64));
            case "marker" -> ownership.replace(MARKER, UUID.randomUUID().toString());
            case "host" -> ownership.replace("host=127.0.0.1", "host=0.0.0.0");
            case "port" -> ownership.replace("port=35432", "port=35433");
            case "database" -> ownership.replace("database=msf_ci_risk_registry", "database=postgres");
            case "digest" -> ownership.replace("b".repeat(64), "e".repeat(64));
            case "repository" -> ownership.replace("repository=mightyoung/might-software-factory", "repository=other/repo");
            case "run" -> ownership.replace("run_id=12345", "run_id=54321");
            case "attempt" -> ownership.replace("run_attempt=1", "run_attempt=2");
            case "old-engine" -> ownership.replace("engine_major=28", "engine_major=27");
            case "missing-engine" -> ownership.replace("engine_major=28\n", "");
            case "duplicate" -> ownership + "port=35432\n";
            case "unknown" -> ownership + "extra=value\n";
            default -> ownership + "\n";
        };
        write(receipt, changed); rejected();
    }

    @Test void refusesSymlinkAndWorldReadableCredentialFiles() throws Exception {
        Files.setPosixFilePermissions(admin, PosixFilePermissions.fromString("rw-r--r--")); rejected();
        Files.setPosixFilePermissions(admin, PosixFilePermissions.fromString("rw-------"));
        Path link = root.resolve("password-link"); Files.createSymbolicLink(link, admin);
        environment.put("MSF_CI_PG18_ADMIN_PASSWORD_FILE", link.toString()); rejected();
    }

    @Test void refusesOversizedMalformedOrSharedCredentials() throws Exception {
        write(admin, "c".repeat(4097)); rejected();
        write(admin, "credential\r\n"); rejected();
        write(admin, "d".repeat(64)); rejected();
    }

    @Test void refusesNoncanonicalIdentityAndDigest() {
        environment.put("MSF_CI_PG18_INSTANCE_ID", "1-2-3-4-5"); rejected();
        environment.put("MSF_CI_PG18_INSTANCE_ID", MARKER);
        environment.put("MSF_CI_PG18_IMAGE_DIGEST", "postgres:18-bookworm"); rejected();
    }

    @Test void captureLaneAcknowledgmentAndDatabaseCannotEnterRegistryTarget() {
        environment.put("MSF_CI_PG18_ACK", "ephemeral-risk-capture-only"); rejected();
        environment.put("MSF_CI_PG18_ACK", "ephemeral-risk-registry-only");
        environment.put("MSF_CI_PG18_URL", "jdbc:postgresql://127.0.0.1:35432/msf_ci_risk_capture"); rejected();
    }

    @Test void actualJacksonConfigurationRoundTripsTheProductionEvent() throws Exception {
        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withUserConfiguration(Class.forName("org.springframework.modulith.events.jackson.JacksonEventSerializationConfiguration"))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBeansOfType(org.springframework.modulith.events.core.EventSerializer.class)).hasSize(1);
                    var serializer = context.getBean(org.springframework.modulith.events.core.EventSerializer.class);
                    assertThat(serializer.getClass().getName()).isEqualTo("org.springframework.modulith.events.jackson.JacksonEventSerializer");
                    var event = new com.mightyoung.softwarefactory.dualprevention.risk.RiskScenarioDraftChangedEvent(
                            UUID.randomUUID(), UUID.randomUUID(), "risk_scenario", UUID.randomUUID(), "risk_scenario.create", 1,
                            java.time.Instant.parse("2026-11-07T00:00:00Z"), UUID.randomUUID(), "CREATE");
                    String serialized = serializer.serialize(event).toString();
                    assertThat(serializer.deserialize(serialized, event.getClass())).isEqualTo(event);
                });
    }

    private static void write(Path path, String value) throws Exception {
        Files.writeString(path, value);
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
    }

    private void rejected() {
        assertThatThrownBy(() -> CiPg18RegistryTarget.from(environment))
                .isInstanceOf(IllegalStateException.class).hasMessage("CI PostgreSQL target rejected").hasNoCause();
    }
}
