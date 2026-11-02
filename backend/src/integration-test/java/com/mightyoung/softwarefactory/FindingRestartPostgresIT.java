package com.mightyoung.softwarefactory;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flowable.engine.ProcessEngine;
import org.flowable.spring.SpringProcessEngineConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import static org.assertj.core.api.Assertions.assertThat;

/** One graceful process-restart acceptance using two distinct java -jar JVMs and retained owned history.
 * Does not prove abrupt kill, host failure, background job recovery or production deployment.
 */
class FindingRestartPostgresIT {
    private final ObjectMapper json = new ObjectMapper();

    @Test void committedFindingSurvivesActualApplicationJvmRestartWithoutDuplicateProcessOrHistory() throws Exception {
        var target = BusinessWorkflowPostgresFixture.validateTarget();
        Path jar = packagedJar();
        Path work = Files.createTempDirectory("msf-finding-restart-");
        Files.setPosixFilePermissions(work, PosixFilePermissions.fromString("rwx------"));
        List<Process> children = new ArrayList<>();
        try (var fixture = new BusinessWorkflowPostgresFixture(new DelegatingPasswordEncoder(
                "bcrypt", Map.of("bcrypt", new BCryptPasswordEncoder(12))));
             var https = IdentityAcceptanceHttps.create();
             var runtimeSource = runtimeSource(target.jdbcUrl())) {
            try {
                String deploymentId;
                try (var engine = new Engine(runtimeSource)) {
                    deploymentId = engine.value.getRepositoryService().createDeployment().name(fixture.run)
                            .tenantId(fixture.tenantId.toString())
                            .addClasspathResource("processes/finding-remediation-v1.bpmn20.xml")
                .addClasspathResource("processes/finding-remediation-v2.bpmn20.xml").deploy().getId();
                }
                int firstPort = freePort();
                Process first = start(jar, work, "application-1.log", target.jdbcUrl(), firstPort, https);
                children.add(first);
                UUID command = UUID.randomUUID();
                UUID finding;
                JsonNode created;
                try (var browser = awaitHttps(first, firstPort, https)) {
                    login(browser, fixture);
                    created = data(browser.postWithHeaders("/api/v1/findings", json.writeValueAsString(Map.of(
                            "organizationId", fixture.organizationId, "description", fixture.run + " restart",
                            "discoveredAt", OffsetDateTime.now().minusHours(1).toString(), "grade", "UNKNOWN",
                            "sourceCategory", "FIELD_OBSERVATION", "urgency", "NORMAL", "location", "Synthetic restart location")),
                            browser.csrf(), Map.of("Idempotency-Key", command.toString())), 200);
                    finding = UUID.fromString(created.path("findingId").asString());
                    assertThat(created.path("version").asLong()).isEqualTo(1);
                    assertThat(created.path("state").asString()).isEqualTo("PERSON_CONFIRM_PENDING");
                    awaitCompletedPublication(fixture, command);
                }
                Map<String, List<String>> beforeStop = snapshot(fixture, finding, command);
                assertThat(beforeStop.get("receipt")).hasSize(1);
                assertThat(beforeStop.get("audit")).hasSize(1);
                assertThat(beforeStop.get("publication")).hasSize(1);
                assertThat(beforeStop.get("binding")).hasSize(1);
                EngineState beforeRestart;
                try (var engine = new Engine(runtimeSource)) {
                    beforeRestart = inspectEngine(engine.value, fixture, finding, deploymentId);
                }
                assertThat(fixture.<Integer>read(db -> db.queryForObject("""
                        select count(*) from public.finding_command_receipt where tenant_id=? and command_id=?
                           and result_finding_id=? and result_version=1 and completed_at is not null
                        """, Integer.class, fixture.tenantId, command, finding))).isEqualTo(1);
                stopGracefully(first);
                assertThat(first.isAlive()).isFalse();
                assertThat(snapshot(fixture, finding, command)).isEqualTo(beforeStop);

                EngineState persisted;
                try (var engine = new Engine(runtimeSource)) {
                    persisted = inspectEngine(engine.value, fixture, finding, deploymentId);
                    assertThat(persisted).isEqualTo(beforeRestart);
                }
                int secondPort = freePort();
                Process second = start(jar, work, "application-2.log", target.jdbcUrl(), secondPort, https);
                children.add(second);
                assertThat(second.pid()).isNotEqualTo(first.pid());
                try (var browser = awaitHttps(second, secondPort, https)) {
                    login(browser, fixture);
                    JsonNode detail = data(browser.get("/api/v1/entities/finding/" + finding), 200);
                    assertThat(detail.path("id").asString()).isEqualTo(finding.toString());
                    assertThat(detail.path("version").asLong()).isEqualTo(created.path("version").asLong());
                    assertThat(detail.path("state").asString()).isEqualTo(created.path("state").asString());
                    try (var engine = new Engine(runtimeSource)) {
                        assertThat(inspectEngine(engine.value, fixture, finding, deploymentId)).isEqualTo(persisted);
                    }
                    assertThat(snapshot(fixture, finding, command)).isEqualTo(beforeStop);
                }
                stopGracefully(second);
                assertThat(snapshot(fixture, finding, command)).isEqualTo(beforeStop);
            } finally {
                for (Process child : children) stopOwnedChild(child);
            }
        } finally {
            // Only local logs are retained privately; they are never dumped to test output.
            Files.writeString(work.resolve("scope.txt"), "Owned graceful application JVM restart only; business history retained.\n");
            Files.setPosixFilePermissions(work.resolve("scope.txt"), PosixFilePermissions.fromString("rw-------"));
        }
    }

