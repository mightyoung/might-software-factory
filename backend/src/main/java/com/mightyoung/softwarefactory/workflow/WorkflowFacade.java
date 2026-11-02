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

    /** Returns the exact process definition key bound to this tenant-scoped business record. */
    Optional<String> processDefinitionKey(UUID tenantId, String businessType, UUID businessId);
}
