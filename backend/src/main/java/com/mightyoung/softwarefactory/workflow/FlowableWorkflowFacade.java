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
import java.util.UUID;

/** Tenant-checking adapter around the embedded Flowable engine and the shared PostgreSQL transaction. */
@Component
public final class FlowableWorkflowFacade implements WorkflowFacade {

    private final RuntimeService runtimeService;
    private final TaskService taskService;
    private final RepositoryService repositoryService;
    private final DSLContext dsl;
    private final WorkflowBindingTransactionLock bindingLock;
    private final DataSource dataSource;
    private final ManagementService managementService;
    private final AuthorizationWriteGuard authorizationGuard;

    public FlowableWorkflowFacade(RuntimeService runtimeService, TaskService taskService,
                                  RepositoryService repositoryService, DSLContext dsl,
                                  WorkflowBindingTransactionLock bindingLock, DataSource dataSource,
                                  ManagementService managementService, AuthorizationWriteGuard authorizationGuard) {
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
        Connection connection = taskReadConnection(tenantId);
        try {
            return managementService.executeCommand(context -> {
                Connection engineConnection = CommandContextUtil.getDbSqlSession(context)
                        .getSqlSession().getConnection();
                if (DataSourceUtils.getTargetConnection(engineConnection)
                        != DataSourceUtils.getTargetConnection(connection)) {
                    throw unavailable("Workflow reads require the application transaction connection");
                }
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
                return new WorkflowTaskReadSnapshot(tenantId, businessType, businessId, 1,
                        definitionId, definition.getKey(), processId, taskDefinitionKey,
                        task.getId(), task.getAssignee(), !instance.isSuspended() && !task.isSuspended());
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
