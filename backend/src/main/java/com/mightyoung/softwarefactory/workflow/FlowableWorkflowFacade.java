package com.mightyoung.softwarefactory.workflow;

import com.mightyoung.softwarefactory.authorization.AuthorizationWriteGuard;
import org.flowable.common.engine.api.FlowableException;
import org.flowable.engine.ManagementService;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.impl.util.CommandContextUtil;
import org.flowable.engine.repository.ProcessDefinition;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.task.api.Task;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Tenant-checking adapter around the embedded Flowable engine and the shared PostgreSQL transaction. */
@Component
public final class FlowableWorkflowFacade implements WorkflowFacade, WorkflowTaskClaim {

    private final org.flowable.engine.HistoryService historyService;
    private final RuntimeService runtimeService;
    private final TaskService taskService;
    private final RepositoryService repositoryService;
    private final DSLContext dsl;
    private final WorkflowBindingTransactionLock bindingLock;
    private final DataSource dataSource;
    private final ManagementService managementService;
    private final AuthorizationWriteGuard authorizationGuard;
    private final Set<Pool> taskPools;

    public FlowableWorkflowFacade(RuntimeService runtimeService, TaskService taskService,
                                  RepositoryService repositoryService, DSLContext dsl,
                                  WorkflowBindingTransactionLock bindingLock, DataSource dataSource,
                                  ManagementService managementService, AuthorizationWriteGuard authorizationGuard,
                                  org.flowable.engine.HistoryService historyService, Set<Pool> taskPools) {
        this.taskPools = Set.copyOf(taskPools);
        this.historyService = historyService;
        this.runtimeService = runtimeService;
        this.taskService = taskService;
        this.repositoryService = repositoryService;
        this.dsl = dsl;
        this.bindingLock = bindingLock;
        this.dataSource = dataSource;
        this.managementService = managementService;
        this.authorizationGuard = authorizationGuard;
    }

    @Override
    public void startProcess(
            UUID tenantId,
            String businessType,
            UUID businessId,
            int businessRevision,
            String processDefinitionKey,
            Map<String, Object> variables) {
        bindingLock.acquire(tenantId, businessType, businessId);
        ProcessInstance instance;
        try {
            instance = runtimeService.createProcessInstanceBuilder()
                    .processDefinitionKey(processDefinitionKey)
                    .tenantId(tenantId.toString())
                    .businessKey(businessType + ":" + businessId)
                    .variables(variables)
                    .start();
        } catch (FlowableException failure) {
            throw new WorkflowEngineUnavailableException(failure);
        }
        if (instance == null || instance.getId() == null || instance.getProcessDefinitionId() == null
                || !tenantId.toString().equals(instance.getTenantId())) {
            throw new IllegalStateException("Flowable did not start a tenant-bound process instance");
        }
        int inserted = dsl.execute("""
                insert into public.workflow_binding
                    (tenant_id, business_type, business_id, business_revision, submission_no,
                     definition_id, process_instance_id)
                values (cast(? as uuid), ?, cast(? as uuid), ?, 1, ?, ?)
                """, tenantId, businessType, businessId, businessRevision,
                instance.getProcessDefinitionId(), instance.getId());
        if (inserted != 1) throw new IllegalStateException("Flowable binding did not persist");
    }

