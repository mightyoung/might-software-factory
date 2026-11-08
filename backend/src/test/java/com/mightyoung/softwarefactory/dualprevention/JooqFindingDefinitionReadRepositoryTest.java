package com.mightyoung.softwarefactory.dualprevention;

import com.mightyoung.softwarefactory.applicationregistry.definition.EntityDefinition.Use;
import com.mightyoung.softwarefactory.applicationregistry.reference.ReferenceQueryContract;
import com.mightyoung.softwarefactory.authorization.*;
import com.mightyoung.softwarefactory.identity.*;
import com.mightyoung.softwarefactory.tenancy.*;
import java.sql.Connection;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.jooq.*;
import org.jooq.impl.DSL;
import org.jooq.tools.jdbc.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Mock JDBC boundary evidence only; actual PostgreSQL isolation and RLS remain separate acceptance. */
class JooqFindingDefinitionReadRepositoryTest {
    final UUID actor = UUID.randomUUID(), account = UUID.randomUUID(), tenant = UUID.randomUUID(), member = UUID.randomUUID();
    final UUID first = UUID.fromString("30000000-0000-0000-0000-000000000001"), second = UUID.fromString("30000000-0000-0000-0000-000000000002"), third = UUID.fromString("30000000-0000-0000-0000-000000000003");
    final Set<UUID> organizations = Set.of(first, second, third);
    final CurrentTenantContext context = new CurrentTenantContext(actor, account, tenant, member, 4, 5, 1);
    final DataSource source = mock(DataSource.class);
    final Connection connection = mock(Connection.class);
    final ConnectionHolder holder = new ConnectionHolder(connection);
    final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
    final List<String> sql = new ArrayList<>();
    final List<Object[]> candidateBindings = new ArrayList<>();
    boolean readAllowed = true, createAllowed = true, timeoutsValid = true;
    AccountSecurityState accountState = new AccountSecurityState("ACTIVE", 1);
    TenantApplicationState application = new TenantApplicationState(true, 1);
    FindingReadAuthorizationSnapshot current;
    List<ReferenceQueryContract.Item> rows = List.of();
    JooqFindingDefinitionReadRepository repository;

