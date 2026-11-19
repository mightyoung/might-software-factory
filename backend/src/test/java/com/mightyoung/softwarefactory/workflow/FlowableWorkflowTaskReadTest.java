package com.mightyoung.softwarefactory.workflow;

import com.mightyoung.softwarefactory.authorization.AuthorizationWriteGuard;
import org.apache.ibatis.session.SqlSession;
import org.flowable.common.engine.impl.db.DbSqlSession;
import org.flowable.common.engine.impl.interceptor.Command;
import org.flowable.common.engine.impl.interceptor.CommandContext;
import org.flowable.engine.ManagementService;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.repository.ProcessDefinition;
import org.flowable.engine.repository.ProcessDefinitionQuery;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.engine.runtime.ProcessInstanceQuery;
import org.flowable.task.api.Task;
import org.flowable.task.api.TaskQuery;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.tools.jdbc.MockConnection;
import org.jooq.tools.jdbc.MockResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Mock evidence for refusal/identity/read-only behavior; no physical PG snapshot race claim. */
class FlowableWorkflowTaskReadTest {
    private static final UUID TENANT = UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final UUID FINDING = UUID.fromString("40000000-0000-0000-0000-000000000001");
    private final DataSource source = mock(DataSource.class);
    private final Connection connection = mock(Connection.class);
    private final ConnectionHolder holder = new ConnectionHolder(connection);
    private final org.flowable.engine.HistoryService history = mock(org.flowable.engine.HistoryService.class);
    private final RuntimeService runtime = mock(RuntimeService.class);
    private final TaskService tasks = mock(TaskService.class);
    private final RepositoryService repository = mock(RepositoryService.class);
    private final ManagementService management = mock(ManagementService.class);
    private final WorkflowBindingTransactionLock lock = mock(WorkflowBindingTransactionLock.class);
    private final AuthorizationWriteGuard guard = mock(AuthorizationWriteGuard.class);
    private final ProcessInstance instance = mock(ProcessInstance.class);
    private final ProcessDefinition definition = mock(ProcessDefinition.class);
    private final Task task = mock(Task.class);
    private final TaskQuery taskQuery = mock(TaskQuery.class, RETURNS_SELF);
    private final SqlSession sqlSession = mock(SqlSession.class);
    private final List<String> bindingSql = new ArrayList<>();
    private boolean bindingPresent = true;
    private int revision = 1;
    private String actualTenant = TENANT.toString();
    private String actualIsolation = "repeatable read";
    private String actualReadOnly = "on";
    private FlowableWorkflowFacade facade;