    @Override
    public void startProcessByDefinitionId(UUID tenantId, String businessType, UUID businessId,
            int businessRevision, WorkflowDefinitionPin definition, Map<String, Object> variables) {
        Objects.requireNonNull(definition);
        Objects.requireNonNull(tenantId, "Tenant required");
        Objects.requireNonNull(businessId, "Business ID required");
        if (businessRevision != 1) throw unavailable("Unsupported pinned business revision");
        if (!tenantId.equals(definition.tenantId())) throw unavailable("Definition tenant mismatch");
        Connection connection = commandConnection(tenantId);
        bindingLock.acquire(tenantId, businessType, businessId);
        try {
            managementService.executeCommand(context -> {
                requireEngineConnection(context, connection);
                PackagedWorkflowDefinitions.verify(repositoryService, definition);
                ProcessInstance instance = runtimeService.createProcessInstanceBuilder()
                        .processDefinitionId(definition.definitionId()).tenantId(tenantId.toString())
                        .businessKey(businessType + ":" + businessId).variables(variables).start();
                if (instance == null || instance.getId() == null
                        || !definition.definitionId().equals(instance.getProcessDefinitionId())
                        || !tenantId.toString().equals(instance.getTenantId())
                        || !(businessType + ":" + businessId).equals(instance.getBusinessKey())) {
                    throw unavailable("Exact process start returned a different identity");
                }
                int inserted = DSL.using(connection, SQLDialect.POSTGRES).execute("""
                        insert into public.workflow_binding
                            (tenant_id, business_type, business_id, business_revision, submission_no,
                             definition_id, process_instance_id)
                        values (cast(? as uuid), ?, cast(? as uuid), ?, 1, ?, ?)
                        """, tenantId, businessType, businessId, businessRevision,
                        definition.definitionId(), instance.getId());
                if (inserted != 1) throw unavailable("Exact process binding did not persist");
                return null;
            });
        } catch (FlowableException failure) { throw new WorkflowEngineUnavailableException(failure); }
    }

    @Override
    public WorkflowCommandTaskAuthority acquireTaskAuthority(UUID tenantId, String businessType,
            UUID businessId, String taskDefinitionKey, UUID actorMemberId) {
        Objects.requireNonNull(actorMemberId, "Actor required");
        Connection connection = commandConnection(tenantId);
        bindingLock.acquire(tenantId, businessType, businessId);
        WorkflowTaskReadSnapshot snapshot = withCurrentTask(tenantId, businessType, businessId,
                taskDefinitionKey, connection, (identity, task) -> {
                    requireAssignedActive(identity, actorMemberId.toString());
                    return identity;
                });
        var authority = new WorkflowCommandTaskAuthority(snapshot, connection, this);
        TransactionSynchronizationManager.registerSynchronization(authority);
        return authority;
    }

    @Override
    public void completeTask(WorkflowCommandTaskAuthority authority, Map<String, Object> variables) {
        Objects.requireNonNull(authority, "Task authority required");
        var expected = authority.snapshot();
        Connection connection = commandConnection(expected.tenantId());
        if (authority.owner != this || authority.invalidated || authority.connection != connection
                || TransactionSynchronizationManager.getSynchronizations().stream()
                    .noneMatch(synchronization -> synchronization == authority)) {
            throw unavailable("Task authority belongs to a different or invalidated transaction");
        }
        bindingLock.acquire(expected.tenantId(), expected.businessType(), expected.businessId());
        withCurrentTask(expected.tenantId(), expected.businessType(), expected.businessId(),
                expected.taskDefinitionKey(), connection, (actual, task) -> {
                    if (!expected.equals(actual)) throw new WorkflowTaskStateConflictException();
                    requireAssignedActive(actual, expected.assignee());
                    taskService.complete(actual.taskId(), variables);
                    authority.invalidated = true;
                    return null;
                });
    }

    @Override
    public void claimAndComplete(WorkflowTaskReadSnapshot expected, UUID memberId, Map<String, Object> variables) {
        Objects.requireNonNull(memberId, "Claiming member required");
        Connection connection = commandConnection(expected.tenantId());
        bindingLock.acquire(expected.tenantId(), expected.businessType(), expected.businessId());
        withCurrentTask(expected.tenantId(), expected.businessType(), expected.businessId(),
                expected.taskDefinitionKey(), connection, (actual, task) -> {
                    // Unassigned individual tasks (for example a missing finding assignee)
                    // are unresolved, and must never become claimable through this operation.
                    var pool = new Pool(actual.businessType(), actual.processDefinitionKey(), actual.taskDefinitionKey());
                    if (!taskPools.contains(pool)
                            || !expected.equals(actual) || !actual.active() || !blank(actual.assignee())) {
                        throw new WorkflowTaskStateConflictException();
                    }
                    taskService.claim(actual.taskId(), memberId.toString());
                    taskService.complete(actual.taskId(), variables);
                    return null;
                });
    }

