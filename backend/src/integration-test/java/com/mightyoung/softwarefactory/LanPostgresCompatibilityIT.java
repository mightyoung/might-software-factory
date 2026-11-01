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
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
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
import org.junit.jupiter.api.BeforeEach;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import javax.sql.DataSource;
import java.net.URI;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Set;
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
    private static final String TRUSTED_TARGET_ALLOWLIST = "LAN_PG_TRUSTED_TEST_TARGET_ALLOWLIST";
    private static final String TUNNEL_LOCAL_PORT = "PG18_TUNNEL_LOCAL_PORT";
    private static final String APPROVED_PG18_DATABASE = "might_software_factory_pg18_test";
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
    @Autowired private HikariDataSource hikariDataSource;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DSLContext dsl;
    @Autowired private ProcessEngine processEngine;
    @Autowired private RepositoryService repositoryService;
    @Autowired private RuntimeService runtimeService;
    @Autowired private ManagementService managementService;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private FindByIndexNameSessionRepository<?> sessionRepository;
    @Autowired private P0FailingProjectionDelegate projectionDelegate;

    @BeforeEach
    void constrainPoolAfterDatabaseInitializationToExerciseConnectionReuse() {
        assertThat(hikariDataSource.getHikariPoolMXBean().getActiveConnections()).isZero();
        hikariDataSource.setMaximumPoolSize(1);
        hikariDataSource.getHikariPoolMXBean().softEvictConnections();
        assertThat(hikariDataSource.getMaximumPoolSize()).isEqualTo(1);
    }

    @DynamicPropertySource
    static void lanPostgresProperties(DynamicPropertyRegistry properties) {
        DatabaseTarget target = DatabaseTarget.preflightFromEnvironment(System.getenv());
        properties.add("spring.datasource.url", target::jdbcUrl);
        properties.add("spring.datasource.username", () -> required("LAN_PG_USERNAME"));
        properties.add("spring.datasource.password", () -> required("LAN_PG_PASSWORD"));
    }

    @Test
    void appRoleTargetRejectsUrlDifferentFromDedicatedGate() {
        String testDatabase = "might_software_factory_test";
        java.util.Map<String, String> misconfiguredEnvironment = java.util.Map.of(
                "LAN_PG_JDBC_URL", "jdbc:postgresql://192.0.2.10:5432/" + testDatabase,
                "LAN_PG_USERNAME", "test-operator",
                "LAN_PG_PASSWORD", "not-used-by-this-test",
                "LAN_PG_TEST_DATABASE_NAME", testDatabase,
                "LAN_PG_DEDICATED_TEST_DB_ACK", CONFIRMATION_PREFIX + testDatabase,
                TRUSTED_TARGET_ALLOWLIST,
                "jdbc:postgresql://192.0.2.10:5432/" + testDatabase,
                "LAN_PG_APP_USERNAME", "test-application",
                "LAN_PG_TEST_JDBC_URL", "jdbc:postgresql://192.0.2.10:5432/might_software_factory_dev");

        assertThatThrownBy(() -> DatabaseTarget.forAppRole(misconfiguredEnvironment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("LAN_PG_TEST_JDBC_URL must match the URL accepted by the disposable-test database gate");
    }

    @Test
    void selectedFrameworksShareTheConfiguredPostgresConnectionAndSpringTransactionManager() {
        assertThatCode(() -> dsl.fetchValue("select 1", Integer.class))
                .doesNotThrowAnyException();

        ProcessEngineConfigurationImpl engineConfiguration =
                (ProcessEngineConfigurationImpl) processEngine.getProcessEngineConfiguration();
        assertThat(engineConfiguration.getDataSource())
                .isInstanceOf(TransactionAwareDataSourceProxy.class);
        assertThat(((TransactionAwareDataSourceProxy) engineConfiguration.getDataSource())
                .getTargetDataSource()).isSameAs(dataSource);
        assertThat(engineConfiguration).isInstanceOf(org.flowable.spring.SpringProcessEngineConfiguration.class);
        assertThat(((org.flowable.spring.SpringProcessEngineConfiguration) engineConfiguration)
                .getTransactionManager()).isSameAs(transactionManager);
        int expectedPostgresMajor = Integer.parseInt(
                System.getenv().getOrDefault("LAN_PG_EXPECTED_MAJOR_VERSION", "18"));
        int actualPostgresVersion = jdbc.queryForObject(
                "select current_setting('server_version_num')::int", Integer.class);
        assertThat(actualPostgresVersion / 10_000)
                .as("PostgreSQL compatibility target selected by LAN_PG_EXPECTED_MAJOR_VERSION (defaults to design target 18)")
                .isEqualTo(expectedPostgresMajor);
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
        var deployment = repositoryService.createDeployment()
                .name("P0 transaction rollback " + businessKey)
                .tenantId("tenant-a")
                .addString("p0-rollback.bpmn20.xml", simpleUserTaskProcess("p0TransactionRollback"))
                .deploy();

        try {
            String processDefinitionId = repositoryService.createProcessDefinitionQuery()
                    .deploymentId(deployment.getId()).singleResult().getId();
            TransactionTemplate transaction = new TransactionTemplate(transactionManager);
            assertThatThrownBy(() -> transaction.execute(status -> {
                var process = runtimeService.createProcessInstanceBuilder()
                        .processDefinitionId(processDefinitionId)
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
        } finally {
            try {
                repositoryService.deleteDeployment(deployment.getId(), true);
            } finally {
                if (processInstanceId.get() != null) {
                    deleteProbeWrites(processInstanceId.get());
                }
            }
        }
    }

    @Test
    void timerFailureRollsBackProjectionTransitionAndOutboxWhileKeepingTheEngineInstanceRetryable() {
        String businessKey = "p0-timer-" + UUID.randomUUID();
        var deployment = repositoryService.createDeployment()
                .name("P0 timer rollback " + businessKey)
                .tenantId("tenant-a")
                .addString("p0-timer-rollback.bpmn20.xml", TIMER_PROCESS_XML)
                .deploy();

        String processInstanceId = null;
        try {
            String processDefinitionId = repositoryService.createProcessDefinitionQuery()
                    .deploymentId(deployment.getId()).singleResult().getId();
            var process = runtimeService.createProcessInstanceBuilder()
                    .processDefinitionId(processDefinitionId)
                    .tenantId("tenant-a")
                    .businessKey(businessKey)
                    .start();
            processInstanceId = process.getId();
            Job timer = managementService.createTimerJobQuery()
                    .processInstanceId(process.getId())
                    .singleResult();
            assertThat(timer).isNotNull();

            Job firstAttempt = managementService.moveTimerToExecutableJob(timer.getId());
            assertThat(firstAttempt.getRetries()).isPositive();
            assertThatThrownBy(() -> managementService.executeJob(firstAttempt.getId()))
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
            managementService.executeJob(executableAfterFailure.getId());

            assertThat(projectionDelegate.successfulWriteStatementsFor(process.getId())).isEqualTo(6);
            assertThat(runtimeService.createProcessInstanceQuery().processInstanceId(process.getId()).singleResult())
                    .isNull();
            assertThat(jdbc.queryForObject(
                    "select count(*) from p0_finding_projection where process_instance_id = ?",
                    Integer.class, process.getId())).isEqualTo(1);
            assertThat(jdbc.queryForObject(
                    "select count(*) from p0_finding_transition where process_instance_id = ?",
                    Integer.class, process.getId())).isEqualTo(1);
            assertThat(jdbc.queryForObject(
                    "select count(*) from p0_outbox where event_key like ?",
                    Integer.class, process.getId() + ":%")).isEqualTo(1);
            assertThat(managementService.createJobQuery().processInstanceId(process.getId()).count()).isZero();
            assertThat(managementService.createDeadLetterJobQuery().processInstanceId(process.getId()).count()).isZero();
        } finally {
            try {
                repositoryService.deleteDeployment(deployment.getId(), true);
            } finally {
                if (processInstanceId != null) {
                    deleteProbeWrites(processInstanceId);
                }
            }
        }
    }

    private void deleteProbeWrites(String processInstanceId) {
        jdbc.update("delete from p0_outbox where event_key like ?", processInstanceId + ":%");
        jdbc.update("delete from p0_finding_transition where process_instance_id = ?", processInstanceId);
        jdbc.update("delete from p0_finding_projection where process_instance_id = ?", processInstanceId);
    }

    @Test
    void tenantRlsBlocksCrossTenantRowsAndTransactionLocalSettingDoesNotLeakThroughThePool() {
        HikariConfig appPoolConfig = new HikariConfig();
        DatabaseTarget appTarget = DatabaseTarget.forAppRole(System.getenv());
        appPoolConfig.setJdbcUrl(appTarget.jdbcUrl());
        appPoolConfig.setUsername(required("LAN_PG_APP_USERNAME"));
        appPoolConfig.setPassword(required("LAN_PG_APP_PASSWORD"));
        appPoolConfig.setMaximumPoolSize(1);
        appPoolConfig.setMinimumIdle(1);
        appPoolConfig.setPoolName("p0-rls-app-role");

        try (HikariDataSource appDataSource = new HikariDataSource(appPoolConfig)) {
            JdbcTemplate appJdbc = new JdbcTemplate(appDataSource);
            TransactionTemplate appTransaction = new TransactionTemplate(
                    new org.springframework.jdbc.datasource.DataSourceTransactionManager(appDataSource));

            assertThat(appJdbc.queryForObject("select current_database()", String.class))
                    .isEqualTo(appTarget.databaseName());
            assertThat(appJdbc.queryForObject("select current_user = session_user and session_user = ?",
                    Boolean.class, required("LAN_PG_APP_USERNAME"))).isTrue();
            assertThat(appJdbc.queryForObject("""
                    select rolsuper or rolbypassrls or rolcreatedb or rolcreaterole or rolreplication or rolinherit
                    from pg_roles where rolname = session_user
                    """, Boolean.class)).isFalse();
            assertThat(appJdbc.queryForObject("""
                    select exists (
                        select 1 from pg_auth_members
                        where member = (select oid from pg_roles where rolname = session_user)
                    )
                    """, Boolean.class)).isFalse();
            assertThat(appJdbc.queryForObject(
                    "select has_schema_privilege(current_user, 'public', 'CREATE')", Boolean.class)).isFalse();
            assertThat(appJdbc.queryForObject("""
                    select has_table_privilege(current_user, 'public.p0_tenant_probe', 'SELECT')
                       and has_table_privilege(current_user, 'public.p0_tenant_probe', 'INSERT')
                       and has_table_privilege(current_user, 'public.p0_tenant_probe', 'UPDATE')
                       and has_table_privilege(current_user, 'public.p0_tenant_probe', 'DELETE')
                    """, Boolean.class)).isTrue();
            assertThat(appJdbc.queryForObject(
                    "select has_table_privilege(current_user, 'public.p0_finding_projection', 'SELECT')",
                    Boolean.class)).isFalse();
            assertThat(appJdbc.queryForObject("""
                    select pg_get_userbyid(relowner)
                    from pg_class where oid = 'public.p0_tenant_probe'::regclass
                    """, String.class)).isNotEqualTo(required("LAN_PG_APP_USERNAME"));
            assertThat(appJdbc.queryForObject("""
                    select relrowsecurity and relforcerowsecurity
                    from pg_class where oid = 'public.p0_tenant_probe'::regclass
                    """, Boolean.class)).isTrue();
            assertThat(appJdbc.queryForObject("""
                    select exists (
                        select 1 from pg_policies
                        where schemaname = 'public'
                          and tablename = 'p0_tenant_probe'
                          and policyname = 'p0_tenant_probe_isolation'
                          and cmd = 'ALL'
                          and 'public' = any(roles)
                          and position('tenant_id' in coalesce(qual, '')) > 0
                          and position('app.tenant_id' in coalesce(qual, '')) > 0
                          and position('tenant_id' in coalesce(with_check, '')) > 0
                          and position('app.tenant_id' in coalesce(with_check, '')) > 0
                    )
                    """, Boolean.class)).isTrue();

            String suffix = UUID.randomUUID().toString();
            String tenantA = "tenant-a-" + suffix;
            String tenantB = "tenant-b-" + suffix;
            String keyA = "a-" + suffix;
            String keyB = "b-" + suffix;
            String noContextInsertKey = "no-context-insert-" + suffix;
            String crossTenantInsertKey = "cross-insert-" + suffix;
            String crossTenantUpdatedKey = "cross-updated-" + suffix;

            try {
                int backendPid = insertTenantProbe(appJdbc, appTransaction, tenantA, tenantA, keyA);
                assertCleanTenantContextOnSameConnection(appJdbc, appTransaction, backendPid);
                assertRlsRejected("INSERT without tenant context",
                        () -> insertTenantProbeWithoutContext(appJdbc, appTransaction, tenantA, noContextInsertKey));
                assertCleanTenantContextOnSameConnection(appJdbc, appTransaction, backendPid);

                assertThat(insertTenantProbe(appJdbc, appTransaction, tenantB, tenantB, keyB))
                        .isEqualTo(backendPid);
                assertThat(readTenantProbeCount(appJdbc, appTransaction, tenantA, keyA)).isEqualTo(1);
                assertThat(readTenantProbeCount(appJdbc, appTransaction, tenantB, keyB)).isEqualTo(1);
                assertThat(readTenantProbeCount(appJdbc, appTransaction, tenantA, keyB)).isZero();
                assertThat(readTenantProbeCount(appJdbc, appTransaction, tenantB, keyA)).isZero();
                assertCleanTenantContextOnSameConnection(appJdbc, appTransaction, backendPid);

                assertRlsRejected("cross-tenant INSERT",
                        () -> insertTenantProbe(appJdbc, appTransaction, tenantA, tenantB, crossTenantInsertKey));
                assertCleanTenantContextOnSameConnection(appJdbc, appTransaction, backendPid);
                assertThat(readTenantProbeCount(appJdbc, appTransaction, tenantB, crossTenantInsertKey)).isZero();

                assertThat(updateOtherTenantProbe(appJdbc, appTransaction, tenantA, tenantB, keyB,
                        crossTenantUpdatedKey)).isZero();
                assertThat(readTenantProbeCount(appJdbc, appTransaction, tenantB, keyB)).isEqualTo(1);
                assertThat(readTenantProbeCount(appJdbc, appTransaction, tenantB, crossTenantUpdatedKey)).isZero();
                assertThat(deleteOtherTenantProbe(appJdbc, appTransaction, tenantA, tenantB, keyB)).isZero();
                assertThat(readTenantProbeCount(appJdbc, appTransaction, tenantB, keyB)).isEqualTo(1);

                assertRlsRejected("cross-tenant UPDATE",
                        () -> updateTenantProbeTenant(appJdbc, appTransaction, tenantA, tenantB, keyA));
                assertCleanTenantContextOnSameConnection(appJdbc, appTransaction, backendPid);
                assertThat(readTenantProbeCount(appJdbc, appTransaction, tenantA, keyA)).isEqualTo(1);
                assertThat(readTenantProbeCount(appJdbc, appTransaction, tenantB, keyA)).isZero();
            } finally {
                deleteTenantProbe(appJdbc, appTransaction, tenantA, keyA);
                deleteTenantProbe(appJdbc, appTransaction, tenantA, noContextInsertKey);
                deleteTenantProbe(appJdbc, appTransaction, tenantB, keyB);
                deleteTenantProbe(appJdbc, appTransaction, tenantB, crossTenantInsertKey);
                deleteTenantProbe(appJdbc, appTransaction, tenantB, crossTenantUpdatedKey);
                assertThat(jdbc.queryForObject("select count(*) from p0_tenant_probe", Integer.class)).isZero();
            }
        }
    }

    private static int insertTenantProbe(JdbcTemplate appJdbc, TransactionTemplate appTransaction,
                                         String contextTenantId, String rowTenantId, String key) {
        return appTransaction.execute(status -> {
            setTenantContext(appJdbc, contextTenantId);
            int backendPid = appJdbc.queryForObject("select pg_backend_pid()", Integer.class);
            appJdbc.update("insert into p0_tenant_probe (tenant_id, item_key) values (?, ?)", rowTenantId, key);
            return backendPid;
        });
    }

    private static void insertTenantProbeWithoutContext(JdbcTemplate appJdbc, TransactionTemplate appTransaction,
                                                        String rowTenantId, String key) {
        appTransaction.executeWithoutResult(status -> appJdbc.update(
                "insert into p0_tenant_probe (tenant_id, item_key) values (?, ?)", rowTenantId, key));
    }

    private static void updateTenantProbeTenant(JdbcTemplate appJdbc, TransactionTemplate appTransaction,
                                                String contextTenantId, String rowTenantId, String key) {
        appTransaction.executeWithoutResult(status -> {
            setTenantContext(appJdbc, contextTenantId);
            appJdbc.update("update p0_tenant_probe set tenant_id = ? where tenant_id = ? and item_key = ?",
                    rowTenantId, contextTenantId, key);
        });
    }

    private static int updateOtherTenantProbe(JdbcTemplate appJdbc, TransactionTemplate appTransaction,
                                              String contextTenantId, String rowTenantId, String key,
                                              String replacementKey) {
        return appTransaction.execute(status -> {
            setTenantContext(appJdbc, contextTenantId);
            return appJdbc.update("update p0_tenant_probe set item_key = ? where tenant_id = ? and item_key = ?",
                    replacementKey, rowTenantId, key);
        });
    }

    private static int deleteOtherTenantProbe(JdbcTemplate appJdbc, TransactionTemplate appTransaction,
                                              String contextTenantId, String rowTenantId, String key) {
        return appTransaction.execute(status -> {
            setTenantContext(appJdbc, contextTenantId);
            return appJdbc.update("delete from p0_tenant_probe where tenant_id = ? and item_key = ?",
                    rowTenantId, key);
        });
    }

    private static int readTenantProbeCount(JdbcTemplate appJdbc, TransactionTemplate appTransaction,
                                            String contextTenantId, String key) {
        return appTransaction.execute(status -> {
            setTenantContext(appJdbc, contextTenantId);
            return appJdbc.queryForObject("select count(*) from p0_tenant_probe where item_key = ?",
                    Integer.class, key);
        });
    }

    private static RlsConnectionState inspectUnscopedConnection(JdbcTemplate appJdbc,
                                                                 TransactionTemplate appTransaction) {
        return appTransaction.execute(status -> new RlsConnectionState(
                appJdbc.queryForObject("select pg_backend_pid()", Integer.class),
                appJdbc.queryForObject("select nullif(current_setting('app.tenant_id', true), '') is null",
                        Boolean.class),
                appJdbc.queryForObject("select count(*) from p0_tenant_probe", Integer.class)));
    }

    private static void assertCleanTenantContextOnSameConnection(JdbcTemplate appJdbc,
                                                                  TransactionTemplate appTransaction,
                                                                  int expectedBackendPid) {
        RlsConnectionState state = inspectUnscopedConnection(appJdbc, appTransaction);
        assertThat(state.backendPid()).isEqualTo(expectedBackendPid);
        assertThat(state.tenantContextUnset()).isTrue();
        assertThat(state.visibleRows()).isZero();
    }

    private static void deleteTenantProbe(JdbcTemplate appJdbc, TransactionTemplate appTransaction,
                                          String contextTenantId, String key) {
        appTransaction.executeWithoutResult(status -> {
            setTenantContext(appJdbc, contextTenantId);
            appJdbc.update("delete from p0_tenant_probe where tenant_id = ? and item_key = ?",
                    contextTenantId, key);
        });
    }

    private static void setTenantContext(JdbcTemplate appJdbc, String tenantId) {
        appJdbc.queryForObject("select set_config('app.tenant_id', ?, true)", String.class, tenantId);
    }

    private record RlsConnectionState(int backendPid, boolean tenantContextUnset, int visibleRows) { }

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

    static final class DatabaseTarget {
        private final String jdbcUrl;
        private final String databaseName;

        private DatabaseTarget(String jdbcUrl, String databaseName) {
            this.jdbcUrl = jdbcUrl;
            this.databaseName = databaseName;
        }

        static DatabaseTarget forAppRole(java.util.Map<String, String> environment) {
            DatabaseTarget acceptedTarget = fromEnvironment(environment);
            String appRoleUrl = requiredFrom(environment, "LAN_PG_TEST_JDBC_URL");
            if (!acceptedTarget.jdbcUrl().equals(appRoleUrl)) {
                throw new IllegalStateException(
                        "LAN_PG_TEST_JDBC_URL must match the URL accepted by the disposable-test database gate");
            }
            String migrationRole = requiredFrom(environment, "LAN_PG_USERNAME");
            String applicationRole = requiredFrom(environment, "LAN_PG_APP_USERNAME");
            if (migrationRole.equals(applicationRole)) {
                throw new IllegalStateException("Migration and application PostgreSQL roles must be separate");
            }
            return acceptedTarget;
        }

        static DatabaseTarget preflightFromEnvironment(java.util.Map<String, String> environment) {
            DatabaseTarget target = forAppRole(environment);
            // Validate both secrets before opening either connection.
            String migrationPassword = requiredFrom(environment, "LAN_PG_PASSWORD");
            String applicationPassword = requiredFrom(environment, "LAN_PG_APP_PASSWORD");
            validateRoleOnTarget(target, requiredFrom(environment, "LAN_PG_USERNAME"),
                    migrationPassword, true);
            validateRoleOnTarget(target, requiredFrom(environment, "LAN_PG_APP_USERNAME"),
                    applicationPassword, false);
            return target;
        }

        private static void validateRoleOnTarget(DatabaseTarget target, String username,
                String password, boolean migrationRole) {
            String label = migrationRole ? "migration" : "application";
            RolePreflight state;
            try (Connection connection = DriverManager.getConnection(target.jdbcUrl(), username, password);
                    PreparedStatement query = connection.prepareStatement("""
                            select current_database(), current_user, session_user,
                                   pg_get_userbyid(database_info.datdba), pg_get_userbyid(public_schema.nspowner),
                                   role_info.rolsuper, role_info.rolbypassrls, role_info.rolcreatedb,
                                   role_info.rolcreaterole, role_info.rolreplication, role_info.rolinherit,
                                   exists(select 1 from pg_auth_members membership
                                           where membership.member = role_info.oid),
                                   has_database_privilege(current_user, current_database(), 'CONNECT'),
                                   has_schema_privilege(current_user, 'public', 'USAGE'),
                                   has_schema_privilege(current_user, 'public', 'CREATE')
                              from pg_roles role_info
                              join pg_database database_info on database_info.datname = current_database()
                              join pg_namespace public_schema on public_schema.nspname = 'public'
                             where role_info.rolname = current_user
                            """)) {
                try (ResultSet result = query.executeQuery()) {
                    if (!result.next()) {
                        throw new IllegalStateException("Read-only PostgreSQL target preflight returned no role row");
                    }
                    state = new RolePreflight(
                            result.getString(1), result.getString(2), result.getString(3),
                            result.getString(4), result.getString(5),
                            result.getBoolean(6), result.getBoolean(7), result.getBoolean(8),
                            result.getBoolean(9), result.getBoolean(10), result.getBoolean(11),
                            result.getBoolean(12), result.getBoolean(13), result.getBoolean(14),
                            result.getBoolean(15));
                }
            } catch (SQLException exception) {
                throw new IllegalStateException(
                        "Read-only PostgreSQL target preflight could not inspect the " + label + " role", exception);
            }
            assertRolePreflight(state, target.databaseName(), username, migrationRole);
        }

        static void assertRolePreflight(RolePreflight state, String expectedDatabase,
                String expectedRole, boolean migrationRole) {
            String label = migrationRole ? "migration" : "application";
            if (!expectedDatabase.equals(state.databaseName())) {
                throw new IllegalStateException("Connected PostgreSQL database does not match the trusted test target");
            }
            if (!expectedRole.equals(state.currentUser()) || !expectedRole.equals(state.sessionUser())) {
                throw new IllegalStateException("Connected PostgreSQL identity does not match the configured " + label + " role");
            }
            if (expectedRole.equals(state.databaseOwner()) || expectedRole.equals(state.schemaOwner())) {
                throw new IllegalStateException("PostgreSQL " + label + " role must not own the target database or public schema");
            }
            if (state.superuser() || state.bypassRls() || state.createDatabase() || state.createRole()
                    || state.replication() || state.inherit() || state.memberOfAnotherRole()) {
                throw new IllegalStateException("PostgreSQL " + label + " role must be non-elevated, NOINHERIT, and have no memberships");
            }
            if (!state.connect() || !state.schemaUsage() || state.schemaCreate() != migrationRole) {
                throw new IllegalStateException(migrationRole
                        ? "Migration role requires database CONNECT and public USAGE/CREATE"
                        : "Application role requires database CONNECT and public USAGE, with no public CREATE");
            }
        }

        record RolePreflight(String databaseName, String currentUser, String sessionUser,
                String databaseOwner, String schemaOwner, boolean superuser, boolean bypassRls,
                boolean createDatabase, boolean createRole, boolean replication, boolean inherit,
                boolean memberOfAnotherRole, boolean connect, boolean schemaUsage, boolean schemaCreate) { }

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
                // pgJDBC query properties override host/path and separately supplied credentials.
                // This gate deliberately accepts only a literal endpoint, without URL properties.
                if (uri.getRawQuery() != null || uri.getRawFragment() != null) {
                    throw new IllegalStateException("LAN_PG_JDBC_URL must not contain query parameters or fragments");
                }
                if (uri.getUserInfo() != null || url.toLowerCase(java.util.Locale.ROOT).contains("password=")) {
                    throw new IllegalStateException("Credentials must be supplied separately from LAN_PG_JDBC_URL");
                }
                if (host == null || host.isBlank() || path == null || path.length() < 2) {
                    throw new IllegalStateException("LAN_PG_JDBC_URL must name a PostgreSQL host and database");
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
                String trustedTargets = environment.get(TRUSTED_TARGET_ALLOWLIST);
                if (trustedTargets == null || trustedTargets.isBlank()) {
                    throw new IllegalStateException("Trusted PostgreSQL test target allowlist is missing");
                }
                Set<String> trustedJdbcTargets = Arrays.stream(trustedTargets.split(",", -1))
                        .map(String::trim)
                        .filter(name -> !name.isEmpty())
                        .collect(java.util.stream.Collectors.toUnmodifiableSet());
                if (!trustedJdbcTargets.contains(url)) {
                    throw new IllegalStateException(
                            "LAN_PG_JDBC_URL target is not in LAN_PG_TRUSTED_TEST_TARGET_ALLOWLIST");
                }
                if (isLoopbackLiteral(host) && !isApprovedPg18SshTunnel(environment, uri, database)) {
                    throw new IllegalStateException(
                            "Loopback PostgreSQL targets require the approved PG18 database, wrapper tunnel-port match, and exact allowlisted URL");
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

        private static boolean isApprovedPg18SshTunnel(
                java.util.Map<String, String> environment, URI uri, String database) {
            String localPort = environment.get(TUNNEL_LOCAL_PORT);
            return APPROVED_PG18_DATABASE.equals(database)
                    && uri.getPort() > 0
                    && Integer.toString(uri.getPort()).equals(localPort);
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
