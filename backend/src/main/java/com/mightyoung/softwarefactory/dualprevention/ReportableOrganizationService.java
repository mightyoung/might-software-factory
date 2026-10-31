package com.mightyoung.softwarefactory.dualprevention;

import com.mightyoung.softwarefactory.authorization.FindingReadAuthorizationSnapshot;
import com.mightyoung.softwarefactory.authorization.FindingWorkflowAuthorizationRepository;
import com.mightyoung.softwarefactory.identity.CurrentTenantContext;
import com.mightyoung.softwarefactory.identity.SessionContextResolver;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

/** Returns only the current member's active organization scope for the finding.create action. */
@Service
public final class ReportableOrganizationService {

    private static final String CREATE_PERMISSION = "finding.create";

    private final SessionContextResolver sessionContextResolver;
    private final FindingWorkflowAuthorizationRepository authorizationRepository;
    private final ReportableOrganizationReadRepository organizationRepository;

    public ReportableOrganizationService(
            SessionContextResolver sessionContextResolver,
            FindingWorkflowAuthorizationRepository authorizationRepository,
            ReportableOrganizationReadRepository organizationRepository) {
        this.sessionContextResolver = Objects.requireNonNull(sessionContextResolver, "sessionContextResolver");
        this.authorizationRepository = Objects.requireNonNull(authorizationRepository, "authorizationRepository");
        this.organizationRepository = Objects.requireNonNull(organizationRepository, "organizationRepository");
    }

    public List<ReportableOrganization> reportable() {
        CurrentTenantContext context = sessionContextResolver.currentTenant();
        FindingReadAuthorizationSnapshot snapshot = authorizationRepository
                .loadActionSnapshot(context, CREATE_PERMISSION, "create")
                .orElseThrow(() -> new AccessDeniedException("The current member cannot report findings"));
        if (!isCurrentHuman(context, snapshot)) {
            throw new AccessDeniedException("The current member cannot report findings");
        }
        return organizationRepository.findActive(context.tenantId(), snapshot.organizationIds());
    }

    private static boolean isCurrentHuman(
            CurrentTenantContext context, FindingReadAuthorizationSnapshot snapshot) {
        return snapshot.organizationIds() != null
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
