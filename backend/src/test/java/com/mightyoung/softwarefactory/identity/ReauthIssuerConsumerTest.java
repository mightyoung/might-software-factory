package com.mightyoung.softwarefactory.identity;

import com.mightyoung.softwarefactory.auditops.JooqSessionSensitiveAudit;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import javax.sql.DataSource;
import org.jooq.*;
import org.jooq.Record;
import org.jooq.impl.DSL;
import org.jooq.tools.jdbc.MockConnection;
import org.jooq.tools.jdbc.MockExecuteContext;
import org.jooq.tools.jdbc.MockResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static com.mightyoung.softwarefactory.identity.ReauthIssuerContracts.*;
import static com.mightyoung.softwarefactory.identity.SessionSensitiveContracts.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Actual issuer/service/DAL/audit -> actual revoke DAL/service, on a stateful mock JDBC engine.
 * The boundary's commit/rollback is deliberately simulated. This is not PG, deferred-constraint,
 * production guard, HTTP, or durable-commit acceptance. No proof is seeded by this fixture.
 */
class ReauthIssuerConsumerTest {
    static final Instant NOW = Instant.parse("2026-11-04T00:01:00.123456Z");
    static final String HASH = "{bcrypt}$2b$12$" + "a".repeat(53);
    static final String SECRET = "PRIVATE_INDEPENDENT_REAUTH_FAILURE";

    @AfterEach void clear() {
        SecurityContextHolder.clearContext();
        assertThat(TransactionSynchronizationManager.getResourceMap()).isEmpty();
        assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
    }

    @Test void callerChosenTokenPublicDalIssuanceApiIsAbsent() {
        // The original executable exploit is archived with its real assertion RED. This locks
        // removal of that public forge surface, independently of the live-capability attacks.
        assertThat(java.lang.reflect.Modifier.isPublic(ReauthIssuerStore.class.getModifiers())).isFalse();
        assertThat(java.lang.reflect.Modifier.isPublic(JooqReauthIssuerStore.class.getModifiers())).isFalse();
        assertThat(ReauthIssuerService.class.getConstructors()).isEmpty();
        assertThat(Arrays.stream(ReauthIssuerStore.class.getDeclaredMethods())
                .filter(method -> method.getName().equals("issue"))
                .anyMatch(method -> Arrays.asList(method.getParameterTypes()).contains(IdentityLifecycleContracts.OneUseSecret.class)))
                .isFalse();
    }

    @Test void realIssuerProofIsConsumedByTheUnmodifiedRealRevokeAndBothReceiptsReplayWithoutNewEffects() throws Exception {
        try (Fixture fixture = new Fixture()) {
            UUID issueKey = UUID.randomUUID();
            try (Issued issued = fixture.issue(issueKey, fixture.target)) {
                var proof = fixture.database.proofs.get(issued.metadata().proofId());
                assertThat(proof).isNotNull();
                assertThat(proof.get("state")).isEqualTo("ISSUED");
                assertThat(proof.get("secret_digest")).isEqualTo(issued.secret().digest());
                assertThat(fixture.database.issues).hasSize(1);
                assertThat(fixture.database.successes("ISSUE_REAUTH")).hasSize(1).allSatisfy(event -> {
                    assertThat(event.get("target_primary_id")).isNull();
                    assertThat(event.get("challenge_id")).isNull();
                    assertThat(event.get("proof_id")).isEqualTo(issued.metadata().proofId());
                });
                UUID revokeKey = UUID.randomUUID();
                var command = new RevokeCommand(revokeKey, fixture.target, new ExpectedAuthority(0), issued.metadata().proofId(), issued.secret());
                fixture.consumer().revoke(fixture.request, command, UUID.randomUUID());
                assertThat(fixture.database.proofs.get(issued.metadata().proofId()).get("state")).isEqualTo("CONSUMED");
                assertThat(fixture.database.authorities.get(fixture.target.toString()).get("state")).isEqualTo("REVOKED");
                assertThat(fixture.database.authorities.get(fixture.target.toString()).get("version")).isEqualTo(1L);
                assertThat(fixture.database.consumptions).hasSize(1);
                assertThat(fixture.database.successes("REVOKE_SESSION")).hasSize(1);
                fixture.observeSecrets();
                int mutations = fixture.database.mutations;
                fixture.consumer().revoke(fixture.request, command, UUID.randomUUID());
                try (Outcome replay = fixture.issuer().issueSessionRevoke(fixture.request, issueKey, UUID.randomUUID(), fixture.input(fixture.target))) {
                    assertThat(replay).isInstanceOf(Replay.class);
                    assertThat(((Replay) replay).tokenDelivery()).isEqualTo("UNAVAILABLE_ON_REPLAY");
                    assertThat(replay.metadata()).isEqualTo(issued.metadata());
                    assertThat(Arrays.stream(Replay.class.getMethods()).map(java.lang.reflect.Method::getName)).doesNotContain("secret");
                }
                assertThat(fixture.database.mutations).isEqualTo(mutations);
                assertThat(fixture.database.successes("ISSUE_REAUTH")).hasSize(1);
                assertThat(fixture.database.successes("REVOKE_SESSION")).hasSize(1);
                fixture.assertCapturedSecretClosed();
            }
        }
    }

