package com.mightyoung.softwarefactory.authorization;

import com.mightyoung.softwarefactory.directory.MemberDirectoryReadRepository;
import com.mightyoung.softwarefactory.directory.MemberDirectorySnapshot;
import com.mightyoung.softwarefactory.identity.AccountSecurityState;
import com.mightyoung.softwarefactory.identity.AccountStatusReadRepository;
import com.mightyoung.softwarefactory.identity.CurrentTenantContext;
import com.mightyoung.softwarefactory.identity.PrincipalStatus;
import com.mightyoung.softwarefactory.identity.PrincipalStatusReadRepository;
import com.mightyoung.softwarefactory.tenancy.TenantMembershipReadRepository;
import com.mightyoung.softwarefactory.tenancy.TenantMembershipState;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.tools.jdbc.MockConnection;
import org.jooq.tools.jdbc.MockResult;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** A credential change invalidates read and action snapshots without retaining a previous scope. */
class CredentialVersionAuthorizationRepositoryTest {

    private static final UUID ACTOR_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID ACCOUNT_ID = UUID.fromString("11000000-0000-0000-0000-000000000001");
    private static final UUID TENANT_ID = UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final UUID MEMBER_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID ORG_ID = UUID.fromString("31000000-0000-0000-0000-000000000001");

    @ParameterizedTest(name = "workflow = {0}")
    @ValueSource(booleans = {false, true})
    void aCredentialChangeRejectsTheNextOldSessionSnapshotAndAFreshLoginCanResolveAgain(boolean workflow) {
        Harness harness = new Harness(workflow);

        assertThat(harness.load.apply(context(9L))).isPresent()
                .get().extracting(FindingReadAuthorizationSnapshot::organizationIds).isEqualTo(Set.of(ORG_ID));
        harness.account.set(Optional.of(new AccountSecurityState("ACTIVE", 10L)));

        assertThat(harness.load.apply(context(9L))).isEmpty();
        assertThat(harness.directoryCalls.get()).isEqualTo(1);
        assertThat(harness.roleCalls.get()).isEqualTo(1);
        assertThat(harness.membershipCalls.get()).isEqualTo(1);

        assertThat(harness.load.apply(context(10L))).isPresent()
                .get().extracting(FindingReadAuthorizationSnapshot::organizationIds).isEqualTo(Set.of(ORG_ID));
        assertThat(harness.accountCalls.get()).isEqualTo(3);
        assertThat(harness.directoryCalls.get()).isEqualTo(2);
        assertThat(harness.roleCalls.get()).isEqualTo(2);
    }

    @ParameterizedTest(name = "workflow = {0}, state = {1}")
    @MethodSource("unusableAccountStates")
    void missingOrDifferentCredentialVersionsStopBeforeMembershipAndScopeQueries(
            boolean workflow, Optional<AccountSecurityState> account) {
        Harness harness = new Harness(workflow);
        harness.account.set(account);

        assertThat(harness.load.apply(context(9L))).isEmpty();

        assertThat(harness.accountCalls.get()).isEqualTo(1);
        assertThat(harness.membershipCalls.get()).isZero();
        assertThat(harness.directoryCalls.get()).isZero();
        assertThat(harness.roleCalls.get()).isZero();
    }

    @ParameterizedTest(name = "workflow = {0}, inactive status = {1}")
    @MethodSource("inactiveAccountStates")
    void aMatchingCredentialVersionDoesNotBypassTheExistingAccountStatusCheck(boolean workflow, String status) {
        Harness harness = new Harness(workflow);
        harness.account.set(Optional.of(new AccountSecurityState(status, 9L)));

        var snapshot = harness.load.apply(context(9L));

        assertThat(snapshot).isPresent();
        assertThat(snapshot.orElseThrow().accountStatus()).isEqualTo(status);
        assertThat(snapshot.orElseThrow().organizationIds()).isEmpty();
        assertThat(harness.directoryCalls.get()).isZero();
        assertThat(harness.roleCalls.get()).isZero();
    }

