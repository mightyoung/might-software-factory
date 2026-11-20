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
import org.jooq.Field;
import org.jooq.Record1;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.tools.jdbc.MockConnection;
import org.jooq.tools.jdbc.MockDataProvider;
import org.jooq.tools.jdbc.MockResult;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import java.util.List;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PersistentFindingReadAuthorizationRepositoryTest {

    private static final UUID ACTOR_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID ACCOUNT_ID = UUID.fromString("11000000-0000-0000-0000-000000000001");
    private static final UUID TENANT_ID = UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final UUID MEMBER_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID ORG_ID = UUID.fromString("31000000-0000-0000-0000-000000000001");
    private static final UUID CHILD_ORG_ID = UUID.fromString("31000000-0000-0000-0000-000000000002");
    private static final UUID EXPLICIT_ORG_ID = UUID.fromString("31000000-0000-0000-0000-000000000003");
    private static final UUID POSITION_ID = UUID.fromString("32000000-0000-0000-0000-000000000001");
    private static final UUID GROUP_ID = UUID.fromString("33000000-0000-0000-0000-000000000001");
    private static final UUID DEPT_SCOPE_ID = UUID.fromString("34000000-0000-0000-0000-000000000001");
    private static final UUID TREE_SCOPE_ID = UUID.fromString("34000000-0000-0000-0000-000000000002");
    private static final UUID EXPLICIT_SCOPE_ID = UUID.fromString("34000000-0000-0000-0000-000000000003");
    private static final UUID SELF_SCOPE_ID = UUID.fromString("34000000-0000-0000-0000-000000000004");
    private static final UUID RELATION_SCOPE_ID = UUID.fromString("34000000-0000-0000-0000-000000000005");

    @Test
    void composesPublicModuleReadsAndUnionsSupportedScopesInOneTenantTransaction() {
        DSLContext values = DSL.using(SQLDialect.POSTGRES);
        List<List<Object>> settings = new ArrayList<>();
        MockDataProvider provider = context -> {
            settings.add(Arrays.asList(context.bindings()));
            return new MockResult[] {singleValue(values, "set_config", String.class, TENANT_ID.toString())};
        };
        DSLContext dsl = DSL.using(new MockConnection(provider), SQLDialect.POSTGRES);
        CapturingTransactionManager transactionManager = new CapturingTransactionManager();
        MemberDirectorySnapshot directorySnapshot = new MemberDirectorySnapshot(
                Set.of(ORG_ID), Set.of(POSITION_ID), Set.of(GROUP_ID));
        MemberDirectoryReadRepository directory = new MemberDirectoryReadRepository() {
            @Override
            public MemberDirectorySnapshot findCurrentMemberships(UUID tenantId, UUID memberId) {
                assertThat(tenantId).isEqualTo(TENANT_ID);
                assertThat(memberId).isEqualTo(MEMBER_ID);
                return directorySnapshot;
            }

            @Override
            public Set<UUID> expandDepartmentTree(UUID tenantId, UUID memberId) {
                assertThat(tenantId).isEqualTo(TENANT_ID);
                assertThat(memberId).isEqualTo(MEMBER_ID);
                return Set.of(ORG_ID, CHILD_ORG_ID);
            }

            @Override
            public Set<UUID> findActiveOrganizations(UUID tenantId, Set<UUID> organizationIds) {
                assertThat(tenantId).isEqualTo(TENANT_ID);
                assertThat(organizationIds).containsExactly(EXPLICIT_ORG_ID);
                return Set.of(EXPLICIT_ORG_ID);
            }
        };
        RoleScopeReadRepository roleScopes = (tenantId, memberId, organizations, positions, groups, permission) -> {
            assertThat(tenantId).isEqualTo(TENANT_ID);
            assertThat(memberId).isEqualTo(MEMBER_ID);
            assertThat(organizations).containsExactly(ORG_ID);
            assertThat(positions).containsExactly(POSITION_ID);
            assertThat(groups).containsExactly(GROUP_ID);
            assertThat(permission).isEqualTo("finding.read");
            return List.of(
                    new RoleScopeDescriptor(DEPT_SCOPE_ID, "DEPT", null),
                    new RoleScopeDescriptor(TREE_SCOPE_ID, "DEPT_TREE", null),
                    new RoleScopeDescriptor(EXPLICIT_SCOPE_ID, "EXPLICIT", EXPLICIT_ORG_ID),
                    new RoleScopeDescriptor(SELF_SCOPE_ID, "SELF", null),
                    new RoleScopeDescriptor(RELATION_SCOPE_ID, "RELATION", null));
        };

        PersistentFindingReadAuthorizationRepository repository = repository(
                dsl,
                actorId -> {
                    assertThat(actorId).isEqualTo(ACTOR_ID);
                    return Optional.of(activeHumanPrincipal());
                },
                accountId -> {
                    assertThat(accountId).isEqualTo(ACCOUNT_ID);
                    return Optional.of(new AccountSecurityState("ACTIVE", 1L));
                },
                (tenantId, accountId, memberId) -> Optional.of(
                        new TenantMembershipState("ACTIVE", "ACTIVE", 17L, 23L)),
                directory,
                roleScopes,
                transactionManager);

        Optional<FindingReadAuthorizationSnapshot> snapshot = repository.loadFindingReadSnapshot(
                new CurrentTenantContext(ACTOR_ID, ACCOUNT_ID, TENANT_ID, MEMBER_ID, 17L, 23L, 1L), "finding.read");

        assertThat(snapshot).contains(new FindingReadAuthorizationSnapshot(
                ACTOR_ID, ACCOUNT_ID, "HUMAN", "ACTIVE",
                "ACTIVE", "ACTIVE", "ACTIVE", 17L, 23L,
                Set.of(ORG_ID, CHILD_ORG_ID, EXPLICIT_ORG_ID)));
        assertThat(settings).containsExactly(List.of("app.tenant_id", TENANT_ID.toString()));
        assertThat(transactionManager.readOnly).isTrue();
        assertThat(transactionManager.isolationLevel).isEqualTo(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    @Test
    void staleSessionEpochSkipsDirectoryAndRoleResolution() {
        DSLContext values = DSL.using(SQLDialect.POSTGRES);
        MockDataProvider provider = context -> new MockResult[] {
                singleValue(values, "set_config", String.class, TENANT_ID.toString())
        };
        DSLContext dsl = DSL.using(new MockConnection(provider), SQLDialect.POSTGRES);
        AtomicInteger directoryCalls = new AtomicInteger();
        AtomicInteger roleCalls = new AtomicInteger();
        MemberDirectoryReadRepository directory = new MemberDirectoryReadRepository() {
            @Override
            public MemberDirectorySnapshot findCurrentMemberships(UUID tenantId, UUID memberId) {
                directoryCalls.incrementAndGet();
                return new MemberDirectorySnapshot(Set.of(), Set.of(), Set.of());
            }

            @Override
            public Set<UUID> expandDepartmentTree(UUID tenantId, UUID memberId) {
                return Set.of();
            }

            @Override
            public Set<UUID> findActiveOrganizations(UUID tenantId, Set<UUID> organizationIds) {
                return Set.of();
            }
        };

        PersistentFindingReadAuthorizationRepository repository = repository(
                dsl,
                ignored -> Optional.of(activeHumanPrincipal()),
                ignored -> Optional.of(new AccountSecurityState("ACTIVE", 1L)),
                (tenantId, accountId, memberId) -> Optional.of(
                        new TenantMembershipState("ACTIVE", "ACTIVE", 18L, 23L)),
                directory,
                (tenantId, memberId, organizations, positions, groups, permission) -> {
                    roleCalls.incrementAndGet();
                    return List.of();
                },
                new CapturingTransactionManager());

        var snapshot = repository.loadFindingReadSnapshot(
                new CurrentTenantContext(ACTOR_ID, ACCOUNT_ID, TENANT_ID, MEMBER_ID, 17L, 23L, 1L), "finding.read");

        assertThat(snapshot).isPresent();
        assertThat(snapshot.orElseThrow().organizationIds()).isEmpty();
        assertThat(directoryCalls.get()).isZero();
        assertThat(roleCalls.get()).isZero();
    }

    @Test
    void ordinaryQueryAllowsOnlyExplicitSupportedEmptyScopesWithoutChangingScopedHas() {
        var dept = new RoleScopeDescriptor(DEPT_SCOPE_ID, "DEPT", null);
        var tree = new RoleScopeDescriptor(TREE_SCOPE_ID, "DEPT_TREE", null);
        var self = new RoleScopeDescriptor(SELF_SCOPE_ID, "SELF", null);
        var relation = new RoleScopeDescriptor(RELATION_SCOPE_ID, "RELATION", null);
        var explicit = new RoleScopeDescriptor(EXPLICIT_SCOPE_ID, "EXPLICIT", EXPLICIT_ORG_ID);
        var malformedDept = new RoleScopeDescriptor(null, "DEPT", null);
        var unexpected = new RoleScopeDescriptor(UUID.randomUUID(), "FUTURE_MODE", null);
        var context = currentContext();
        for (var supported : List.of(List.of(dept), List.of(tree), List.of(dept, tree))) {
            var repo = ordinaryRepository(Set.of(), supported, "ACTIVE", 17L, 1L);
            assertThat(repo.queryOrganizations(context, "purchase")).isPresent().contains(Set.of());
            assertThat(new PersistentEffectivePermissions(repo).has(context, "ledger.record.purchase.read")).isFalse();
        }
        for (var denied : List.of(List.<RoleScopeDescriptor>of(), List.of(self), List.of(relation),
                List.of(dept, self), List.of(dept, explicit), List.of(malformedDept), List.of(dept, unexpected))) {
            assertThat(ordinaryRepository(Set.of(), denied, "ACTIVE", 17L, 1L)
                    .queryOrganizations(context, "purchase")).isEmpty();
        }
        var previous = ordinaryRepository(Set.of(ORG_ID), List.of(dept, self), "ACTIVE", 17L, 1L);
        assertThat(previous.queryOrganizations(context, "purchase")).contains(Set.of(ORG_ID));
        assertThat(new PersistentEffectivePermissions(previous).has(context, "ledger.record.purchase.read")).isTrue();
    }

    @Test
    void ordinaryQueryDoesNotAcceptStaleInactiveOrUnavailableAuthority() {
        var context = currentContext();
        // A null fixture grant response throws if role resolution is ever attempted.
        assertThat(ordinaryRepository(Set.of(), null, "INACTIVE", 17L, 1L)
                .queryOrganizations(context, "purchase")).isEmpty();
        assertThat(ordinaryRepository(Set.of(), null, "ACTIVE", 18L, 1L)
                .queryOrganizations(context, "purchase")).isEmpty();
        assertThat(ordinaryRepository(Set.of(), null, "ACTIVE", 17L, 2L)
                .queryOrganizations(context, "purchase")).isEmpty();
        assertThatThrownBy(() -> ordinaryRepository(Set.of(), null, "ACTIVE", 17L, 1L)
                .queryOrganizations(context, "purchase")).isInstanceOf(IllegalStateException.class);
    }

    private static CurrentTenantContext currentContext() {
        return new CurrentTenantContext(ACTOR_ID, ACCOUNT_ID, TENANT_ID, MEMBER_ID, 17L, 23L, 1L);
    }

    private static PersistentFindingReadAuthorizationRepository ordinaryRepository(
            Set<UUID> organizations, List<RoleScopeDescriptor> scopes, String memberStatus, long epoch, long credential) {
        DSLContext values = DSL.using(SQLDialect.POSTGRES);
        DSLContext dsl = DSL.using(new MockConnection(context -> {
            assertThat(context.bindings()).containsExactly("app.tenant_id", TENANT_ID.toString());
            return new MockResult[] {singleValue(values, "set_config", String.class, TENANT_ID.toString())};
        }), SQLDialect.POSTGRES);
        var directory = mock(MemberDirectoryReadRepository.class);
        when(directory.findCurrentMemberships(TENANT_ID, MEMBER_ID))
                .thenReturn(new MemberDirectorySnapshot(organizations, Set.of(), Set.of()));
        when(directory.expandDepartmentTree(TENANT_ID, MEMBER_ID)).thenReturn(Set.of());
        return repository(dsl, ignored -> Optional.of(activeHumanPrincipal()),
                ignored -> Optional.of(new AccountSecurityState("ACTIVE", credential)),
                (tenant, account, member) -> Optional.of(new TenantMembershipState("ACTIVE", memberStatus, epoch, 23L)),
                directory, (tenant, member, orgs, positions, groups, permission) -> {
                    assertThat(permission).isEqualTo("ledger.record.purchase.read");
                    assertThat(tenant).isEqualTo(TENANT_ID);
                    assertThat(member).isEqualTo(MEMBER_ID);
                    if (scopes == null) throw new IllegalStateException("Unavailable fixture role source");
                    return scopes;
                }, new CapturingTransactionManager());
    }

    private static PersistentFindingReadAuthorizationRepository repository(
            DSLContext dsl,
            PrincipalStatusReadRepository principalStatus,
            AccountStatusReadRepository accountStatus,
            TenantMembershipReadRepository tenancy,
            MemberDirectoryReadRepository directory,
            RoleScopeReadRepository roleScopes,
            CapturingTransactionManager transactionManager) {
        return new PersistentFindingReadAuthorizationRepository(
                dsl, accountStatus, principalStatus, tenancy, directory, roleScopes, transactionManager);
    }

    private static PrincipalStatus activeHumanPrincipal() {
        return new PrincipalStatus(ACTOR_ID, ACCOUNT_ID, "HUMAN", "ACTIVE");
    }

    private static <T> MockResult singleValue(DSLContext dsl, String key, Class<T> type, T value) {
        Field<T> field = DSL.field(key, type);
        var result = dsl.newResult(field);
        Record1<T> row = dsl.newRecord(field);
        row.set(field, value);
        result.add(row);
        return new MockResult(result.size(), result);
    }

    private static final class CapturingTransactionManager extends AbstractPlatformTransactionManager {
        private boolean readOnly;
        private int isolationLevel;

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            readOnly = definition.isReadOnly();
            isolationLevel = definition.getIsolationLevel();
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
        }
    }
}
