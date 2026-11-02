package com.mightyoung.softwarefactory.authorization;

import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Uses only transaction advisory locks. Authorization queries must run in later SQL statements. */
@Component
public final class PostgresAuthorizationWriteGuard implements AuthorizationWriteGuard {
    static final int ROOT_NAMESPACE = 0x4D534647;
    static final int ROOT_KEY = 1;
    private final DataSource dataSource;

    public PostgresAuthorizationWriteGuard(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource);
    }

    @Override public void forFindingCommand(UUID tenantId) {
        acquire(new Scope(Mode.COMMAND, tenantKeys(List.of(tenantId))));
    }

    @Override public void forGlobalSecurityChange() {
        acquire(new Scope(Mode.GLOBAL, List.of()));
    }

    @Override public void forTenantSecurityChange(Collection<UUID> tenantIds) {
        if (tenantIds == null || tenantIds.isEmpty()) {
            throw new AuthorizationWriteGuardException("Security writers must declare affected tenants");
        }
        acquire(new Scope(Mode.TENANT, tenantKeys(tenantIds)));
    }

    static long tenantKey(UUID tenantId) {
        Objects.requireNonNull(tenantId, "Tenant is required");
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(
                    ("msf.authorization.v1/tenant/" + tenantId).getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.wrap(hash).getLong();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", impossible);
        }
    }

    static List<Long> tenantKeys(Collection<UUID> tenantIds) {
        return tenantIds.stream().map(PostgresAuthorizationWriteGuard::tenantKey).distinct().sorted().toList();
    }

    private void acquire(Scope scope) {
        Object resource = TransactionSynchronizationManager.getResource(dataSource);
        ConnectionHolder holder = resource instanceof ConnectionHolder h ? h : null;
        try {
            if (holder == null || !holder.isSynchronizedWithTransaction()
                    || !TransactionSynchronizationManager.isActualTransactionActive()
                    || !TransactionSynchronizationManager.isSynchronizationActive()) {
                throw new AuthorizationWriteGuardException("A managed transaction on the guard datasource is required");
            }
            if (holder.isRollbackOnly()) {
                throw new AuthorizationWriteGuardException("Authorization guard transaction is already rollback-only");
            }
            Connection connection = holder.getConnection();
            requireReadCommittedWriteTransaction(connection);
            for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
                if (synchronization instanceof GuardScope previous && previous.owner == this) {
                    if (previous.invalidated) {
                        throw new AuthorizationWriteGuardException("Authorization guard scope was invalidated by savepoint rollback");
                    }
                    if (previous.connection != connection || !previous.scope.equals(scope)) {
                        throw new AuthorizationWriteGuardException("Authorization guard scope cannot be changed or upgraded");
                    }
                    return;
                }
            }
            String originalTimeout = scalar(connection, "select current_setting('lock_timeout')");
            // PostgreSQL exposes the normalized duration as an interval; keep tighter caller limits.
            String effectiveTimeout = scalar(connection, """
                    select case when current_setting('lock_timeout')::interval = interval '0'
                             or current_setting('lock_timeout')::interval > interval '5 seconds'
                           then '5000ms' else current_setting('lock_timeout') end
                    """);
            setTimeout(connection, effectiveTimeout);
            advisory(connection, scope.mode == Mode.GLOBAL
                    ? "select pg_advisory_xact_lock(?, ?)" : "select pg_advisory_xact_lock_shared(?, ?)",
                    ROOT_NAMESPACE, ROOT_KEY);
            for (long tenantKey : scope.keys) {
                advisory(connection, scope.mode == Mode.COMMAND
                        ? "select pg_advisory_xact_lock_shared(?)" : "select pg_advisory_xact_lock(?)", tenantKey);
            }
            setTimeout(connection, originalTimeout);
            TransactionSynchronizationManager.registerSynchronization(new GuardScope(this, connection, holder, scope));
        } catch (SQLException | RuntimeException failure) {
            if (holder != null) holder.setRollbackOnly();
            if (failure instanceof AuthorizationWriteGuardException guardFailure) throw guardFailure;
            if (failure instanceof SQLException sqlFailure && transientDatabaseFailure(sqlFailure)) {
                throw new AuthorizationWriteGuardUnavailableException(
                        "Authorization guard database unavailable; retry in a new transaction", failure);
            }
            throw new AuthorizationWriteGuardException("Authorization guard failed; roll back the transaction", failure);
        }
    }

    private static boolean transientDatabaseFailure(SQLException failure) {
        String state = failure.getSQLState();
        return failure instanceof java.sql.SQLTransientConnectionException
                || failure instanceof java.sql.SQLRecoverableException
                || (state != null && (state.startsWith("08") || state.equals("55P03")
                    || state.equals("40P01") || state.equals("40001")
                    || state.equals("57P01") || state.equals("57P02") || state.equals("57P03")));
    }

    private static void requireReadCommittedWriteTransaction(Connection connection) throws SQLException {
        if (connection.getAutoCommit() || connection.isReadOnly()
                || connection.getTransactionIsolation() != Connection.TRANSACTION_READ_COMMITTED
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                || !"read committed".equals(scalar(connection, "select current_setting('transaction_isolation')"))
                || !"off".equals(scalar(connection, "select current_setting('transaction_read_only')"))) {
            throw new AuthorizationWriteGuardException("Authorization guard requires an actual READ COMMITTED read-write transaction");
        }
    }

    private static String scalar(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql); ResultSet result = statement.executeQuery()) {
            if (!result.next()) throw new SQLException("Missing PostgreSQL transaction setting");
            return result.getString(1);
        }
    }

    private static void setTimeout(Connection connection, String timeout) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("select set_config('lock_timeout', ?, true)")) {
            statement.setString(1, timeout);
            statement.execute();
        }
    }

    private static void advisory(Connection connection, String sql, Number... keys) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < keys.length; i++) {
                if (keys[i] instanceof Integer key) statement.setInt(i + 1, key);
                else statement.setLong(i + 1, keys[i].longValue());
            }
            statement.execute();
        }
    }

    private enum Mode { COMMAND, GLOBAL, TENANT }
    private record Scope(Mode mode, List<Long> keys) { }
    private static final class GuardScope implements TransactionSynchronization {
        private final PostgresAuthorizationWriteGuard owner;
        private final Connection connection;
        private final ConnectionHolder holder;
        private final Scope scope;
        private boolean invalidated;

        private GuardScope(PostgresAuthorizationWriteGuard owner, Connection connection,
                           ConnectionHolder holder, Scope scope) {
            this.owner = owner;
            this.connection = connection;
            this.holder = holder;
            this.scope = scope;
        }

        @Override public void savepointRollback(Object savepoint) {
            // PostgreSQL can release acquired locks. Spring resets the holder's rollback-only flag
            // after this callback, so retain an independent marker even for a pre-existing savepoint.
            invalidated = true;
            holder.setRollbackOnly();
        }

        @Override public void beforeCommit(boolean readOnly) {
            if (invalidated) {
                holder.setRollbackOnly();
                throw new AuthorizationWriteGuardException("Cannot commit after authorization guard savepoint rollback");
            }
        }
    }
}
