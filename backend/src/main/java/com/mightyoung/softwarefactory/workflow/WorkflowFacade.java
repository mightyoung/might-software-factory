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

    void completeTask(
            UUID tenantId,
            String businessType,
            UUID businessId,
            String taskDefinitionKey,
            UUID actorMemberId,
            Map<String, Object> variables);

    /** Reads the pinned active task in the caller's managed tenant transaction, without a binding lock.
     * Currently bounded to the existing revision-1, submission-1 binding contract.
     * Missing binding/task is a conflict; unresolved assignment is returned as a null/blank assignee.
     */
    WorkflowTaskReadSnapshot readCurrentTask(
            UUID tenantId, String businessType, UUID businessId, String taskDefinitionKey);

    /** Returns the exact process definition key bound to this tenant-scoped business record. */
    Optional<String> processDefinitionKey(UUID tenantId, String businessType, UUID businessId);
}
