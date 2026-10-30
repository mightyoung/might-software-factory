package com.mightyoung.softwarefactory.authorization;

import com.mightyoung.softwarefactory.directory.MemberDirectoryReadRepository;
import com.mightyoung.softwarefactory.directory.MemberDirectorySnapshot;
import com.mightyoung.softwarefactory.identity.AccountStatusReadRepository;
import com.mightyoung.softwarefactory.identity.CurrentTenantContext;
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
                accountId -> {
                    assertThat(accountId).isEqualTo(ACCOUNT_ID);
                    return Optional.of("ACTIVE");
                },
                (tenantId, accountId, memberId) -> Optional.of(
                        new TenantMembershipState("ACTIVE", "ACTIVE", 17L, 23L)),
                directory,
                roleScopes,
                transactionManager);

        Optional<FindingReadAuthorizationSnapshot> snapshot = repository.loadFindingReadSnapshot(
                new CurrentTenantContext(ACTOR_ID, ACCOUNT_ID, TENANT_ID, MEMBER_ID, 17L, 23L), "finding.read");

        assertThat(snapshot).contains(new FindingReadAuthorizationSnapshot(
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
                ignored -> Optional.of("ACTIVE"),
                (tenantId, accountId, memberId) -> Optional.of(
                        new TenantMembershipState("ACTIVE", "ACTIVE", 18L, 23L)),
                directory,
                (tenantId, memberId, organizations, positions, groups, permission) -> {
                    roleCalls.incrementAndGet();
                    return List.of();
                },
                new CapturingTransactionManager());

        var snapshot = repository.loadFindingReadSnapshot(
                new CurrentTenantContext(ACTOR_ID, ACCOUNT_ID, TENANT_ID, MEMBER_ID, 17L, 23L), "finding.read");

        assertThat(snapshot).isPresent();
        assertThat(snapshot.orElseThrow().organizationIds()).isEmpty();
        assertThat(directoryCalls.get()).isZero();
        assertThat(roleCalls.get()).isZero();
    }

    private static PersistentFindingReadAuthorizationRepository repository(
            DSLContext dsl,
            AccountStatusReadRepository accountStatus,
            TenantMembershipReadRepository tenancy,
            MemberDirectoryReadRepository directory,
            RoleScopeReadRepository roleScopes,
            CapturingTransactionManager transactionManager) {
        return new PersistentFindingReadAuthorizationRepository(
                dsl, accountStatus, tenancy, directory, roleScopes, transactionManager);
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
