package com.mightyoung.softwarefactory.workflow;

import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.common.engine.api.FlowableException;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.task.api.Task;
import org.jooq.DSLContext;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/** Tenant-checking adapter around the embedded Flowable engine and the shared PostgreSQL transaction. */
@Component
public final class FlowableWorkflowFacade implements WorkflowFacade {

    private final RuntimeService runtimeService;
    private final TaskService taskService;
    private final DSLContext dsl;

    public FlowableWorkflowFacade(RuntimeService runtimeService, TaskService taskService, DSLContext dsl) {
        this.runtimeService = runtimeService;
        this.taskService = taskService;
        this.dsl = dsl;
    }

    @Override
    public void startProcess(
            UUID tenantId,
            String businessType,
            UUID businessId,
            int businessRevision,
            String processDefinitionKey,
            Map<String, Object> variables) {
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
        var binding = dsl.fetchOne("""
                select business_revision, definition_id, process_instance_id
                  from public.workflow_binding
                 where tenant_id = cast(? as uuid) and business_type = ?
                   and business_id = cast(? as uuid) and submission_no = 1
                 for update
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
}
