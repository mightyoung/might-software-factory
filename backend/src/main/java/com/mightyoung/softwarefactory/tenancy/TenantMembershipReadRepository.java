package com.mightyoung.softwarefactory.tenancy;

import java.util.Optional;
import java.util.UUID;

/** Public tenancy query that verifies the session account/member/tenant binding. */
public interface TenantMembershipReadRepository {

    Optional<TenantMembershipState> findCurrentMembership(UUID tenantId, UUID accountId, UUID memberId);
}
