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

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Loads identity and role state; standalone reads use RR, while REQUIRED joins a caller's transaction. */
@Repository
public final class PersistentFindingWorkflowAuthorizationRepository
        implements FindingWorkflowAuthorizationRepository {

    // Both the bounded search and the authoritative write check use this complete predicate.
    // Direct target membership makes DEPT and DEPT_TREE sufficient; other grant subjects may
    // come from any effective active membership, independently of the target organization.
    private static final String ASSIGNABLE_MEMBER_SQL = """
              from public.tenant_member m
              join public.account a on a.id = m.account_id and a.status = 'ACTIVE'
              join public.tenant t on t.id = m.tenant_id and t.status = 'ACTIVE'
             where m.tenant_id = cast(? as uuid)
               and m.status = 'ACTIVE'
               and exists (
                   select 1 from public.principal p
                    where p.account_id = a.id and p.kind = 'HUMAN' and p.status = 'ACTIVE'
               )
               and exists (
                   select 1 from public.member_org target_membership
                   join public.org_unit target_org
                     on target_org.tenant_id = target_membership.tenant_id
                    and target_org.id = target_membership.org_id and target_org.active = true
                    where target_membership.tenant_id = m.tenant_id
                      and target_membership.member_id = m.id
                      and target_membership.org_id = cast(? as uuid)
                      and target_membership.valid_from <= current_timestamp
                      and (target_membership.valid_to is null
                           or target_membership.valid_to > current_timestamp)
                      and exists (
                          select 1 from public.role_grant rg
                          join public."role" r
                            on r.tenant_id = rg.tenant_id and r.id = rg.role_id and r.active = true
                          join public.role_permission rp
                            on rp.tenant_id = rg.tenant_id and rp.role_id = rg.role_id
                          join public.permission permission
                            on permission.id = rp.permission_id and permission.code = ?
                           and permission.resource_type = 'finding' and permission.action = ?
                          join public.scope_rule sr
                            on sr.tenant_id = rp.tenant_id and sr.id = rp.scope_rule_id
                         where rg.tenant_id = m.tenant_id
                           and rg.valid_from <= current_timestamp
                           and (rg.valid_to is null or rg.valid_to > current_timestamp)
                           and (
                               (rg.subject_kind = 'MEMBER' and rg.member_id = m.id)
                               or (rg.subject_kind = 'ORG' and exists (
                                   select 1 from public.member_org mo
                                   join public.org_unit ou
                                     on ou.tenant_id = mo.tenant_id and ou.id = mo.org_id
                                    and ou.active = true
                                    where mo.tenant_id = m.tenant_id and mo.member_id = m.id
                                      and mo.org_id = rg.org_id and mo.valid_from <= current_timestamp
                                      and (mo.valid_to is null or mo.valid_to > current_timestamp)
                               ))
                               or (rg.subject_kind = 'POSITION' and exists (
                                   select 1 from public.member_position mp
                                   join public."position" pos
                                     on pos.tenant_id = mp.tenant_id and pos.id = mp.position_id
                                    and pos.active = true
                                    where mp.tenant_id = m.tenant_id and mp.member_id = m.id
                                      and mp.position_id = rg.position_id
                                      and mp.valid_from <= current_timestamp
                                      and (mp.valid_to is null or mp.valid_to > current_timestamp)
                               ))
                               or (rg.subject_kind = 'GROUP' and exists (
                                   select 1 from public.group_member gm
                                   join public.user_group g
                                     on g.tenant_id = gm.tenant_id and g.id = gm.group_id
                                    and g.active = true
                                    where gm.tenant_id = m.tenant_id and gm.member_id = m.id
                                      and gm.group_id = rg.group_id and gm.valid_from <= current_timestamp
                                      and (gm.valid_to is null or gm.valid_to > current_timestamp)
                               ))
                           )
                           and (sr.kind in ('DEPT', 'DEPT_TREE')
                                or (sr.kind = 'EXPLICIT' and exists (
                                    select 1 from public.scope_org so
                                     where so.tenant_id = sr.tenant_id and so.scope_rule_id = sr.id
                                       and so.org_id = target_membership.org_id
                                )))
                      )
               )
            """;

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
            AccountSecurityState account = accountStatusRepository.findState(context.accountId()).orElse(null);
            if (account == null || account.credentialVersion() != context.credentialVersion()) {
                return Optional.empty();
            }
            String accountStatus = account.status();
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
    public List<AssignableFindingMember> findAssignableMembers(
            UUID tenantId, UUID organizationId, String permissionCode,
            String namePrefix, UUID afterMemberId, int limit) {
        if (limit < 1 || limit > 51) {
            throw new IllegalArgumentException("Assignment search limit must be between 1 and 51");
        }
        if (namePrefix != null && namePrefix.codePointCount(0, namePrefix.length()) > 100) {
            throw new IllegalArgumentException("Assignment name prefix must not exceed 100 code points");
        }
        if (tenantId == null || organizationId == null || !isFindingPermission(permissionCode)) {
            return List.of();
        }
        return readTransaction.execute(status -> {
            setLocalTenant(tenantId);
            StringBuilder sql = new StringBuilder("select m.id, m.display_name\n")
                    .append(ASSIGNABLE_MEMBER_SQL);
            List<Object> bindings = new ArrayList<>(List.of(
                    tenantId, organizationId, permissionCode, permissionCode.substring("finding.".length())));
            if (namePrefix != null && !namePrefix.isEmpty()) {
                sql.append(" and m.display_name like ? escape '\\'\n");
                bindings.add(namePrefix.replace("\\", "\\\\")
                        .replace("%", "\\%").replace("_", "\\_") + "%");
            }
            if (afterMemberId != null) {
                sql.append(" and m.id > cast(? as uuid)\n");
                bindings.add(afterMemberId);
            }
            sql.append(" order by m.id asc limit ?");
            bindings.add(limit);
            return List.copyOf(dsl.fetch(sql.toString(), bindings.toArray()).map(row ->
                    new AssignableFindingMember(row.get(0, UUID.class), row.get(1, String.class))));
        });
    }

    @Override
    public boolean isAssignableMemberAuthorizedForAction(
            UUID tenantId, UUID memberId, UUID organizationId, String permissionCode) {
        if (tenantId == null || memberId == null || organizationId == null
                || !isFindingPermission(permissionCode)) {
            return false;
        }
        return Boolean.TRUE.equals(readTransaction.execute(status -> {
            setLocalTenant(tenantId);
            return (Boolean) dsl.fetchValue("select exists (select 1\n" + ASSIGNABLE_MEMBER_SQL
                            + " and m.id = cast(? as uuid))",
                    tenantId, organizationId, permissionCode,
                    permissionCode.substring("finding.".length()), memberId);
        }));
    }

    private static boolean isFindingPermission(String permissionCode) {
        return permissionCode != null && permissionCode.startsWith("finding.")
                && permissionCode.length() > "finding.".length();
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
