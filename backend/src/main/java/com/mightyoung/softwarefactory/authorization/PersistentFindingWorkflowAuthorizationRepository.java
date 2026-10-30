package com.mightyoung.softwarefactory.authorization;

import com.mightyoung.softwarefactory.directory.MemberDirectoryReadRepository;
import com.mightyoung.softwarefactory.directory.MemberDirectorySnapshot;
import com.mightyoung.softwarefactory.identity.AccountStatusReadRepository;
import com.mightyoung.softwarefactory.identity.CurrentTenantContext;
import com.mightyoung.softwarefactory.identity.PrincipalStatus;
import com.mightyoung.softwarefactory.identity.PrincipalStatusReadRepository;
import com.mightyoung.softwarefactory.tenancy.TenantMembershipReadRepository;
import com.mightyoung.softwarefactory.tenancy.TenantMembershipState;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Loads the identity and scoped role state used by workflow writes in one repeatable-read view. */
@Repository
public final class PersistentFindingWorkflowAuthorizationRepository
        implements FindingWorkflowAuthorizationRepository {

    private final DSLContext dsl;
    private final AccountStatusReadRepository accountStatusRepository;
    private final PrincipalStatusReadRepository principalStatusRepository;
    private final TenantMembershipReadRepository tenantMembershipRepository;
    private final MemberDirectoryReadRepository directoryRepository;
    private final RoleScopeReadRepository roleScopeRepository;
    private final TransactionTemplate readTransaction;

    public PersistentFindingWorkflowAuthorizationRepository(
            DSLContext dsl,
            AccountStatusReadRepository accountStatusRepository,
            PrincipalStatusReadRepository principalStatusRepository,
            TenantMembershipReadRepository tenantMembershipRepository,
            MemberDirectoryReadRepository directoryRepository,
            RoleScopeReadRepository roleScopeRepository,
            PlatformTransactionManager transactionManager) {
        this.dsl = dsl;
        this.accountStatusRepository = accountStatusRepository;
        this.principalStatusRepository = principalStatusRepository;
        this.tenantMembershipRepository = tenantMembershipRepository;
        this.directoryRepository = directoryRepository;
        this.roleScopeRepository = roleScopeRepository;
        this.readTransaction = new TransactionTemplate(transactionManager);
        this.readTransaction.setReadOnly(true);
        this.readTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    @Override
    public Optional<FindingReadAuthorizationSnapshot> loadActionSnapshot(
            CurrentTenantContext context, String permissionCode, String action) {
        return readTransaction.execute(status -> {
            setLocalTenant(context.tenantId());
            PrincipalStatus principal = principalStatusRepository.findByActorId(context.actorId()).orElse(null);
            if (principal == null) return Optional.empty();
            String accountStatus = accountStatusRepository.findStatus(context.accountId()).orElse(null);
            TenantMembershipState membership = tenantMembershipRepository.findCurrentMembership(
                    context.tenantId(), context.accountId(), context.memberId()).orElse(null);
            if (membership == null) return Optional.empty();

            Set<UUID> organizationIds = Set.of();
            if (isCurrentHuman(context, principal, accountStatus, membership)) {
                MemberDirectorySnapshot directory = directoryRepository.findCurrentMemberships(
                        context.tenantId(), context.memberId());
                var scopes = roleScopeRepository.findActiveActionScopes(context.tenantId(), context.memberId(),
                        directory.organizationIds(), directory.positionIds(), directory.groupIds(),
                        "finding", action, permissionCode);
                organizationIds = resolveOrganizationScope(context, directory, scopes);
            }

            return Optional.of(new FindingReadAuthorizationSnapshot(
                    principal.principalId(), principal.accountId(), principal.kind(), principal.status(),
                    accountStatus, membership.tenantStatus(), membership.memberStatus(),
                    membership.tenantAuthEpoch(), membership.memberAuthEpoch(), organizationIds));
        });
    }

    @Override
    public boolean isAssignableMemberAuthorizedForAction(
            UUID tenantId, UUID memberId, UUID organizationId, String permissionCode) {
        if (tenantId == null || memberId == null || organizationId == null
                || permissionCode == null || !permissionCode.startsWith("finding.")) {
            return false;
        }
        String action = permissionCode.substring("finding.".length());
        return Boolean.TRUE.equals(readTransaction.execute(status -> {
            setLocalTenant(tenantId);
            Boolean activeHuman = (Boolean) dsl.fetchValue("""
                    select exists (
                        select 1
                          from public.tenant_member m
                          join public.account a on a.id = m.account_id and a.status = 'ACTIVE'
                          join public.principal p on p.account_id = a.id
                               and p.kind = 'HUMAN' and p.status = 'ACTIVE'
                          join public.tenant t on t.id = m.tenant_id and t.status = 'ACTIVE'
                         where m.tenant_id = cast(? as uuid)
                           and m.id = cast(? as uuid)
                           and m.status = 'ACTIVE'
                    )
                    """, tenantId, memberId);
            if (!Boolean.TRUE.equals(activeHuman)) return false;

            MemberDirectorySnapshot directory = directoryRepository.findCurrentMemberships(tenantId, memberId);
            if (!directory.organizationIds().contains(organizationId)) return false;
            var scopes = roleScopeRepository.findActiveActionScopes(
                    tenantId, memberId, directory.organizationIds(), directory.positionIds(), directory.groupIds(),
                    "finding", action, permissionCode);
            Set<UUID> departmentTree = scopes.stream().anyMatch(scope -> "DEPT_TREE".equals(scope.kind()))
                    ? directoryRepository.expandDepartmentTree(tenantId, memberId)
                    : Set.of();
            return FindingWorkflowActionAssignmentPolicy.allows(
                    organizationId, directory.organizationIds(), departmentTree, scopes);
        }));
    }

    private static boolean isCurrentHuman(
            CurrentTenantContext context,
            PrincipalStatus principal,
            String accountStatus,
            TenantMembershipState membership) {
        return context.actorId().equals(principal.principalId())
                && context.accountId().equals(principal.accountId())
                && "HUMAN".equals(principal.kind())
                && "ACTIVE".equals(principal.status())
                && "ACTIVE".equals(accountStatus)
                && "ACTIVE".equals(membership.tenantStatus())
                && "ACTIVE".equals(membership.memberStatus())
                && membership.tenantAuthEpoch() == context.tenantAuthEpoch()
                && membership.memberAuthEpoch() == context.memberAuthEpoch();
    }

    private Set<UUID> resolveOrganizationScope(
            CurrentTenantContext context,
            MemberDirectorySnapshot directory,
            java.util.List<RoleScopeDescriptor> roleScopes) {
        Set<UUID> organizations = new LinkedHashSet<>();
        Set<UUID> explicitOrganizations = new LinkedHashSet<>();
        boolean includeDepartmentTree = false;
        for (RoleScopeDescriptor descriptor : roleScopes) {
            switch (descriptor.kind()) {
                case "DEPT" -> organizations.addAll(directory.organizationIds());
                case "DEPT_TREE" -> includeDepartmentTree = true;
                case "EXPLICIT" -> {
                    if (descriptor.explicitOrganizationId() != null) {
                        explicitOrganizations.add(descriptor.explicitOrganizationId());
                    }
                }
                default -> {
                    // SELF and RELATION have no finding organization-scope contract yet.
                }
            }
        }
        if (includeDepartmentTree) {
            organizations.addAll(directoryRepository.expandDepartmentTree(context.tenantId(), context.memberId()));
        }
        organizations.addAll(directoryRepository.findActiveOrganizations(context.tenantId(), explicitOrganizations));
        return Set.copyOf(organizations);
    }

    private void setLocalTenant(UUID tenantId) {
        String applied = (String) dsl.fetchValue("select set_config(?, ?, true)",
                "app.tenant_id", tenantId.toString());
        if (!tenantId.toString().equals(applied)) {
            throw new IllegalStateException("Unable to apply the workflow authorization tenant context");
        }
    }
}
