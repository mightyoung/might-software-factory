package com.mightyoung.softwarefactory.platformadmin.digitaltwin;

import com.mightyoung.softwarefactory.authorization.AuthorizationWriteGuard;
import com.mightyoung.softwarefactory.identity.CapturedHumanRequest;
import com.mightyoung.softwarefactory.identity.CurrentTenantContext;
import com.mightyoung.softwarefactory.identity.HumanSessionAuthorityTransaction;
import com.mightyoung.softwarefactory.identity.SessionSensitiveException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static com.mightyoung.softwarefactory.platformadmin.digitaltwin.StudioPublicationPolicy.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StudioPlayerReadTest {
    private final DataSource source = mock(DataSource.class);
    private final Connection connection = mock(Connection.class);
    private final AuthorizationWriteGuard guard = mock(AuthorizationWriteGuard.class);
    private final StudioGuardedPlayerRead guarded = mock(StudioGuardedPlayerRead.class);
    private final CapturedHumanRequest request = mock(CapturedHumanRequest.class);
    private final CurrentTenantContext tenant = new CurrentTenantContext(UUID.randomUUID(), UUID.randomUUID(),
            UUID.randomUUID(), UUID.randomUUID(), 1, 1, 1);
    private final Target target = new Target(tenant.tenantId(), "floor");
    private final DataSourceTransactionManager manager = new DataSourceTransactionManager(source);
    private final StudioPlayerRead owner = new StudioPlayerRead(source, manager, guard, guarded);

    @Test void bindsTenantAndTakesBusinessGuardBeforePinnedReadAndCommit() throws Exception {
        var binding = prepareConnection();
        var projection = new StudioPlayerPublication.Published(target, 2, "digest", new byte[]{1});
        when(guarded.read(scope(), target, 2L)).thenReturn(Optional.of(projection));

        assertSame(projection, owner.read(request, target, 2L).orElseThrow());
        var order = inOrder(connection, binding, guard, guarded);
        order.verify(connection).setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        order.verify(binding).setString(1, tenant.tenantId().toString());
        order.verify(binding, times(2)).executeQuery();
        order.verify(guard).forBusinessCommand(tenant.tenantId());
        order.verify(guarded).read(scope(), target, 2L);
        order.verify(connection).commit();
        assertFalse(TransactionSynchronizationManager.hasResource(source));
    }

    @Test void commitFailureCannotReturnProjection() throws Exception {
        prepareConnection();
        when(guarded.read(scope(), target, null)).thenReturn(Optional.of(
                new StudioPlayerPublication.Published(target, 1, "digest", new byte[]{1})));
        doThrow(new SQLException("simulated failure")).when(connection).commit();

        assertThrows(TransactionException.class, () -> owner.read(request, target, null));
        verify(guarded).read(scope(), target, null);
        verify(connection).commit();
        assertFalse(TransactionSynchronizationManager.hasResource(source));
    }

    @Test void revokedScreenRemainsForbiddenAndRollsBack() throws Exception {
        prepareConnection();
        when(guarded.read(scope(), target, 9L)).thenThrow(new Forbidden());

        assertThrows(Forbidden.class, () -> owner.read(request, target, 9L));
        verify(connection).rollback();
        verify(connection, never()).commit();
        verify(guarded).read(scope(), target, 9L);

        verifyNoMoreInteractions(guarded);
    }

    @Test void foreignTargetRefusedBeforeSqlAndAmbientTransactionNeverJoined() throws Exception {
        when(request.context()).thenReturn(tenant);
        assertThrows(Forbidden.class,
                () -> owner.read(request, new Target(UUID.randomUUID(), "floor"), null));
        verifyNoInteractions(source, connection, guard, guarded);
        prepareConnection();
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            assertThrows(SessionSensitiveException.class, () -> owner.read(request, target, null));
            status.setRollbackOnly();
        });
        verify(connection, never()).prepareStatement(anyString());
        verifyNoInteractions(guard, guarded);
    }

    private HumanSessionAuthorityTransaction.WriteScope scope() {
        return new HumanSessionAuthorityTransaction.WriteScope(request,
                HumanSessionAuthorityTransaction.Guard.FINDING_COMMAND, List.of(tenant.tenantId()));
    }

    private PreparedStatement prepareConnection() throws Exception {
        when(request.context()).thenReturn(tenant);
        when(source.getConnection()).thenReturn(connection);
        var isolation = new AtomicInteger(Connection.TRANSACTION_REPEATABLE_READ);
        var auto = new AtomicBoolean(true);
        doAnswer(call -> { isolation.set(call.getArgument(0)); return null; }).when(connection).setTransactionIsolation(anyInt());
        doAnswer(call -> { auto.set(call.getArgument(0)); return null; }).when(connection).setAutoCommit(anyBoolean());
        when(connection.getTransactionIsolation()).thenAnswer(call -> isolation.get());
        when(connection.getAutoCommit()).thenAnswer(call -> auto.get());
        var binding = mock(PreparedStatement.class);
        when(connection.prepareStatement(StudioPublish.TENANT_SQL)).thenReturn(binding);
        when(connection.prepareStatement(StudioPublish.TENANT_CHECK_SQL)).thenReturn(binding);
        when(binding.executeQuery()).thenAnswer(call -> {
            var rows = mock(ResultSet.class);
            when(rows.next()).thenReturn(true, false);
            when(rows.getString(1)).thenReturn(tenant.tenantId().toString());
            return rows;
        });
        return binding;
    }
}
