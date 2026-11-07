package com.mightyoung.softwarefactory.workflow;

import java.util.UUID;

/** Point-in-time pinned task identity, not a transferable authorization decision.
 * A null or blank assignee explicitly represents unresolved assignment.
 */
public record WorkflowTaskReadSnapshot(
        UUID tenantId,
        String businessType,
        UUID businessId,
        int businessRevision,
        String definitionId,
        String processDefinitionKey,
        String processInstanceId,
        String taskDefinitionKey,
        String taskId,
        String assignee,
        boolean active) { }