    private void login(IdentityAcceptanceHttps.Browser browser, BusinessWorkflowPostgresFixture fixture) throws Exception {
        assertThat(browser.get("/api/v1/auth/csrf").status()).isEqualTo(204);
        assertThat(browser.post("/api/v1/auth/login", json.writeValueAsString(Map.of(
                "loginKey", fixture.reporter.loginKey(), "password", fixture.reporter.password(),
                "tenantCode", fixture.tenantCode)), browser.csrf()).status()).isEqualTo(204);
        assertThat(browser.get("/api/v1/auth/csrf").status()).isEqualTo(204);
        assertThat(browser.get("/api/v1/session/current").status()).isEqualTo(200);
    }

    private JsonNode data(IdentityAcceptanceHttps.Response response, int expected) throws Exception {
        assertThat(response.status()).as("Real child JVM HTTPS response status").isEqualTo(expected);
        return json.readTree(response.body()).path("data");
    }

    private static Map<String, List<String>> snapshot(BusinessWorkflowPostgresFixture fixture, UUID finding, UUID command) {
        return fixture.read(db -> {
            Map<String, List<String>> snapshot = new LinkedHashMap<>();
            snapshot.put("finding", db.queryForList("select row_to_json(x)::text from public.finding x where tenant_id=? and id=?", String.class, fixture.tenantId, finding));
            snapshot.put("receipt", db.queryForList("select row_to_json(x)::text from public.finding_command_receipt x where tenant_id=? and command_id=?", String.class, fixture.tenantId, command));
            snapshot.put("binding", db.queryForList("select row_to_json(x)::text from public.workflow_binding x where tenant_id=? and business_id=?", String.class, fixture.tenantId, finding));
            snapshot.put("audit", db.queryForList("select row_to_json(x)::text from public.audit_event x where tenant_id=? and resource_id=? order by id", String.class, fixture.tenantId, finding));
            snapshot.put("transition", db.queryForList("select row_to_json(x)::text from public.finding_transition x where tenant_id=? and finding_id=? order by id", String.class, fixture.tenantId, finding));
            snapshot.put("publication", db.queryForList("select row_to_json(x)::text from public.event_publication x where serialized_event like ? order by id", String.class, "%" + command + "%"));
            snapshot.put("deployment", db.queryForList("select row_to_json(x)::text from public.act_re_deployment x where tenant_id_=? order by id_", String.class, fixture.tenantId.toString()));
            return snapshot;
        });
    }

