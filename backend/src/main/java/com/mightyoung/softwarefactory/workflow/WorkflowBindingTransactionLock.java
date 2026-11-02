package com.mightyoung.softwarefactory.workflow;

import com.mightyoung.softwarefactory.authorization.AuthorizationWriteGuard;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import java.util.UUID;

/** Serializes immutable binding use without granting UPDATE merely to lock a row.
 * One binding per transaction; this lock is not an action authorization decision.
 */
@Component
public final class WorkflowBindingTransactionLock {
    private static final int NAMESPACE = 0x4D534657;
    private final DataSource dataSource;
    private final AuthorizationWriteGuard authorizationGuard;

    public WorkflowBindingTransactionLock(DataSource dataSource, AuthorizationWriteGuard authorizationGuard) {
        this.dataSource = dataSource;
        this.authorizationGuard = authorizationGuard;
    }

    public void acquire(UUID tenantId, String businessType, UUID businessId) {
        Objects.requireNonNull(tenantId); Objects.requireNonNull(businessId);
        if (businessType == null || businessType.isBlank()) throw new IllegalArgumentException("Business type required");
        // Reuses the actual same-datasource RW/RC validation and savepoint rollback protection.
        authorizationGuard.forFindingCommand(tenantId);
        Object resource = TransactionSynchronizationManager.getResource(dataSource);
        if (!(resource instanceof ConnectionHolder holder) || !holder.isSynchronizedWithTransaction()) {
            throw new IllegalStateException("Workflow binding requires the managed application datasource");
        }
        var binding = new Binding(tenantId, businessType, businessId);
        try {
            if (!tenantId.toString().equals(setting(holder.getConnection(),
                    "select current_setting('app.tenant_id', true)"))) {
                throw new IllegalStateException("Workflow binding tenant context mismatch");
            }
            for (var synchronization : TransactionSynchronizationManager.getSynchronizations()) {
                if (synchronization instanceof Held held && held.owner == this) {
                    if (!held.binding.equals(binding) || held.connection != holder.getConnection()) {
                        throw new IllegalStateException("Declare a single workflow binding per transaction");
                    }
                    return;
                }
            }
            Connection connection = holder.getConnection();
            String original = setting(connection, "select current_setting('lock_timeout')");
            String effective = setting(connection, """
                    select case when current_setting('lock_timeout')::interval = interval '0'
                             or current_setting('lock_timeout')::interval > interval '5 seconds'
                           then '5000ms' else current_setting('lock_timeout') end
                    """);
            timeout(connection, effective);
            try (var statement = connection.prepareStatement("select pg_advisory_xact_lock(?, ?)")) {
                statement.setInt(1, NAMESPACE);
                statement.setInt(2, key(binding));
                statement.execute();
            }
            timeout(connection, original);
            TransactionSynchronizationManager.registerSynchronization(new Held(this, connection, binding));
        } catch (SQLException failure) {
            holder.setRollbackOnly();
            throw new WorkflowEngineUnavailableException(failure);
        } catch (RuntimeException failure) {
            holder.setRollbackOnly();
            throw failure;
        }
    }

    private static int key(Binding binding) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(("msf.workflow.binding.v1/"
                    + binding.tenantId + "/" + binding.businessType.length() + ":" + binding.businessType
                    + "/" + binding.businessId + "/1").getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.wrap(digest).getInt();
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 required", impossible);
        }
    }

    private static String setting(Connection connection, String sql) throws SQLException {
        try (var statement = connection.prepareStatement(sql); var rows = statement.executeQuery()) {
            if (!rows.next()) throw new SQLException("Missing transaction setting");
            return rows.getString(1);
        }
    }

    private static void timeout(Connection connection, String value) throws SQLException {
        try (var statement = connection.prepareStatement("select set_config('lock_timeout', ?, true)")) {
            statement.setString(1, value); statement.execute();
        }
    }
    private record Binding(UUID tenantId, String businessType, UUID businessId) { }
    private record Held(WorkflowBindingTransactionLock owner, Connection connection, Binding binding)
            implements TransactionSynchronization { }
}