    @Override
    public void validateReplayParticipant(UUID tenantId, String businessType, UUID businessId,
            WorkflowTaskReadSnapshot original, UUID actorMemberId) {
        Objects.requireNonNull(original, "Persisted participant required");
        Objects.requireNonNull(actorMemberId, "Actor required");
        Connection connection = commandConnection(tenantId);
        bindingLock.acquire(tenantId, businessType, businessId);
        if (!tenantId.equals(original.tenantId()) || !businessId.equals(original.businessId())
                || !businessType.equals(original.businessType()) || original.businessRevision() != 1
                || blank(original.definitionId()) || blank(original.processDefinitionKey())
                || blank(original.processInstanceId()) || blank(original.taskId())
                || blank(original.taskDefinitionKey())) {
            throw new WorkflowTaskStateConflictException();
        }
        if (!actorMemberId.toString().equals(original.assignee())) {
            throw new AccessDeniedException("The current member does not match the recorded participant");
        }
        try {
            managementService.executeCommand(context -> {
                requireEngineConnection(context, connection);
                var binding = DSL.using(connection, SQLDialect.POSTGRES).fetchOne("""
                        select business_revision, definition_id, process_instance_id
                          from public.workflow_binding
                         where tenant_id = cast(? as uuid) and business_type = ?
                           and business_id = cast(? as uuid) and submission_no = 1
                        """, tenantId, businessType, businessId);
                if (binding == null) throw new WorkflowResourceNotVisibleException();
                if (!Integer.valueOf(1).equals(binding.get("business_revision", Integer.class))
                        || !original.definitionId().equals(binding.get("definition_id", String.class))
                        || !original.processInstanceId().equals(binding.get("process_instance_id", String.class))) {
                    throw unavailable("Recorded participant does not match the immutable workflow binding");
                }
                ProcessDefinition definition = repositoryService.createProcessDefinitionQuery()
                        .processDefinitionId(original.definitionId()).singleResult();
                if (definition == null || !original.definitionId().equals(definition.getId())
                        || !tenantId.toString().equals(definition.getTenantId())
                        || !original.processDefinitionKey().equals(definition.getKey())) {
                    throw unavailable("Recorded participant definition does not match Flowable");
                }
                ProcessInstance instance = runtimeService.createProcessInstanceQuery()
                        .processInstanceId(original.processInstanceId()).singleResult();
                if (instance != null && (!tenantId.toString().equals(instance.getTenantId())
                        || !original.processInstanceId().equals(instance.getId())
                        || !original.definitionId().equals(instance.getProcessDefinitionId())
                        || !(businessType + ":" + businessId).equals(instance.getBusinessKey()))) {
                    throw unavailable("Recorded participant process does not match Flowable");
                }
                Task task = taskService.createTaskQuery().taskId(original.taskId()).singleResult();
                if (task != null) {
                    if (instance == null || !original.taskId().equals(task.getId())
                            || !tenantId.toString().equals(task.getTenantId())
                            || !original.processInstanceId().equals(task.getProcessInstanceId())
                            || !original.definitionId().equals(task.getProcessDefinitionId())
                            || !original.taskDefinitionKey().equals(task.getTaskDefinitionKey())) {
                        throw unavailable("Recorded participant task does not match the pinned process");
                    }
                    if (!actorMemberId.toString().equals(task.getAssignee())) {
                        throw new AccessDeniedException("The recorded task is assigned to another member");
                    }
                }
                return null;
            });
        } catch (FlowableException failure) { throw new WorkflowEngineUnavailableException(failure); }
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }

    private Connection commandConnection(UUID tenantId) {
        if (TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw unavailable("Task authority requires a read-write command transaction");
        }
        return taskReadConnection(tenantId);
    }

