package com.mightyoung.softwarefactory.authorization;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Authorization-owned role and permission query for one entity action. */
public interface RoleScopeReadRepository {

    List<RoleScopeDescriptor> findActiveScopes(
            UUID tenantId,
            UUID memberId,
            Set<UUID> organizationIds,
            Set<UUID> positionIds,
            Set<UUID> groupIds,
            String permissionCode);
}