    private static void awaitCompletedPublication(BusinessWorkflowPostgresFixture fixture, UUID command) {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (System.nanoTime() < deadline) {
            Integer complete = fixture.read(db -> db.queryForObject("select count(*) from public.event_publication where serialized_event like ? and completion_date is not null", Integer.class, "%" + command + "%"));
            if (complete != null && complete == 1) return;
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(25));
        }
        throw new AssertionError("Actual transactional publication did not complete before restart");
    }

    private static EngineState inspectEngine(ProcessEngine engine, BusinessWorkflowPostgresFixture fixture, UUID finding, String deploymentId) {
        String processId = fixture.read(db -> db.queryForObject("select process_instance_id from public.workflow_binding where tenant_id=? and business_id=?", String.class, fixture.tenantId, finding));
        var instance = engine.getRuntimeService().createProcessInstanceQuery().processInstanceId(processId).singleResult();
        assertThat(instance).isNotNull();
        assertThat(instance.getTenantId()).isEqualTo(fixture.tenantId.toString());
        var task = engine.getTaskService().createTaskQuery().processInstanceId(processId).singleResult();
        assertThat(task).isNotNull();
        assertThat(task.getTaskDefinitionKey()).isEqualTo("personConfirm");
        assertThat(task.getAssignee()).isEqualTo(fixture.person.memberId().toString());
        assertThat(engine.getRepositoryService().getProcessDefinition(instance.getProcessDefinitionId()).getDeploymentId()).isEqualTo(deploymentId);
        long definitions = engine.getRepositoryService().createProcessDefinitionQuery().processDefinitionTenantId(fixture.tenantId.toString()).count();
        assertThat(definitions).isEqualTo(2);
        assertThat(engine.getRepositoryService().createProcessDefinitionQuery()
                .processDefinitionTenantId(fixture.tenantId.toString()).list().stream()
                .map(org.flowable.engine.repository.ProcessDefinition::getKey).toList())
                .containsExactlyInAnyOrder("findingRemediationV1", "findingRemediationV2");
        assertThat(engine.getRepositoryService().getProcessDefinition(instance.getProcessDefinitionId()).getKey())
                .isEqualTo("findingRemediationV2");
        return new EngineState(processId, instance.getProcessDefinitionId(), task.getId(), task.getTaskDefinitionKey(), task.getAssignee(), definitions);
    }

    private Process start(Path jar, Path work, String logName, String jdbcUrl, int port, IdentityAcceptanceHttps https) throws Exception {
        Path log = Files.createFile(work.resolve(logName));
        Files.setPosixFilePermissions(log, PosixFilePermissions.fromString("rw-------"));
        ProcessBuilder builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-jar", jar.toString());
        builder.directory(work.toFile());
        var environment = builder.environment();
        environment.clear(); // No migration/operator secrets, URLs, acknowledgements, config paths or proxies reach the child.
        environment.put("LAN_PG_APP_JDBC_URL", jdbcUrl);
        environment.put("LAN_PG_APP_USERNAME", required("LAN_PG_APP_USERNAME"));
        environment.put("LAN_PG_APP_PASSWORD", required("LAN_PG_APP_PASSWORD"));
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("spring.datasource.url", jdbcUrl);
        properties.put("spring.datasource.username", required("LAN_PG_APP_USERNAME"));
        properties.put("spring.datasource.password", required("LAN_PG_APP_PASSWORD"));
        properties.put("spring.datasource.hikari.maximum-pool-size", 4);
        properties.put("spring.datasource.hikari.minimum-idle", 0);
        properties.put("server.address", "127.0.0.1"); properties.put("server.port", port);
        properties.put("server.ssl.enabled", true); properties.put("server.ssl.key-store", https.keyStore().toUri().toString());
        properties.put("server.ssl.key-store-password", https.password()); properties.put("server.ssl.key-store-type", "PKCS12");
        properties.put("server.ssl.key-alias", "identity-acceptance"); properties.put("server.shutdown", "graceful");
        properties.put("spring.lifecycle.timeout-per-shutdown-phase", "10s");
        properties.put("spring.flyway.enabled", false); properties.put("spring.session.jdbc.initialize-schema", "never");
        properties.put("spring.session.jdbc.cleanup-cron", "-");
        properties.put("spring.modulith.events.jdbc.schema-initialization.enabled", false);
        properties.put("flowable.database-schema-update", "false"); properties.put("flowable.check-process-definitions", false);
        properties.put("flowable.async-executor-activate", false); properties.put("flowable.async-history-executor-activate", false);
        properties.put("flowable.history-cleaning-enabled", false); properties.put("flowable.idm.enabled", false); properties.put("flowable.eventregistry.enabled", false);
        properties.put("logging.level.org.jooq.tools.LoggerListener", "OFF");
        properties.put("spring.mvc.log-request-details", false); properties.put("server.tomcat.accesslog.enabled", false);
        environment.put("SPRING_APPLICATION_JSON", json.writeValueAsString(properties));
        builder.redirectErrorStream(true).redirectOutput(log.toFile());
        return builder.start();
    }

    private static IdentityAcceptanceHttps.Browser awaitHttps(Process child, int port, IdentityAcceptanceHttps https) throws Exception {
        var browser = https.browser(port);
        long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
        try {
            while (System.nanoTime() < deadline && child.isAlive()) {
                try { if (browser.get("/api/v1/auth/csrf").status() == 204) return browser; }
                catch (Exception notReady) { /* No exception bodies/headers or credentials are logged. */ }
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(100));
            }
            throw new AssertionError("Owned child application did not become HTTPS ready; inspect its private local log");
        } catch (Throwable failure) { browser.close(); throw failure; }
    }

    private static HikariDataSource runtimeSource(String jdbcUrl) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl); config.setUsername(required("LAN_PG_APP_USERNAME")); config.setPassword(required("LAN_PG_APP_PASSWORD"));
        config.setMaximumPoolSize(2); config.setMinimumIdle(0); config.setPoolName("restart-engine-observer");
        return new HikariDataSource(config);
    }
    private static final class Engine implements AutoCloseable {
        final ProcessEngine value;
        Engine(HikariDataSource source) {
            var config = new SpringProcessEngineConfiguration();
            config.setDataSource(source); config.setTransactionManager(new DataSourceTransactionManager(source));
            config.setDatabaseSchemaUpdate("false"); config.setAsyncExecutorActivate(false); config.setAsyncHistoryExecutorActivate(false);
            config.setAsyncExecutorAsyncJobAcquisitionEnabled(false); config.setAsyncExecutorTimerJobAcquisitionEnabled(false);
            config.setAsyncExecutorResetExpiredJobsEnabled(false); config.setEnableHistoryCleaning(false);
            config.setDisableIdmEngine(true); config.setDisableEventRegistry(true); config.setEnableConfiguratorServiceLoader(false);
            value = config.buildProcessEngine();
        }
        @Override public void close() { value.close(); }
    }
    private record EngineState(String processId, String definitionId, String taskId, String taskKey, String assignee, long definitions) { }
    private static int freePort() throws Exception { try (var socket = new ServerSocket(0, 0, java.net.InetAddress.getByName("127.0.0.1"))) { return socket.getLocalPort(); } }
    private static void stopGracefully(Process process) throws Exception {
        process.destroy();
        assertThat(process.waitFor(30, TimeUnit.SECONDS)).as("Owned app must exit by graceful shutdown").isTrue();
        assertThat(process.isAlive()).isFalse();
    }
    private static void stopOwnedChild(Process process) throws Exception {
        if (!process.isAlive()) return;
        process.destroy();
        if (!process.waitFor(15, TimeUnit.SECONDS)) { process.destroyForcibly(); assertThat(process.waitFor(5, TimeUnit.SECONDS)).isTrue(); }
    }
    private static Path packagedJar() {
        for (Path candidate : List.of(Path.of("target/backend-0.1.0-SNAPSHOT.jar"), Path.of("backend/target/backend-0.1.0-SNAPSHOT.jar"))) {
            if (Files.isRegularFile(candidate)) return candidate.toAbsolutePath();
        }
        throw new IllegalStateException("Package actual backend jar before running process-restart acceptance");
    }
    private static String required(String name) { String value = System.getenv(name); if (value == null || value.isBlank()) throw new IllegalStateException("Missing " + name); return value; }
}
