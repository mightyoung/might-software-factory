package com.mightyoung.softwarefactory.directory;

import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;

/** Public directory queries for effective memberships and active organization scope. */
public interface MemberDirectoryReadRepository {

    MemberDirectorySnapshot findCurrentMemberships(UUID tenantId, UUID memberId);

    /** Uses the caller's single database-observed evaluation instant; no legacy-clock fallback. */
    default MemberDirectorySnapshot findCurrentMembershipsAt(
            UUID tenantId, UUID memberId, OffsetDateTime evaluatedAt) {
        throw new UnsupportedOperationException("Explicit directory evaluation time is not supported");
    }

    Set<UUID> expandDepartmentTree(UUID tenantId, UUID memberId);

    /** Uses the same instant as the membership and role evaluation. */
    default Set<UUID> expandDepartmentTreeAt(UUID tenantId, UUID memberId, OffsetDateTime evaluatedAt) {
        throw new UnsupportedOperationException("Explicit directory evaluation time is not supported");
    }

    Set<UUID> findActiveOrganizations(UUID tenantId, Set<UUID> organizationIds);
    /** Fixed-shape current organization lookup for an already owned caller transaction. */
    default Set<UUID> findActiveOrganizationsInCaller(UUID tenantId, Set<UUID> organizationIds) {
        throw new UnsupportedOperationException("Caller-owned organization lookup is required");
    }
}
