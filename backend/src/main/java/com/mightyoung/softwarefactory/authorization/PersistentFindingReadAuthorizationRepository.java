package com.mightyoung.softwarefactory.authorization;

import com.mightyoung.softwarefactory.directory.MemberDirectoryReadRepository;
import com.mightyoung.softwarefactory.directory.MemberDirectorySnapshot;
import com.mightyoung.softwarefactory.identity.AccountStatusReadRepository;
import com.mightyoung.softwarefactory.identity.AccountSecurityState;
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
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Composes the modules' public read queries into request authorization state. Standalone reads use
 * repeatable read; REQUIRED propagation joins an existing caller's isolation level. Authorization
 * source rows are not locked against concurrent revocation.
 */
@Repository
public final class PersistentFindingReadAuthorizationRepository
        implements PersistentEntityReadAuthorizationRepository {

    private final DSLContext dsl;
    private final AccountStatusReadRepository accountStatusRepository;
    private final PrincipalStatusReadRepository principalStatusRepository;
    private final TenantMembershipReadRepository tenantMembershipRepository;
    private final MemberDirectoryReadRepository directoryRepository;
    private final RoleScopeReadRepository roleScopeRepository;
    private final TransactionTemplate readTransaction;

    public PersistentFindingReadAuthorizationRepository(
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
    public Optional<FindingReadAuthorizationSnapshot> loadFindingReadSnapshot(
            CurrentTenantContext context, String permissionCode) {
        return readTransaction.execute(status -> {
            setLocalTenant(context.tenantId());

            PrincipalStatus principal = principalStatusRepository.findByActorId(context.actorId()).orElse(null);
            if (principal == null) {
                return Optional.empty();
            }
            AccountSecurityState account = accountStatusRepository.findState(context.accountId()).orElse(null);
            if (account == null || account.credentialVersion() != context.credentialVersion()) {
                return Optional.empty();
            }
            String accountStatus = account.status();
            TenantMembershipState membership = tenantMembershipRepository.findCurrentMembership(
                    context.tenantId(), context.accountId(), context.memberId()).orElse(null);
            if (membership == null) {
                return Optional.empty();
            }

            Set<UUID> organizationIds = Set.of();
            if (canResolveRoles(context, principal, accountStatus, membership)) {
                MemberDirectorySnapshot directory = directoryRepository.findCurrentMemberships(
                        context.tenantId(), context.memberId());
                var roleScopes = roleScopeRepository.findActiveScopes(
                        context.tenantId(), context.memberId(), directory.organizationIds(),
                        directory.positionIds(), directory.groupIds(), permissionCode);
                organizationIds = resolveOrganizationScope(context, directory, roleScopes);
            }

            return Optional.of(new FindingReadAuthorizationSnapshot(
                    principal.principalId(),
                    principal.accountId(),
                    principal.kind(),
                    principal.status(),
                    accountStatus,
                    membership.tenantStatus(),
                    membership.memberStatus(),
                    membership.tenantAuthEpoch(),
                    membership.memberAuthEpoch(),
                    organizationIds));
        });
    }

    private static boolean canResolveRoles(
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
            List<RoleScopeDescriptor> roleScopes) {
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
                    // SELF and RELATION have no finding organization predicate contract yet.
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
            throw new IllegalStateException("Unable to apply the authorization tenant context");
        }
    }
}