    private static void requireAssignedActive(WorkflowTaskReadSnapshot snapshot, String actor) {
        if (!snapshot.active()) throw new WorkflowTaskStateConflictException();
        if (actor == null || actor.isBlank() || !actor.equals(snapshot.assignee())) {
            throw new AccessDeniedException("The current member is not assigned to this workflow task");
        }
    }

    private static void requireEngineConnection(
            org.flowable.common.engine.impl.interceptor.CommandContext context, Connection connection) {
        Connection engineConnection = CommandContextUtil.getDbSqlSession(context)
                .getSqlSession().getConnection();
        if (DataSourceUtils.getTargetConnection(engineConnection)
                != DataSourceUtils.getTargetConnection(connection)) {
            throw unavailable("Workflow operations require the application transaction connection");
        }
    }

    @Override
    public void completeTask(
            UUID tenantId,
            String businessType,
            UUID businessId,
            String taskDefinitionKey,
            UUID actorMemberId,
            Map<String, Object> variables) {
        bindingLock.acquire(tenantId, businessType, businessId);
        var binding = dsl.fetchOne("""
                select business_revision, definition_id, process_instance_id
                  from public.workflow_binding
                 where tenant_id = cast(? as uuid) and business_type = ?
                   and business_id = cast(? as uuid) and submission_no = 1
                """, tenantId, businessType, businessId);
        if (binding == null || binding.get("business_revision", Integer.class) != 1) {
            throw new WorkflowResourceNotVisibleException();
        }
        String processInstanceId = binding.get("process_instance_id", String.class);
        ProcessInstance instance;
        Task task;
        try {
            instance = runtimeService.createProcessInstanceQuery()
                    .processInstanceId(processInstanceId)
                    .singleResult();
            if (instance == null || !tenantId.toString().equals(instance.getTenantId())
                    || !binding.get("definition_id", String.class).equals(instance.getProcessDefinitionId())) {
                throw new WorkflowEngineUnavailableException(
                        new IllegalStateException("The persisted process binding does not match Flowable"));
            }
            task = taskService.createTaskQuery()
                    .processInstanceId(processInstanceId)
                    .taskDefinitionKey(taskDefinitionKey)
                    .singleResult();
        } catch (FlowableException failure) {
            throw new WorkflowEngineUnavailableException(failure);
        }
        if (task == null) throw new WorkflowTaskStateConflictException();
        if (!actorMemberId.toString().equals(task.getAssignee())) {
            throw new AccessDeniedException("The current member is not assigned to this workflow task");
        }
        try {
            taskService.complete(task.getId(), variables);
        } catch (FlowableException failure) {
            throw new WorkflowEngineUnavailableException(failure);
        }
    }

    @Override
    public WorkflowTaskReadSnapshot readCurrentTask(
            UUID tenantId, String businessType, UUID businessId, String taskDefinitionKey) {
        Objects.requireNonNull(tenantId, "Tenant required");
        Objects.requireNonNull(businessId, "Business ID required");
        if (businessType == null || businessType.isBlank()
                || taskDefinitionKey == null || taskDefinitionKey.isBlank()) {
            throw new IllegalArgumentException("Business type and task key required");
        }
        return withCurrentTask(tenantId, businessType, businessId, taskDefinitionKey,
                taskReadConnection(tenantId), (snapshot, task) -> snapshot);
    }