    @Test void sameIssuanceKeyCannotChangeItsTargetAndDoesNotTerminalizeTheOriginalProof() throws Exception {
        try (Fixture fixture = new Fixture()) {
            UUID key = UUID.randomUUID();
            try (Issued original = fixture.issue(key, fixture.target)) {
                fixture.observeSecrets();
                assertThatThrownBy(() -> fixture.issuer().issueSessionRevoke(fixture.request, key, UUID.randomUUID(), fixture.input(fixture.other)))
                        .isInstanceOf(SessionSensitiveException.class)
                        .satisfies(error -> assertThat(((SessionSensitiveException) error).reason()).isEqualTo(SessionSensitiveException.Reason.CONFLICT));
                assertThat(fixture.database.proofs).hasSize(1);
                assertThat(fixture.database.proofs.get(original.metadata().proofId()).get("state")).isEqualTo("ISSUED");
                assertThat(fixture.database.issues).hasSize(1);
                assertThat(fixture.database.successes("ISSUE_REAUTH")).hasSize(1);
                fixture.assertCapturedSecretClosed();
            }
        }
    }

    @Test void retainedKeyRotationTerminalizesOnlyTheExactPriorProofAndTheNewTokenStillFeedsRealConsumer() throws Exception {
        try (Fixture fixture = new Fixture()) {
            try (Issued first = fixture.issue(UUID.randomUUID(), fixture.target); Issued unrelated = fixture.issue(UUID.randomUUID(), fixture.other)) {
                fixture.rotate(true);
                try (Issued replacement = fixture.issue(UUID.randomUUID(), fixture.target)) {
                    assertThat(fixture.database.proofs.get(first.metadata().proofId())).containsEntry("state", "REVOKED").containsEntry("version", 2L);
                    assertThat(fixture.database.proofs.get(unrelated.metadata().proofId())).containsEntry("state", "ISSUED").containsEntry("version", 1L);
                    assertThat(fixture.database.proofs.get(replacement.metadata().proofId())).containsEntry("state", "ISSUED").containsEntry("body_key_id", "k2");
                    fixture.consumer().revoke(fixture.request, new RevokeCommand(UUID.randomUUID(), fixture.target, new ExpectedAuthority(0),
                            replacement.metadata().proofId(), replacement.secret()), UUID.randomUUID());
                    assertThat(fixture.database.proofs.get(replacement.metadata().proofId()).get("state")).isEqualTo("CONSUMED");
                    assertThat(fixture.database.proofs.get(unrelated.metadata().proofId()).get("state")).isEqualTo("ISSUED");
                }
            }
        }
    }

    @Test void freshExplicitIssuanceExpiresTheExactExpiredProofBeforeInsertingItsReplacement() throws Exception {
        try (Fixture fixture = new Fixture(); Issued old = fixture.issue(UUID.randomUUID(), fixture.target)) {
            fixture.clock.now = fixture.clock.now.plusSeconds(301);
            try (Issued replacement = fixture.issue(UUID.randomUUID(), fixture.target)) {
                assertThat(fixture.database.proofs.get(old.metadata().proofId())).containsEntry("state", "EXPIRED").containsEntry("version", 2L);
                assertThat(fixture.database.proofs.get(replacement.metadata().proofId())).containsEntry("state", "ISSUED").containsEntry("version", 1L);
                assertThat(replacement.metadata().proofId()).isNotEqualTo(old.metadata().proofId());
                assertThat(fixture.database.issues).hasSize(2);
            }
        }
    }

    @Test void missingOldBodyKeyFailsClosedInsteadOfIssuingAlongsideTheOldProof() throws Exception {
        try (Fixture fixture = new Fixture(); Issued old = fixture.issue(UUID.randomUUID(), fixture.target)) {
            // Session binding stays on k2, independently of the proof body retained under k1.
            fixture.database.sessionKey = "k2";
            fixture.rotate(false);
            assertThatThrownBy(() -> fixture.issue(UUID.randomUUID(), fixture.target)).isInstanceOf(SessionSensitiveException.class);
            assertThat(fixture.database.proofs).hasSize(1);
            assertThat(fixture.database.proofs.get(old.metadata().proofId()).get("state")).isEqualTo("ISSUED");
            assertThat(fixture.database.successes("ISSUE_REAUTH")).hasSize(1);
        }
    }

