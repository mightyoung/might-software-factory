package com.mightyoung.softwarefactory.directory;

import java.util.Set;
import java.util.UUID;

/** Public directory queries for effective memberships and active organization scope. */
public interface MemberDirectoryReadRepository {

    MemberDirectorySnapshot findCurrentMemberships(UUID tenantId, UUID memberId);

    Set<UUID> expandDepartmentTree(UUID tenantId, UUID memberId);

    Set<UUID> findActiveOrganizations(UUID tenantId, Set<UUID> organizationIds);
}