    @BeforeEach void begin() throws Exception {
        holder.setSynchronizedWithTransaction(true);
        TransactionSynchronizationManager.bindResource(source, holder);
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
        when(connection.isReadOnly()).thenReturn(true);
        when(connection.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_REPEATABLE_READ);
        var jdbc = new MockConnection(context -> {
            bindingSql.add(context.sql());
            if (context.sql().stripLeading().startsWith("insert")) {
                return new MockResult[]{new MockResult(1)};
            }
            var values = DSL.using(SQLDialect.POSTGRES);
            var businessRevision = DSL.field("business_revision", Integer.class);
            var definitionId = DSL.field("definition_id", String.class);
            var processId = DSL.field("process_instance_id", String.class);
            var rows = values.newResult(businessRevision, definitionId, processId);
            if (bindingPresent) {
                var row = values.newRecord(businessRevision, definitionId, processId);
                row.set(businessRevision, revision); row.set(definitionId, "definition-1");
                row.set(processId, "process-1"); rows.add(row);
            }
            return new MockResult[]{new MockResult(rows.size(), rows)};
        });
        when(connection.prepareStatement(anyString())).thenAnswer(call -> {
            String sql = call.getArgument(0);
            if (!sql.contains("current_setting")) return jdbc.prepareStatement(sql);
            var statement = mock(PreparedStatement.class);
            var rows = mock(ResultSet.class);
            when(rows.next()).thenReturn(true, false);
            when(rows.getString(1)).thenReturn(actualTenant);
            when(rows.getString(2)).thenReturn(actualIsolation);
            when(rows.getString(3)).thenReturn(actualReadOnly);
            when(statement.executeQuery()).thenReturn(rows);
            return statement;
        });
        var context = mock(CommandContext.class);
        var db = mock(DbSqlSession.class);
        when(context.getSession(DbSqlSession.class)).thenReturn(db);
        when(db.getSqlSession()).thenReturn(sqlSession);
        when(sqlSession.getConnection()).thenReturn(connection);
        when(management.executeCommand(org.mockito.ArgumentMatchers.<Command<Object>>any()))
                .thenAnswer(call -> ((Command<?>) call.getArgument(0)).execute(context));
        var instanceQuery = mock(ProcessInstanceQuery.class, RETURNS_SELF);
        when(runtime.createProcessInstanceQuery()).thenReturn(instanceQuery);
        when(instanceQuery.singleResult()).thenReturn(instance);
        when(instance.getId()).thenReturn("process-1");
        when(instance.getTenantId()).thenReturn(TENANT.toString());
        when(instance.getProcessDefinitionId()).thenReturn("definition-1");
        when(instance.getBusinessKey()).thenReturn("FINDING:" + FINDING);
        var definitionQuery = mock(ProcessDefinitionQuery.class, RETURNS_SELF);
        when(repository.createProcessDefinitionQuery()).thenReturn(definitionQuery);
        when(definitionQuery.singleResult()).thenReturn(definition);
        when(definition.getId()).thenReturn("definition-1");
        when(definition.getTenantId()).thenReturn(TENANT.toString());
        when(definition.getKey()).thenReturn("findingRemediationV2");
        when(tasks.createTaskQuery()).thenReturn(taskQuery);
        when(taskQuery.listPage(0, 2)).thenReturn(List.of(task));
        when(task.getId()).thenReturn("task-1");
        when(task.getTenantId()).thenReturn(TENANT.toString());
        when(task.getProcessInstanceId()).thenReturn("process-1");
        when(task.getProcessDefinitionId()).thenReturn("definition-1");
        when(task.getTaskDefinitionKey()).thenReturn("remediation");
        when(task.getAssignee()).thenReturn("member-1");
        facade = new FlowableWorkflowFacade(runtime, tasks, repository,
                mock(org.jooq.DSLContext.class), lock, source, management, guard, history);
    }

    @AfterEach void end() {
        TransactionSynchronizationManager.unbindResourceIfPossible(source);
        TransactionSynchronizationManager.clear();
    }

    @Test void bindingReadUsesSameConnectionAndNeverTakesCommandLock() {
        assertThat(facade.readBoundDefinition(TENANT,"FINDING",FINDING)).contains(
                new WorkflowBindingReadSnapshot(TENANT,"FINDING",FINDING,1,"definition-1","findingRemediationV2","process-1"));
        verifyNoInteractions(lock,guard,tasks,history);
    }
    @Test void unboundReadIsEmptyWithoutQueryingEngineIdentity() {
        bindingPresent=false;
        assertThat(facade.readBoundDefinition(TENANT,"FINDING",FINDING)).isEmpty();
        verifyNoInteractions(lock,guard,tasks,history);
    }
    @Test void endedBindingRequiresExactCompletedHistory() {
        when(runtime.createProcessInstanceQuery().singleResult()).thenReturn(null);
        var query=mock(org.flowable.engine.history.HistoricProcessInstanceQuery.class,RETURNS_SELF);
        var historic=mock(org.flowable.engine.history.HistoricProcessInstance.class);
        when(history.createHistoricProcessInstanceQuery()).thenReturn(query);
        when(query.singleResult()).thenReturn(historic);
        when(historic.getId()).thenReturn("process-1");
        when(historic.getTenantId()).thenReturn(TENANT.toString());
        when(historic.getProcessDefinitionId()).thenReturn("definition-1");
        when(historic.getBusinessKey()).thenReturn("FINDING:"+FINDING);
        when(historic.getEndTime()).thenReturn(new java.util.Date());
        assertThat(facade.readBoundDefinition(TENANT,"FINDING",FINDING)).isPresent();
        when(historic.getTenantId()).thenReturn(UUID.randomUUID().toString());
        assertThatThrownBy(() -> facade.readBoundDefinition(TENANT,"FINDING",FINDING)).isInstanceOf(WorkflowEngineUnavailableException.class);
        verifyNoInteractions(lock,guard,tasks);
    }