    private <T> T withCurrentTask(UUID tenantId, String businessType, UUID businessId,
            String taskDefinitionKey, Connection connection,
            java.util.function.BiFunction<WorkflowTaskReadSnapshot, Task, T> operation) {
        try {
            return managementService.executeCommand(context -> {
                requireEngineConnection(context, connection);
                // Explicitly use the validated caller connection, never an independent jOOQ connection.
                var binding = DSL.using(connection, SQLDialect.POSTGRES).fetchOne("""
                        select business_revision, definition_id, process_instance_id
                          from public.workflow_binding
                         where tenant_id = cast(? as uuid) and business_type = ?
                           and business_id = cast(? as uuid) and submission_no = 1
                        """, tenantId, businessType, businessId);
                if (binding == null) throw new WorkflowResourceNotVisibleException();
                if (!Integer.valueOf(1).equals(binding.get("business_revision", Integer.class))) {
                    throw unavailable("Unsupported pinned business revision");
                }
                String definitionId = binding.get("definition_id", String.class);
                String processId = binding.get("process_instance_id", String.class);
                if (definitionId == null || definitionId.isBlank() || processId == null || processId.isBlank()) {
                    throw unavailable("Incomplete persisted process binding");
                }
                ProcessDefinition definition = repositoryService.createProcessDefinitionQuery()
                        .processDefinitionId(definitionId).singleResult();
                ProcessInstance instance = runtimeService.createProcessInstanceQuery()
                        .processInstanceId(processId).singleResult();
                if (definition == null || instance == null
                        || !tenantId.toString().equals(definition.getTenantId())
                        || !definitionId.equals(definition.getId())
                        || definition.getKey() == null || definition.getKey().isBlank()
                        || !tenantId.toString().equals(instance.getTenantId())
                        || !processId.equals(instance.getId())
                        || !definitionId.equals(instance.getProcessDefinitionId())
                        || !(businessType + ":" + businessId).equals(instance.getBusinessKey())) {
                    throw unavailable("The pinned process identity does not match Flowable");
                }
                var tasks = taskService.createTaskQuery().processInstanceId(processId)
                        .taskDefinitionKey(taskDefinitionKey).listPage(0, 2);
                if (tasks.isEmpty()) throw new WorkflowTaskStateConflictException();
                if (tasks.size() != 1) throw unavailable("Ambiguous current workflow task");
                Task task = tasks.getFirst();
                if (task.getId() == null || task.getId().isBlank()
                        || !tenantId.toString().equals(task.getTenantId())
                        || !processId.equals(task.getProcessInstanceId())
                        || !definitionId.equals(task.getProcessDefinitionId())
                        || !taskDefinitionKey.equals(task.getTaskDefinitionKey())) {
                    throw unavailable("The current task does not match the pinned process");
                }
                return operation.apply(new WorkflowTaskReadSnapshot(tenantId, businessType, businessId, 1,
                        definitionId, definition.getKey(), processId, taskDefinitionKey,
                        task.getId(), task.getAssignee(), !definition.isSuspended()
                                && !instance.isSuspended() && !task.isSuspended()), task);
            });
        } catch (FlowableException failure) {
            throw new WorkflowEngineUnavailableException(failure);
        }
    }

    private Connection taskReadConnection(UUID tenantId) {
        Object resource = TransactionSynchronizationManager.getResource(dataSource);
        if (!(resource instanceof ConnectionHolder holder) || !holder.isSynchronizedWithTransaction()
                || holder.isRollbackOnly() || !TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            throw unavailable("Workflow task read requires the managed application transaction");
        }
        try {
            Connection connection = holder.getConnection();
            boolean readOnly = TransactionSynchronizationManager.isCurrentTransactionReadOnly();
            int requiredIsolation = readOnly ? Connection.TRANSACTION_REPEATABLE_READ
                    : Connection.TRANSACTION_READ_COMMITTED;
            if (connection.getAutoCommit() || connection.isReadOnly() != readOnly
                    || connection.getTransactionIsolation() != requiredIsolation) {
                throw unavailable("Workflow task read requires RC command or read-only RR snapshot");
            }
            try (var statement = connection.prepareStatement("""
                    select current_setting('app.tenant_id', true),
                           current_setting('transaction_isolation'), current_setting('transaction_read_only')
                    """); var rows = statement.executeQuery()) {
                if (!rows.next() || !tenantId.toString().equals(rows.getString(1))
                        || !(readOnly ? "repeatable read" : "read committed").equals(rows.getString(2))
                        || !(readOnly ? "on" : "off").equals(rows.getString(3))) {
                    throw unavailable("Workflow task read tenant or physical transaction mismatch");
                }
            }
            if (!readOnly) authorizationGuard.requireFindingCommand(tenantId);
            return connection;
        } catch (SQLException failure) {
            throw new WorkflowEngineUnavailableException(failure);
        }
    }

