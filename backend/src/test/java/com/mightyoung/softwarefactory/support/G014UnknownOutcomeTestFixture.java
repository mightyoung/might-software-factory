package com.mightyoung.softwarefactory.support;

import com.mightyoung.softwarefactory.authorization.EntityActionAuthorization;
import com.mightyoung.softwarefactory.authorization.RegisteredEntityAction;
import com.mightyoung.softwarefactory.identity.HumanSessionAuthorityTransaction;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import javax.sql.DataSource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Actual owner/gate/TransactionTemplate with a synthetic JDBC driver; no database or HTTP authorization proof. */
public final class G014UnknownOutcomeTestFixture {
    private G014UnknownOutcomeTestFixture() { }

    public static G014JdbcTransactionManager.LifecycleException failure(
            G014JdbcTransactionManager.LifecycleOutcome outcome) throws SQLException {
        var pool = mock(DataSource.class);
        var driver = mock(Connection.class);
        when(pool.getConnection()).thenReturn(driver);
        when(driver.getAutoCommit()).thenReturn(true, false);
        when(driver.isReadOnly()).thenReturn(false);
        when(driver.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_READ_COMMITTED);
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
        if (outcome == G014JdbcTransactionManager.LifecycleOutcome.COMMIT_UNKNOWN)
            doThrow(new SQLException("PRIVATE driver commit acknowledgment", "08006")).when(driver).commit();
        else
            doThrow(new SQLException("PRIVATE driver rollback acknowledgment", "08006")).when(driver).rollback();
        Throwable thrown = catchThrowable(() -> template.execute(status -> {
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
        verify(driver, never()).setAutoCommit(true);
        verify(driver, never()).close();
        if (outcome == G014JdbcTransactionManager.LifecycleOutcome.COMMIT_UNKNOWN) {
            verify(driver).commit();
            verify(driver, never()).rollback();
        } else {
            verify(driver, never()).commit();
            verify(driver).rollback();
        }
        return failure;
    }
}
