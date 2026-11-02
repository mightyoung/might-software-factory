package com.mightyoung.softwarefactory.authorization;

import com.mightyoung.softwarefactory.identity.CurrentTenantContext;
import com.mightyoung.softwarefactory.tenancy.TenantApplicationReadRepository;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** Resolves finding.read against current persisted identity, role, and organization state. */
@Component
public final class PersistentEntityReadAccessPolicy implements EntityReadAccessPolicy {

    private static final String FINDING_ENTITY_KEY = "finding";
    private static final String FINDING_READ_PERMISSION = "finding.read";

    private final PersistentEntityReadAuthorizationRepository repository;
    private final TenantApplicationReadRepository applications;

    public PersistentEntityReadAccessPolicy(PersistentEntityReadAuthorizationRepository repository,
            TenantApplicationReadRepository applications) {
        this.repository = repository;
        this.applications = applications;
    }

    @Override
    public Optional<EntityReadGrant> resolveEntityReadGrant(CurrentTenantContext context, String entityKey) {
        if (context == null || !FINDING_ENTITY_KEY.equals(entityKey)
                || context.actorId() == null || context.accountId() == null
                || context.tenantId() == null || context.memberId() == null) {
            return Optional.empty();
        }

        if (applications.findCurrent(context.tenantId(), "safety")
                .filter(com.mightyoung.softwarefactory.tenancy.TenantApplicationState::enabled).isEmpty()) {
            return Optional.empty();
        }

        FindingReadAuthorizationSnapshot snapshot = repository
                .loadFindingReadSnapshot(context, FINDING_READ_PERMISSION)
                .orElse(null);
        if (snapshot == null
                || !context.actorId().equals(snapshot.principalId())
                || !context.accountId().equals(snapshot.principalAccountId())
                || !"HUMAN".equals(snapshot.principalKind())
                || !"ACTIVE".equals(snapshot.principalStatus())
                || !"ACTIVE".equals(snapshot.accountStatus())
                || !"ACTIVE".equals(snapshot.tenantStatus())
                || !"ACTIVE".equals(snapshot.memberStatus())
                || snapshot.tenantAuthEpoch() != context.tenantAuthEpoch()
                || snapshot.memberAuthEpoch() != context.memberAuthEpoch()
                || snapshot.organizationIds() == null || snapshot.organizationIds().isEmpty()) {
            return Optional.empty();
        }

        return Optional.of(new EntityReadGrant(context.tenantId(), snapshot.organizationIds()));
    }
}