    private static Stream<Arguments> unusableAccountStates() {
        return Stream.of(false, true).flatMap(workflow -> Stream.of(
                Arguments.of(workflow, Optional.<AccountSecurityState>empty()),
                Arguments.of(workflow, Optional.of(new AccountSecurityState("ACTIVE", 8L))),
                Arguments.of(workflow, Optional.of(new AccountSecurityState("ACTIVE", 10L)))));
    }

    private static Stream<Arguments> inactiveAccountStates() {
        return Stream.of(false, true).flatMap(workflow -> Stream.of("LOCKED", "DISABLED")
                .map(status -> Arguments.of(workflow, status)));
    }

    private static CurrentTenantContext context(long credentialVersion) {
        return new CurrentTenantContext(ACTOR_ID, ACCOUNT_ID, TENANT_ID, MEMBER_ID, 17L, 23L, credentialVersion);
    }

    private static final class Harness {
        private final AtomicReference<Optional<AccountSecurityState>> account = new AtomicReference<>(
                Optional.of(new AccountSecurityState("ACTIVE", 9L)));
        private final AtomicInteger accountCalls = new AtomicInteger();
        private final AtomicInteger membershipCalls = new AtomicInteger();
        private final AtomicInteger directoryCalls = new AtomicInteger();
        private final AtomicInteger roleCalls = new AtomicInteger();
        private final Function<CurrentTenantContext, Optional<FindingReadAuthorizationSnapshot>> load;

        private Harness(boolean workflow) {
            var dsl = DSL.using(new MockConnection(context -> {
                assertThat(context.sql()).contains("set_config");
                var field = DSL.field("set_config", String.class);
                var values = DSL.using(SQLDialect.POSTGRES);
                var result = values.newResult(field);
                result.add(values.newRecord(field).values(TENANT_ID.toString()));
                return new MockResult[] {new MockResult(1, result)};
            }), SQLDialect.POSTGRES);
            AccountStatusReadRepository accountRepository = accountId -> {
                assertThat(accountId).isEqualTo(ACCOUNT_ID);
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isTrue();
                assertThat(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())
                        .isEqualTo(TransactionDefinition.ISOLATION_REPEATABLE_READ);
                accountCalls.incrementAndGet();
                return account.get();
            };
            PrincipalStatusReadRepository principalRepository = actorId -> Optional.of(
                    new PrincipalStatus(ACTOR_ID, ACCOUNT_ID, "HUMAN", "ACTIVE"));
            TenantMembershipReadRepository membershipRepository = (tenantId, accountId, memberId) -> {
                membershipCalls.incrementAndGet();
                return Optional.of(new TenantMembershipState("ACTIVE", "ACTIVE", 17L, 23L));
            };
            MemberDirectoryReadRepository directory = new MemberDirectoryReadRepository() {
                @Override
                public MemberDirectorySnapshot findCurrentMemberships(UUID tenantId, UUID memberId) {
                    directoryCalls.incrementAndGet();
                    return new MemberDirectorySnapshot(Set.of(ORG_ID), Set.of(), Set.of());
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
            RoleScopeReadRepository roles = (tenantId, memberId, organizations, positions, groups, permission) -> {
                roleCalls.incrementAndGet();
                return List.of(new RoleScopeDescriptor(UUID.randomUUID(), "DEPT", null));
            };
            var transactionManager = new TestTransactionManager();
            if (workflow) {
                var repository = new PersistentFindingWorkflowAuthorizationRepository(dsl, accountRepository,
                        principalRepository, membershipRepository, directory, roles, transactionManager);
                load = context -> repository.loadActionSnapshot(context, "finding.dispatch", "dispatch");
            } else {
                var repository = new PersistentFindingReadAuthorizationRepository(dsl, accountRepository,
                        principalRepository, membershipRepository, directory, roles, transactionManager);
                load = context -> repository.loadFindingReadSnapshot(context, "finding.read");
            }
        }
    }

    private static final class TestTransactionManager extends AbstractPlatformTransactionManager {
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object transaction, TransactionDefinition definition) { }
        @Override protected void doCommit(DefaultTransactionStatus status) { }
        @Override protected void doRollback(DefaultTransactionStatus status) { }
    }
}
