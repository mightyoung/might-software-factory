package com.mightyoung.softwarefactory.dualprevention.finding.workflow;

import com.mightyoung.softwarefactory.authorization.FindingReadAuthorizationSnapshot;
import com.mightyoung.softwarefactory.authorization.FindingWorkflowAuthorizationRepository;
import com.mightyoung.softwarefactory.identity.CurrentTenantContext;
import org.jooq.DSLContext;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** Rechecks principal, tenant/member epochs, action permission, and organization scope on every command. */
@Component
public final class PersistentFindingWorkflowAccessPolicy implements FindingWorkflowAccessPolicy {

    private final FindingWorkflowAuthorizationRepository authorizationRepository;
    private final DSLContext dsl;

    public PersistentFindingWorkflowAccessPolicy(
            FindingWorkflowAuthorizationRepository authorizationRepository,
            DSLContext dsl) {
        this.authorizationRepository = authorizationRepository;
        this.dsl = dsl;
    }

    @Override
    public void requireAction(CurrentTenantContext context, String permissionCode, UUID organizationId) {
        String prefix = "finding.";
        if (context == null || permissionCode == null || !permissionCode.startsWith(prefix)
                || permissionCode.length() == prefix.length() || organizationId == null) {
            throw new AccessDeniedException("A verified finding action context is required");
        }
        String action = permissionCode.substring(prefix.length());
        FindingReadAuthorizationSnapshot snapshot = authorizationRepository
                .loadActionSnapshot(context, permissionCode, action).orElse(null);
        if (!validCurrentHuman(context, snapshot) || snapshot.organizationIds().isEmpty()) {
            throw new AccessDeniedException("The current member is not authorized for this finding action");
        }
        if (!snapshot.organizationIds().contains(organizationId)) {
            throw new com.mightyoung.softwarefactory.dualprevention.FindingNotVisibleException();
        }
    }

    @Override
    public void requireAssignableMember(CurrentTenantContext context, UUID memberId, UUID organizationId) {
        if (context == null || memberId == null || organizationId == null) {
            throw new AccessDeniedException("An active remediation member is required");
        }
        String status = (String) dsl.fetchValue("""
                select status from public.tenant_member
                 where tenant_id = cast(? as uuid) and id = cast(? as uuid)
                """, context.tenantId(), memberId);
        if (!"ACTIVE".equals(status)) {
            throw new FindingWorkflowConfigurationException(
                    "A configured workflow member is missing or inactive");
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
