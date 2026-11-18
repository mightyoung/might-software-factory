package com.mightyoung.softwarefactory.authorization;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Authorization-owned role and permission query for one entity action. */
public interface RoleScopeReadRepository {

    /** Explicit temporal composition must never silently use the legacy transaction clock. */
    default List<RoleScopeDescriptor> findActiveActionScopesAt(
            UUID tenantId, UUID memberId, Set<UUID> organizationIds, Set<UUID> positionIds,
            Set<UUID> groupIds, String resourceType, String action, String permissionCode,
            OffsetDateTime evaluatedAt) {
        throw new UnsupportedOperationException("Explicit role evaluation time is not supported");
    }

    List<RoleScopeDescriptor> findActiveScopes(
            UUID tenantId,
            UUID memberId,
            Set<UUID> organizationIds,
            Set<UUID> positionIds,
            Set<UUID> groupIds,
            String permissionCode);

    /** Resolves a non-read action using the same active membership and scoped RBAC joins. */
    default List<RoleScopeDescriptor> findActiveActionScopes(
            UUID tenantId,
            UUID memberId,
            Set<UUID> organizationIds,
            Set<UUID> positionIds,
            Set<UUID> groupIds,
            String resourceType,
            String action,
            String permissionCode) {
        return findActiveScopes(tenantId, memberId, organizationIds, positionIds, groupIds, permissionCode);
    }
}
