package com.mightyoung.softwarefactory;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flowable.common.engine.impl.persistence.StrongUuidGenerator;
import org.flowable.engine.ManagementService;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.ProcessEngineConfiguration;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.JavaDelegate;
import org.flowable.job.api.Job;
import org.flowable.job.api.JobNotFoundException;
import org.flowable.spring.SpringProcessEngineConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Real-PG graceful engine replacement, not a process-kill or automatic job-recovery test.
 * Own engines, pool, deployment, jobs and event keys only; no shared Spring engine is stopped.
 */
class FlowableRestartRecoveryPostgresIT {

    private static final String PROCESS_KEY = "p0GracefulRestartRecovery";
    private static final String PROCESS_XML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                              xmlns:flowable="http://flowable.org/bpmn"
                              targetNamespace="urn:might:p0:restart">
              <bpmn:process id="p0GracefulRestartRecovery" isExecutable="true">
                <bpmn:startEvent id="start" />
                <bpmn:sequenceFlow id="toTask" sourceRef="start" targetRef="task" />
                <bpmn:userTask id="task" name="Await restart" />
                <bpmn:sequenceFlow id="toTimer" sourceRef="task" targetRef="timer" />
                <bpmn:intermediateCatchEvent id="timer">
                  <bpmn:timerEventDefinition><bpmn:timeDuration>PT1H</bpmn:timeDuration></bpmn:timerEventDefinition>
                </bpmn:intermediateCatchEvent>
                <bpmn:sequenceFlow id="toOutbox" sourceRef="timer" targetRef="outbox" />
                <bpmn:serviceTask id="outbox" flowable:delegateExpression="${p0RestartOutbox}" />
                <bpmn:sequenceFlow id="toEnd" sourceRef="outbox" targetRef="end" />
                <bpmn:endEvent id="end" />
              </bpmn:process>
            </bpmn:definitions>
            """;

    @Test
    void committedTasksTimersAndFailedRetrySurviveGracefulReplacementWithoutDuplicatingOutbox() {
        // No pool or engine is created until the same read-only target/role gate has passed.
        var target = LanPostgresCompatibilityIT.DatabaseTarget.preflightFromEnvironment(System.getenv());
        String suffix = UUID.randomUUID().toString();
        String tenantId = "p0-restart-" + suffix;
        HikariConfig poolConfiguration = new HikariConfig();
        poolConfiguration.setJdbcUrl(target.jdbcUrl());
        poolConfiguration.setUsername(System.getenv("LAN_PG_USERNAME"));
        poolConfiguration.setPassword(System.getenv("LAN_PG_PASSWORD"));
        poolConfiguration.setMaximumPoolSize(2); // Failed-job recording starts REQUIRES_NEW.
        poolConfiguration.setMinimumIdle(1);
        poolConfiguration.setPoolName("p0-restart-" + suffix);

        try (var pool = new HikariDataSource(poolConfiguration)) {
            JdbcTemplate jdbc = new JdbcTemplate(pool);
            int expectedMajor = Integer.parseInt(System.getenv().getOrDefault("LAN_PG_EXPECTED_MAJOR_VERSION", "18"));
            assertThat(jdbc.queryForObject("select current_setting('server_version_num')::int", Integer.class) / 10_000)
                    .isEqualTo(expectedMajor);
            List<String> processIds = new ArrayList<>();
            String deploymentId = null;
            ProcessEngine engine = null;
            try {
                engine = newOwnedEngine(pool, jdbc, suffix, false);
                var deployment = engine.getRepositoryService().createDeployment()
                        .name("P0 graceful restart " + suffix)
                        .tenantId(tenantId)
                        .addString("p0-graceful-restart.bpmn20.xml", PROCESS_XML)
                        .deploy();
                deploymentId = deployment.getId();
                String definitionId = engine.getRepositoryService().createProcessDefinitionQuery()
                        .deploymentId(deploymentId).singleResult().getId();
                assertThat(engine.getRepositoryService().getProcessDefinition(definitionId).getKey())
                        .isEqualTo(PROCESS_KEY);

                var waitingProcess = engine.getRuntimeService().createProcessInstanceBuilder()
                        .processDefinitionId(definitionId).tenantId(tenantId).businessKey("waiting-" + suffix).start();
                processIds.add(waitingProcess.getId());
                String waitingTaskId = engine.getTaskService().createTaskQuery()
                        .processInstanceId(waitingProcess.getId()).singleResult().getId();

                var timerProcess = engine.getRuntimeService().createProcessInstanceBuilder()
                        .processDefinitionId(definitionId).tenantId(tenantId).businessKey("timer-" + suffix).start();
                processIds.add(timerProcess.getId());
                String taskBeforeTimer = engine.getTaskService().createTaskQuery()
                        .processInstanceId(timerProcess.getId()).singleResult().getId();
                engine.getTaskService().complete(taskBeforeTimer);
                Job originalTimer = timerFor(engine, timerProcess.getId());

                ProcessEngine firstEngine = engine;
                engine = null;
                firstEngine.close();
                assertThat(pool.isClosed()).isFalse();
                engine = newOwnedEngine(pool, jdbc, suffix, true);
                assertThat(engine).isNotSameAs(firstEngine);
                assertThat(engine.getTaskService().createTaskQuery().taskId(waitingTaskId).singleResult())
                        .isNotNull().satisfies(task -> {
                            assertThat(task.getProcessInstanceId()).isEqualTo(waitingProcess.getId());
                            assertThat(task.getTenantId()).isEqualTo(tenantId);
                        });
                Job recoveredTimer = timerFor(engine, timerProcess.getId());
                assertThat(recoveredTimer.getId()).isEqualTo(originalTimer.getId());
                assertThat(recoveredTimer.getDuedate()).isEqualTo(originalTimer.getDuedate());
                assertThat(recoveredTimer.getRetries()).isEqualTo(originalTimer.getRetries());

                engine.getTaskService().complete(waitingTaskId);
                assertThat(engine.getTaskService().createTaskQuery().taskId(waitingTaskId).count()).isZero();
                Job waitingProcessTimer = timerFor(engine, waitingProcess.getId());
                ManagementService failedManagement = engine.getManagementService();
                Job firstAttempt = failedManagement.moveTimerToExecutableJob(recoveredTimer.getId());
                assertThatThrownBy(() -> failedManagement.executeJob(firstAttempt.getId()))
                        .hasRootCauseInstanceOf(RecoveryWriteFailure.class);
                assertEventCount(jdbc, timerProcess.getId(), 0);
                Job persistedRetry = timerFor(engine, timerProcess.getId());
                assertThat(persistedRetry.getRetries()).isEqualTo(firstAttempt.getRetries() - 1).isPositive();
                assertThat(failedManagement.getTimerJobExceptionStacktrace(persistedRetry.getId()))
                        .contains(RecoveryWriteFailure.class.getName());
                assertThat(failedManagement.createJobQuery().processInstanceId(timerProcess.getId()).count()).isZero();
                assertThat(failedManagement.createDeadLetterJobQuery().processInstanceId(timerProcess.getId()).count())
                        .isZero();

                ProcessEngine secondEngine = engine;
                engine = null;
                secondEngine.close();
                assertThat(pool.isClosed()).isFalse();
                // A new delegate, with no in-memory attempt counter, is used by the third engine.
                engine = newOwnedEngine(pool, jdbc, suffix, false);
                assertThat(engine).isNotSameAs(secondEngine).isNotSameAs(firstEngine);
                Job restoredRetry = timerFor(engine, timerProcess.getId());
                assertThat(restoredRetry.getId()).isEqualTo(persistedRetry.getId());
                assertThat(restoredRetry.getRetries()).isEqualTo(persistedRetry.getRetries());
                assertThat(engine.getManagementService().getTimerJobExceptionStacktrace(restoredRetry.getId()))
                        .contains(RecoveryWriteFailure.class.getName());
                assertEventCount(jdbc, timerProcess.getId(), 0);
                assertThat(timerFor(engine, waitingProcess.getId()).getId()).isEqualTo(waitingProcessTimer.getId());

                ManagementService recoveredManagement = engine.getManagementService();
                Job retry = recoveredManagement.moveTimerToExecutableJob(restoredRetry.getId());
                recoveredManagement.executeJob(retry.getId());
                Job resumedTaskTimer = recoveredManagement.moveTimerToExecutableJob(waitingProcessTimer.getId());
                recoveredManagement.executeJob(resumedTaskTimer.getId());
                for (String processId : processIds) {
                    assertThat(engine.getRuntimeService().createProcessInstanceQuery().processInstanceId(processId).count())
                            .isZero();
                    assertThat(recoveredManagement.createTimerJobQuery().processInstanceId(processId).count()).isZero();
                    assertThat(recoveredManagement.createJobQuery().processInstanceId(processId).count()).isZero();
                    assertThat(recoveredManagement.createDeadLetterJobQuery().processInstanceId(processId).count()).isZero();
                    assertEventCount(jdbc, processId, 1);
                }
                assertThatThrownBy(() -> recoveredManagement.executeJob(retry.getId()))
                        .isInstanceOf(JobNotFoundException.class);
                assertEventCount(jdbc, timerProcess.getId(), 1);
            } finally {
                try {
                    if (deploymentId != null) {
                        // If replacement construction failed, obtain an owned engine solely for cleanup.
                        if (engine == null) engine = newOwnedEngine(pool, jdbc, suffix, false);
                        engine.getRepositoryService().deleteDeployment(deploymentId, true);
                    }
                } finally {
                    try {
                        for (String processId : processIds) {
                            jdbc.update("delete from p0_outbox where event_key = ?", eventKey(processId));
                        }
                    } finally {
                        if (engine != null) engine.close();
                    }
                }
            }
        }
    }

    private static ProcessEngine newOwnedEngine(HikariDataSource pool, JdbcTemplate jdbc,
                                                String suffix, boolean failAfterOutboxWrite) {
        var configuration = new SpringProcessEngineConfiguration();
        configuration.setEngineName("p0-restart-" + suffix + "-" + UUID.randomUUID());
        configuration.setDataSource(pool);
        configuration.setTransactionManager(new DataSourceTransactionManager(pool));
        configuration.setDatabaseType("postgres");
        configuration.setDatabaseSchema("public");
        configuration.setDatabaseSchemaUpdate(ProcessEngineConfiguration.DB_SCHEMA_UPDATE_FALSE);
        configuration.setAsyncExecutorActivate(false);
        configuration.setAsyncHistoryExecutorActivate(false);
        configuration.setAsyncHistoryEnabled(false);
        configuration.setAsyncExecutorAsyncJobAcquisitionEnabled(false);
        configuration.setAsyncExecutorTimerJobAcquisitionEnabled(false);
        configuration.setAsyncExecutorResetExpiredJobsEnabled(false);
        configuration.setAsyncHistoryExecutorAsyncJobAcquisitionEnabled(false);
        configuration.setAsyncHistoryExecutorResetExpiredJobsEnabled(false);
        configuration.setEnableHistoryCleaning(false);
        configuration.setDisableIdmEngine(true);
        configuration.setDisableEventRegistry(true);
        configuration.setEnableConfiguratorServiceLoader(false);
        configuration.setIdGenerator(new StrongUuidGenerator());
        configuration.setBeans(Map.of("p0RestartOutbox", new RecoveryOutboxDelegate(jdbc, failAfterOutboxWrite)));
        return configuration.buildProcessEngine();
    }

    private static Job timerFor(ProcessEngine engine, String processId) {
        Job timer = engine.getManagementService().createTimerJobQuery().processInstanceId(processId).singleResult();
        assertThat(timer).isNotNull();
        return timer;
    }

    private static void assertEventCount(JdbcTemplate jdbc, String processId, int expected) {
        assertThat(jdbc.queryForObject("select count(*) from p0_outbox where event_key = ?",
                Integer.class, eventKey(processId))).isEqualTo(expected);
    }

    private static String eventKey(String processId) {
        return processId + ":restart-recovered";
    }

    private static final class RecoveryOutboxDelegate implements JavaDelegate {
        private final JdbcTemplate jdbc;
        private final boolean failAfterWrite;

        private RecoveryOutboxDelegate(JdbcTemplate jdbc, boolean failAfterWrite) {
            this.jdbc = jdbc;
            this.failAfterWrite = failAfterWrite;
        }

        @Override
        public void execute(DelegateExecution execution) {
            jdbc.update("insert into p0_outbox (event_key, event_type) values (?, ?)",
                    eventKey(execution.getProcessInstanceId()), "P0_GRACEFUL_RESTART_RECOVERED");
            if (failAfterWrite) throw new RecoveryWriteFailure();
        }
    }

    private static final class RecoveryWriteFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
}
