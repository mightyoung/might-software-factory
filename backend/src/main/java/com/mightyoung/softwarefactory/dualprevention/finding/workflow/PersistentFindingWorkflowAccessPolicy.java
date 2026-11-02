package com.mightyoung.softwarefactory.dualprevention.finding.workflow;

import com.mightyoung.softwarefactory.authorization.FindingReadAuthorizationSnapshot;
import com.mightyoung.softwarefactory.authorization.FindingWorkflowAuthorizationRepository;
import com.mightyoung.softwarefactory.identity.CurrentTenantContext;
import com.mightyoung.softwarefactory.tenancy.TenantApplicationReadRepository;
import com.mightyoung.softwarefactory.tenancy.TenantApplicationState;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** Rechecks principal, tenant/member epochs, action permission, and organization scope on every command. */
@Component
public final class PersistentFindingWorkflowAccessPolicy implements FindingWorkflowAccessPolicy {

    private final FindingWorkflowAuthorizationRepository authorizationRepository;
    private final TenantApplicationReadRepository applications;

    public PersistentFindingWorkflowAccessPolicy(FindingWorkflowAuthorizationRepository authorizationRepository,
            TenantApplicationReadRepository applications) {
        this.authorizationRepository = authorizationRepository;
        this.applications = applications;
    }

    @Override
    public void requireAction(CurrentTenantContext context, String permissionCode, UUID organizationId) {
        String prefix = "finding.";
        if (context == null || permissionCode == null || !permissionCode.startsWith(prefix)
                || permissionCode.length() == prefix.length() || organizationId == null) {
            throw new AccessDeniedException("A verified finding action context is required");
        }
        requireApplication(context);
        String action = permissionCode.substring(prefix.length());
        FindingReadAuthorizationSnapshot snapshot = authorizationRepository
                .loadActionSnapshot(context, permissionCode, action).orElse(null);
        if (!validCurrentHuman(context, snapshot) || snapshot.organizationIds() == null || snapshot.organizationIds().isEmpty()) {
            throw new AccessDeniedException("The current member is not authorized for this finding action");
        }
        if (!snapshot.organizationIds().contains(organizationId)) {
            throw new com.mightyoung.softwarefactory.dualprevention.FindingNotVisibleException();
        }
    }

    @Override
    public void requireAssignableMember(
            CurrentTenantContext context, UUID memberId, UUID organizationId, String permissionCode) {
        if (context == null || memberId == null || organizationId == null || permissionCode == null) {
            throw new AccessDeniedException("An active, organization-scoped workflow member is required");
        }
        requireApplication(context);
        if (!authorizationRepository.isAssignableMemberAuthorizedForAction(
                context.tenantId(), memberId, organizationId, permissionCode)) {
            throw new AccessDeniedException(
                    "The workflow member is inactive or lacks the required action scope in this organization");
        }
    }

    private void requireApplication(CurrentTenantContext context) {
        if (applications.findCurrent(context.tenantId(), "safety").filter(TenantApplicationState::enabled).isEmpty()) {
            throw new AccessDeniedException("The tenant application is unavailable");
        }
    }

    private static boolean validCurrentHuman(
            CurrentTenantContext context, FindingReadAuthorizationSnapshot snapshot) {
        return snapshot != null
                && context.actorId().equals(snapshot.principalId())
                && context.accountId().equals(snapshot.principalAccountId())
                && "HUMAN".equals(snapshot.principalKind())
                && "ACTIVE".equals(snapshot.principalStatus())
                && "ACTIVE".equals(snapshot.accountStatus())
                && "ACTIVE".equals(snapshot.tenantStatus())
                && "ACTIVE".equals(snapshot.memberStatus())
                && context.tenantAuthEpoch() == snapshot.tenantAuthEpoch()
                && context.memberAuthEpoch() == snapshot.memberAuthEpoch();
    }
}
