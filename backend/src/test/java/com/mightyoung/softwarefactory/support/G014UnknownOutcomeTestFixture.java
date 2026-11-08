package com.mightyoung.softwarefactory.support;

import com.mightyoung.softwarefactory.authorization.EntityActionAuthorization;
import com.mightyoung.softwarefactory.authorization.RegisteredEntityAction;
import com.mightyoung.softwarefactory.identity.HumanSessionAuthorityTransaction;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import javax.sql.DataSource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Actual owner/gate/TransactionTemplate with a stateful synthetic JDBC driver; no database or HTTP authorization proof. */
public final class G014UnknownOutcomeTestFixture {
    public enum CleanupFault { RESET, CLOSE }
    private G014UnknownOutcomeTestFixture() { }

    public static G014JdbcTransactionManager.LifecycleException failure(
            G014JdbcTransactionManager.LifecycleOutcome outcome) throws SQLException {
        if (outcome != G014JdbcTransactionManager.LifecycleOutcome.COMMIT_UNKNOWN
                && outcome != G014JdbcTransactionManager.LifecycleOutcome.ROLLBACK_UNKNOWN)
            throw new IllegalArgumentException("The fixture requires an unknown outcome");
        return exercise(outcome, null);
    }

    public static G014JdbcTransactionManager.LifecycleException committedCleanupFailure(CleanupFault fault) throws SQLException {
        return exercise(G014JdbcTransactionManager.LifecycleOutcome.COMMITTED_CLEANUP_FAILED, Objects.requireNonNull(fault));
    }

    private static G014JdbcTransactionManager.LifecycleException exercise(
            G014JdbcTransactionManager.LifecycleOutcome outcome, CleanupFault fault) throws SQLException {
        var pool = mock(DataSource.class);
        var driver = mock(Connection.class);
        var state = new DriverState();
        when(pool.getConnection()).thenReturn(driver);
        when(driver.getAutoCommit()).thenAnswer(invocation -> state.autoCommit);
        when(driver.isReadOnly()).thenAnswer(invocation -> state.readOnly);
        when(driver.getTransactionIsolation()).thenAnswer(invocation -> state.isolation);
        doAnswer(invocation -> {
            state.autoCommit = invocation.getArgument(0);
            if (state.autoCommit && state.committed && fault == CleanupFault.RESET)
                throw new SQLException("PRIVATE committed reset detail", "08006");
            return null;
        }).when(driver).setAutoCommit(anyBoolean());
        doAnswer(invocation -> { state.readOnly = invocation.getArgument(0); return null; })
                .when(driver).setReadOnly(anyBoolean());
        doAnswer(invocation -> { state.isolation = invocation.getArgument(0); return null; })
                .when(driver).setTransactionIsolation(anyInt());
        doAnswer(invocation -> {
            if (outcome == G014JdbcTransactionManager.LifecycleOutcome.COMMIT_UNKNOWN)
                throw new SQLException("PRIVATE driver commit acknowledgment", "08006");
            state.committed = true;
            return null;
        }).when(driver).commit();
        doAnswer(invocation -> {
            if (outcome == G014JdbcTransactionManager.LifecycleOutcome.ROLLBACK_UNKNOWN)
                throw new SQLException("PRIVATE driver rollback acknowledgment", "08006");
            return null;
        }).when(driver).rollback();
        doAnswer(invocation -> {
            state.returned = true;
            if (fault == CleanupFault.CLOSE) throw new SQLException("PRIVATE close after return detail", "08006");
            return null;
        }).when(driver).close();

        var manager = new G014JdbcTransactionManager(pool, List.of());
        var template = new TransactionTemplate(manager);
        template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        var authority = mock(HumanSessionAuthorityTransaction.class);
        var actions = mock(EntityActionAuthorization.class);
        var scopes = mock(EntityActionAuthorization.FinalActionScopes.class);
        when(scopes.observedAt()).thenReturn(Instant.parse("2026-11-07T00:00:00Z"));
        when(actions.requireFinalCommand(any(), any(), any())).thenReturn(scopes);
        var validator = mock(TransactionSqlSealGate.RegisteredCommandValidator.class);
        var action = RegisteredEntityAction.WORKSHOP_CREATE;
        when(validator.requiredActions(any())).thenReturn(Set.of(action));
        when(authority.withGuarded(any(), any())).thenAnswer(invocation -> {
            Function<HumanSessionAuthorityTransaction.GuardedBoundary, Object> work = invocation.getArgument(1);
            Object result = work.apply(mock(HumanSessionAuthorityTransaction.GuardedBoundary.class));
            var connection = manager.currentFrame().jdbc.connection;
            connection.getAutoCommit();
            connection.isReadOnly();
            connection.getTransactionIsolation();
            return result;
        });
        var gate = new TransactionSqlSealGate(manager, authority, actions, Map.of(action, validator));
        var guarded = new AtomicReference<G014JdbcGuard>();
        Throwable thrown = catchThrowable(() -> template.execute(status -> {
            guarded.set(manager.currentFrame().jdbc);
            if (outcome == G014JdbcTransactionManager.LifecycleOutcome.ROLLBACK_UNKNOWN)
                throw new IllegalStateException("PRIVATE callback failure");
            var plan = mock(TransactionSqlSealGate.CommandPlan.class);
            when(plan.commandId()).thenReturn(UUID.randomUUID());
            when(plan.targetId()).thenReturn(UUID.randomUUID());
            when(plan.action()).thenReturn(action);
            var scope = mock(HumanSessionAuthorityTransaction.WriteScope.class);
            when(scope.guard()).thenReturn(HumanSessionAuthorityTransaction.Guard.FINDING_COMMAND);
            gate.bindCommand(plan, scope);
            gate.finalizeCommand();
            return null;
        }));
        assertThat(thrown).isExactlyInstanceOf(G014JdbcTransactionManager.LifecycleException.class);
        var failure = (G014JdbcTransactionManager.LifecycleException) thrown;
        assertThat(failure.outcome()).isEqualTo(outcome);
        assertThat(failure.getCause()).isNull();
        assertThat(failure.getSuppressed()).isEmpty();
        assertThat(manager.retainedUnusableLeaseCount()).isEqualTo(1);
        assertThat(TransactionSynchronizationManager.hasResource(manager.commandDataSource())).isFalse();
        if (outcome == G014JdbcTransactionManager.LifecycleOutcome.COMMITTED_CLEANUP_FAILED) {
            assertThat(state.committed).isTrue();
            assertThat(guarded.get().phase()).isEqualTo(G014JdbcGuard.Phase.COMMITTED);
            verify(driver).commit();
            verify(driver, never()).rollback();
            verify(driver).setAutoCommit(true);
            if (fault == CleanupFault.RESET) {
                verify(driver, never()).close();
                assertThat(state.returned).isFalse();
            } else {
                verify(driver).close();
                assertThat(state.returned).isTrue();
            }
        } else {
            verify(driver, never()).setAutoCommit(true);
            verify(driver, never()).close();
            if (outcome == G014JdbcTransactionManager.LifecycleOutcome.COMMIT_UNKNOWN) {
                verify(driver).commit();
                verify(driver, never()).rollback();
            } else {
                verify(driver, never()).commit();
                verify(driver).rollback();
            }
        }
        return failure;
    }

    private static final class DriverState {
        boolean autoCommit = true;
        boolean readOnly;
        int isolation = Connection.TRANSACTION_READ_COMMITTED;
        boolean committed;
        boolean returned;
    }
}
