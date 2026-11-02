package com.mightyoung.softwarefactory.authorization;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class PostgresAuthorizationWriteGuardTest {
    private static final UUID TENANT = UUID.fromString("30000000-0000-0000-0000-000000000001");
    private final DataSource source = mock(DataSource.class);
    private final Connection connection = mock(Connection.class);
    private final ConnectionHolder holder = new ConnectionHolder(connection);
    private final List<String> operations = new ArrayList<>();
    private final PostgresAuthorizationWriteGuard guard = new PostgresAuthorizationWriteGuard(source);
    private String originalTimeout = "0";
    private String effectiveTimeout = "5000ms";
    private String actualIsolation = "read committed";
    private String actualReadOnly = "off";
    private boolean failLock;
    private String failureState = "55P03";

    @BeforeEach void begin() throws Exception {
        holder.setSynchronizedWithTransaction(true);
        TransactionSynchronizationManager.bindResource(source, holder);
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        when(connection.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_READ_COMMITTED);
        when(connection.prepareStatement(anyString())).thenAnswer(call -> {
            String sql = call.getArgument(0);
            operations.add(sql);
            if (failLock && sql.contains("pg_advisory")) throw new SQLException("database detail must remain private", failureState);
            PreparedStatement statement = mock(PreparedStatement.class);
            doAnswer(binding -> { operations.add("int=" + binding.getArgument(1)); return null; })
                    .when(statement).setInt(anyInt(), anyInt());
            doAnswer(binding -> { operations.add("long=" + binding.getArgument(1)); return null; })
                    .when(statement).setLong(anyInt(), anyLong());
            doAnswer(binding -> { operations.add("timeout=" + binding.getArgument(1)); return null; })
                    .when(statement).setString(eq(1), anyString());
            ResultSet result = mock(ResultSet.class);
            when(result.next()).thenReturn(true);
            String value = sql.contains("transaction_isolation") ? actualIsolation
                    : sql.contains("transaction_read_only") ? actualReadOnly
                    : sql.contains("case when") ? effectiveTimeout : originalTimeout;
            when(result.getString(1)).thenReturn(value);
            when(statement.executeQuery()).thenReturn(result);
            return statement;
        });
    }

    @AfterEach void end() {
        TransactionSynchronizationManager.unbindResourceIfPossible(source);
        TransactionSynchronizationManager.clear();
    }

    @Test void stableKeysAndSignedOrdering() {
        UUID zero = UUID.fromString("00000000-0000-0000-0000-000000000000");
        UUID high = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");
        assertThat(PostgresAuthorizationWriteGuard.tenantKey(zero)).isEqualTo(2416968273707118953L);
        assertThat(PostgresAuthorizationWriteGuard.tenantKey(TENANT)).isEqualTo(-2188433459312971883L);
        assertThat(PostgresAuthorizationWriteGuard.tenantKey(high)).isEqualTo(5294606846127505921L);
        assertThat(PostgresAuthorizationWriteGuard.tenantKeys(List.of(high, TENANT, zero, TENANT)))
                .containsExactly(-2188433459312971883L, 2416968273707118953L, 5294606846127505921L);
        assertThat(PostgresAuthorizationWriteGuard.ROOT_NAMESPACE).isEqualTo(0x4D534647);
    }

    @Test void commandLocksRootThenTenantAndRestoresLocalTimeout() {
        guard.forFindingCommand(TENANT);
        assertThat(operations).containsSubsequence("timeout=5000ms", "select pg_advisory_xact_lock_shared(?, ?)",
                "select pg_advisory_xact_lock_shared(?)", "timeout=0");
        int count = operations.size();
        guard.forFindingCommand(TENANT);
        assertThat(operations.subList(count, operations.size())).noneMatch(sql -> sql.contains("pg_advisory"));
    }

    @Test void preservesStricterCallerTimeout() {
        originalTimeout = "1s";
        effectiveTimeout = "1s";
        guard.forTenantSecurityChange(List.of(TENANT));
        assertThat(operations).containsSubsequence("timeout=1s", "select pg_advisory_xact_lock_shared(?, ?)",
                "select pg_advisory_xact_lock(?)", "timeout=1s");
        assertThat(operations).doesNotContain("timeout=5000ms");
    }

    @Test void multiTenantWriterUsesSortedDeduplicatedSignedKeys() {
        guard.forTenantSecurityChange(List.of(UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff"),
                TENANT, UUID.fromString("00000000-0000-0000-0000-000000000000"), TENANT));
        assertThat(operations.stream().filter(operation -> operation.startsWith("long=")))
                .containsExactly("long=-2188433459312971883", "long=2416968273707118953", "long=5294606846127505921");
        assertThat(operations).containsSubsequence("int=" + 0x4D534647, "int=1");
    }

    @Test void synchronizationDoesNotLeakIntoNextTransaction() {
        guard.forFindingCommand(TENANT);
        TransactionSynchronizationManager.clearSynchronization();
        TransactionSynchronizationManager.initSynchronization();
        guard.forGlobalSecurityChange();
        assertThat(operations).contains("select pg_advisory_xact_lock(?, ?)");
    }

    @Test void globalWriterUsesExclusiveRootOnly() {
        guard.forGlobalSecurityChange();
        assertThat(operations.stream().filter(sql -> sql.contains("pg_advisory")))
                .containsExactly("select pg_advisory_xact_lock(?, ?)");
    }

    @Test void rejectsUpgradeAndMarksWholeTransactionRollbackOnly() {
        guard.forFindingCommand(TENANT);
        assertThatThrownBy(() -> guard.forTenantSecurityChange(List.of(TENANT)))
                .isInstanceOf(AuthorizationWriteGuardException.class);
        assertThat(holder.isRollbackOnly()).isTrue();
    }

    @Test void rejectsUnmanagedOrDifferentDatasource() {
        TransactionSynchronizationManager.unbindResource(source);
        assertThatThrownBy(() -> guard.forFindingCommand(TENANT))
                .isInstanceOf(AuthorizationWriteGuardException.class);
        assertThat(operations).isEmpty();
    }

    @Test void rejectsActualRepeatableReadEvenIfTemplateDeclaredReadCommitted() throws Exception {
        when(connection.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_REPEATABLE_READ);
        assertRejectedWithoutLocks();
    }

    @Test void rejectsDatabaseRepeatableReadEvenIfConnectionClaimsReadCommitted() {
        actualIsolation = "repeatable read";
        assertRejectedWithoutLocks();
    }

    @Test void rejectsDatabaseReadOnly() {
        actualReadOnly = "on";
        assertRejectedWithoutLocks();
    }

    @Test void rejectsConnectionReadOnly() throws Exception {
        when(connection.isReadOnly()).thenReturn(true);
        assertRejectedWithoutLocks();
    }

    @Test void rejectsAutocommit() throws Exception {
        when(connection.getAutoCommit()).thenReturn(true);
        assertRejectedWithoutLocks();
    }

    @Test void lockTimeoutEscapesAndMarksRollbackWithoutTryingToRestoreAbortedTransaction() {
        failLock = true;
        assertThatThrownBy(() -> guard.forFindingCommand(TENANT))
                .isInstanceOf(AuthorizationWriteGuardUnavailableException.class).hasCauseInstanceOf(SQLException.class);
        assertThat(holder.isRollbackOnly()).isTrue();
        assertThat(operations).doesNotContain("timeout=0");
    }

    @Test void connectionFailureIsRetryableButDatabaseConfigurationFailureIsNot() {
        for (String state : List.of("08006", "40P01", "40001", "57P01")) {
            failureState = state;
            failLock = true;
            holder.resetRollbackOnly();
            assertThatThrownBy(() -> guard.forFindingCommand(TENANT))
                    .isInstanceOf(AuthorizationWriteGuardUnavailableException.class);
            assertThat(holder.isRollbackOnly()).isTrue();
        }
        for (String state : List.of("42601", "42501", "57014")) {
            failureState = state;
            holder.resetRollbackOnly();
            assertThatThrownBy(() -> guard.forFindingCommand(TENANT))
                    .isExactlyInstanceOf(AuthorizationWriteGuardException.class);
            assertThat(holder.isRollbackOnly()).isTrue();
        }
    }

    @Test void springSavepointRollbackInvalidatesCachedScopeAndPoisonsWholeTransaction() {
        guard.forFindingCommand(TENANT);
        Object savepoint = new Object();
        var synchronization = TransactionSynchronizationManager.getSynchronizations().getFirst();
        synchronization.savepoint(savepoint);
        synchronization.savepointRollback(savepoint);
        assertThat(holder.isRollbackOnly()).isTrue();
        holder.resetRollbackOnly(); // Spring JdbcTransactionObjectSupport does this after the callback.
        int statements = operations.size();
        assertThatThrownBy(() -> guard.forFindingCommand(TENANT))
                .isExactlyInstanceOf(AuthorizationWriteGuardException.class);
        assertThat(operations.subList(statements,operations.size())).noneMatch(sql -> sql.contains("pg_advisory"));
        assertThat(holder.isRollbackOnly()).isTrue();
    }

    @Test void firstGuardInsideExistingSpringSavepointIsAlsoInvalidatedOnRollback() {
        Object existingSavepoint = new Object();
        // No guard synchronization existed when this savepoint was created.
        guard.forFindingCommand(TENANT);
        TransactionSynchronizationManager.getSynchronizations().forEach(
                synchronization -> synchronization.savepointRollback(existingSavepoint));
        assertThat(holder.isRollbackOnly()).isTrue();
        holder.resetRollbackOnly();
        assertThatThrownBy(() -> guard.forFindingCommand(TENANT))
                .isExactlyInstanceOf(AuthorizationWriteGuardException.class);
        assertThat(holder.isRollbackOnly()).isTrue();
    }

    @Test void invalidatedScopePreventsCommitEvenWithoutAnotherGuardCall() {
        guard.forFindingCommand(TENANT);
        var synchronization = TransactionSynchronizationManager.getSynchronizations().getFirst();
        synchronization.savepointRollback(new Object());
        holder.resetRollbackOnly();
        assertThatThrownBy(() -> synchronization.beforeCommit(false))
                .isExactlyInstanceOf(AuthorizationWriteGuardException.class);
        assertThat(holder.isRollbackOnly()).isTrue();
    }

    @Test void sameScopeCannotBeReusedAfterOtherTransactionFailure() {
        guard.forFindingCommand(TENANT);
        holder.setRollbackOnly();
        assertThatThrownBy(() -> guard.forFindingCommand(TENANT))
                .isExactlyInstanceOf(AuthorizationWriteGuardException.class);
    }

    private void assertRejectedWithoutLocks() {
        assertThatThrownBy(() -> guard.forFindingCommand(TENANT)).isInstanceOf(AuthorizationWriteGuardException.class);
        assertThat(holder.isRollbackOnly()).isTrue();
        assertThat(operations).noneMatch(sql -> sql.contains("pg_advisory"));
    }
}
