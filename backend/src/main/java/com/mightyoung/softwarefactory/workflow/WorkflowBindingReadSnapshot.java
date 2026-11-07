package com.mightyoung.softwarefactory.workflow;

import java.util.UUID;

/** Verified internal binding identity. Engine identifiers never belong in the HTTP response. */
public record WorkflowBindingReadSnapshot(UUID tenantId, String businessType, UUID businessId,
        int businessRevision, String definitionId, String processDefinitionKey, String processInstanceId) { }
