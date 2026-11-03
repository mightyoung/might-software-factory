package com.mightyoung.softwarefactory.workflow;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Tenant-safe public boundary for the embedded BPMN engine. Business modules never call Flowable
 * services directly and must pair process changes with their projection changes in one transaction.
 */
public interface WorkflowFacade {

    void startProcess(
            UUID tenantId,
            String businessType,
            UUID businessId,
            int businessRevision,
            String processDefinitionKey,
            Map<String, Object> variables);

    /** Revalidates the exact reviewed definition and starts by ID in the managed command transaction. */
    void startProcessByDefinitionId(UUID tenantId, String businessType, UUID businessId,
            int businessRevision, WorkflowDefinitionPin definition, Map<String, Object> variables);

    /** Requires an existing security guard; locks the binding and validates the assigned active task. */
    WorkflowCommandTaskAuthority acquireTaskAuthority(UUID tenantId, String businessType,
            UUID businessId, String taskDefinitionKey, UUID actorMemberId);

    /** Revalidates the acquired identity in the same physical transaction before completion. */
    void completeTask(WorkflowCommandTaskAuthority authority, Map<String, Object> variables);

    void completeTask(
            UUID tenantId,
            String businessType,
            UUID businessId,
            String taskDefinitionKey,
            UUID actorMemberId,
            Map<String, Object> variables);

    /** Validates server-stored participant proof for replay, including an original task if still live.
     * The caller remains responsible for current action eligibility and the immutable receipt proof.
     */
    void validateReplayParticipant(UUID tenantId, String businessType, UUID businessId,
            WorkflowTaskReadSnapshot original, UUID actorMemberId);

    /** Reads the pinned active task in the caller's managed tenant transaction, without a binding lock.
     * Currently bounded to the existing revision-1, submission-1 binding contract.
     * Missing binding/task is a conflict; unresolved assignment is returned as a null/blank assignee.
     */
    WorkflowTaskReadSnapshot readCurrentTask(
            UUID tenantId, String businessType, UUID businessId, String taskDefinitionKey);

    /** Nonmutating exact binding read; absence is valid for an uninitialized record. */
    Optional<WorkflowBindingReadSnapshot> readBoundDefinition(UUID tenantId, String businessType, UUID businessId);

    /** Returns the exact process definition key bound to this tenant-scoped business record. */
    Optional<String> processDefinitionKey(UUID tenantId, String businessType, UUID businessId);
}
