package com.mightyoung.softwarefactory.platformadmin.digitaltwin;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static com.mightyoung.softwarefactory.platformadmin.digitaltwin.StudioPublicationPolicy.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StudioPublicationWritesTest {
    final Connection sql = mock(Connection.class);
    final StudioPublicationWrites writes = new StudioPublicationWrites(sql);
    final Target target = new Target(UUID.randomUUID(), "floor");
    final Caller caller = new Caller(target.tenantId(), UUID.randomUUID(), UUID.randomUUID(), true, Set.of());
    final UUID stream = UUID.randomUUID();

    @Test void absentHeadIsLockedBeforeAppendAndPointerUsesSqlNull() throws Exception {
        var create = update(StudioPublicationWrites.CREATE_SQL, 1);
        var lock = mock(PreparedStatement.class);
        var rows = mock(ResultSet.class);
        when(sql.prepareStatement(StudioPublicationWrites.LOCK_SQL)).thenReturn(lock);
        when(lock.executeQuery()).thenReturn(rows);
        when(rows.next()).thenReturn(true, false);
        when(rows.getObject(1, UUID.class)).thenReturn(stream);
        var locked = writes.lock(target, stream);
        assertNull(locked.head());
        assertEquals(stream, locked.streamId());
        var accepted = snapshot();
        var append = update(StudioPublicationWrites.APPEND_SQL, 1);
        var pointer = update(StudioPublicationWrites.POINTER_SQL, 1);
        var audit = UUID.randomUUID();
        writes.append(locked, accepted, caller, audit, Instant.EPOCH);
        // The synthetic call below checks SQL only; a future owner must append real audit before it.
        writes.advance(locked, accepted);
        var order = inOrder(sql, create, lock, append, pointer);
        order.verify(sql).prepareStatement(StudioPublicationWrites.CREATE_SQL);
        order.verify(create).executeUpdate();
        order.verify(sql).prepareStatement(StudioPublicationWrites.LOCK_SQL);
        order.verify(lock).executeQuery();
        order.verify(sql).prepareStatement(StudioPublicationWrites.APPEND_SQL);
        order.verify(append).setObject(6, caller.memberId());
        order.verify(append).setObject(7, audit);
        order.verify(append).executeUpdate();
        order.verify(sql).prepareStatement(StudioPublicationWrites.POINTER_SQL);
        order.verify(pointer).setNull(4, java.sql.Types.BIGINT);
        order.verify(pointer).executeUpdate();
        verify(sql, never()).commit();
        verify(sql, never()).close();
    }

    @Test void existingHeadDigestIsReadAfterLockAndConditionalPointerMustAffectOneRow() throws Exception {
        update(StudioPublicationWrites.CREATE_SQL, 0);
        var lock = mock(PreparedStatement.class);
        var rows = mock(ResultSet.class);
        when(sql.prepareStatement(StudioPublicationWrites.LOCK_SQL)).thenReturn(lock);
        when(lock.executeQuery()).thenReturn(rows);
        when(rows.next()).thenReturn(true, false);
        when(rows.getObject(1, UUID.class)).thenReturn(stream);
        when(rows.getObject(2, Long.class)).thenReturn(1L);
        var headQuery = mock(PreparedStatement.class);
        var headRows = mock(ResultSet.class);
        when(sql.prepareStatement(StudioPublicationWrites.HEAD_SQL)).thenReturn(headQuery);
        when(headQuery.executeQuery()).thenReturn(headRows);
        when(headRows.next()).thenReturn(true, false);
        when(headRows.getString(1)).thenReturn("a".repeat(64));
        var locked = writes.lock(target, UUID.randomUUID());
        assertEquals(new Head(target, 1, "a".repeat(64)), locked.head());
        var accepted = snapshot(locked.head());
        update(StudioPublicationWrites.APPEND_SQL, 1);
        writes.append(locked, accepted, caller, UUID.randomUUID(), Instant.EPOCH);
        var pointer = update(StudioPublicationWrites.POINTER_SQL, 0);
        assertThrows(IllegalStateException.class, () -> writes.advance(locked, accepted));
        verify(pointer).setLong(4, 1L);
        verify(sql, never()).commit();
    }

    private PreparedStatement update(String query, int count) throws Exception {
        var statement = mock(PreparedStatement.class);
        when(sql.prepareStatement(query)).thenReturn(statement);
        when(statement.executeUpdate()).thenReturn(count);
        return statement;
    }

    private StudioPublicationService.Snapshot snapshot() { return snapshot(null); }
    private StudioPublicationService.Snapshot snapshot(Head head) {
        var accepted = new AtomicReference<StudioPublicationService.Snapshot>();
        var service = new StudioPublicationService(command -> command.apply(new StudioPublicationService.PublicationUnit() {
            @Override public Head lockHead(Target key) { return head; }
            @Override public void append(StudioPublicationService.Snapshot snapshot) { accepted.set(snapshot); }
            @Override public void audit(Caller actor, StudioPublicationService.Snapshot snapshot) {}
        }));
        service.publish(caller, target, head == null ? 0 : head.version(),
                """
                {"snapshotVersion":1,"scene":{"schemaVersion":1,"screenId":"floor","devices":[]},
                 "floorplan":{"walls":[],"zones":[]}}
                """.getBytes(StandardCharsets.UTF_8));
        return accepted.get();
    }
}