    @Test void exactHashAndCurrentAuthorityAreRecheckedAfterPasswordWorkAndThePreparationIncludesThatWork() throws Exception {
        for (String fault : List.of("hash", "source", "slow")) {
            try (Fixture fixture = new Fixture()) {
                fixture.observeSecrets();
                fixture.afterPassword = () -> {
                    switch (fault) {
                        case "hash" -> fixture.database.hash = "{bcrypt}$2b$12$" + "b".repeat(53);
                        case "source" -> fixture.database.currentAuthority = false;
                        case "slow" -> fixture.clock.now = fixture.clock.now.plusSeconds(61);
                    }
                };
                assertThatThrownBy(() -> fixture.issue(UUID.randomUUID(), fixture.target)).isInstanceOf(SessionSensitiveException.class);
                assertThat(fixture.database.proofs).isEmpty();
                assertThat(fixture.database.issues).isEmpty();
                assertThat(fixture.database.successes("ISSUE_REAUTH")).isEmpty();
                fixture.assertCapturedSecretClosed();
            }
        }
    }

    @Test void actualLateAuditFailureRollsBackProofReceiptAndOldProofTerminalizationAndClosesTheToken() throws Exception {
        try (Fixture fixture = new Fixture(); Issued old = fixture.issue(UUID.randomUUID(), fixture.target)) {
            fixture.observeSecrets();
            fixture.database.failSuccessAudit = true;
            assertThatThrownBy(() -> fixture.issue(UUID.randomUUID(), fixture.target)).isInstanceOf(SessionSensitiveException.class).hasNoCause();
            assertThat(fixture.database.proofs).hasSize(1);
            assertThat(fixture.database.proofs.get(old.metadata().proofId())).containsEntry("state", "ISSUED").containsEntry("version", 1L);
            assertThat(fixture.database.issues).hasSize(1);
            assertThat(fixture.database.successes("ISSUE_REAUTH")).hasSize(1);
            fixture.assertCapturedSecretClosed();
        }
    }

    @Test void postCallbackCommitOrCleanupFailureNeverDeliversItsTokenAndReconciliationReturnsOnlyMetadata() throws Exception {
        for (Fault fault : List.of(Fault.COMMIT_SQL, Fault.COMMIT_RUNTIME, Fault.COMMIT_ERROR, Fault.CLEANUP_ERROR)) {
            try (Fixture fixture = new Fixture()) {
                fixture.observeSecrets();
                UUID key = UUID.randomUUID(); fixture.fault = fault;
                assertThatThrownBy(() -> fixture.issue(key, fixture.target)).isInstanceOf(SessionSensitiveException.class).hasNoCause()
                        .hasMessage("UNAVAILABLE").satisfies(error -> assertThat(error.getSuppressed()).isEmpty());
                fixture.assertCapturedSecretClosed();
                assertThat(fixture.database.proofs).hasSize(1);
                var proofId = fixture.database.proofs.keySet().iterator().next();
                fixture.fault = Fault.NONE;
                try (Outcome replay = fixture.issuer().issueSessionRevoke(fixture.request, key, UUID.randomUUID(), fixture.input(fixture.target))) {
                    assertThat(replay).isInstanceOf(Replay.class);
                    assertThat(replay.metadata().proofId()).isEqualTo(proofId);
                }
                assertThat(fixture.database.proofs).hasSize(1);
                assertThat(fixture.database.issues).hasSize(1);
                assertThat(fixture.database.successes("ISSUE_REAUTH")).hasSize(1);
            }
        }
    }

