package com.mightyoung.softwarefactory.support;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RiskJdbcTransactionOwnerTest {
    static final String SECRET = "PRIVATE_OWNER_SENTINEL";
    enum Fault { NONE, ACQUIRE, GET_ISOLATION, GET_READ_ONLY, SET_ISOLATION, GET_AUTO_COMMIT, SET_AUTO_COMMIT_FALSE,
        COMMIT_SQL, COMMIT_RUNTIME, COMMIT_ERROR, ROLLBACK_SQL, ROLLBACK_RUNTIME, ROLLBACK_ERROR,
        RESET_AUTO_COMMIT, RESET_ISOLATION, CLOSE }

    @Test void constructionBorrowsNothingAndExposesOnlyRestrictedExecution() throws Exception {
        Fixture fixture = fixture(Fault.NONE);
        verifyNoInteractions(fixture.source(), fixture.connection());
        assertThat(Modifier.isPublic(RiskJdbcTransactionOwner.class.getModifiers())).isFalse();
        assertThat(RiskJdbcTransactionOwner.class.getDeclaredConstructors()).allSatisfy(constructor ->
                assertThat(Modifier.isPrivate(constructor.getModifiers())).isTrue());
        assertThat(RiskJdbcTransactionOwner.class.getDeclaredMethods()).noneSatisfy(method ->
                assertThat(Modifier.isPublic(method.getModifiers())).isTrue());
        assertThat(RiskJdbcTransactionOwner.class.getDeclaredMethods()).noneSatisfy(method ->
                assertThat(method.getReturnType().getName()).matches(".*(DataSource|TransactionManager|Connection)$"));
    }

    @Test void ownedReadCommittedTransactionUsesOneHolderAndAcknowledgesBeforeCleanup() throws Exception {
        Fixture fixture = fixture(Fault.NONE);
        AtomicReference<Connection> guarded = new AtomicReference<>();
        String result = fixture.owner().executeOwned(() -> {
            ConnectionHolder holder = (ConnectionHolder) TransactionSynchronizationManager.getResource(fixture.source());
            assertThat(holder).isNotNull();
            assertThat(holder.isSynchronizedWithTransaction()).isTrue();
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())
                    .isEqualTo(Connection.TRANSACTION_READ_COMMITTED);
            guarded.set(holder.getConnection());
            assertThat(holder.getConnection()).isNotSameAs(fixture.connection());
            fixture.driver().operations.add("body");
            return "original result";
        });
        assertThat(result).isEqualTo("original result");
        assertThat(fixture.driver().operations).containsExactly("acquire", "getAutoCommit", "isReadOnly", "getIsolation", "setIsolation:2", "setAutoCommit:false", "body", "commit", "setAutoCommit:true", "setIsolation:8", "close");
        verify(fixture.source(), times(1)).getConnection();
        assertUnbound(fixture);
        int before = fixture.driver().operations.size();
        assertThatThrownBy(guarded.get()::commit).isInstanceOf(SQLException.class);
        assertThat(guarded.get().toString()).isEqualTo("Risk owned JDBC lease[redacted]");
        assertThat(fixture.driver().operations).hasSize(before);
    }

    @Test void alreadyManualReadCommittedConnectionRequiresNoSetupOrResetSetters() throws Exception {
        for (boolean rejectBody : List.of(false, true)) {
            Fixture fixture = fixture(Fault.NONE, false, Connection.TRANSACTION_READ_COMMITTED);
            Runnable execution = () -> fixture.owner().executeOwned(() -> {
                fixture.driver().operations.add("body");
                if (rejectBody) throw secretRuntime();
                return "value";
            });
            if (rejectBody) {
                RiskJdbcTransactionOwner.Failure failure = failure(execution);
                assertThat(failure.outcome()).isEqualTo(RiskJdbcTransactionOwner.Outcome.ROLLED_BACK);
                assertThat(failure.cleanup()).isEqualTo(RiskJdbcTransactionOwner.Cleanup.COMPLETE);
            } else execution.run();
            assertThat(fixture.driver().operations).containsExactly("acquire", "getAutoCommit", "isReadOnly", "getIsolation", "body",
                    rejectBody ? "rollback" : "commit", "close");
            assertThat(fixture.driver().autoCommit).isFalse();
            assertThat(fixture.driver().isolation).isEqualTo(Connection.TRANSACTION_READ_COMMITTED);
            assertUnbound(fixture);
        }
    }

    @ParameterizedTest @EnumSource(Fault.class)
    void sameOwnerCanExplicitlyExecuteAgainOnFreshLeaseAfterEachFailure(Fault fault) throws Exception {
        Fixture fixture = fixture(fault);
        AtomicReference<Connection> previous = new AtomicReference<>();
        RiskJdbcTransactionOwner.Failure first = failure(() -> fixture.owner().executeOwned(() -> {
            previous.set(bound(fixture));
            if (fault == Fault.NONE || fault.name().startsWith("ROLLBACK")) throw secretRuntime();
            return "first";
        }));
        assertSafe(first);
        assertUnbound(fixture);
        List<String> originalOperations = List.copyOf(fixture.driver().operations);
        Driver fresh = new Driver(Fault.NONE);
        Connection freshConnection = mock(Connection.class,
                invocation -> fresh.invoke(invocation.getMethod().getName(), invocation.getArguments()));
        // A separately issued physical lease is deliberate; never reuse or retry the unknown original connection.
        doAnswer(invocation -> { fresh.operations.add("acquire"); return freshConnection; }).when(fixture.source()).getConnection();
        String value = fixture.owner().executeOwned(() -> {
            if (previous.get() != null) {
                assertThat(bound(fixture)).isNotSameAs(previous.get());
                assertThatThrownBy(previous.get()::commit).isInstanceOf(SQLException.class);
            }
            fresh.operations.add("body");
            return "second";
        });
        assertThat(value).isEqualTo("second");
        assertThat(fixture.driver().operations).containsExactlyElementsOf(originalOperations);
        assertThat(fresh.operations).containsExactly("acquire", "getAutoCommit", "isReadOnly", "getIsolation", "setIsolation:2",
                "setAutoCommit:false", "body", "commit", "setAutoCommit:true", "setIsolation:8", "close");
        verify(fixture.source(), times(2)).getConnection();
        assertUnbound(fixture);
    }

    @ParameterizedTest @EnumSource(value = Fault.class, names = {"ACQUIRE", "GET_ISOLATION", "GET_READ_ONLY", "SET_ISOLATION", "GET_AUTO_COMMIT", "SET_AUTO_COMMIT_FALSE"})
    void beginFailurePreventsBodyAndSuperReleaseCannotReturnUnknownConnection(Fault fault) throws Exception {
        Fixture fixture = fixture(fault);
        RiskJdbcTransactionOwner.Failure failure = failure(() -> fixture.owner().executeOwned(() -> {
            throw new AssertionError("body must not run");
        }));
        assertThat(failure.outcome()).isEqualTo((fault == Fault.ACQUIRE || fault == Fault.GET_AUTO_COMMIT || fault == Fault.GET_ISOLATION || fault == Fault.GET_READ_ONLY)
                ? RiskJdbcTransactionOwner.Outcome.NOT_STARTED : RiskJdbcTransactionOwner.Outcome.BEGIN_UNKNOWN);
        assertThat(fixture.driver().operations).doesNotContain("commit", "rollback", "setAutoCommit:true", "setIsolation:8", "close");
        if (fault == Fault.SET_AUTO_COMMIT_FALSE) assertThat(fixture.driver().autoCommit).isFalse();
        assertSafe(failure);
        assertUnbound(fixture);
    }

    @ParameterizedTest @EnumSource(value = Fault.class, names = {"COMMIT_SQL", "COMMIT_RUNTIME", "COMMIT_ERROR"})
    void everyCommitFailureRetainsUnknownWithoutAutomaticRollbackResetOrClose(Fault fault) throws Exception {
        Fixture fixture = fixture(fault);
        RiskJdbcTransactionOwner.Failure failure = failure(() -> fixture.owner().executeOwned(() -> "done"));
        assertThat(failure.outcome()).isEqualTo(RiskJdbcTransactionOwner.Outcome.COMMIT_UNKNOWN);
        assertThat(failure.cleanup()).isEqualTo(RiskJdbcTransactionOwner.Cleanup.NOT_ATTEMPTED);
        assertThat(fixture.driver().commitApplied).isTrue();
        assertThat(fixture.driver().operations.stream().filter("commit"::equals).count()).isEqualTo(1);
        assertThat(fixture.driver().operations).doesNotContain("rollback", "setAutoCommit:true", "setIsolation:8", "close");
        assertSafe(failure);
        assertUnbound(fixture);
    }

    @ParameterizedTest @EnumSource(value = Fault.class, names = {"ROLLBACK_SQL", "ROLLBACK_RUNTIME", "ROLLBACK_ERROR"})
    void rollbackFailureIsUnknownAndNeverReturnsConnection(Fault fault) throws Exception {
        Fixture fixture = fixture(fault);
        RiskJdbcTransactionOwner.Failure failure = failure(() -> fixture.owner().executeOwned(() -> { throw secretRuntime(); }));
        assertThat(failure.outcome()).isEqualTo(RiskJdbcTransactionOwner.Outcome.ROLLBACK_UNKNOWN);
        assertThat(fixture.driver().operations.stream().filter("rollback"::equals).count()).isEqualTo(1);
        assertThat(fixture.driver().operations).doesNotContain("commit", "setAutoCommit:true", "setIsolation:8", "close");
        assertSafe(failure);
        assertUnbound(fixture);
    }

    @ParameterizedTest @EnumSource(value = Fault.class, names = {"RESET_AUTO_COMMIT", "RESET_ISOLATION", "CLOSE"})
    void committedOutcomeSurvivesCleanupFailureAndNeverBecomesOrdinarySuccess(Fault fault) throws Exception {
        Fixture fixture = fixture(fault);
        RiskJdbcTransactionOwner.Failure failure = failure(() -> fixture.owner().executeOwned(() -> "done"));
        assertThat(failure.outcome()).isEqualTo(RiskJdbcTransactionOwner.Outcome.COMMITTED);
        assertThat(failure.cleanup()).isEqualTo(fault == Fault.CLOSE
                ? RiskJdbcTransactionOwner.Cleanup.CLOSE_UNKNOWN : RiskJdbcTransactionOwner.Cleanup.FAILED);
        assertThat(fixture.driver().operations.stream().filter("commit"::equals).count()).isEqualTo(1);
        assertThat(fixture.driver().operations).doesNotContain("rollback");
        assertThat(fixture.driver().operations.stream().filter("close"::equals).count()).isEqualTo(fault == Fault.CLOSE ? 1 : 0);
        if (fault == Fault.CLOSE) assertThat(fixture.driver().returned).isTrue();
        assertSafe(failure);
        assertUnbound(fixture);
    }

    @Test void callbackRuntimeAndErrorAreSanitizedBeforeRealSpringRollback() throws Exception {
        for (boolean error : List.of(false, true)) {
            Fixture fixture = fixture(Fault.NONE);
            RiskJdbcTransactionOwner.Failure failure = failure(() -> fixture.owner().executeOwned(() -> {
                if (error) throw new AssertionError(SECRET, secretRuntime());
                throw secretRuntime();
            }));
            assertThat(failure.outcome()).isEqualTo(RiskJdbcTransactionOwner.Outcome.ROLLED_BACK);
            assertThat(failure.cleanup()).isEqualTo(RiskJdbcTransactionOwner.Cleanup.COMPLETE);
            assertThat(fixture.driver().operations).containsSubsequence("rollback", "setAutoCommit:true", "setIsolation:8", "close");
            assertThat(fixture.driver().operations).doesNotContain("commit");
            assertSafe(failure);
            assertUnbound(fixture);
        }
    }

    @Test void forbiddenBusinessCommitIsRejectedBeforeDelegateAndDefiniteRollbackRemainsPossible() throws Exception {
        Fixture fixture = fixture(Fault.NONE);
        RiskJdbcTransactionOwner.Failure failure = failure(() -> fixture.owner().executeOwned(() -> {
            Connection connection = bound(fixture);
            assertThatThrownBy(connection::commit).isInstanceOf(SQLException.class);
            return "body caught refusal";
        }));
        assertThat(failure.outcome()).isEqualTo(RiskJdbcTransactionOwner.Outcome.ROLLED_BACK);
        assertThat(failure.cleanup()).isEqualTo(RiskJdbcTransactionOwner.Cleanup.COMPLETE);
        assertThat(fixture.driver().operations).doesNotContain("commit");
        assertThat(fixture.driver().operations).containsSubsequence("rollback", "setAutoCommit:true", "setIsolation:8", "close");
        assertUnbound(fixture);
    }

    @Test void everyUnsupportedConnectionSignatureRefusesBeforeDriverInvocation() throws Exception {
        for (var method : Connection.class.getMethods()) {
            String name = method.getName();
            if ((method.getParameterCount() == 0 && List.of("getAutoCommit", "isReadOnly", "getTransactionIsolation", "isClosed").contains(name))
                    || name.equals("isWrapperFor")) continue;
            Fixture fixture = fixture(Fault.NONE);
            RiskJdbcTransactionOwner.Failure failure = failure(() -> fixture.owner().executeOwned(() -> {
                Object[] arguments = new Object[method.getParameterCount()];
                Class<?>[] types = method.getParameterTypes();
                for (int i = 0; i < types.length; i++) arguments[i] = defaultValue(types[i]);
                int before = fixture.driver().operations.size();
                assertThatThrownBy(() -> method.invoke(bound(fixture), arguments))
                        .isInstanceOf(InvocationTargetException.class)
                        .cause().isInstanceOf(SQLException.class);
                assertThat(fixture.driver().operations).hasSize(before);
                return null;
            }));
            assertThat(failure.outcome()).as(method.toGenericString()).isEqualTo(RiskJdbcTransactionOwner.Outcome.ROLLED_BACK);
            assertUnbound(fixture);
        }
    }

    @Test void wrongThreadCannotUseEscapedControlPermit() throws Exception {
        Fixture fixture = fixture(Fault.NONE);
        AtomicReference<Throwable> observed = new AtomicReference<>();
        RiskJdbcTransactionOwner.Failure failure = failure(() -> fixture.owner().executeOwned(() -> {
            Connection connection = bound(fixture);
            Thread thread = new Thread(() -> {
                try { connection.commit(); }
                catch (Throwable denied) { observed.set(denied); }
            });
            thread.start();
            try { thread.join(5000); } catch (InterruptedException interrupted) { throw new AssertionError(interrupted); }
            assertThat(thread.isAlive()).isFalse();
            assertThat(observed.get()).isInstanceOf(SQLException.class);
            return null;
        }));
        assertThat(failure.outcome()).isEqualTo(RiskJdbcTransactionOwner.Outcome.ROLLED_BACK);
        assertThat(fixture.driver().operations).doesNotContain("commit");
        assertUnbound(fixture);
    }

    @Test void bothExpiredClientInfoOverloadsUseDeclaredSafeException() throws Exception {
        Fixture fixture = fixture(Fault.NONE);
        AtomicReference<Connection> previous = new AtomicReference<>();
        fixture.owner().executeOwned(() -> { previous.set(bound(fixture)); return null; });
        List<String> before = List.copyOf(fixture.driver().operations);
        assertThatThrownBy(() -> previous.get().setClientInfo("key", SECRET))
                .isExactlyInstanceOf(java.sql.SQLClientInfoException.class).satisfies(RiskJdbcTransactionOwnerTest::assertSafe);
        assertThatThrownBy(() -> previous.get().setClientInfo(new java.util.Properties()))
                .isExactlyInstanceOf(java.sql.SQLClientInfoException.class).satisfies(RiskJdbcTransactionOwnerTest::assertSafe);
        assertThat(fixture.driver().operations).containsExactlyElementsOf(before);
        assertUnbound(fixture);
    }

    @Test void bothWrongThreadClientInfoOverloadsUseDeclaredSafeExceptionBeforeDriver() throws Exception {
        Fixture fixture = fixture(Fault.NONE);
        List<Throwable> observed = new ArrayList<>();
        RiskJdbcTransactionOwner.Failure failure = failure(() -> fixture.owner().executeOwned(() -> {
            Connection connection = bound(fixture);
            Thread thread = new Thread(() -> {
                try { connection.setClientInfo("key", SECRET); } catch (Throwable rejected) { observed.add(rejected); }
                try { connection.setClientInfo(new java.util.Properties()); } catch (Throwable rejected) { observed.add(rejected); }
            });
            thread.start();
            try { thread.join(5000); } catch (InterruptedException interrupted) { throw new AssertionError(interrupted); }
            assertThat(thread.isAlive()).isFalse();
            assertThat(observed).hasSize(2).allSatisfy(rejected -> {
                assertThat(rejected).isExactlyInstanceOf(java.sql.SQLClientInfoException.class);
                assertSafe(rejected);
            });
            return null;
        }));
        assertThat(failure.outcome()).isEqualTo(RiskJdbcTransactionOwner.Outcome.ROLLED_BACK);
        assertThat(fixture.driver().operations).containsExactly("acquire", "getAutoCommit", "isReadOnly", "getIsolation", "setIsolation:2",
                "setAutoCommit:false", "rollback", "setAutoCommit:true", "setIsolation:8", "close");
        assertUnbound(fixture);
    }

    @Test void everyProvenBaselineIsReadBeforeMutationAndRestoredAfterKnownAcknowledgment() throws Exception {
        for (boolean autoCommit : List.of(false, true)) {
            for (boolean readOnly : List.of(false, true)) {
                for (int isolation : List.of(Connection.TRANSACTION_READ_COMMITTED, Connection.TRANSACTION_SERIALIZABLE)) {
                    Fixture fixture = fixture(Fault.NONE, autoCommit, isolation);
                    var readOnlyState = new java.util.concurrent.atomic.AtomicBoolean(readOnly);
                    doAnswer(call -> { fixture.driver.operations.add("isReadOnly"); return readOnlyState.get(); }).when(fixture.connection).isReadOnly();
                    doAnswer(call -> {
                        boolean value = call.getArgument(0);
                        fixture.driver.operations.add("setReadOnly:" + value); readOnlyState.set(value); return null;
                    }).when(fixture.connection).setReadOnly(anyBoolean());
                    fixture.owner.executeOwned(() -> {
                        assertThat(readOnlyState.get()).isFalse();
                        assertThat(fixture.driver.autoCommit).isFalse();
                        assertThat(fixture.driver.isolation).isEqualTo(Connection.TRANSACTION_READ_COMMITTED);
                        return null;
                    });
                    assertThat(fixture.driver.operations.subList(0, 4)).containsExactly("acquire", "getAutoCommit", "isReadOnly", "getIsolation");
                    if (readOnly) assertThat(fixture.driver.operations.indexOf("setReadOnly:false")).isEqualTo(4);
                    assertThat(readOnlyState.get()).isEqualTo(readOnly);
                    assertThat(fixture.driver.autoCommit).isEqualTo(autoCommit);
                    assertThat(fixture.driver.isolation).isEqualTo(isolation);
                    assertUnbound(fixture);
                }
            }
        }
    }

    @Test void anyPreboundHolderIsRefusedWithNoOwnerActionsAndNoForeignMutation() throws Exception {
        Fixture fixture = fixture(Fault.NONE);
        Connection foreignConnection = mock(Connection.class);
        ConnectionHolder foreign = new ConnectionHolder(foreignConnection);
        TransactionSynchronizationManager.bindResource(fixture.source(), foreign);
        try {
            RiskJdbcTransactionOwner.Failure failure = failure(() -> fixture.owner().executeOwned(() -> { throw new AssertionError("body"); }));
            assertThat(failure.outcome()).isEqualTo(RiskJdbcTransactionOwner.Outcome.NOT_STARTED);
            assertThat(TransactionSynchronizationManager.getResource(fixture.source())).isSameAs(foreign);
            verifyNoInteractions(fixture.source(), fixture.connection(), foreignConnection);
        } finally { assertThat(TransactionSynchronizationManager.unbindResource(fixture.source())).isSameAs(foreign); }
    }

    @Test void foreignHolderSubstitutionIsPreservedAndOwnLeaseRetainedWithoutOwnerActions() throws Exception {
        Fixture fixture = fixture(Fault.NONE);
        Connection foreignConnection = mock(Connection.class);
        ConnectionHolder foreign = new ConnectionHolder(foreignConnection);
        try {
            RiskJdbcTransactionOwner.Failure failure = failure(() -> fixture.owner().executeOwned(() -> {
                TransactionSynchronizationManager.unbindResource(fixture.source());
                TransactionSynchronizationManager.bindResource(fixture.source(), foreign);
                return null;
            }));
            assertThat(failure.outcome()).isEqualTo(RiskJdbcTransactionOwner.Outcome.ACTIVE);
            assertThat(failure.cleanup()).isEqualTo(RiskJdbcTransactionOwner.Cleanup.FAILED);
            assertThat(TransactionSynchronizationManager.getResource(fixture.source())).isSameAs(foreign);
            assertThat(fixture.driver().operations).doesNotContain("commit", "rollback", "setAutoCommit:true", "setIsolation:8", "close");
            verifyNoInteractions(foreignConnection);
        } finally { assertThat(TransactionSynchronizationManager.unbindResource(fixture.source())).isSameAs(foreign); }
    }

    @Test void existingSynchronizationScopeIsRefusedWithoutChangingItsCallbacks() throws Exception {
        Fixture fixture = fixture(Fault.NONE);
        var synchronization = mock(org.springframework.transaction.support.TransactionSynchronization.class);
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.registerSynchronization(synchronization);
        try {
            RiskJdbcTransactionOwner.Failure failure = failure(() -> fixture.owner().executeOwned(() -> { throw new AssertionError("body"); }));
            assertThat(failure.outcome()).isEqualTo(RiskJdbcTransactionOwner.Outcome.NOT_STARTED);
            assertThat(TransactionSynchronizationManager.getSynchronizations()).containsExactly(synchronization);
            verifyNoInteractions(fixture.source(), fixture.connection(), synchronization);
        } finally { TransactionSynchronizationManager.clearSynchronization(); }
    }

    @Test void nestedExecutionRefusesBeforeBorrowingAgain() throws Exception {
        Fixture fixture = fixture(Fault.NONE);
        String result = fixture.owner().executeOwned(() -> {
            assertThatThrownBy(() -> fixture.owner().executeOwned(() -> "nested")).isInstanceOf(RiskJdbcTransactionOwner.Failure.class);
            return "outer";
        });
        assertThat(result).isEqualTo("outer");
        verify(fixture.source(), times(1)).getConnection();
        assertUnbound(fixture);
    }

    static Connection bound(Fixture fixture) {
        return ((ConnectionHolder) TransactionSynchronizationManager.getResource(fixture.source())).getConnection();
    }
    static void assertUnbound(Fixture fixture) {
        assertThat(TransactionSynchronizationManager.hasResource(fixture.source())).isFalse();
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
    }
    static RiskJdbcTransactionOwner.Failure failure(Runnable action) {
        try { action.run(); throw new AssertionError("Expected safe failure"); }
        catch (RiskJdbcTransactionOwner.Failure failure) { return failure; }
    }
    static void assertSafe(Throwable error) {
        for (Throwable cursor = error; cursor != null; cursor = cursor.getCause()) {
            assertThat(cursor.getMessage()).doesNotContain(SECRET);
            assertThat(cursor.getSuppressed()).isEmpty();
            if (cursor instanceof SQLException sql) {
                assertThat((Object) sql.getNextException()).isNull();
                assertThat(sql.getSQLState() == null || sql.getSQLState().matches("[0-9A-Z]{5}")).isTrue();
            }
        }
    }
    static SQLException secretSql() {
        SQLException error = new SQLException(SECRET, "XX000", 91, new IllegalStateException(SECRET + " cause"));
        error.addSuppressed(new IllegalArgumentException(SECRET + " suppressed"));
        error.setNextException(new SQLException(SECRET + " next", SECRET));
        return error;
    }
    static RuntimeException secretRuntime() { return new IllegalStateException(SECRET, secretSql()); }
    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == short.class) return (short) 0;
        if (type == byte.class) return (byte) 0;
        if (type == float.class) return 0F;
        if (type == double.class) return 0D;
        if (type == char.class) return (char) 0;
        throw new AssertionError(type);
    }
    record Fixture(DataSource source, Connection connection, RiskJdbcTransactionOwner owner, Driver driver) { }
    static Fixture fixture(Fault fault) throws Exception {
        return fixture(fault, true, Connection.TRANSACTION_SERIALIZABLE);
    }
    static Fixture fixture(Fault fault, boolean autoCommit, int isolation) throws Exception {
        Driver driver = new Driver(fault, autoCommit, isolation);
        Connection connection = mock(Connection.class, invocation -> driver.invoke(invocation.getMethod().getName(), invocation.getArguments()));
        DataSource source = mock(DataSource.class);
        when(source.getConnection()).thenAnswer(invocation -> {
            driver.operations.add("acquire");
            if (fault == Fault.ACQUIRE) throw secretSql();
            return connection;
        });
        clearInvocations(source, connection);
        return new Fixture(source, connection, RiskJdbcTransactionOwner.create(source), driver);
    }
    static final class Driver {
        final Fault fault;
        final List<String> operations = new ArrayList<>();
        boolean autoCommit = true;
        int isolation = Connection.TRANSACTION_SERIALIZABLE;
        boolean commitApplied;
        boolean returned;
        Driver(Fault fault) { this(fault, true, Connection.TRANSACTION_SERIALIZABLE); }
        Driver(Fault fault, boolean autoCommit, int isolation) {
            this.fault = fault; this.autoCommit = autoCommit; this.isolation = isolation;
        }
        Object invoke(String name, Object[] args) throws Throwable {
            return switch (name) {
                case "toString" -> SECRET + " connection";
                case "getTransactionIsolation" -> { operations.add("getIsolation"); if (fault == Fault.GET_ISOLATION) throw secretSql(); yield isolation; }
                case "getAutoCommit" -> { operations.add("getAutoCommit"); if (fault == Fault.GET_AUTO_COMMIT) throw secretSql(); yield autoCommit; }
                case "isReadOnly" -> { operations.add("isReadOnly"); if (fault == Fault.GET_READ_ONLY) throw secretSql(); yield false; }
                case "setTransactionIsolation" -> {
                    int value = (Integer) args[0]; operations.add("setIsolation:" + value); isolation = value;
                    if ((value == Connection.TRANSACTION_READ_COMMITTED && fault == Fault.SET_ISOLATION)
                            || (value == Connection.TRANSACTION_SERIALIZABLE && fault == Fault.RESET_ISOLATION)) throw secretSql();
                    yield null;
                }
                case "setAutoCommit" -> {
                    boolean value = (Boolean) args[0]; operations.add("setAutoCommit:" + value); autoCommit = value;
                    if ((!value && fault == Fault.SET_AUTO_COMMIT_FALSE) || (value && fault == Fault.RESET_AUTO_COMMIT)) throw secretSql();
                    yield null;
                }
                case "commit" -> {
                    operations.add("commit"); commitApplied = true;
                    if (fault == Fault.COMMIT_SQL) throw secretSql();
                    if (fault == Fault.COMMIT_RUNTIME) throw secretRuntime();
                    if (fault == Fault.COMMIT_ERROR) throw new AssertionError(SECRET, secretSql());
                    yield null;
                }
                case "rollback" -> {
                    operations.add("rollback");
                    if (fault == Fault.ROLLBACK_SQL) throw secretSql();
                    if (fault == Fault.ROLLBACK_RUNTIME) throw secretRuntime();
                    if (fault == Fault.ROLLBACK_ERROR) throw new AssertionError(SECRET, secretSql());
                    yield null;
                }
                case "close" -> { operations.add("close"); returned = true; if (fault == Fault.CLOSE) throw secretSql(); yield null; }
                default -> throw new AssertionError("Unexpected driver operation " + name);
            };
        }
    }
}