    @BeforeEach void setup() throws Exception {
        current = snapshot(4, 5, organizations);
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(
                VerifiedSessionPrincipalTestFixture.create(actor, account, tenant, member, 4, 5), null, "ROLE_AUTHENTICATED"));
        when(connection.isReadOnly()).thenReturn(true);
        when(connection.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_REPEATABLE_READ);
        var jdbc = new MockConnection(call -> {
            sql.add(call.sql());
            DSLContext db = DSL.using(SQLDialect.POSTGRES);
            if (call.sql().contains("current_setting")) {
                var t = DSL.field("tenant", String.class); var i = DSL.field("isolation", String.class);
                var r = DSL.field("readonly", String.class); var st = DSL.field("statement_timeout_valid", Boolean.class);
                var lt = DSL.field("lock_timeout_valid", Boolean.class);
                var result = db.newResult(t, i, r, st, lt);
                result.add(db.newRecord(t, i, r, st, lt).values(tenant.toString(), "repeatable read", "on", timeoutsValid, timeoutsValid));
                return new MockResult[]{new MockResult(1, result)};
            }
            if (call.sql().contains("set_config")) {
                var field = DSL.field("set_config", String.class); var result = db.newResult(field);
                result.add(db.newRecord(field).values(tenant.toString())); return new MockResult[]{new MockResult(1, result)};
            }
            if (call.sql().contains("org_unit")) {
                candidateBindings.add(call.bindings());
                var id = DSL.field("id", UUID.class); var code = DSL.field("code", String.class); var name = DSL.field("name", String.class);
                var result = db.newResult(id, code, name);
                rows.forEach(item -> result.add(db.newRecord(id, code, name).values(item.id(), item.code(), item.name())));
                return new MockResult[]{new MockResult(rows.size(), result)};
            }
            throw new AssertionError("Unexpected SQL in mocked boundary");
        });
        when(connection.prepareStatement(anyString())).thenAnswer(call -> jdbc.prepareStatement(call.getArgument(0)));
        when(manager.getTransaction(any())).thenAnswer(call -> {
            TransactionDefinition definition = call.getArgument(0);
            assertThat(definition.isReadOnly()).isTrue();
            assertThat(definition.getIsolationLevel()).isEqualTo(TransactionDefinition.ISOLATION_REPEATABLE_READ);
            assertThat(definition.getTimeout()).isEqualTo(5);
            holder.setSynchronizedWithTransaction(true); TransactionSynchronizationManager.bindResource(source, holder);
            TransactionSynchronizationManager.initSynchronization(); TransactionSynchronizationManager.setActualTransactionActive(true);
            TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
            TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
            return new SimpleTransactionStatus();
        });
        var readAuthorizer = new FindingReadAuthorizer((ctx, entity) -> {
            assertManaged(ctx); assertThat(entity).isEqualTo("finding");
            return readAllowed ? Optional.of(new EntityReadGrant(tenant, organizations)) : Optional.empty();
        });
        FindingWorkflowAuthorizationRepository workflow = new FindingWorkflowAuthorizationRepository() {
            public Optional<FindingReadAuthorizationSnapshot> loadActionSnapshot(CurrentTenantContext ctx, String permission, String action) {
                assertManaged(ctx); assertThat(permission).isEqualTo("finding.create"); assertThat(action).isEqualTo("create");
                return createAllowed ? Optional.ofNullable(current) : Optional.empty();
            }
            public List<AssignableFindingMember> findAssignableMembers(UUID t, UUID o, String permission, String prefix, UUID after, int limit) { throw new AssertionError("Unrelated action"); }
            public boolean isAssignableMemberAuthorizedForAction(UUID t, UUID m, UUID o, String permission) { throw new AssertionError("Unrelated action"); }
        };
        repository = new JooqFindingDefinitionReadRepository(source, DSL.using(connection, SQLDialect.POSTGRES), manager,
                new SessionContextResolver(), (t, app) -> { assertManaged(context); assertThat(t).isEqualTo(tenant); assertThat(app).isEqualTo("safety"); return Optional.ofNullable(application); },
                id -> { assertManaged(context); assertThat(id).isEqualTo(account); return Optional.ofNullable(accountState); },
                readAuthorizer, (ctx, permission) -> { assertManaged(ctx); assertThat(permission).isEqualTo("finding.read"); return Optional.ofNullable(current); }, workflow);
    }
    private void assertManaged(CurrentTenantContext ctx) {
        assertThat(ctx).isEqualTo(context);
        assertThat(TransactionSynchronizationManager.getResource(source)).isSameAs(holder);
        assertThat(holder.getConnection()).isSameAs(connection);
        assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isTrue();
        assertThat(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel()).isEqualTo(Connection.TRANSACTION_REPEATABLE_READ);
    }
    @AfterEach void cleanup() {
        TransactionSynchronizationManager.unbindResourceIfPossible(source); TransactionSynchronizationManager.clear();
        SecurityContextHolder.clearContext();
    }
    @Test void readAndCreateAuthorizationUseSeparateCurrentPublicPortsOnTheOwnedPhysicalSnapshot() {
        repository.inSnapshot(context, snapshot -> {
            var read = snapshot.authorize(Use.READ); var create = snapshot.authorize(Use.CREATE);
            assertThat(read.use()).isEqualTo(Use.READ); assertThat(create.use()).isEqualTo(Use.CREATE);
            assertThat(read.organizationIds()).isEqualTo(organizations);
            assertThat(read.applicationVersion()).isEqualTo(1); return null;
        });
        assertThat(sql).allSatisfy(query -> assertThat(query.toLowerCase()).doesNotContain("for update", "for share", "insert ", "delete ", "public.account", "role_grant", "tenant_member"));
        verifyNoInteractions(source);
    }
    @Test void createOnlyCallerNeverObtainsReadDefinitionAuthority() {
        readAllowed = false;
        repository.inSnapshot(context, snapshot -> {
            assertThat(snapshot.authorize(Use.CREATE).use()).isEqualTo(Use.CREATE);
            assertThatThrownBy(() -> snapshot.authorize(Use.READ)).isInstanceOf(AccessDeniedException.class); return null;
        });
    }
    @Test void readOnlyCallerNeverObtainsCreateCandidateAuthority() {
        createAllowed = false;
        repository.inSnapshot(context, snapshot -> {
            var read = snapshot.authorize(Use.READ);
            assertThatThrownBy(() -> snapshot.organizations(read, new ReferenceQueryContract.Request("", null, 20)))
                    .isInstanceOf(FindingDefinitionUnavailableException.class);
            assertThatThrownBy(() -> snapshot.authorize(Use.CREATE)).isInstanceOf(AccessDeniedException.class); return null;
        });
        assertThat(candidateBindings).isEmpty();
    }
    @Test void copiedCallerScopeCannotMasqueradeAsTheOwnerIssuedToken() {
        repository.inSnapshot(context, snapshot -> {
            var issued = snapshot.authorize(Use.CREATE);
            var copy = new FindingDefinitionReadRepository.Authority(issued.use(), issued.context(), issued.organizationIds(), issued.applicationVersion());
            assertThatThrownBy(() -> snapshot.organizations(copy, new ReferenceQueryContract.Request("", null, 20)))
                    .isInstanceOf(FindingDefinitionUnavailableException.class); return null;
        });
        assertThat(candidateBindings).isEmpty();
    }
    @Test void predicatesAndLiteralSearchAreAppliedBeforeKeysetAndLimitPlusOne() {
        rows = List.of(new ReferenceQueryContract.Item(first, "A", "One"), new ReferenceQueryContract.Item(second, "B", "Two"), new ReferenceQueryContract.Item(third, "C", "Three"));
        var page = repository.inSnapshot(context, snapshot -> snapshot.organizations(snapshot.authorize(Use.CREATE), new ReferenceQueryContract.Request("x%_\\", first, 2)));
        assertThat(page.items()).extracting(ReferenceQueryContract.Item::id).containsExactly(first, second);
        assertThat(page.nextAfterId()).isEqualTo(second);
        String query = sql.stream().filter(item -> item.contains("org_unit")).findFirst().orElseThrow();
        assertThat(query).contains("tenant_id", "active", "id in", "ilike", "escape", "id >", "order by id asc");
        assertThat(query.toLowerCase()).containsPattern("(limit|fetch next)");
        assertThat(query).doesNotContain(tenant.toString(), first.toString(), "x%_\\");
        assertThat(candidateBindings).hasSize(1);
        assertThat(Arrays.asList(candidateBindings.getFirst())).contains("%x\\%\\_\\\\%", 3L);
    }
    @Test void staleEpochCredentialDisabledAppAndScopeParityDenyWithoutCandidateSql() {
        repository.inSnapshot(context, snapshot -> {
            current = snapshot(4, 6, organizations);
            assertThatThrownBy(() -> snapshot.authorize(Use.CREATE)).isInstanceOf(AccessDeniedException.class);
            current = snapshot(4, 5, Set.of(first));
            assertThatThrownBy(() -> snapshot.authorize(Use.READ)).isInstanceOf(AccessDeniedException.class);
            current = snapshot(4, 5, organizations); accountState = new AccountSecurityState("ACTIVE", 2);
            assertThatThrownBy(() -> snapshot.authorize(Use.READ)).isInstanceOf(AccessDeniedException.class);
            accountState = new AccountSecurityState("ACTIVE", 1); application = new TenantApplicationState(false, 2);
            assertThatThrownBy(() -> snapshot.authorize(Use.CREATE)).isInstanceOf(AccessDeniedException.class);
            application = null;
            assertThatThrownBy(() -> snapshot.authorize(Use.READ)).isInstanceOf(AccessDeniedException.class); return null;
        });
        assertThat(candidateBindings).isEmpty();
    }
    @Test void missingTechnicalInactiveAndWrongIdentityNeverIssueAuthority() {
        repository.inSnapshot(context, boundary -> {
            var cases = List.of(
                    new FindingReadAuthorizationSnapshot(UUID.randomUUID(), account, "HUMAN", "ACTIVE", "ACTIVE", "ACTIVE", "ACTIVE", 4, 5, organizations),
                    new FindingReadAuthorizationSnapshot(actor, UUID.randomUUID(), "HUMAN", "ACTIVE", "ACTIVE", "ACTIVE", "ACTIVE", 4, 5, organizations),
                    new FindingReadAuthorizationSnapshot(actor, account, "AGENT", "ACTIVE", "ACTIVE", "ACTIVE", "ACTIVE", 4, 5, organizations),
                    new FindingReadAuthorizationSnapshot(actor, account, "HUMAN", "DISABLED", "ACTIVE", "ACTIVE", "ACTIVE", 4, 5, organizations),
                    new FindingReadAuthorizationSnapshot(actor, account, "HUMAN", "ACTIVE", "DISABLED", "ACTIVE", "ACTIVE", 4, 5, organizations),
                    new FindingReadAuthorizationSnapshot(actor, account, "HUMAN", "ACTIVE", "ACTIVE", "DISABLED", "ACTIVE", 4, 5, organizations),
                    new FindingReadAuthorizationSnapshot(actor, account, "HUMAN", "ACTIVE", "ACTIVE", "ACTIVE", "DISABLED", 4, 5, organizations),
                    snapshot(4, 5, Set.of()));
            for (var candidate : cases) {
                current = candidate;
                assertThatThrownBy(() -> boundary.authorize(Use.CREATE)).isInstanceOf(AccessDeniedException.class);
                assertThatThrownBy(() -> boundary.authorize(Use.READ)).isInstanceOf(AccessDeniedException.class);
            }
            current = null;
            assertThatThrownBy(() -> boundary.authorize(Use.CREATE)).isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> boundary.authorize(Use.READ)).isInstanceOf(AccessDeniedException.class);
            return null;
        });
        assertThat(candidateBindings).isEmpty();
    }
    @Test void snapshotCannotEscapeOrMoveToAnotherThread() throws Exception {
        var escaped = new AtomicReference<FindingDefinitionReadRepository.Snapshot>();
        repository.inSnapshot(context, snapshot -> {
            escaped.set(snapshot);
            var failure = new AtomicReference<Throwable>();
            var thread = new Thread(() -> { try { snapshot.requireActive(); } catch (Throwable error) { failure.set(error); } });
            thread.start(); try { thread.join(2000); } catch (InterruptedException error) { throw new AssertionError(error); }
            assertThat(thread.isAlive()).isFalse(); assertThat(failure.get()).isInstanceOf(FindingDefinitionUnavailableException.class); return null;
        });
        assertThatThrownBy(() -> escaped.get().requireActive()).isInstanceOf(FindingDefinitionUnavailableException.class);
    }
    @Test void ownerRequiresOriginalHolderModeTenantTimeoutAndSynchronization() {
        assertThatThrownBy(() -> repository.inSnapshot(context, snapshot -> {
            timeoutsValid = false; snapshot.requireActive(); return null;
        })).isInstanceOf(FindingDefinitionUnavailableException.class);
    }
    @Test void changedHolderAndSavepointInvalidateTheOwnedScope() {
        assertThatThrownBy(() -> repository.inSnapshot(context, snapshot -> {
            TransactionSynchronizationManager.unbindResource(source);
            TransactionSynchronizationManager.bindResource(source, new ConnectionHolder(connection));
            snapshot.requireActive(); return null;
        })).isInstanceOf(FindingDefinitionUnavailableException.class);
    }
    @Test void incomingTransactionIsRejectedBeforeStartingOrReading() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        assertThatThrownBy(() -> repository.inSnapshot(context, snapshot -> null)).isInstanceOf(FindingDefinitionUnavailableException.class);
        verifyNoInteractions(manager); assertThat(sql).isEmpty();
    }
    @Test void savepointInvalidatesTheScope() {
        assertThatThrownBy(() -> repository.inSnapshot(context, snapshot -> {
            for (var synchronization : TransactionSynchronizationManager.getSynchronizations()) synchronization.savepoint(new Object());
            snapshot.requireActive(); return null;
        })).isInstanceOf(FindingDefinitionUnavailableException.class);
    }
    @Test void changedPhysicalIsolationFailsClosed() throws Exception {
        assertThatThrownBy(() -> repository.inSnapshot(context, snapshot -> {
            try { when(connection.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_READ_COMMITTED); }
            catch (java.sql.SQLException error) { throw new AssertionError(error); }
            snapshot.requireActive(); return null;
        })).isInstanceOf(FindingDefinitionUnavailableException.class);
    }
    @Test void attachingAnotherResourceCannotBroadenTheOwnedSnapshot() {
        var foreign = new Object();
        try {
            assertThatThrownBy(() -> repository.inSnapshot(context, snapshot -> {
                TransactionSynchronizationManager.bindResource(foreign, new Object());
                snapshot.requireActive(); return null;
            })).isInstanceOf(FindingDefinitionUnavailableException.class);
        } finally { TransactionSynchronizationManager.unbindResourceIfPossible(foreign); }
    }
    private FindingReadAuthorizationSnapshot snapshot(long tenantEpoch, long memberEpoch, Set<UUID> orgs) {
        return new FindingReadAuthorizationSnapshot(actor, account, "HUMAN", "ACTIVE", "ACTIVE", "ACTIVE", "ACTIVE", tenantEpoch, memberEpoch, orgs);
    }
}
