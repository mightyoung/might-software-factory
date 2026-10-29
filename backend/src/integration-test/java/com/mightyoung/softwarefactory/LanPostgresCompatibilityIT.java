package com.mightyoung.softwarefactory;

import org.flowable.engine.ManagementService;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.flowable.job.api.Job;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.dao.DataAccessException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.session.Session;

import javax.sql.DataSource;
import java.net.URI;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("lan-postgres-compatibility")
@Import(P0FailingProjectionDelegateConfiguration.class)
class LanPostgresCompatibilityIT {

    private static final String CONFIRMATION_PREFIX = "I_UNDERSTAND_THIS_IS_A_DISPOSABLE_DB:";
    private static final String TIMER_PROCESS = "p0TimerProjectionRollback";
    private static final String TIMER_PROCESS_XML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                              xmlns:flowable="http://flowable.org/bpmn"
                              targetNamespace="urn:might:p0:compatibility">
              <bpmn:process id="p0TimerProjectionRollback" name="P0 timer projection rollback" isExecutable="true">
                <bpmn:startEvent id="start" />
                <bpmn:sequenceFlow id="toTimer" sourceRef="start" targetRef="timer" />
                <bpmn:intermediateCatchEvent id="timer">
                  <bpmn:timerEventDefinition><bpmn:timeDuration>PT1H</bpmn:timeDuration></bpmn:timerEventDefinition>
                </bpmn:intermediateCatchEvent>
                <bpmn:sequenceFlow id="toProjection" sourceRef="timer" targetRef="projection" />
                <bpmn:serviceTask id="projection" flowable:delegateExpression="${p0FailingProjectionDelegate}" />
                <bpmn:sequenceFlow id="toEnd" sourceRef="projection" targetRef="end" />
                <bpmn:endEvent id="end" />
              </bpmn:process>
            </bpmn:definitions>
            """;

    @Autowired private DataSource dataSource;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DSLContext dsl;
    @Autowired private ProcessEngine processEngine;
    @Autowired private RepositoryService repositoryService;
    @Autowired private RuntimeService runtimeService;
    @Autowired private ManagementService managementService;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private FindByIndexNameSessionRepository<?> sessionRepository;
    @Autowired private P0FailingProjectionDelegate projectionDelegate;

    @DynamicPropertySource
    static void lanPostgresProperties(DynamicPropertyRegistry properties) {
        DatabaseTarget target = DatabaseTarget.fromEnvironment(System.getenv());
        properties.add("spring.datasource.url", target::jdbcUrl);
        properties.add("spring.datasource.username", () -> required("LAN_PG_USERNAME"));
        properties.add("spring.datasource.password", () -> required("LAN_PG_PASSWORD"));
    }

    @Test
    void selectedFrameworksShareTheConfiguredPostgresConnectionAndSpringTransactionManager() {
        assertThatCode(() -> dsl.fetchValue("select 1", Integer.class))
                .doesNotThrowAnyException();

        ProcessEngineConfigurationImpl engineConfiguration =
                (ProcessEngineConfigurationImpl) processEngine.getProcessEngineConfiguration();
        assertThat(engineConfiguration.getDataSource()).isSameAs(dataSource);
        assertThat(engineConfiguration).isInstanceOf(org.flowable.spring.SpringProcessEngineConfiguration.class);
        assertThat(((org.flowable.spring.SpringProcessEngineConfiguration) engineConfiguration)
                .getTransactionManager()).isSameAs(transactionManager);
        assertThat(jdbc.queryForObject("select current_setting('server_version_num')::int", Integer.class))
                .isBetween(180000, 189999);
    }

    @Test
    void flowableSessionJooqAndFlywayCanUseTheDedicatedPostgresDatabase() {
        String sessionId = persistProbeSession(sessionRepository);

        Session reloaded = sessionRepository.findById(sessionId);
        assertThat(reloaded).isNotNull();
        assertThat((String) reloaded.getAttribute("p0CompatibilityProbe")).isEqualTo("jdbc-session-ok");
        sessionRepository.deleteById(sessionId);

        assertThat(jdbc.queryForObject(
                "select count(*) from flyway_schema_history where version = '1' and success",
                Integer.class)).isEqualTo(1);
        assertThat(dsl.fetchValue("select current_database()", String.class))
                .isEqualTo(DatabaseTarget.fromEnvironment(System.getenv()).databaseName());
    }

    @Test
    void springTransactionRollsBackFlowableEngineProjectionTransitionAndOutboxTogether() {
        String businessKey = "p0-rollback-" + UUID.randomUUID();
        AtomicReference<String> processInstanceId = new AtomicReference<>();
        repositoryService.createDeployment()
                .name("P0 transaction rollback " + businessKey)
                .tenantId("tenant-a")
                .addString("p0-rollback.bpmn20.xml", simpleUserTaskProcess("p0TransactionRollback"))
                .deploy();

        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        assertThatThrownBy(() -> transaction.execute(status -> {
            var process = runtimeService.createProcessInstanceBuilder()
                    .processDefinitionKey("p0TransactionRollback")
                    .tenantId("tenant-a")
                    .businessKey(businessKey)
                    .start();
            processInstanceId.set(process.getId());
            jdbc.update("insert into p0_finding_projection values (?, ?, ?, ?)",
                    process.getId(), "tenant-a", "PERSON_CONFIRM_PENDING", 1L);
            jdbc.update("insert into p0_finding_transition values (?, ?)", process.getId(), "transition-1");
            jdbc.update("insert into p0_outbox values (?, ?)", process.getId() + ":event-1", "FINDING_CREATED");
            throw new DeliberateRollback();
        })).isInstanceOf(DeliberateRollback.class);

        assertThat(runtimeService.createProcessInstanceQuery()
                .processInstanceBusinessKey(businessKey, "tenant-a")
                .singleResult()).isNull();
        assertThat(jdbc.queryForObject(
                "select count(*) from p0_finding_projection where process_instance_id = ?",
                Integer.class, processInstanceId.get())).isZero();
        assertThat(jdbc.queryForObject(
                "select count(*) from p0_finding_transition where process_instance_id = ?",
                Integer.class, processInstanceId.get())).isZero();
        assertThat(jdbc.queryForObject(
                "select count(*) from p0_outbox where event_key = ?",
                Integer.class, processInstanceId.get() + ":event-1")).isZero();
    }

    @Test
    void timerFailureRollsBackProjectionTransitionAndOutboxWhileKeepingTheEngineInstanceRetryable() {
        String businessKey = "p0-timer-" + UUID.randomUUID();
        repositoryService.createDeployment()
                .name("P0 timer rollback " + businessKey)
                .tenantId("tenant-a")
                .addString("p0-timer-rollback.bpmn20.xml", TIMER_PROCESS_XML)
                .deploy();

        var process = runtimeService.createProcessInstanceBuilder()
                .processDefinitionKey(TIMER_PROCESS)
                .tenantId("tenant-a")
                .businessKey(businessKey)
                .start();
        Job timer = managementService.createTimerJobQuery()
                .processInstanceId(process.getId())
                .singleResult();
        assertThat(timer).isNotNull();

        Job retryable = managementService.moveTimerToExecutableJob(timer.getId());
        assertThatThrownBy(() -> managementService.executeJob(retryable.getId()))
                .hasRootCauseInstanceOf(P0FailingProjectionDelegate.ProjectionWriteFailure.class);
        assertThat(projectionDelegate.successfulWriteStatementsFor(process.getId())).isEqualTo(3);

        assertThat(jdbc.queryForObject(
                "select count(*) from p0_finding_projection where process_instance_id = ?",
                Integer.class, process.getId())).isZero();
        assertThat(jdbc.queryForObject(
                "select count(*) from p0_finding_transition where process_instance_id = ?",
                Integer.class, process.getId())).isZero();
        assertThat(jdbc.queryForObject(
                "select count(*) from p0_outbox where event_key like ?",
                Integer.class, process.getId() + ":%")).isZero();
        assertThat(runtimeService.createProcessInstanceQuery().processInstanceId(process.getId()).singleResult())
                .isNotNull();
        Job executableAfterFailure = managementService.createJobQuery()
                .processInstanceId(process.getId())
                .singleResult();
        assertThat(executableAfterFailure).isNotNull();
        assertThat(executableAfterFailure.getRetries()).isPositive();
        assertThat(managementService.createDeadLetterJobQuery()
                .processInstanceId(process.getId())
                .count()).isZero();
    }

    @Test
    void tenantRlsBlocksCrossTenantRowsAndTransactionLocalSettingDoesNotLeakThroughThePool() {
        Boolean bypassesRls = jdbc.queryForObject("""
                select rolsuper or rolbypassrls
                from pg_roles
                where rolname = current_user
                """, Boolean.class);
        assertThat(bypassesRls).isFalse();

        String suffix = UUID.randomUUID().toString();
        String tenantA = "tenant-a-" + suffix;
        String tenantB = "tenant-b-" + suffix;
        String keyA = "a-" + suffix;
        String keyB = "b-" + suffix;
        String crossTenantInsertKey = "cross-insert-" + suffix;

        try {
            insertTenantProbe(tenantA, tenantA, keyA);
            insertTenantProbe(tenantB, tenantB, keyB);

            assertThat(readTenantProbeCount(tenantA, keyA)).isEqualTo(1);
            assertThat(readTenantProbeCount(tenantB, keyB)).isEqualTo(1);
            assertThat(readTenantProbeCount(tenantA, keyB)).isZero();
            assertThat(readTenantProbeCount(tenantB, keyA)).isZero();
            assertThat(jdbc.queryForObject("select count(*) from p0_tenant_probe", Integer.class)).isZero();

            assertRlsRejected("cross-tenant INSERT",
                    () -> insertTenantProbe(tenantA, tenantB, crossTenantInsertKey));
            assertThat(readTenantProbeCount(tenantB, crossTenantInsertKey)).isZero();
            assertRlsRejected("cross-tenant UPDATE", () -> updateTenantProbeTenant(tenantA, tenantB, keyA));
            assertThat(readTenantProbeCount(tenantA, keyA)).isEqualTo(1);
            assertThat(readTenantProbeCount(tenantB, keyA)).isZero();
        } finally {
            deleteTenantProbe(tenantA, keyA);
            deleteTenantProbe(tenantB, keyB);
            deleteTenantProbe(tenantB, crossTenantInsertKey);
        }
    }

    private void insertTenantProbe(String contextTenantId, String rowTenantId, String key) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            setTenantContext(contextTenantId);
            jdbc.update("insert into p0_tenant_probe (tenant_id, item_key) values (?, ?)", rowTenantId, key);
        });
    }

    private void updateTenantProbeTenant(String contextTenantId, String rowTenantId, String key) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            setTenantContext(contextTenantId);
            jdbc.update("update p0_tenant_probe set tenant_id = ? where tenant_id = ? and item_key = ?",
                    rowTenantId, contextTenantId, key);
        });
    }

    private int readTenantProbeCount(String contextTenantId, String key) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            setTenantContext(contextTenantId);
            return jdbc.queryForObject("select count(*) from p0_tenant_probe where item_key = ?",
                    Integer.class, key);
        });
    }

    private void deleteTenantProbe(String contextTenantId, String key) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            setTenantContext(contextTenantId);
            jdbc.update("delete from p0_tenant_probe where tenant_id = ? and item_key = ?", contextTenantId, key);
        });
    }

    private void setTenantContext(String tenantId) {
        jdbc.queryForObject("select set_config('app.tenant_id', ?, true)", String.class, tenantId);
    }

    private static void assertRlsRejected(String operationName, Runnable operation) {
        Throwable failure = catchThrowable(operation::run);
        assertThat(failure).as(operationName).isInstanceOf(DataAccessException.class);
        Throwable databaseCause = ((DataAccessException) failure).getMostSpecificCause();
        assertThat(databaseCause).as("%s database cause", operationName)
                .isInstanceOfSatisfying(SQLException.class, exception ->
                        assertThat(exception.getSQLState()).isEqualTo("42501"));
    }

    private static <S extends Session> String persistProbeSession(FindByIndexNameSessionRepository<S> repository) {
        S session = repository.createSession();
        session.setAttribute("p0CompatibilityProbe", "jdbc-session-ok");
        repository.save(session);
        return session.getId();
    }

    private static String simpleUserTaskProcess(String processKey) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                                  targetNamespace="urn:might:p0:compatibility">
                  <bpmn:process id="%s" isExecutable="true">
                    <bpmn:startEvent id="start" />
                    <bpmn:sequenceFlow id="toTask" sourceRef="start" targetRef="task" />
                    <bpmn:userTask id="task" name="Wait" />
                    <bpmn:sequenceFlow id="toEnd" sourceRef="task" targetRef="end" />
                    <bpmn:endEvent id="end" />
                  </bpmn:process>
                </bpmn:definitions>
                """.formatted(processKey);
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Required LAN PostgreSQL test input is missing: " + name);
        }
        return value;
    }

    private static final class DatabaseTarget {
        private final String jdbcUrl;
        private final String databaseName;

        private DatabaseTarget(String jdbcUrl, String databaseName) {
            this.jdbcUrl = jdbcUrl;
            this.databaseName = databaseName;
        }

        static DatabaseTarget fromEnvironment(java.util.Map<String, String> environment) {
            String url = requiredFrom(environment, "LAN_PG_JDBC_URL");
            String username = requiredFrom(environment, "LAN_PG_USERNAME");
            String password = requiredFrom(environment, "LAN_PG_PASSWORD");
            if (username.isBlank() || password.isBlank()) {
                throw new IllegalStateException("LAN PostgreSQL username and password must be non-empty");
            }
            if (!url.startsWith("jdbc:postgresql://")) {
                throw new IllegalStateException("LAN_PG_JDBC_URL must use jdbc:postgresql://");
            }

            try {
                URI uri = URI.create(url.substring("jdbc:".length()));
                String host = uri.getHost();
                String path = uri.getPath();
                if (uri.getUserInfo() != null || url.toLowerCase(java.util.Locale.ROOT).contains("password=")) {
                    throw new IllegalStateException("Credentials must be supplied separately from LAN_PG_JDBC_URL");
                }
                if (host == null || host.isBlank() || isLoopbackLiteral(host) || path == null || path.length() < 2) {
                    throw new IllegalStateException("LAN_PG_JDBC_URL must name a PostgreSQL host and database; literal loopback hosts are rejected");
                }
                String database = path.substring(1);
                String expected = requiredFrom(environment, "LAN_PG_TEST_DATABASE_NAME");
                String confirmation = requiredFrom(environment, "LAN_PG_DEDICATED_TEST_DB_ACK");
                if (!database.equals(expected)) {
                    throw new IllegalStateException("LAN_PG_TEST_DATABASE_NAME must exactly match the JDBC URL database");
                }
                if (!confirmation.equals(CONFIRMATION_PREFIX + database)) {
                    throw new IllegalStateException("Dedicated disposable test database confirmation is missing or does not match the database name");
                }
                return new DatabaseTarget(url, database);
            } catch (IllegalArgumentException exception) {
                throw new IllegalStateException("LAN_PG_JDBC_URL is not a valid PostgreSQL JDBC URI", exception);
            }
        }

        String jdbcUrl() { return jdbcUrl; }
        String databaseName() { return databaseName; }

        private static boolean isLoopbackLiteral(String host) {
            String normalized = host.toLowerCase(java.util.Locale.ROOT);
            return normalized.equals("localhost") || normalized.startsWith("127.")
                    || normalized.equals("0.0.0.0") || normalized.equals("::1")
                    || normalized.equals("[::1]") || normalized.startsWith("[::ffff:127.");
        }

        private static String requiredFrom(java.util.Map<String, String> environment, String name) {
            String value = environment.get(name);
            if (value == null || value.isBlank()) {
                throw new IllegalStateException("Required LAN PostgreSQL test input is missing: " + name);
            }
            return value;
        }
    }

    private static final class DeliberateRollback extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
}