    @Test void readsPinnedIdentityOnCallerConnectionWithoutBindingLockOrMutation() {
        var snapshot = read();
        assertThat(snapshot).isEqualTo(new WorkflowTaskReadSnapshot(TENANT, "FINDING", FINDING, 1,
                "definition-1", "findingRemediationV2", "process-1", "remediation", "task-1", "member-1", true));
        assertThat(bindingSql).hasSize(1).allSatisfy(sql ->
                assertThat(sql.toLowerCase()).doesNotContain("for update", "for share", "insert", "update "));
        verify(taskQuery).listPage(0, 2);
        verifyNoInteractions(lock, guard);
        verify(tasks, never()).complete(anyString(), anyMap());
        verify(runtime, never()).createProcessInstanceBuilder();
    }

    @Test void rcReadRequiresExistingCommandGuard() throws Exception {
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
        when(connection.isReadOnly()).thenReturn(false);
        when(connection.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_READ_COMMITTED);
        actualIsolation = "read committed"; actualReadOnly = "off";
        assertThat(read().active()).isTrue();
        verify(guard).requireFindingCommand(TENANT);
        verify(guard, never()).forFindingCommand(any());
    }

    @Test void absentRcGuardFailsWithoutBindingLockOrEngineQueries() throws Exception {
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
        when(connection.isReadOnly()).thenReturn(false);
        when(connection.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_READ_COMMITTED);
        actualIsolation = "read committed"; actualReadOnly = "off";
        doThrow(new com.mightyoung.softwarefactory.authorization.AuthorizationWriteGuardException("No guard"))
                .when(guard).requireFindingCommand(TENANT);
        assertThatThrownBy(this::read).isInstanceOf(
                com.mightyoung.softwarefactory.authorization.AuthorizationWriteGuardException.class);
        assertThat(bindingSql).isEmpty();
        verifyNoInteractions(management, runtime, tasks, repository, lock);
        verify(guard, never()).forFindingCommand(any());
    }

    @Test void flowableQueryFailureIsUnavailable() {
        when(taskQuery.listPage(0, 2)).thenThrow(new org.flowable.common.engine.api.FlowableException("private engine detail"));
        assertThatThrownBy(this::read).isInstanceOf(WorkflowEngineUnavailableException.class);
        verifyNoInteractions(lock, guard);
    }

