package com.mightyoung.softwarefactory.authorization;

import com.mightyoung.softwarefactory.identity.CurrentTenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ManagedIdentitySecurityTransactionTest {
    private final DataSource source = mock(DataSource.class);
    private final Connection connection = mock(Connection.class);
    private final CurrentTenantContext context = new CurrentTenantContext(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),2,3,4);
    private final DataSourceTransactionManager manager = new DataSourceTransactionManager(source);
    private final ManagedIdentitySecurityTransaction bridge = new ManagedIdentitySecurityTransaction(source,manager,new PostgresAuthorizationWriteGuard(source));
    private final List<String> operations = new ArrayList<>();
    private final AtomicBoolean autoCommit = new AtomicBoolean(true);
    private final AtomicInteger isolation = new AtomicInteger(Connection.TRANSACTION_READ_COMMITTED);
    private boolean incorrectTenant;

    @BeforeEach void jdbc() throws Exception {
        when(source.getConnection()).thenReturn(connection);
        when(connection.getAutoCommit()).thenAnswer(call -> autoCommit.get());
        doAnswer(call -> { autoCommit.set(call.getArgument(0)); return null; }).when(connection).setAutoCommit(anyBoolean());
        when(connection.getTransactionIsolation()).thenAnswer(call -> isolation.get());
        doAnswer(call -> { isolation.set(call.getArgument(0)); return null; }).when(connection).setTransactionIsolation(anyInt());
        var metadata = mock(DatabaseMetaData.class); when(metadata.supportsSavepoints()).thenReturn(true);
        when(connection.getMetaData()).thenReturn(metadata); when(connection.setSavepoint(anyString())).thenReturn(mock(Savepoint.class));
        when(connection.prepareStatement(anyString())).thenAnswer(call -> {
            String sql = call.getArgument(0); operations.add(sql);
            var statement = mock(PreparedStatement.class);
            var parameter = new AtomicReference<String>();
            doAnswer(binding -> { parameter.set(binding.getArgument(1)); return null; }).when(statement).setString(eq(1),anyString());
            var rows = mock(ResultSet.class); when(rows.next()).thenReturn(true);
            when(rows.getString(1)).thenAnswer(read -> sql.contains("app.tenant_id")
                    ? incorrectTenant ? "wrong" : parameter.get()
                    : sql.contains("transaction_isolation") ? "read committed"
                    : sql.contains("transaction_read_only") ? "off"
                    : sql.contains("case when") ? "5000ms" : "0");
            when(statement.executeQuery()).thenReturn(rows); return statement;
        });
    }
    @AfterEach void cleanThread() { TransactionSynchronizationManager.clear(); }

    @Test void globalGuardAndTenantSettingPrecedeTheExactSameConnectionMarker() throws Exception {
        int value = bridge.execute(context,() -> {
            bridge.requireActive(context);
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isFalse();
            assertThat(((ConnectionHolder)TransactionSynchronizationManager.getResource(source)).getConnection()).isSameAs(connection);
            return 42;
        });
        assertThat(value).isEqualTo(42);
        assertThat(operations.stream().filter(sql -> sql.contains("pg_advisory"))).hasSize(1);
        assertThat(operations.indexOf("select pg_advisory_xact_lock(?, ?)"))
                .isLessThan(operations.indexOf("select set_config('app.tenant_id', ?, true)"));
        verify(source).getConnection(); verify(connection).commit(); verify(connection,never()).rollback();
        assertThatThrownBy(() -> bridge.requireActive(context)).isInstanceOf(AuthorizationWriteGuardException.class);
    }

    @Test void rejectsAnAmbientTransactionWithoutCallingTheWork() {
        var called = new AtomicBoolean();
        assertThatThrownBy(() -> new TransactionTemplate(manager).executeWithoutResult(status ->
                bridge.execute(context,() -> { called.set(true); return null; })))
                .isInstanceOf(AuthorizationWriteGuardException.class);
        assertThat(called.get()).isFalse(); assertThat(operations).isEmpty();
    }

    @Test void requireActiveNeverAcquiresALockWithoutTheBridgeMarker() {
        assertThatThrownBy(() -> new TransactionTemplate(manager).executeWithoutResult(status -> bridge.requireActive(context)))
                .isInstanceOf(AuthorizationWriteGuardException.class);
        assertThat(operations).isEmpty();
    }

    @Test void rejectsAChangedCompleteContextAndRollsBack() throws Exception {
        var other = new CurrentTenantContext(context.actorId(),context.accountId(),context.tenantId(),context.memberId(),2,3,5);
        assertThatThrownBy(() -> bridge.execute(context,() -> { bridge.requireActive(other); return null; }))
                .isInstanceOf(AuthorizationWriteGuardException.class);
        verify(connection).rollback();
    }

    @Test void rejectsAReplacedDatasourceConnectionHolder() throws Exception {
        assertThatThrownBy(() -> bridge.execute(context,() -> {
            var holder = (ConnectionHolder)TransactionSynchronizationManager.getResource(source);
            var replacement = new ConnectionHolder(mock(Connection.class)); replacement.setSynchronizedWithTransaction(true);
            TransactionSynchronizationManager.unbindResource(source);
            TransactionSynchronizationManager.bindResource(source,replacement);
            try { bridge.requireActive(context); return null; }
            finally {
                TransactionSynchronizationManager.unbindResource(source);
                TransactionSynchronizationManager.bindResource(source,holder);
            }
        })).isInstanceOf(AuthorizationWriteGuardException.class);
        verify(connection).rollback();
    }

    @Test void rejectsAnotherDatasourceMarker() {
        var other = new ManagedIdentitySecurityTransaction(mock(DataSource.class),manager,mock(AuthorizationWriteGuard.class));
        assertThatThrownBy(() -> bridge.execute(context,() -> { other.requireActive(context); return null; }))
                .isInstanceOf(AuthorizationWriteGuardException.class);
    }

    @Test void reentryRechecksReadOnlyAndActualIsolation() {
        assertThatThrownBy(() -> bridge.execute(context,() -> {
            TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
            bridge.requireActive(context); return null;
        })).isInstanceOf(AuthorizationWriteGuardException.class);
        assertThatThrownBy(() -> bridge.execute(context,() -> {
            isolation.set(Connection.TRANSACTION_REPEATABLE_READ);
            bridge.requireActive(context); return null;
        })).isInstanceOf(AuthorizationWriteGuardException.class);
    }

    @Test void savepointRollbackInvalidatesTheMarkerEvenWhenSpringClearsRollbackOnly() throws Exception {
        assertThatThrownBy(() -> bridge.execute(context,() -> {
            var nested = new TransactionTemplate(manager); nested.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
            assertThatThrownBy(() -> nested.executeWithoutResult(status -> { throw new NestedFailure(); }))
                    .isInstanceOf(NestedFailure.class);
            bridge.requireActive(context); return null;
        })).isInstanceOf(AuthorizationWriteGuardException.class);
        verify(connection).rollback(any(Savepoint.class)); verify(connection).rollback();
    }

    @Test void savepointRollbackPreventsCommitWithoutAnotherRequireActiveCall() throws Exception {
        var finishedWork = new AtomicBoolean();
        assertThatThrownBy(() -> bridge.execute(context,() -> {
            var nested = new TransactionTemplate(manager); nested.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
            assertThatThrownBy(() -> nested.executeWithoutResult(status -> { throw new NestedFailure(); }))
                    .isInstanceOf(NestedFailure.class);
            finishedWork.set(true); return null;
        })).isInstanceOf(AuthorizationWriteGuardException.class);
        assertThat(finishedWork.get()).isTrue(); verify(connection,never()).commit(); verify(connection).rollback();
    }

    @Test void incorrectTenantApplicationNeverInvokesTheWorkOrCommits() throws Exception {
        incorrectTenant = true; var called = new AtomicBoolean();
        assertThatThrownBy(() -> bridge.execute(context,() -> { called.set(true); return null; }))
                .isInstanceOf(AuthorizationWriteGuardException.class);
        assertThat(called.get()).isFalse(); verify(connection).rollback(); verify(connection,never()).commit();
    }
    static final class NestedFailure extends RuntimeException { }
}
