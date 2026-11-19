package com.mightyoung.softwarefactory.workflow;

import java.util.Objects;
import java.util.UUID;

/** Exact reviewed definition identity; never a latest-key selector. */
public record WorkflowDefinitionPin(UUID tenantId, String definitionId, String expectedKey,
                                    String resourceSha256) {
    public WorkflowDefinitionPin {
        Objects.requireNonNull(tenantId, "Tenant required");
        if (tenantId.equals(new UUID(0, 0)) || definitionId == null || definitionId.isBlank()
                || (expectedKey == null || !PackagedWorkflowDefinitions.RESOURCES.containsKey(expectedKey)) || resourceSha256 == null
                || !resourceSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Invalid exact workflow definition pin");
        }
    }
}
