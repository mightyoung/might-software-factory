package com.mightyoung.softwarefactory.platformadmin.digitaltwin;

import com.mightyoung.softwarefactory.identity.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;

import static com.mightyoung.softwarefactory.identity.SessionSensitiveContracts.Enforcement.*;
import static com.mightyoung.softwarefactory.platformadmin.digitaltwin.StudioPublicationPolicy.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StudioGuardedPlayerReadTest {
    private final DataSource source = mock(DataSource.class);
    private final Connection connection = mock(Connection.class);
    private final HumanSessionAuthorityTransaction callers = mock(HumanSessionAuthorityTransaction.class);
    private final CurrentHumanSessionAuthority human = mock(CurrentHumanSessionAuthority.class);
    private final CapturedHumanRequest request = mock(CapturedHumanRequest.class);
    private final HumanSessionAuthorityTransaction.GuardedBoundary boundary = mock(HumanSessionAuthorityTransaction.GuardedBoundary.class);
    private final CheckedHumanSessionAuthority checked = mock(CheckedHumanSessionAuthority.class);
    private final CurrentTenantContext context = new CurrentTenantContext(UUID.randomUUID(), UUID.randomUUID(),
            UUID.randomUUID(), UUID.randomUUID(), 1, 1, 1);
    private final Target target = new Target(context.tenantId(), "floor");
    private final StudioGuardedPlayerRead reader = new StudioGuardedPlayerRead(source, callers, human);

    @Test void absentParentAndForeignTenantFailBeforeAnySql() {
        assertThrows(SessionSensitiveException.class, () -> reader.read(null, target, null));
        var scope = scope();
        inParent(holder -> {
            assertThrows(Forbidden.class, () -> reader.read(scope, new Target(UUID.randomUUID(), "floor"), null));
            assertTrue(holder.isRollbackOnly());
        });
        verifyNoInteractions(source, connection, callers, human);
    }

    @Test void guardRejectionStopsBeforeSessionOrBusinessSql() {
        var scope = prepared();
        var other = mock(Connection.class);
        doAnswer(call -> {
            if (call.getArgument(3) != other) throw SessionSensitiveException.unavailable();
            return null;
        }).when(callers).requireGuarded(eq(scope), eq(boundary), same(source), any());
        inParent(holder -> {
            assertThrows(SessionSensitiveException.class, () -> reader.read(scope, target, null));
            assertTrue(holder.isRollbackOnly());
        });
        verifyNoInteractions(source, connection, human);
    }

    @Test void staleSessionStopsBeforeAnyBusinessSql() {
        var scope = prepared();
        when(human.requireGuardedCheckedCurrent(scope, boundary)).thenThrow(SessionSensitiveException.rejected());
        inParent(holder -> {
            assertThrows(SessionSensitiveException.class, () -> reader.read(scope, target, null));
            assertTrue(holder.isRollbackOnly());
        });
        verifyNoInteractions(connection);
    }

    @Test void offEnforcementStopsBeforeAnyBusinessSql() {
        var scope = prepared();
        when(checked.enforcement()).thenReturn(OFF);
        inParent(holder -> {
            assertThrows(SessionSensitiveException.class, () -> reader.read(scope, target, null));
            assertTrue(holder.isRollbackOnly());
        });
        verifyNoInteractions(connection);
    }

    @Test void revokedScreenNeverReadsAnyPublication() throws Exception {
        var scope = prepared();
        screenAuthority(false);
        inParent(holder -> {
            assertThrows(Forbidden.class, () -> reader.read(scope, target, 2L));
            assertTrue(holder.isRollbackOnly());
        });
        verify(connection).prepareStatement(StudioAuthorityQueries.SCREEN_SQL);
        verifyNoMoreInteractions(connection);
    }

    @Test void pinnedMissNeverFallsBackAndFreshProofUsesExactBorrowedConnection() throws Exception {
        var scope = prepared();
        var authority = screenAuthority(true);
        var publication = mock(PreparedStatement.class);
        when(connection.prepareStatement(StudioPublishedLookup.PIN_SQL)).thenReturn(publication);
        when(publication.executeQuery()).thenReturn(mock(ResultSet.class));
        inParent(holder -> {
            assertTrue(reader.read(scope, target, 2L).isEmpty());
            assertFalse(holder.isRollbackOnly());
        });
        var order = inOrder(callers, human, checked, connection, authority, publication);
        order.verify(callers).requireGuarded(scope, boundary, source, connection);
        order.verify(human).requireGuardedCheckedCurrent(scope, boundary);
        order.verify(checked).observedAt(scope, boundary);
        order.verify(connection).prepareStatement(StudioAuthorityQueries.SCREEN_SQL);
        order.verify(authority).setObject(1, context.tenantId());
        order.verify(authority).setObject(2, context.memberId());
        order.verify(authority).setString(3, "floor");
        order.verify(authority).executeQuery();
        order.verify(callers).requireGuarded(scope, boundary, source, connection);
        order.verify(connection).prepareStatement(StudioPublishedLookup.PIN_SQL);
        order.verify(publication).setObject(1, context.tenantId());
        order.verify(publication).setString(2, "floor");
        order.verify(publication).setLong(3, 2L);
        order.verify(publication).executeQuery();
        order.verify(callers).requireGuarded(scope, boundary, source, connection);
        verify(connection, never()).prepareStatement(StudioPublishedLookup.CURRENT_SQL);
        verify(connection, never()).close();
        verifyNoInteractions(source);
    }

    @Test void sqlFailureIsNotAbsenceAndPoisonsParent() throws Exception {
        var scope = prepared();
        screenAuthority(true);
        when(connection.prepareStatement(StudioPublishedLookup.CURRENT_SQL)).thenThrow(new SQLException("internal details"));
        inParent(holder -> {
            var failure = assertThrows(IllegalStateException.class, () -> reader.read(scope, target, null));
            assertFalse(failure.getMessage().contains("internal"));
            assertTrue(holder.isRollbackOnly());
        });
    }

    @Test void invalidatedFinalGuardPreventsReturningAnInternalResult() throws Exception {
        var scope = prepared();
        screenAuthority(true);
        var statement = mock(PreparedStatement.class);
        when(connection.prepareStatement(StudioPublishedLookup.PIN_SQL)).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(mock(ResultSet.class));
        doNothing().doNothing().doThrow(SessionSensitiveException.unavailable())
                .when(callers).requireGuarded(scope, boundary, source, connection);
        inParent(holder -> {
            assertThrows(SessionSensitiveException.class, () -> reader.read(scope, target, 3L));
            assertTrue(holder.isRollbackOnly());
        });
        verify(statement).executeQuery();
    }

    private HumanSessionAuthorityTransaction.WriteScope scope() {
        when(request.context()).thenReturn(context);
        return new HumanSessionAuthorityTransaction.WriteScope(request,
                HumanSessionAuthorityTransaction.Guard.FINDING_COMMAND, List.of(context.tenantId()));
    }

    private HumanSessionAuthorityTransaction.WriteScope prepared() {
        var scope = scope();
        when(callers.withGuarded(eq(scope), any()))
                .thenAnswer(call -> call.<Function<HumanSessionAuthorityTransaction.GuardedBoundary, ?>>getArgument(1).apply(boundary));
        when(human.requireGuardedCheckedCurrent(scope, boundary)).thenReturn(checked);
        when(checked.enforcement()).thenReturn(ENFORCING);
        when(checked.observedAt(scope, boundary)).thenReturn(Instant.EPOCH);
        return scope;
    }

    private PreparedStatement screenAuthority(boolean allowed) throws SQLException {
        var statement = mock(PreparedStatement.class);
        var rows = mock(ResultSet.class);
        when(connection.prepareStatement(StudioAuthorityQueries.SCREEN_SQL)).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(rows);
        when(rows.next()).thenReturn(true, false);
        when(rows.getBoolean(1)).thenReturn(allowed);
        return statement;
    }

    private void inParent(Consumer<ConnectionHolder> test) {
        var holder = new ConnectionHolder(connection);
        holder.setSynchronizedWithTransaction(true);
        TransactionSynchronizationManager.bindResource(source, holder);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
        try { test.accept(holder); }
        finally {
            TransactionSynchronizationManager.unbindResource(source);
            TransactionSynchronizationManager.clear();
        }
    }
}
