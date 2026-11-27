package com.mightyoung.softwarefactory.platformadmin.digitaltwin;

import com.mightyoung.softwarefactory.identity.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
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

    @Test void exactAppendAuditPointerOrderAndNoDecisionBeforeCommit() throws Exception {
        var scope = prepare();
        allowPublication();
        var stream = UUID.randomUUID();
        update(StudioPublicationWrites.CREATE_SQL, 1);
        setupLock(stream, null);
        var append = update(StudioPublicationWrites.APPEND_SQL, 1);
        var audit = update(StudioPublicationAudit.INSERT_SQL, 1);
        var pointer = update(StudioPublicationWrites.POINTER_SQL, 1);
        inParent(() -> {
            var staged = command().stage(scope, target(), 0, sourceBytes());
            assertThrows(IllegalStateException.class, staged::committedDecision);
            org.springframework.transaction.support.TransactionSynchronizationManager.getSynchronizations()
                    .forEach(s -> s.afterCompletion(org.springframework.transaction.support.TransactionSynchronization.STATUS_COMMITTED));
            assertEquals(StudioPublicationPolicy.Outcome.APPEND, staged.committedDecision().outcome());
        });
        var order = inOrder(append, audit, pointer);
        order.verify(append).executeUpdate();
        order.verify(audit).executeUpdate();
        order.verify(pointer).executeUpdate();
        var reserved = org.mockito.ArgumentCaptor.forClass(UUID.class);
        verify(append).setObject(eq(7), reserved.capture());
        verify(audit).setObject(2, reserved.getValue());
        verify(audit).setObject(4, stream);
        verify(audit).setObject(3, context.actorId());
        verify(connection, never()).commit();
    }

    @Test void badInputAndForeignTargetDoNotReachWriteStorage() throws Exception {
        var scope = prepare();
        allowPublication();
        inParent(() -> assertThrows(IllegalArgumentException.class,
                () -> command().stage(scope, target(), 0, new byte[]{1})));
        verify(connection, never()).prepareStatement(StudioPublicationWrites.CREATE_SQL);
        inParent(() -> assertThrows(StudioPublicationPolicy.Forbidden.class,
                () -> command().stage(scope, new StudioPublicationPolicy.Target(UUID.randomUUID(), "floor"), 0, sourceBytes())));
        verify(connection, never()).prepareStatement(StudioPublicationWrites.LOCK_SQL);
    }

    @Test void staleAndUnchangedNeverAuditOrAppend() throws Exception {
        var scope = prepare();
        allowPublication();
        update(StudioPublicationWrites.CREATE_SQL, 0);
        setupLock(UUID.randomUUID(), 2L);
        var digest = StudioDraftDecoder.decode(sourceBytes()).facts().canonicalSha256();
        var lookup = mock(PreparedStatement.class);
        var rows = mock(ResultSet.class);
        when(connection.prepareStatement(StudioPublicationWrites.HEAD_SQL)).thenReturn(lookup);
        when(lookup.executeQuery()).thenReturn(rows);
        when(rows.next()).thenReturn(true, false, true, false);
        when(rows.getString(1)).thenReturn(digest);
        inParent(() -> assertThrows(StudioPublicationPolicy.VersionConflict.class,
                () -> command().stage(scope, target(), 1, sourceBytes())));
        inParent(() -> {
            var staged = command().stage(scope, target(), 2, sourceBytes());
            TransactionSynchronizationManager.getSynchronizations().forEach(s -> s.afterCompletion(1));
            assertThrows(IllegalStateException.class, staged::committedDecision);
        });
        verify(connection, never()).prepareStatement(StudioPublicationAudit.INSERT_SQL);
        verify(connection, never()).prepareStatement(StudioPublicationWrites.APPEND_SQL);
    }

    @Test void failedAuditPoisonsOwnerAndNeverMovesPointer() throws Exception {
        var scope = prepare();
        allowPublication();
        update(StudioPublicationWrites.CREATE_SQL, 1);
        setupLock(UUID.randomUUID(), null);
        update(StudioPublicationWrites.APPEND_SQL, 1);
        update(StudioPublicationAudit.INSERT_SQL, 0);
        inParent(() -> {
            assertThrows(SessionSensitiveException.class, () -> command().stage(scope, target(), 0, sourceBytes()));
            assertTrue(((ConnectionHolder) TransactionSynchronizationManager.getResource(source)).isRollbackOnly());
        });
        verify(connection, never()).prepareStatement(StudioPublicationWrites.POINTER_SQL);
    }

    @Test void publishOwnerBindsVerifiedTenantAndWaitsForActualSpringCommitCall() throws Exception {
        var scope = prepare();
        var guard = mock(com.mightyoung.softwarefactory.authorization.AuthorizationWriteGuard.class);
        var owner = owner(guard);
        var tenantSetting = prepareOwnerConnection();
        allowPublication();
        update(StudioPublicationWrites.CREATE_SQL, 1);
        setupLock(UUID.randomUUID(), null);
        var append = update(StudioPublicationWrites.APPEND_SQL, 1);
        var audit = update(StudioPublicationAudit.INSERT_SQL, 1);
        var pointer = update(StudioPublicationWrites.POINTER_SQL, 1);
        assertEquals(StudioPublicationPolicy.Outcome.APPEND, owner.publish(request, target(), 0, sourceBytes()).outcome());
        var order = inOrder(connection, tenantSetting, guard, callers, append, audit, pointer);
        order.verify(connection).setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        order.verify(tenantSetting).setString(1, context.tenantId().toString());
        order.verify(tenantSetting, times(2)).executeQuery();
        order.verify(guard).forBusinessCommand(context.tenantId());
        order.verify(callers).requireGuarded(scope, boundary, source, connection);
        order.verify(append).executeUpdate();
        order.verify(audit).executeUpdate();
        order.verify(pointer).executeUpdate();
        order.verify(connection).commit();
    }

    @Test void publishOwnerCommitExceptionNeverReturnsDecision() throws Exception {
        prepare();
        var owner = owner(mock(com.mightyoung.softwarefactory.authorization.AuthorizationWriteGuard.class));
        prepareOwnerConnection();
        allowPublication();
        update(StudioPublicationWrites.CREATE_SQL, 1);
        setupLock(UUID.randomUUID(), null);
        update(StudioPublicationWrites.APPEND_SQL, 1);
        update(StudioPublicationAudit.INSERT_SQL, 1);
        update(StudioPublicationWrites.POINTER_SQL, 1);
        doThrow(new java.sql.SQLException("simulated commit failure")).when(connection).commit();
        assertThrows(org.springframework.transaction.TransactionException.class,
                () -> owner.publish(request, target(), 0, sourceBytes()));
        verify(connection).commit();
        assertFalse(TransactionSynchronizationManager.hasResource(source));
    }

    @Test void lostHttpResponseAfterCommitCannotAppendOrAuditAgainAtOriginalVersion() throws Exception {
        prepare();
        var owner = owner(mock(com.mightyoung.softwarefactory.authorization.AuthorizationWriteGuard.class));
        prepareOwnerConnection();
        allowPublication();
        when(update(StudioPublicationWrites.CREATE_SQL, 1).executeUpdate()).thenReturn(1, 0);
        // The mock head advances only when the real Spring transaction manager calls commit.
        var head = new java.util.concurrent.atomic.AtomicReference<Long>();
        var stream = UUID.randomUUID();
        setupLockFrom(stream, head::get);
        doAnswer(call -> { head.set(1L); return null; }).when(connection).commit();
        var append = update(StudioPublicationWrites.APPEND_SQL, 1);
        var audit = update(StudioPublicationAudit.INSERT_SQL, 1);
        var pointer = update(StudioPublicationWrites.POINTER_SQL, 1);
        var lookup = mock(PreparedStatement.class);
        var rows = mock(ResultSet.class);
        when(connection.prepareStatement(StudioPublicationWrites.HEAD_SQL)).thenReturn(lookup);
        when(lookup.executeQuery()).thenReturn(rows);
        when(rows.next()).thenReturn(true, false);
        when(rows.getString(1)).thenReturn(StudioDraftDecoder.decode(sourceBytes()).facts().canonicalSha256());
        var requests = mock(SessionSensitiveRequests.class);
        when(requests.capture(any())).thenReturn(request);
        var adapter = new StudioPublishHttp(requests, owner);
        var lost = spy(new MockHttpServletResponse());
        doThrow(new java.io.IOException("simulated lost response")).when(lost).getOutputStream();

        assertThrows(java.io.IOException.class, () -> adapter.postAfterSecurity("floor", originalPublish(), lost));
        assertEquals(1L, head.get());
        assertEquals(0, lost.getContentAsByteArray().length);

        var retry = new MockHttpServletResponse();
        adapter.postAfterSecurity("floor", originalPublish(), retry);
        assertEquals(409, retry.getStatus());
        assertEquals(0, retry.getContentAsByteArray().length);
        assertEquals(1L, head.get());
        verify(append, times(1)).executeUpdate();
        verify(audit, times(1)).executeUpdate();
        verify(pointer, times(1)).executeUpdate();
        var order = inOrder(connection, lost);
        order.verify(connection).commit();
        order.verify(lost).getOutputStream();
        order.verify(connection).rollback();
        verify(connection, times(1)).commit();
    }

    private MockHttpServletRequest originalPublish() {
        var post = new MockHttpServletRequest("POST", "/api/v1/admin/studio/screens/floor/publish");
        post.setContentType("application/json");
        post.addHeader(StudioPublishInput.EXPECTED_VERSION, "0");
        post.setContent(sourceBytes());
        return post;
    }

    @Test void publishOwnerPreflightRefusesForeignTenantAndExistingTransactionWithoutSql() {
        when(request.context()).thenReturn(context);
        var guard = mock(com.mightyoung.softwarefactory.authorization.AuthorizationWriteGuard.class);
        var owner = owner(guard);
        assertThrows(StudioPublicationPolicy.Forbidden.class,
                () -> owner.publish(request, new StudioPublicationPolicy.Target(UUID.randomUUID(), "floor"), 0, sourceBytes()));
        inParent(() -> assertThrows(SessionSensitiveException.class,
                () -> owner.publish(request, target(), 0, sourceBytes())));
        verifyNoInteractions(source, connection, guard);
    }

    private StudioPublish owner(com.mightyoung.softwarefactory.authorization.AuthorizationWriteGuard guard) {
        var manager = new org.springframework.jdbc.datasource.DataSourceTransactionManager(source);
        return new StudioPublish(source, manager, guard, command());
    }

    private PreparedStatement prepareOwnerConnection() throws Exception {
        when(source.getConnection()).thenReturn(connection);
        var isolation = new java.util.concurrent.atomic.AtomicInteger(Connection.TRANSACTION_REPEATABLE_READ);
        var auto = new java.util.concurrent.atomic.AtomicBoolean(true);
        doAnswer(invocation -> { isolation.set(invocation.getArgument(0)); return null; }).when(connection).setTransactionIsolation(anyInt());
        doAnswer(invocation -> { auto.set(invocation.getArgument(0)); return null; }).when(connection).setAutoCommit(anyBoolean());
        when(connection.getTransactionIsolation()).thenAnswer(invocation -> isolation.get());
        when(connection.getAutoCommit()).thenAnswer(invocation -> auto.get());
        var binding = mock(PreparedStatement.class);
        when(connection.prepareStatement(StudioPublish.TENANT_SQL)).thenReturn(binding);
        when(connection.prepareStatement(StudioPublish.TENANT_CHECK_SQL)).thenReturn(binding);
        when(binding.executeQuery()).thenAnswer(invocation -> {
            var rows = mock(ResultSet.class);
            when(rows.next()).thenReturn(true, false);
            when(rows.getString(1)).thenReturn(context.tenantId().toString());
            return rows;
        });
        return binding;
    }

    private StudioPublicationCommand command() {
        return new StudioPublicationCommand(new StudioGuardedPublish(source, callers, human));
    }

    private byte[] sourceBytes() {
        return """
                {"snapshotVersion":1,"scene":{"schemaVersion":1,"screenId":"floor","devices":[]},
                 "floorplan":{"walls":[],"zones":[]}}
                """.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private void allowPublication() throws Exception {
        when(checked.enforcement()).thenReturn(ENFORCING);
        var statement = mock(PreparedStatement.class);
        when(connection.prepareStatement(StudioAuthorityQueries.PUBLISH_SQL)).thenReturn(statement);
        when(statement.executeQuery()).thenAnswer(call -> {
            var rows = mock(ResultSet.class);
            when(rows.next()).thenReturn(true, false);
            when(rows.getBoolean(1)).thenReturn(true);
            return rows;
        });
    }

    private PreparedStatement update(String sql, int count) throws Exception {
        var statement = mock(PreparedStatement.class);
        when(connection.prepareStatement(sql)).thenReturn(statement);
        when(statement.executeUpdate()).thenReturn(count);
        return statement;
    }

    private void setupLock(UUID stream, Long head) throws Exception {
        setupLockFrom(stream, () -> head);
    }

    private void setupLockFrom(UUID stream, java.util.function.Supplier<Long> head) throws Exception {
        var statement = mock(PreparedStatement.class);
        when(connection.prepareStatement(StudioPublicationWrites.LOCK_SQL)).thenReturn(statement);
        when(statement.executeQuery()).thenAnswer(call -> {
            var rows = mock(ResultSet.class);
            when(rows.next()).thenReturn(true, false);
            when(rows.getObject(1, UUID.class)).thenReturn(stream);
            when(rows.getObject(2, Long.class)).thenReturn(head.get());
            return rows;
        });
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
