package com.mightyoung.softwarefactory.platformadmin.digitaltwin;

import com.mightyoung.softwarefactory.authorization.AuthorizationWriteGuard;
import com.mightyoung.softwarefactory.identity.CheckedHumanSessionAuthority;
import com.mightyoung.softwarefactory.identity.CurrentHumanSessionAuthority;
import com.mightyoung.softwarefactory.identity.HumanSessionAuthorityTransaction;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

import static com.mightyoung.softwarefactory.identity.SessionSensitiveContracts.Enforcement.ENFORCING;
import static org.mockito.Mockito.*;

/** Runs the real owner and projection against test doubles for session proof, guard and JDBC. */
final class StudioPlayerStorageFixture {
    final DataSource source = mock(DataSource.class);
    final Connection connection = mock(Connection.class);
    final PreparedStatement grant = mock(PreparedStatement.class);
    final PreparedStatement pin = mock(PreparedStatement.class);
    final StudioDraftDecoder.Decoded snapshot = StudioDraftDecoder.decode("""
            {"snapshotVersion":1,"scene":{"schemaVersion":1,"screenId":"floor","devices":[]},
             "floorplan":{"walls":[],"zones":[]}}
            """.getBytes(StandardCharsets.UTF_8));
    final StudioPlayerRead reader;
    boolean granted;
    private final UUID tenantId;

    StudioPlayerStorageFixture(UUID tenantId) {
        this.tenantId = tenantId;
        var callers = mock(HumanSessionAuthorityTransaction.class);
        var human = mock(CurrentHumanSessionAuthority.class);
        var checked = mock(CheckedHumanSessionAuthority.class);
        var boundary = mock(HumanSessionAuthorityTransaction.GuardedBoundary.class);
        when(callers.withGuarded(any(), any())).thenAnswer(call ->
                call.<Function<HumanSessionAuthorityTransaction.GuardedBoundary, ?>>getArgument(1).apply(boundary));
        when(human.requireGuardedCheckedCurrent(any(), same(boundary))).thenReturn(checked);
        when(checked.enforcement()).thenReturn(ENFORCING);
        when(checked.observedAt(any(), same(boundary))).thenReturn(Instant.EPOCH);
        reader = new StudioPlayerRead(source, new DataSourceTransactionManager(source),
                mock(AuthorizationWriteGuard.class), new StudioGuardedPlayerRead(source, callers, human));
    }

    void resetStorage() throws SQLException {
        reset(source, connection, grant, pin);
        granted = false;
        when(source.getConnection()).thenReturn(connection);
        when(connection.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_READ_COMMITTED);
        var tenant = mock(PreparedStatement.class);
        when(connection.prepareStatement(StudioPublish.TENANT_SQL)).thenReturn(tenant);
        when(connection.prepareStatement(StudioPublish.TENANT_CHECK_SQL)).thenReturn(tenant);
        when(tenant.executeQuery()).thenAnswer(call -> {
            var rows = mock(ResultSet.class);
            when(rows.next()).thenReturn(true, false);
            when(rows.getString(1)).thenReturn(tenantId.toString());
            return rows;
        });
        when(connection.prepareStatement(StudioAuthorityQueries.SCREEN_SQL)).thenReturn(grant);
        when(grant.executeQuery()).thenAnswer(call -> {
            var rows = mock(ResultSet.class);
            when(rows.next()).thenReturn(true, false);
            when(rows.getBoolean(1)).thenReturn(granted);
            return rows;
        });
        when(connection.prepareStatement(StudioPublishedLookup.PIN_SQL)).thenReturn(pin);
        var requested = new AtomicLong();
        doAnswer(call -> { requested.set(call.getArgument(1)); return null; }).when(pin).setLong(eq(3), anyLong());
        when(pin.executeQuery()).thenAnswer(call -> requested.get() == 7 ? publishedRow() : mock(ResultSet.class));
        var current = mock(PreparedStatement.class);
        when(connection.prepareStatement(StudioPublishedLookup.CURRENT_SQL)).thenReturn(current);
        when(current.executeQuery()).thenAnswer(call -> publishedRow());
    }

    private ResultSet publishedRow() throws SQLException {
        var rows = mock(ResultSet.class);
        when(rows.next()).thenReturn(true, false);
        when(rows.getObject(1, UUID.class)).thenReturn(tenantId);
        when(rows.getString(2)).thenReturn("floor");
        when(rows.getLong(3)).thenReturn(7L);
        when(rows.getString(4)).thenReturn(snapshot.facts().canonicalSha256());
        when(rows.getBytes(5)).thenReturn(snapshot.canonicalBytes());
        return rows;
    }
}
