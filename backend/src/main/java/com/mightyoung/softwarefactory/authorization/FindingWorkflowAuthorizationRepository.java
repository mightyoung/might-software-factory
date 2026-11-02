package com.mightyoung.softwarefactory.authorization;

import com.mightyoung.softwarefactory.identity.CurrentTenantContext;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Resolves current persisted identity and the effective organization scope for one finding action. */
public interface FindingWorkflowAuthorizationRepository {

    Optional<FindingReadAuthorizationSnapshot> loadActionSnapshot(
            CurrentTenantContext context, String permissionCode, String action);

    List<AssignableFindingMember> findAssignableMembers(
            UUID tenantId, UUID organizationId, String permissionCode,
            String namePrefix, UUID afterMemberId, int limit);

    boolean isAssignableMemberAuthorizedForAction(
            UUID tenantId, UUID memberId, UUID organizationId, String permissionCode);
}
