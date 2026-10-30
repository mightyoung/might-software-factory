package com.mightyoung.softwarefactory.authorization;

import java.util.Set;
import java.util.UUID;

/**
 * Server-resolved row scope for one entity read action. Empty scopes mean no readable rows and are
 * denied by consumers; there is no wildcard or request-controlled tenant selector.
 */
public record EntityReadGrant(UUID tenantId, Set<UUID> organizationIds) {

    public EntityReadGrant {
        if (tenantId == null || organizationIds == null) {
            throw new IllegalArgumentException("Tenant and organization scope are required");
        }
        organizationIds = Set.copyOf(organizationIds);
    }
}
