package com.mightyoung.softwarefactory.platformadmin.digitaltwin;

import com.mightyoung.softwarefactory.identity.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

import static com.mightyoung.softwarefactory.identity.SessionSensitiveContracts.Enforcement.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StudioGuardedPublishTest {
    final DataSource source = mock(DataSource.class);
    final Connection connection = mock(Connection.class);
    final CurrentHumanSessionAuthority human = mock(CurrentHumanSessionAuthority.class);
    final HumanSessionAuthorityTransaction callers = mock(HumanSessionAuthorityTransaction.class);
    final CapturedHumanRequest request = mock(CapturedHumanRequest.class);
    final HumanSessionAuthorityTransaction.GuardedBoundary boundary = mock(HumanSessionAuthorityTransaction.GuardedBoundary.class);
    final CheckedHumanSessionAuthority checked = mock(CheckedHumanSessionAuthority.class);
    final CurrentTenantContext context = new CurrentTenantContext(UUID.randomUUID(), UUID.randomUUID(),
            UUID.randomUUID(), UUID.randomUUID(), 1, 1, 1);

    @Test void absentParentFailsWithoutOpeningConnection() {
        assertThrows(SessionSensitiveException.class,
                () -> new StudioGuardedPublish(source, callers, human).run(null, null, authorized -> fail()));
        verifyNoInteractions(source, callers, human);
    }

    @Test void checkedHumanAndCapabilityPrecedeCallbackOnExactBorrowedConnection() throws Exception {
        var scope = prepare();
        when(checked.enforcement()).thenReturn(ENFORCING);
        var statement = mock(PreparedStatement.class);
        var rows = mock(ResultSet.class);
        when(connection.prepareStatement(StudioAuthorityQueries.PUBLISH_SQL)).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(rows);
        when(rows.next()).thenReturn(true, false);
        when(rows.getBoolean(1)).thenReturn(true);
        var publication = mock(Runnable.class);
        inParent(() -> assertEquals("done", new StudioGuardedPublish(source, callers, human).run(scope, target(), authorized -> {
            var caller = authorized.caller();
            assertSame(connection, authorized.connection());
            assertEquals(context.actorId(), caller.actorId());
            assertEquals(context.memberId(), caller.memberId());
            publication.run();
            return "done";
        })));
        var order = inOrder(callers, human, checked, connection, statement, publication);
        order.verify(callers).requireGuarded(scope, boundary, source, connection);
        order.verify(human).requireGuardedCheckedCurrent(scope, boundary);
        order.verify(checked).enforcement();
        order.verify(connection).prepareStatement(StudioAuthorityQueries.PUBLISH_SQL);
        order.verify(statement).setObject(1, context.tenantId());
        order.verify(statement).setObject(2, context.memberId());
        order.verify(statement).executeQuery();
        order.verify(publication).run();
        verifyNoInteractions(source);
        verify(connection, never()).close();
    }

    @Test void offSessionDoesNotQueryOrInvokeCallback() {
        var scope = prepare();
        when(checked.enforcement()).thenReturn(OFF);
        inParent(() -> assertThrows(SessionSensitiveException.class,
                () -> new StudioGuardedPublish(source, callers, human).run(scope, target(), authorized -> fail())));
        verifyNoInteractions(connection);
    }

    @Test void differentPhysicalConnectionIsRejectedBeforeSessionOrCapability() {
        var scope = prepare();
        var owned = mock(Connection.class);
        doAnswer(call -> {
            if (call.getArgument(3) != owned) throw SessionSensitiveException.unavailable();
            return null;
        }).when(callers).requireGuarded(eq(scope), eq(boundary), same(source), any());
        inParent(() -> assertThrows(SessionSensitiveException.class,
                () -> new StudioGuardedPublish(source, callers, human).run(scope, target(), authorized -> fail())));
        verifyNoInteractions(connection, human);
    }

    @Test void staleScopeDoesNotQueryOrInvokeCallback() {
        var scope = prepare();
        when(human.requireGuardedCheckedCurrent(scope, boundary)).thenThrow(SessionSensitiveException.rejected());
        inParent(() -> assertThrows(SessionSensitiveException.class,
                () -> new StudioGuardedPublish(source, callers, human).run(scope, target(), authorized -> fail())));
        verifyNoInteractions(connection);
    }

    @Test void revokedStudioCapabilityDoesNotPublishAndPoisonsOwnerTransaction() throws Exception {
        var scope = prepare();
        when(checked.enforcement()).thenReturn(ENFORCING);
        var statement = mock(PreparedStatement.class);
        var rows = mock(ResultSet.class);
        when(connection.prepareStatement(StudioAuthorityQueries.PUBLISH_SQL)).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(rows);
        when(rows.next()).thenReturn(true, false);
        inParent(() -> {
            assertThrows(StudioPublicationPolicy.Forbidden.class,
                    () -> new StudioGuardedPublish(source, callers, human).run(scope, target(), authorized -> fail()));
            var holder = (ConnectionHolder) TransactionSynchronizationManager.getResource(source);
            assertTrue(holder.isRollbackOnly());
        });
    }

    @Test void studioAuditUsesScopedActorAndCannotBeVerifiedWithWrongIdOrAfterCallback() throws Exception {
        var scope = prepare();
        when(checked.enforcement()).thenReturn(ENFORCING);
        var capability = mock(PreparedStatement.class);
        var allowed = mock(ResultSet.class);
        when(connection.prepareStatement(StudioAuthorityQueries.PUBLISH_SQL)).thenReturn(capability);
        when(capability.executeQuery()).thenReturn(allowed);
        when(allowed.next()).thenReturn(true, false);
        when(allowed.getBoolean(1)).thenReturn(true);
        var insert = mock(PreparedStatement.class);
        when(connection.prepareStatement(StudioPublicationAudit.INSERT_SQL)).thenReturn(insert);
        when(insert.executeUpdate()).thenReturn(1);
        var id = UUID.randomUUID();
        var record = new StudioPublicationAudit.Record(id, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), target());
        var escaped = new java.util.concurrent.atomic.AtomicReference<StudioGuardedPublish.Authorized>();
        var audit = new StudioPublicationAudit();
        inParent(() -> {
            assertThrows(SessionSensitiveException.class, () -> new StudioGuardedPublish(source, callers, human).run(scope, target(), authorized -> {
                escaped.set(authorized);
                var receipt = audit.append(authorized, record);
                assertEquals(id, audit.requireMatches(authorized, record, receipt));
                var wrong = new StudioPublicationAudit.Record(UUID.randomUUID(), record.streamId(), record.commandId(), record.traceId(), target());
                assertThrows(SessionSensitiveException.class, () -> audit.requireMatches(authorized, wrong, receipt));
                return null;
            }));
            assertThrows(SessionSensitiveException.class, () -> audit.append(escaped.get(), record));
        });
        verify(insert).setObject(1, context.tenantId());
        verify(insert).setObject(2, id);
        verify(insert).setObject(3, context.actorId());
        verify(insert).setObject(4, record.streamId());
        verify(insert, times(1)).executeUpdate();
    }

    private StudioPublicationPolicy.Target target() { return new StudioPublicationPolicy.Target(context.tenantId(), "floor"); }

    private HumanSessionAuthorityTransaction.WriteScope prepare() {
        when(request.context()).thenReturn(context);
        var scope = new HumanSessionAuthorityTransaction.WriteScope(request,
                HumanSessionAuthorityTransaction.Guard.FINDING_COMMAND, List.of(context.tenantId()));
        when(callers.withGuarded(eq(scope), any())).thenAnswer(call -> call.<Function<HumanSessionAuthorityTransaction.GuardedBoundary, ?>>getArgument(1).apply(boundary));
        when(human.requireGuardedCheckedCurrent(scope, boundary)).thenReturn(checked);
        when(checked.observedAt(scope, boundary)).thenReturn(Instant.EPOCH);
        return scope;
    }

    private void inParent(Runnable test) {
        var holder = new ConnectionHolder(connection);
        holder.setSynchronizedWithTransaction(true);
        TransactionSynchronizationManager.bindResource(source, holder);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
        try { test.run(); }
        finally {
            TransactionSynchronizationManager.unbindResource(source);
            TransactionSynchronizationManager.clear();
        }
    }
}