    @Test void absentTransactionFailsBeforeEngineOrBindingRead() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
        assertUnavailableBeforeQueries();
    }

    @Test void wrongDatasourceFailsBeforeEngineOrBindingRead() {
        TransactionSynchronizationManager.unbindResource(source);
        assertUnavailableBeforeQueries();
    }

    @Test void rollbackOnlyFailsBeforeEngineOrBindingRead() {
        holder.setRollbackOnly();
        assertUnavailableBeforeQueries();
    }

    @Test void wrongTenantFailsBeforeEngineOrBindingRead() {
        actualTenant = UUID.randomUUID().toString();
        assertUnavailableBeforeQueries();
    }

    @Test void physicalIsolationMismatchFailsBeforeEngineOrBindingRead() {
        actualIsolation = "read committed";
        assertUnavailableBeforeQueries();
    }

    @Test void separateEngineConnectionIsUnavailableBeforeBindingOrTaskQuery() {
        when(sqlSession.getConnection()).thenReturn(mock(Connection.class));
        assertThatThrownBy(this::read).isInstanceOf(WorkflowEngineUnavailableException.class);
        assertThat(bindingSql).isEmpty();
        verifyNoInteractions(runtime, repository, tasks, lock);
    }

    @Test void missingBindingAndMissingTaskHaveExplicitConflictResults() {
        bindingPresent = false;
        assertThatThrownBy(this::read).isInstanceOf(WorkflowResourceNotVisibleException.class);
        bindingPresent = true;
        when(taskQuery.listPage(0, 2)).thenReturn(List.of());
        assertThatThrownBy(this::read).isInstanceOf(WorkflowTaskStateConflictException.class);
    }

    @Test void ambiguousTasksFailUnavailable() {
        when(taskQuery.listPage(0, 2)).thenReturn(List.of(task, task));
        assertThatThrownBy(this::read).isInstanceOf(WorkflowEngineUnavailableException.class);
    }

    @Test void mismatchedPinnedDefinitionTenantProcessAndTaskFailUnavailable() {
        when(definition.getTenantId()).thenReturn("other-tenant");
        assertThatThrownBy(this::read).isInstanceOf(WorkflowEngineUnavailableException.class);
        when(definition.getTenantId()).thenReturn(TENANT.toString());
        when(instance.getProcessDefinitionId()).thenReturn("latest-definition");
        assertThatThrownBy(this::read).isInstanceOf(WorkflowEngineUnavailableException.class);
        when(instance.getProcessDefinitionId()).thenReturn("definition-1");
        when(task.getProcessInstanceId()).thenReturn("other-process");
        assertThatThrownBy(this::read).isInstanceOf(WorkflowEngineUnavailableException.class);
    }

    @Test void unsupportedRevisionFailsUnavailable() {
        revision = 2;
        assertThatThrownBy(this::read).isInstanceOf(WorkflowEngineUnavailableException.class);
    }

    @Test void unresolvedAssigneeAndSuspendedTaskRemainExplicit() {
        when(task.getAssignee()).thenReturn(null);
        assertThat(read().assignee()).isNull();
        when(task.isSuspended()).thenReturn(true);
        assertThat(read().active()).isFalse();
    }

    @Test void authorityLocksOnlyAfterExistingGuardAndCompletesExactTaskOnSameConnection() throws Exception {
        UUID actor = UUID.randomUUID();
        commandMode(actor);
        var authority = facade.acquireTaskAuthority(TENANT, "FINDING", FINDING, "remediation", actor);
        assertThat(authority.snapshot().taskId()).isEqualTo("task-1");
        var order = inOrder(guard, lock, management);
        order.verify(guard).requireFindingCommand(TENANT);
        order.verify(lock).acquire(TENANT, "FINDING", FINDING);
        order.verify(management).executeCommand(org.mockito.ArgumentMatchers.<Command<Object>>any());
        facade.completeTask(authority, java.util.Map.of("verificationDecision", "PASS"));
        verify(tasks).complete("task-1", java.util.Map.of("verificationDecision", "PASS"));
        assertThatThrownBy(() -> facade.completeTask(authority, java.util.Map.of()))
                .isInstanceOf(WorkflowEngineUnavailableException.class);
    }

    @Test void authorityCannotBeAcquiredWithMissingGuardOrInReadOnlySnapshot() throws Exception {
        assertThatThrownBy(() -> facade.acquireTaskAuthority(TENANT, "FINDING", FINDING,
                "remediation", UUID.randomUUID())).isInstanceOf(WorkflowEngineUnavailableException.class);
        verifyNoInteractions(lock, management);
        UUID actor = UUID.randomUUID(); commandMode(actor);
        doThrow(new com.mightyoung.softwarefactory.authorization.AuthorizationWriteGuardException("Missing"))
                .when(guard).requireFindingCommand(TENANT);
        assertThatThrownBy(() -> facade.acquireTaskAuthority(TENANT, "FINDING", FINDING,
                "remediation", actor)).isInstanceOf(
                        com.mightyoung.softwarefactory.authorization.AuthorizationWriteGuardException.class);
        verifyNoInteractions(lock, management);
    }

    @Test void authorityRejectsTaskReplacementReassignmentSuspensionAndSavepointRollback() throws Exception {
        UUID actor = UUID.randomUUID(); commandMode(actor);
        var authority = facade.acquireTaskAuthority(TENANT, "FINDING", FINDING, "remediation", actor);
        when(task.getId()).thenReturn("replacement");
        assertThatThrownBy(() -> facade.completeTask(authority, java.util.Map.of()))
                .isInstanceOf(WorkflowTaskStateConflictException.class);
        when(task.getId()).thenReturn("task-1");
        when(task.getAssignee()).thenReturn(UUID.randomUUID().toString());
        assertThatThrownBy(() -> facade.completeTask(authority, java.util.Map.of()))
                .isInstanceOf(WorkflowTaskStateConflictException.class);
        when(task.getAssignee()).thenReturn(actor.toString());
        when(task.isSuspended()).thenReturn(true);
        assertThatThrownBy(() -> facade.completeTask(authority, java.util.Map.of()))
                .isInstanceOf(WorkflowTaskStateConflictException.class);
        when(task.isSuspended()).thenReturn(false);
        authority.savepointRollback(new Object());
        assertThatThrownBy(() -> facade.completeTask(authority, java.util.Map.of()))
                .isInstanceOf(WorkflowEngineUnavailableException.class);
        verify(tasks, never()).complete(anyString(), anyMap());
    }

    @Test void authorityCannotCrossManagedTransactionsEvenWithSameConnection() throws Exception {
        UUID actor = UUID.randomUUID(); commandMode(actor);
        var authority = facade.acquireTaskAuthority(TENANT, "FINDING", FINDING, "remediation", actor);
        TransactionSynchronizationManager.clearSynchronization();
        TransactionSynchronizationManager.initSynchronization();
        assertThatThrownBy(() -> facade.completeTask(authority, java.util.Map.of()))
                .isInstanceOf(WorkflowEngineUnavailableException.class);
        verify(tasks, never()).complete(anyString(), anyMap());
    }

    @Test void authorityRequiresActualAssignedActiveTask() throws Exception {
        UUID actor = UUID.randomUUID(); commandMode(actor);
        when(task.getAssignee()).thenReturn(null);
        assertThatThrownBy(() -> facade.acquireTaskAuthority(TENANT, "FINDING", FINDING,
                "remediation", actor)).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        when(task.getAssignee()).thenReturn(actor.toString());
        when(definition.isSuspended()).thenReturn(true);
        assertThatThrownBy(() -> facade.acquireTaskAuthority(TENANT, "FINDING", FINDING,
                "remediation", actor)).isInstanceOf(WorkflowTaskStateConflictException.class);
    }

    @Test void exactStartUsesOnlyReviewedIdAndPersistsThatId() throws Exception {
        commandMode(UUID.randomUUID());
        var pin = reviewedPin();
        var builder = mock(org.flowable.engine.runtime.ProcessInstanceBuilder.class, RETURNS_SELF);
        when(runtime.createProcessInstanceBuilder()).thenReturn(builder);
        when(builder.start()).thenReturn(instance);
        facade.startProcessByDefinitionId(TENANT, "FINDING", FINDING, 1, pin, java.util.Map.of());
        verify(builder).processDefinitionId("definition-1");
        verify(builder, never()).processDefinitionKey(anyString());
        assertThat(bindingSql).hasSize(1);
        assertThat(bindingSql.getFirst()).contains("insert into public.workflow_binding");
    }

    @Test void exactStartRejectsChangedResourceAndWrongReturnedIdentityBeforeBindingInsert() throws Exception {
        commandMode(UUID.randomUUID());
        var pin = reviewedPin();
        when(repository.getResourceAsStream("deployment", "reviewed.bpmn20.xml"))
                .thenReturn(new java.io.ByteArrayInputStream(new byte[]{1}));
        assertThatThrownBy(() -> facade.startProcessByDefinitionId(TENANT, "FINDING", FINDING,
                1, pin, java.util.Map.of())).isInstanceOf(WorkflowEngineUnavailableException.class);
        verify(runtime, never()).createProcessInstanceBuilder();
        assertThat(bindingSql).isEmpty();
        reviewedPin();
        var builder = mock(org.flowable.engine.runtime.ProcessInstanceBuilder.class, RETURNS_SELF);
        when(runtime.createProcessInstanceBuilder()).thenReturn(builder);
        when(builder.start()).thenReturn(instance);
        when(instance.getProcessDefinitionId()).thenReturn("newer-definition");
        assertThatThrownBy(() -> facade.startProcessByDefinitionId(TENANT, "FINDING", FINDING,
                1, pin, java.util.Map.of())).isInstanceOf(WorkflowEngineUnavailableException.class);
        assertThat(bindingSql).isEmpty();
    }

    private WorkflowDefinitionPin reviewedPin() throws Exception {
        byte[] bytes;
        try (var stream = getClass().getResourceAsStream("/" + PackagedWorkflowDefinitions.RESOURCES.get("findingRemediationV3"))) {
            bytes = stream.readAllBytes();
        }
        when(definition.getKey()).thenReturn("findingRemediationV3");
        when(definition.getDeploymentId()).thenReturn("deployment");
        when(definition.getResourceName()).thenReturn("reviewed.bpmn20.xml");
        when(repository.getResourceAsStream("deployment", "reviewed.bpmn20.xml"))
                .thenAnswer(call -> new java.io.ByteArrayInputStream(bytes));
        return new WorkflowDefinitionPin(TENANT, "definition-1", "findingRemediationV3",
                PackagedWorkflowDefinitions.sha256(bytes));
    }

    @Test void replayValidatesLiveOriginalTaskAndRejectsChangedAssignee() throws Exception {
        UUID actor = UUID.randomUUID(); commandMode(actor);
        var original = replayParticipant(actor);
        when(taskQuery.singleResult()).thenReturn(task);
        facade.validateReplayParticipant(TENANT, "FINDING", FINDING, original, actor);
        verify(taskQuery).taskId("task-1");
        verify(taskQuery, never()).taskDefinitionKey(anyString());
        when(task.getAssignee()).thenReturn(UUID.randomUUID().toString());
        assertThatThrownBy(() -> facade.validateReplayParticipant(TENANT, "FINDING", FINDING, original, actor))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        verify(tasks, never()).complete(anyString(), anyMap());
    }

    @Test void replayAllowsAdvancedAndEndedProcessWithoutRequiringOriginalActiveTask() throws Exception {
        UUID actor = UUID.randomUUID(); commandMode(actor);
        var original = replayParticipant(actor);
        when(taskQuery.singleResult()).thenReturn(null);
        facade.validateReplayParticipant(TENANT, "FINDING", FINDING, original, actor);
        when(runtime.createProcessInstanceQuery().singleResult()).thenReturn(null);
        facade.validateReplayParticipant(TENANT, "FINDING", FINDING, original, actor);
        verify(tasks, never()).complete(anyString(), anyMap());
        verify(runtime, never()).createProcessInstanceBuilder();
    }

    @Test void replayRejectsForeignOriginalBindingDefinitionProcessAndLiveTask() throws Exception {
        UUID actor = UUID.randomUUID(); commandMode(actor);
        var original = replayParticipant(actor);
        var foreign = new WorkflowTaskReadSnapshot(UUID.randomUUID(), "FINDING", FINDING, 1,
                "definition-1", "findingRemediationV2", "process-1", "remediation", "task-1", actor.toString(), true);
        assertThatThrownBy(() -> facade.validateReplayParticipant(TENANT, "FINDING", FINDING, foreign, actor))
                .isInstanceOf(WorkflowTaskStateConflictException.class);
        var otherBinding = new WorkflowTaskReadSnapshot(TENANT, "FINDING", FINDING, 1,
                "definition-other", "findingRemediationV2", "process-1", "remediation", "task-1", actor.toString(), true);
        assertThatThrownBy(() -> facade.validateReplayParticipant(TENANT, "FINDING", FINDING, otherBinding, actor))
                .isInstanceOf(WorkflowEngineUnavailableException.class);
        when(definition.getTenantId()).thenReturn(UUID.randomUUID().toString());
        assertReplayUnavailable(original, actor);
        when(definition.getTenantId()).thenReturn(TENANT.toString());
        when(instance.getProcessDefinitionId()).thenReturn("other-definition");
        assertReplayUnavailable(original, actor);
        when(instance.getProcessDefinitionId()).thenReturn("definition-1");
        when(taskQuery.singleResult()).thenReturn(task);
        when(task.getTaskDefinitionKey()).thenReturn("replacementStage");
        assertReplayUnavailable(original, actor);
        verify(tasks, never()).complete(anyString(), anyMap());
    }

    @Test void replayRequiresExistingGuardAndSamePhysicalEngineContext() throws Exception {
        UUID actor = UUID.randomUUID(); commandMode(actor);
        var original = replayParticipant(actor);
        doThrow(new com.mightyoung.softwarefactory.authorization.AuthorizationWriteGuardException("Missing"))
                .when(guard).requireFindingCommand(TENANT);
        assertThatThrownBy(() -> facade.validateReplayParticipant(TENANT, "FINDING", FINDING, original, actor))
                .isInstanceOf(com.mightyoung.softwarefactory.authorization.AuthorizationWriteGuardException.class);
        verifyNoInteractions(lock, management);
        doNothing().when(guard).requireFindingCommand(TENANT);
        when(sqlSession.getConnection()).thenReturn(mock(Connection.class));
        assertReplayUnavailable(original, actor);
        assertThat(bindingSql).isEmpty();
        verifyNoInteractions(runtime, tasks, repository);
    }

    private void assertReplayUnavailable(WorkflowTaskReadSnapshot original, UUID actor) {
        assertThatThrownBy(() -> facade.validateReplayParticipant(TENANT, "FINDING", FINDING, original, actor))
                .isInstanceOf(WorkflowEngineUnavailableException.class);
    }

    private WorkflowTaskReadSnapshot replayParticipant(UUID actor) {
        return new WorkflowTaskReadSnapshot(TENANT, "FINDING", FINDING, 1,
                "definition-1", "findingRemediationV2", "process-1", "remediation", "task-1", actor.toString(), true);
    }

    private void commandMode(UUID actor) throws Exception {
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
        when(connection.isReadOnly()).thenReturn(false);
        when(connection.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_READ_COMMITTED);
        actualIsolation = "read committed"; actualReadOnly = "off";
        when(task.getAssignee()).thenReturn(actor.toString());
    }

    private WorkflowTaskReadSnapshot read() {
        return facade.readCurrentTask(TENANT, "FINDING", FINDING, "remediation");
    }

    private void assertUnavailableBeforeQueries() {
        assertThatThrownBy(this::read).isInstanceOf(WorkflowEngineUnavailableException.class);
        assertThat(bindingSql).isEmpty();
        verifyNoInteractions(management, runtime, repository, tasks, lock, guard);
    }
}
