package com.mightyoung.softwarefactory;

import com.mightyoung.softwarefactory.dualprevention.finding.workflow.FindingWorkflowChangedEvent;
import com.mightyoung.softwarefactory.dualprevention.finding.workflow.FindingWorkflowState;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Real PostgreSQL transaction participation of the production Modulith publication registry. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("lan-postgres-compatibility")
class ModulithPostgresTransactionIT {

    private static final String EVENT_TYPE = FindingWorkflowChangedEvent.class.getName();
    private static final String PROCESS_XML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                              targetNamespace="urn:might:p0:modulith-transaction">
              <bpmn:process id="p0ModulithTransaction" isExecutable="true">
                <bpmn:startEvent id="start" />
                <bpmn:sequenceFlow id="toTask" sourceRef="start" targetRef="task" />
                <bpmn:userTask id="task" name="Await transaction verification" />
                <bpmn:sequenceFlow id="toEnd" sourceRef="task" targetRef="end" />
                <bpmn:endEvent id="end" />
              </bpmn:process>
            </bpmn:definitions>
            """;

    @Autowired private RepositoryService repositoryService;
    @Autowired private RuntimeService runtimeService;
    @Autowired private TaskService taskService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DSLContext dsl;
    @Autowired private ApplicationEventPublisher applicationEvents;
    @Autowired private PlatformTransactionManager transactionManager;

    @DynamicPropertySource
    static void lanPostgresProperties(DynamicPropertyRegistry properties) {
        var target = LanPostgresCompatibilityIT.DatabaseTarget.preflightFromEnvironment(System.getenv());
        properties.add("spring.datasource.url", target::jdbcUrl);
        properties.add("spring.datasource.username", () -> System.getenv("LAN_PG_USERNAME"));
        properties.add("spring.datasource.password", () -> System.getenv("LAN_PG_PASSWORD"));
    }

    @Test
    void engineProjectionTransitionAndActualPublicationRollBackTogetherWhileCommittedPublicationCompletes()
            throws InterruptedException {
        UUID tenantId = UUID.randomUUID();
        var rollbackEvent = event(tenantId);
        var committedEvent = event(tenantId);
        List<String> processIds = new ArrayList<>();
        var deployment = repositoryService.createDeployment()
                .name("P0 actual publication transaction " + UUID.randomUUID())
                .tenantId(tenantId.toString())
                .addString("p0-modulith-transaction.bpmn20.xml", PROCESS_XML)
                .deploy();
        try {
            String definitionId = repositoryService.createProcessDefinitionQuery()
                    .deploymentId(deployment.getId()).singleResult().getId();
            var transaction = new TransactionTemplate(transactionManager);

            assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
                String processId = startAndPublish(definitionId, tenantId, rollbackEvent, processIds);
                assertProbeRows(processId, 1);
                assertThat(publicationCount(rollbackEvent.commandId(), false)).isEqualTo(1);
                assertThat(publicationCount(rollbackEvent.commandId(), true)).isZero();
                throw new DeliberatePublicationRollback();
            })).isInstanceOf(DeliberatePublicationRollback.class);

            String rolledBackProcessId = processIds.get(0);
            assertThat(runtimeService.createProcessInstanceQuery()
                    .processInstanceId(rolledBackProcessId).count()).isZero();
            assertThat(taskService.createTaskQuery().processInstanceId(rolledBackProcessId).count()).isZero();
            assertProbeRows(rolledBackProcessId, 0);
            assertThat(publicationCount(rollbackEvent.commandId(), false)).isZero();

            String committedProcessId = transaction.execute(status ->
                    startAndPublish(definitionId, tenantId, committedEvent, processIds));
            assertThat(committedProcessId).isNotNull();
            assertThat(runtimeService.createProcessInstanceQuery()
                    .processInstanceId(committedProcessId).count()).isEqualTo(1);
            assertThat(taskService.createTaskQuery().processInstanceId(committedProcessId).count()).isEqualTo(1);
            assertProbeRows(committedProcessId, 1);

            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (publicationCount(committedEvent.commandId(), true) == 0 && System.nanoTime() < deadline) {
                Thread.sleep(25);
            }
            assertThat(publicationCount(committedEvent.commandId(), false)).isEqualTo(1);
            assertThat(publicationCount(committedEvent.commandId(), true))
                    .as("The existing production listener must complete this publication after commit")
                    .isEqualTo(1);
            assertThat(publicationCount(rollbackEvent.commandId(), false)).isZero();
        } finally {
            try {
                repositoryService.deleteDeployment(deployment.getId(), true);
            } finally {
                try {
                    for (String processId : processIds) {
                        jdbc.update("delete from p0_finding_transition where process_instance_id = ?", processId);
                        jdbc.update("delete from p0_finding_projection where process_instance_id = ?", processId);
                    }
                } finally {
                    for (UUID commandId : List.of(rollbackEvent.commandId(), committedEvent.commandId())) {
                        jdbc.update("""
                                delete from event_publication
                                where event_type = ? and serialized_event::jsonb ->> 'commandId' = ?
                                """, EVENT_TYPE, commandId.toString());
                    }
                }
            }
        }
    }

    private String startAndPublish(String definitionId, UUID tenantId, FindingWorkflowChangedEvent event,
                                   List<String> processIds) {
        var process = runtimeService.createProcessInstanceBuilder()
                .processDefinitionId(definitionId)
                .tenantId(tenantId.toString())
                .businessKey("p0-publication-" + event.commandId())
                .start();
        String processId = process.getId();
        processIds.add(processId);
        dsl.execute("insert into p0_finding_projection values (?, ?, ?, ?)",
                processId, tenantId.toString(), "PERSON_CONFIRM_PENDING", 1L);
        jdbc.update("insert into p0_finding_transition values (?, ?)", processId, event.commandId().toString());
        applicationEvents.publishEvent(event);
        return processId;
    }

    private void assertProbeRows(String processId, int expected) {
        assertThat(jdbc.queryForObject("select count(*) from p0_finding_projection where process_instance_id = ?",
                Integer.class, processId)).isEqualTo(expected);
        assertThat(jdbc.queryForObject("select count(*) from p0_finding_transition where process_instance_id = ?",
                Integer.class, processId)).isEqualTo(expected);
    }

    private int publicationCount(UUID commandId, boolean completedOnly) {
        return jdbc.queryForObject("""
                select count(*) from event_publication
                where event_type = ? and serialized_event::jsonb ->> 'commandId' = ?
                """ + (completedOnly ? " and completion_date is not null" : ""),
                Integer.class, EVENT_TYPE, commandId.toString());
    }

    private static FindingWorkflowChangedEvent event(UUID tenantId) {
        return new FindingWorkflowChangedEvent(tenantId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "P0_MODULITH_TRANSACTION_PROBE", null, FindingWorkflowState.PERSON_CONFIRM_PENDING, 1, 1L);
    }

    private static final class DeliberatePublicationRollback extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
}
