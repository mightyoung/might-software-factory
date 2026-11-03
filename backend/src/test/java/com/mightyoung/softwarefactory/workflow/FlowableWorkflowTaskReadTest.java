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
                mock(org.jooq.DSLContext.class), lock, source, management, guard);
    }

    @AfterEach void end() {
        TransactionSynchronizationManager.unbindResourceIfPossible(source);
        TransactionSynchronizationManager.clear();
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

    private WorkflowTaskReadSnapshot read() {
        return facade.readCurrentTask(TENANT, "FINDING", FINDING, "remediation");
    }

    private void assertUnavailableBeforeQueries() {
        assertThatThrownBy(this::read).isInstanceOf(WorkflowEngineUnavailableException.class);
        assertThat(bindingSql).isEmpty();
        verifyNoInteractions(management, runtime, repository, tasks, lock, guard);
    }
}