    @Test void leakedLivePreparationMisuseCannotBeSwallowedToCommitAnIssuedProof() throws Exception {
        for (String misuse : List.of("scope", "thread", "issuer", "reuse", "expired")) {
            try (Fixture fixture = new Fixture()) {
                fixture.attack = (scope, boundary, preparation, actual) -> {
                    if (misuse.equals("expired")) { fixture.clock.now = fixture.clock.now.plusSeconds(61); return; }
                    Runnable invalid = switch (misuse) {
                        case "scope" -> () -> actual.issue(SessionSensitiveSecurityTransaction.Scope.human(Operation.ISSUE_REAUTH, scope.request()), boundary, preparation);
                        case "issuer" -> () -> {
                            var foreign = new JooqReauthIssuerStore(fixture.source, fixture.boundaries, fixture.keys);
                            fixture.service(foreign);
                            foreign.issue(scope, boundary, preparation);
                        };
                        case "reuse" -> () -> { actual.issue(scope, boundary, preparation); actual.issue(scope, boundary, preparation); };
                        default -> () -> actual.issue(scope, boundary, preparation);
                    };
                    if (misuse.equals("thread")) {
                        var result = new java.util.concurrent.atomic.AtomicReference<Throwable>();
                        Thread other = new Thread(() -> result.set(catchThrowable(invalid::run)));
                        other.start();
                        try { other.join(5000); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
                        assertThat(other.isAlive()).isFalse();
                        assertThat(result.get()).isInstanceOf(SessionSensitiveException.class);
                    } else assertThat(catchThrowable(invalid::run)).isInstanceOf(SessionSensitiveException.class);
                    // Deliberately swallow the rejection and try to restore the holder flag.
                    ((ConnectionHolder) TransactionSynchronizationManager.getResource(fixture.source)).resetRollbackOnly();
                };
                assertThatThrownBy(() -> fixture.issue(UUID.randomUUID(), fixture.target)).isInstanceOf(SessionSensitiveException.class);
                assertThat(fixture.database.proofs).as(misuse).isEmpty();
                assertThat(fixture.database.issues).as(misuse).isEmpty();
                assertThat(fixture.database.successes("ISSUE_REAUTH")).as(misuse).isEmpty();
            }
        }
    }

    @Test void closedPreparationCannotBeReusedAndCannotPoisonTheNextLegitimateInvocation() throws Exception {
        try (Fixture fixture = new Fixture(); Issued first = fixture.issue(UUID.randomUUID(), fixture.target)) {
            var closed = fixture.lastPreparation;
            fixture.attack = (scope, boundary, preparation, actual) ->
                    assertThatThrownBy(() -> actual.issue(scope, boundary, closed)).isInstanceOf(SessionSensitiveException.class);
            try (Issued second = fixture.issue(UUID.randomUUID(), fixture.other)) {
                assertThat(second.metadata().proofId()).isNotEqualTo(first.metadata().proofId());
                assertThat(fixture.database.proofs).hasSize(2);
                assertThat(fixture.database.successes("ISSUE_REAUTH")).hasSize(2);
            }
        }
    }

    @FunctionalInterface interface PreparationAttack {
        void run(SessionSensitiveSecurityTransaction.Scope scope, SessionSensitiveSecurityTransaction.GuardedBoundary boundary,
                 ReauthIssuerService.VerifiedPreparation preparation, JooqReauthIssuerStore actual);
    }

    enum Fault { NONE, COMMIT_SQL, COMMIT_RUNTIME, COMMIT_ERROR, CLEANUP_ERROR }
    static final class MutableClock extends Clock {
        Instant now = NOW;
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
    static final class Fixture implements AutoCloseable {
        final MockHttpServletRequest request = CapturedHumanRequestTest.authenticatedRequest();
        final SessionSensitiveRequests requests = new SessionSensitiveRequests(new SessionContextResolver(), Duration.ofHours(12));
        final CapturedHumanRequest original = requests.capture(request);
        final CurrentTenantContext context = original.context();
        final SessionHandle sourceHandle = new SessionHandle(UUID.randomUUID()), target = new SessionHandle(UUID.randomUUID()), other = new SessionHandle(UUID.randomUUID());
        final MutableClock clock = new MutableClock();
        final Database database = new Database(this);
        final DataSource source = mock(DataSource.class);
        final SessionSensitiveSecurityTransaction boundaries = mock(SessionSensitiveSecurityTransaction.class);
        final PasswordEncoder encoder = mock(PasswordEncoder.class);
        final SessionSensitiveAudit audit;
        final Map<String, byte[]> retained = new LinkedHashMap<>();
        SessionSensitiveKeys keys;
        IdentityLifecycleContracts.OneUseSecret lastSecret;
        org.mockito.MockedStatic<IdentityLifecycleContracts.OneUseSecret> secretObservation;
        PreparationAttack attack;
        ReauthIssuerService.VerifiedPreparation lastPreparation;
        Runnable afterPassword;
        Fault fault = Fault.NONE;
        SessionSensitiveSecurityTransaction.Scope activeScope;
        SessionSensitiveSecurityTransaction.GuardedBoundary activeBoundary;
        Connection activeConnection;
        Fixture() throws Exception {
            byte[] first = new byte[32], second = new byte[32]; Arrays.fill(first, (byte) 7); Arrays.fill(second, (byte) 9);
            retained.put("k1", first); retained.put("k2", second); keys = new SessionSensitiveKeys("k1", Map.of("k1", first, "k2", second));
            when(source.getConnection()).thenAnswer(call -> connection());
            doAnswer(call -> {
                var scope = call.<SessionSensitiveSecurityTransaction.Scope>getArgument(0);
                var body = call.<Function<SessionSensitiveSecurityTransaction.GuardedBoundary, Object>>getArgument(1);
                return transaction(scope, body);
            }).when(boundaries).execute(any(), any());
            doAnswer(call -> {
                assertThat((Object) call.getArgument(0)).isSameAs(activeScope);
                assertThat((Object) call.getArgument(1)).isSameAs(activeBoundary);
                assertThat((Object) call.getArgument(2)).isSameAs(source);
                assertThat((Object) call.getArgument(3)).isSameAs(activeConnection);
                assertThat(Thread.currentThread()).isSameAs(database.writerThread);
                activeScope.request().requireCurrent();
                if (((ConnectionHolder) TransactionSynchronizationManager.getResource(source)).isRollbackOnly()) throw SessionSensitiveException.unavailable();
                return null;
            }).when(boundaries).requireGuarded(any(), any(), any(), any());
            when(encoder.matches(any(), anyString())).thenAnswer(call -> {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                assertThat(TransactionSynchronizationManager.getResourceMap()).isEmpty();
                if (afterPassword != null) afterPassword.run();
                return call.getArgument(0).toString().equals("old");
            });
            audit = new JooqSessionSensitiveAudit(source, boundaries, new JdbcTransactionManager(source));
            database.sessions.put(target.toString(), context.accountId().toString());
            database.sessions.put(other.toString(), context.accountId().toString());
        }
        ReauthIssuerService issuer() {
            var actual = new JooqReauthIssuerStore(source, boundaries, keys);
            ReauthIssuerStore recording = new ReauthIssuerStore() {
                @Override public void bindIssuer(ReauthIssuerService issuer) { actual.bindIssuer(issuer); }
                @Override public Optional<PasswordCredentialState> readCredential(CurrentTenantContext context) { return actual.readCredential(context); }
                @Override public Stored issue(SessionSensitiveSecurityTransaction.Scope scope, SessionSensitiveSecurityTransaction.GuardedBoundary boundary,
                        ReauthIssuerService.VerifiedPreparation preparation) {
                    lastPreparation = preparation;
                    if (attack != null) attack.run(scope, boundary, preparation, actual);
                    return actual.issue(scope, boundary, preparation);
                }
            };
            return service(recording);
        }
        ReauthIssuerService service(ReauthIssuerStore store) {
            return new ReauthIssuerService(requests, source, boundaries, store, audit, encoder, limiter(), limiter(), clock, new SecureRandom());
        }
        void observeSecrets() {
            secretObservation = mockStatic(IdentityLifecycleContracts.OneUseSecret.class);
            secretObservation.when(() -> IdentityLifecycleContracts.OneUseSecret.generate(any(SecureRandom.class)))
                    .thenAnswer(invocation -> { lastSecret = (IdentityLifecycleContracts.OneUseSecret) invocation.callRealMethod(); return lastSecret; });
        }
        void assertCapturedSecretClosed() {
            assertThat(lastSecret).as("actual generated secret observed only for cleanup verification").isNotNull();
            assertThatThrownBy(lastSecret::digest).isInstanceOf(RuntimeException.class);
        }
        SessionAuthorityService consumer() {
            return new SessionAuthorityService(requests, boundaries, new JooqSessionAuthorityStore(source, boundaries, keys), audit,
                    new KnownLogoutRequests(serializer -> serializer.setUseBase64Encoding(false)));
        }
        Issued issue(UUID key, SessionHandle target) {
            Outcome outcome = issuer().issueSessionRevoke(request, key, UUID.randomUUID(), input(target));
            assertThat(outcome).isInstanceOf(Issued.class); return (Issued) outcome;
        }
        Request input(SessionHandle target) { return new Request(target, new ExpectedAuthority(0), new char[]{'o','l','d'}); }
        void rotate(boolean retainOld) { keys = new SessionSensitiveKeys("k2", retainOld ? retained : Map.of("k2", retained.get("k2"))); }
        private LoginAttemptLimiter limiter() { return new LoginAttemptLimiter(LoginAttemptLimiter.Config.defaults(), System::nanoTime); }
        private Object transaction(SessionSensitiveSecurityTransaction.Scope scope, Function<SessionSensitiveSecurityTransaction.GuardedBoundary, Object> body) throws Throwable {
            assertThat(TransactionSynchronizationManager.getResourceMap()).isEmpty();
            var snapshot = database.snapshot(); boolean committed = false;
            activeScope = scope; activeBoundary = clock::instant; activeConnection = connection(); database.writerThread = Thread.currentThread();
            ConnectionHolder holder = new ConnectionHolder(activeConnection); holder.setSynchronizedWithTransaction(true);
            TransactionSynchronizationManager.bindResource(source, holder);
            TransactionSynchronizationManager.initSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(true);
            TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
            TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
            try {
                Object result = body.apply(activeBoundary);
                if (holder.isRollbackOnly()) throw SessionSensitiveException.unavailable();
                database.requireReceiptGraphs();
                committed = true;
                if (fault == Fault.COMMIT_SQL) throw new SQLException(SECRET, "XX000");
                if (fault == Fault.COMMIT_RUNTIME) throw new IllegalStateException(SECRET, new SQLException(SECRET));
                if (fault == Fault.COMMIT_ERROR) throw new AssertionError(SECRET);
                return result;
            } catch (Throwable failure) {
                if (!committed) database.restore(snapshot);
                throw failure;
            } finally {
                TransactionSynchronizationManager.unbindResource(source);
                TransactionSynchronizationManager.clearSynchronization(); TransactionSynchronizationManager.clear();
                activeConnection.close(); activeScope = null; activeBoundary = null; activeConnection = null;
                if (committed && fault == Fault.CLEANUP_ERROR) throw new AssertionError(SECRET);
            }
        }
        private Connection connection() {
            Connection jdbc = new MockConnection(execution -> {
                try { return database.execute(execution); }
                catch (SQLException | RuntimeException | Error failure) {
                    database.providerFailure = failure.getClass().getSimpleName();
                    database.providerStatement = execution.sql().strip().replaceAll("\\s+", " ").substring(0, Math.min(70, execution.sql().strip().replaceAll("\\s+", " ").length()));
                    throw failure;
                }
            });
            return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class}, new java.lang.reflect.InvocationHandler() {
                boolean auto = true, readOnly;
                int isolation = Connection.TRANSACTION_READ_COMMITTED;
                @Override public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args) throws Throwable {
                    return switch (method.getName()) {
                        case "getAutoCommit" -> auto;
                        case "setAutoCommit" -> { auto = (boolean) args[0]; yield null; }
                        case "isReadOnly" -> readOnly;
                        case "setReadOnly" -> { readOnly = (boolean) args[0]; yield null; }
                        case "getTransactionIsolation" -> isolation;
                        case "setTransactionIsolation" -> { isolation = (int) args[0]; yield null; }
                        case "toString" -> "Independent component JDBC connection";
                        default -> { try { yield method.invoke(jdbc, args); } catch (InvocationTargetException failure) { throw failure.getCause(); } }
                    };
                }
            });
        }
        @Override public void close() {
            if (secretObservation != null) secretObservation.close();
            original.close();
            if (database.providerFailure != null && !database.failSuccessAudit)
                throw new AssertionError("Independent SQL fixture provider " + database.providerFailure + " during " + database.providerStatement);
        }
    }

    /** Small SQL-state interpreter: only production statements are accepted; no issuer port returns success here. */
    static final class Database {
        final Fixture fixture;
        final Map<String, String> sessions = new LinkedHashMap<>();
        Map<UUID, Map<String, Object>> proofs = new LinkedHashMap<>(), issues = new LinkedHashMap<>(), consumptions = new LinkedHashMap<>(), events = new LinkedHashMap<>();
        Map<String, Map<String, Object>> authorities = new LinkedHashMap<>();
        final List<String> sql = new ArrayList<>();
        String hash = HASH, sessionKey = "k1";
        boolean currentAuthority = true, failSuccessAudit;
        int mutations;
        String providerFailure, providerStatement;
        Thread writerThread;
        Database(Fixture fixture) { this.fixture = fixture; }
        MockResult[] execute(MockExecuteContext execution) throws SQLException {
            String statement = execution.sql().strip().replaceAll("\\s+", " ");
            sql.add(statement);
            Object[] values = execution.bindings();
            if (statement.startsWith("select state,version,")) return rows(List.of(map(
                    "state", "ENFORCING", "version", 1L, "activated_at", offset(NOW.minusSeconds(1)), "activated_by_database_role", "fixture-owner",
                    "activation_event_id", new UUID(0, 1), "activation_evidence_sha256", "a".repeat(64))));
            if (statement.startsWith("select a.credential_version,c.secret_hash")) {
                if (!fixture.context.actorId().equals(uuid(values[0])) || !fixture.context.accountId().equals(uuid(values[1]))
                        || ((Number) values[2]).longValue() != fixture.context.credentialVersion()) return rows(List.of());
                return rows(List.of(map("credential_version", fixture.context.credentialVersion(), "secret_hash", hash)));
            }
            if (statement.contains("from public.spring_session stored")) {
                if (!currentAuthority) return rows(List.of());
                return rows(List.of(map("primary_id", fixture.sourceHandle.toString(), "session_key_id", sessionKey,
                        "session_digest", fixture.keys.sessionDigest(sessionKey, fixture.original.bearerId()),
                        "authenticated_at", offset(fixture.original.authenticatedAt()), "absolute_expires_at", offset(fixture.original.absoluteExpiresAt()))));
            }
            if (statement.startsWith("select principal_name from public.spring_session") || statement.startsWith("select session_id,principal_name from public.spring_session")) {
                String account = sessions.get(values[0].toString());
                return account == null ? rows(List.of()) : rows(List.of(map("principal_name", account, "session_id", "remote-private-" + values[0])));
            }
            if (statement.startsWith("select account_id,state,version from public.account_session_authority") || statement.startsWith("select * from public.account_session_authority")) {
                Map<String, Object> authority = authorities.get(values[0].toString());
                return rows(authority == null ? List.of() : List.of(authority));
            }
            if (statement.contains("from public.sensitive_reauth_issuance_receipt r left join")) {
                var receipt = issues.values().stream().filter(row -> row.get("account_id").equals(uuid(values[0]))
                        && row.get("source_primary_id").equals(values[1]) && row.get("command_key").equals(uuid(values[2]))).findFirst();
                if (receipt.isEmpty()) return rows(List.of());
                var result = new LinkedHashMap<>(receipt.get());
                var proof = proofs.get((UUID) result.get("proof_id"));
                if (proof != null) {
                    for (String field : List.of("action", "source_primary_id", "issued_credential_version", "target_primary_id", "expected_authority_version", "body_key_id", "body_digest", "issuance_receipt_id")) result.put(field, proof.get(field));
                    result.put("proof_account", proof.get("account_id")); result.put("proof_actor", proof.get("actor_id"));
                    result.put("proof_created_at", proof.get("created_at")); result.put("proof_expires_at", proof.get("expires_at"));
                }
                return rows(List.of(result));
            }
            if (statement.contains("from public.sensitive_reauth_proof p left join")) {
                var found = new ArrayList<Map<String, Object>>();
                for (var proof : proofs.values()) {
                    if (!proof.get("account_id").equals(uuid(values[0])) || !proof.get("source_primary_id").equals(values[1])
                            || !proof.get("action").equals("SESSION_REVOKE") || !proof.get("actor_id").equals(uuid(values[2]))
                            || !proof.get("issued_credential_version").equals(((Number) values[3]).longValue())
                            || !proof.get("target_primary_id").equals(values[4]) || !proof.get("expected_authority_version").equals(((Number) values[5]).longValue())
                            || !proof.get("state").equals("ISSUED")) continue;
                    var result = new LinkedHashMap<>(proof);
                    var receipt = issues.get(proof.get("issuance_receipt_id"));
                    if (receipt != null) {
                        for (var field : Map.of("receipt_id", "id", "receipt_proof", "proof_id", "receipt_account", "account_id",
                                "receipt_actor", "actor_id", "receipt_source", "source_primary_id", "receipt_expires", "expires_at", "receipt_completed", "completed_at").entrySet())
                            result.put(field.getKey(), receipt.get(field.getValue()));
                    }
                    found.add(result);
                }
                return rows(found.stream().limit(33).toList());
            }
            if (statement.contains("from public.session_authority_command_receipt")) {
                return rows(consumptions.values().stream().filter(row -> row.get("account_id").equals(uuid(values[0]))
                        && row.get("source_primary_id").equals(values[1]) && row.get("command_key").equals(uuid(values[2]))).toList());
            }
            if (statement.startsWith("select * from public.sensitive_reauth_proof")) {
                var row = proofs.get(uuid(values[0])); return rows(row == null ? List.of() : List.of(row));
            }
            if (statement.startsWith("update public.sensitive_reauth_proof set state=case")) {
                var row = proofs.get(uuid(values[0]));
                if (row == null || !row.get("state").equals("ISSUED") || !row.get("version").equals(((Number) values[1]).longValue())) return affected(0);
                row.put("state", ((OffsetDateTime) row.get("expires_at")).toInstant().isAfter(fixture.clock.instant()) ? "REVOKED" : "EXPIRED");
                row.put("version", ((Long) row.get("version")) + 1); mutations++; return affected(1);
            }
            if (statement.startsWith("update public.sensitive_reauth_proof set state='CONSUMED'")) {
                var row = proofs.get(uuid(values[1]));
                if (row == null || !row.get("state").equals("ISSUED") || !row.get("version").equals(((Number) values[2]).longValue())
                        || !((OffsetDateTime) row.get("expires_at")).toInstant().isAfter(fixture.clock.instant())) return affected(0);
                row.put("state", "CONSUMED"); row.put("version", ((Long) row.get("version")) + 1); row.put("session_consume_receipt_id", uuid(values[0]));
                mutations++; return affected(1);
            }
            if (statement.startsWith("insert into public.")) {
                var row = insert(statement, values);
                if (statement.startsWith("insert into public.identity_sensitive_event") && failSuccessAudit && row.get("result").equals("SUCCESS")) throw new SQLException(SECRET, "XX000");
                if (statement.startsWith("insert into public.sensitive_reauth_proof")) { requireNew(proofs, row); proofs.put((UUID) row.get("id"), row); }
                else if (statement.startsWith("insert into public.sensitive_reauth_issuance_receipt")) { requireNew(issues, row); issues.put((UUID) row.get("id"), row); }
                else if (statement.startsWith("insert into public.session_authority_command_receipt")) { requireNew(consumptions, row); consumptions.put((UUID) row.get("id"), row); }
                else if (statement.startsWith("insert into public.identity_sensitive_event")) { requireNew(events, row); events.put((UUID) row.get("id"), row); }
                else if (statement.startsWith("insert into public.account_session_authority")) {
                    if (authorities.putIfAbsent((String) row.get("primary_id"), row) != null) return affected(0);
                } else throw new SQLException("Unknown component insert");
                mutations++; return affected(1);
            }
            if (statement.startsWith("select set_config(")) return rows(List.of(map("set_config", "")));
            if (statement.equals("select current_setting('app.tenant_id',true)")) return rows(List.of(map("current_setting", "")));
            if (statement.equals("select current_setting('transaction_isolation')")) return rows(List.of(map("current_setting", "read committed")));
            if (statement.equals("select current_setting('transaction_read_only')")) return rows(List.of(map("current_setting", "off")));
            throw new SQLException("Unexpected independent component SQL");
        }
        List<Map<String, Object>> successes(String operation) { return events.values().stream().filter(event -> operation.equals(event.get("operation")) && "SUCCESS".equals(event.get("result"))).toList(); }
        void requireReceiptGraphs() {
            for (var proof : proofs.values()) {
                var receipt = issues.get(proof.get("issuance_receipt_id")); assertThat(receipt).isNotNull();
                assertThat(receipt.get("proof_id")).isEqualTo(proof.get("id"));
                var event = events.get(receipt.get("event_id")); assertThat(event).isNotNull();
                assertThat(event.get("operation")).isEqualTo("ISSUE_REAUTH"); assertThat(event.get("proof_id")).isEqualTo(proof.get("id"));
                assertThat(event.get("target_primary_id")).isNull(); assertThat(event.get("challenge_id")).isNull();
                if (proof.get("state").equals("CONSUMED")) assertThat(consumptions.get(proof.get("session_consume_receipt_id"))).isNotNull();
            }
        }
        Snapshot snapshot() { return new Snapshot(copy(proofs), copy(issues), copy(consumptions), copy(events), copy(authorities), mutations); }
        void restore(Snapshot state) { proofs = state.proofs; issues = state.issues; consumptions = state.consumptions; events = state.events; authorities = state.authorities; mutations = state.mutations; }
        private static <K> Map<K, Map<String, Object>> copy(Map<K, Map<String, Object>> rows) {
            Map<K, Map<String, Object>> result = new LinkedHashMap<>(); rows.forEach((key, value) -> result.put(key, new LinkedHashMap<>(value))); return result;
        }
        private static void requireNew(Map<UUID, Map<String, Object>> rows, Map<String, Object> row) throws SQLException { if (rows.containsKey(row.get("id"))) throw new SQLException("Duplicate component identity", "23505"); }
        record Snapshot(Map<UUID, Map<String, Object>> proofs, Map<UUID, Map<String, Object>> issues, Map<UUID, Map<String, Object>> consumptions,
                        Map<UUID, Map<String, Object>> events, Map<String, Map<String, Object>> authorities, int mutations) {}
    }

    private static Map<String, Object> insert(String statement, Object[] bindings) {
        int columnsStart = statement.indexOf('('), columnsEnd = statement.indexOf(')', columnsStart);
        String[] columns = statement.substring(columnsStart + 1, columnsEnd).split(",");
        int valuesStart = statement.indexOf("values(");
        if (valuesStart < 0) valuesStart = statement.indexOf("values (");
        String body = statement.substring(statement.indexOf('(', valuesStart) + 1, statement.lastIndexOf(')'));
        List<String> expressions = new ArrayList<>(); int depth = 0, start = 0; boolean quote = false;
        for (int i = 0; i < body.length(); i++) {
            char character = body.charAt(i);
            if (character == '\'') quote = !quote;
            if (!quote && character == '(') depth++;
            if (!quote && character == ')') depth--;
            if (!quote && character == ',' && depth == 0) { expressions.add(body.substring(start, i).strip()); start = i + 1; }
        }
        expressions.add(body.substring(start).strip());
        assertThat(expressions).hasSize(columns.length);
        Map<String, Object> row = new LinkedHashMap<>(); int argument = 0;
        for (int i = 0; i < columns.length; i++) {
            String column = columns[i].strip(), expression = expressions.get(i);
            Object value = expression.contains("?") ? bindings[argument++] : expression.startsWith("'") ? expression.substring(1, expression.length() - 1) : Long.valueOf(expression);
            if (value instanceof String text && (column.equals("id") || column.endsWith("_id") && !column.endsWith("primary_id") && !column.endsWith("key_id"))) {
                try { value = UUID.fromString(text); } catch (IllegalArgumentException ignored) { }
            }
            // jOOQ's PG binder may send a timestamp wire String to MockConnection. The simulated
            // database stores a timestamptz value, just as the actual consumer expects from JDBC.
            if (value != null && (column.endsWith("_at") || column.endsWith("_date")))
                value = org.jooq.tools.Convert.convert(value, OffsetDateTime.class);
            if (value instanceof Integer integer && (column.equals("version") || column.endsWith("_version"))) value = integer.longValue();
            row.put(column, value);
        }
        assertThat(argument).isEqualTo(bindings.length); return row;
    }
    private static UUID uuid(Object value) { return value instanceof UUID id ? id : UUID.fromString(value.toString()); }
    private static OffsetDateTime offset(Instant value) { return value.truncatedTo(java.time.temporal.ChronoUnit.MICROS).atOffset(ZoneOffset.UTC); }
    private static Map<String, Object> map(Object... values) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) row.put((String) values[i], values[i + 1]); return row;
    }
    private static MockResult[] affected(int count) { return new MockResult[]{new MockResult(count)}; }
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static MockResult[] rows(List<Map<String, Object>> rows) {
        DSLContext db = DSL.using(SQLDialect.POSTGRES);
        if (rows.isEmpty()) return new MockResult[]{new MockResult(0, db.newResult(DSL.field("empty", String.class)))};
        var sample = rows.getFirst();
        Field<?>[] fields = sample.entrySet().stream().map(entry -> DSL.field(DSL.name(entry.getKey()), (Class) (entry.getValue() == null ? String.class : entry.getValue().getClass()))).toArray(Field<?>[]::new);
        Result<Record> result = db.newResult(fields);
        for (var row : rows) {
            Record record = db.newRecord(fields);
            for (Field field : fields) record.set(field, row.get(field.getName())); result.add(record);
        }
        return new MockResult[]{new MockResult(rows.size(), result)};
    }
}