    private static WorkflowEngineUnavailableException unavailable(String reason) {
        return new WorkflowEngineUnavailableException(new IllegalStateException(reason));
    }

    @Override
    public Optional<WorkflowBindingReadSnapshot> readBoundDefinition(UUID tenantId, String businessType, UUID businessId) {
        Objects.requireNonNull(tenantId); Objects.requireNonNull(businessId);
        if (businessType == null || businessType.isBlank()) throw new IllegalArgumentException("Business type required");
        Connection connection = taskReadConnection(tenantId);
        try {
            return managementService.executeCommand(context -> {
                requireEngineConnection(context, connection);
                var binding = DSL.using(connection, SQLDialect.POSTGRES).fetchOne("""
                        select business_revision, definition_id, process_instance_id from public.workflow_binding
                         where tenant_id=cast(? as uuid) and business_type=? and business_id=cast(? as uuid)
                           and submission_no=1
                        """, tenantId, businessType, businessId);
                if (binding == null) return Optional.empty();
                String definitionId = binding.get("definition_id", String.class);
                String processId = binding.get("process_instance_id", String.class);
                if (!Integer.valueOf(1).equals(binding.get("business_revision", Integer.class))
                        || blank(definitionId) || blank(processId)) throw unavailable("Invalid workflow binding");
                ProcessDefinition definition = repositoryService.createProcessDefinitionQuery()
                        .processDefinitionId(definitionId).singleResult();
                if (definition == null || !definitionId.equals(definition.getId())
                        || !tenantId.toString().equals(definition.getTenantId()) || blank(definition.getKey())) {
                    throw unavailable("Bound definition does not match Flowable");
                }
                ProcessInstance instance = runtimeService.createProcessInstanceQuery().processInstanceId(processId).singleResult();
                if (instance != null && (!processId.equals(instance.getId())
                        || !tenantId.toString().equals(instance.getTenantId())
                        || !definitionId.equals(instance.getProcessDefinitionId())
                        || !(businessType + ":" + businessId).equals(instance.getBusinessKey()))) {
                    throw unavailable("Bound process does not match Flowable");
                }
                if (instance == null) {
                    var historic = historyService.createHistoricProcessInstanceQuery().processInstanceId(processId).singleResult();
                    if (historic == null || historic.getEndTime() == null || !processId.equals(historic.getId())
                            || !tenantId.toString().equals(historic.getTenantId())
                            || !definitionId.equals(historic.getProcessDefinitionId())
                            || !(businessType + ":" + businessId).equals(historic.getBusinessKey())) {
                        throw unavailable("Ended bound process does not match Flowable history");
                    }
                }
                return Optional.of(new WorkflowBindingReadSnapshot(tenantId, businessType, businessId, 1,
                        definitionId, definition.getKey(), processId));
            });
        } catch (FlowableException failure) { throw new WorkflowEngineUnavailableException(failure); }
    }

    @Override
    public Optional<String> processDefinitionKey(UUID tenantId, String businessType, UUID businessId) {
        bindingLock.acquire(tenantId, businessType, businessId);
        var binding = dsl.fetchOne("""
                select business_revision, definition_id
                  from public.workflow_binding
                 where tenant_id = cast(? as uuid) and business_type = ?
                   and business_id = cast(? as uuid) and submission_no = 1
                """, tenantId, businessType, businessId);
        if (binding == null || binding.get("business_revision", Integer.class) != 1) {
            return Optional.empty();
        }
        String definitionId = binding.get("definition_id", String.class);
        ProcessDefinition definition;
        try {
            definition = repositoryService.createProcessDefinitionQuery()
                    .processDefinitionId(definitionId)
                    .singleResult();
        } catch (FlowableException failure) {
            throw new WorkflowEngineUnavailableException(failure);
        }
        if (definition == null || !tenantId.toString().equals(definition.getTenantId())) {
            return Optional.empty();
        }
        return Optional.of(definition.getKey());
    }
}
