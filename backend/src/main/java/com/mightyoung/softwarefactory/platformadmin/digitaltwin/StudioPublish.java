package com.mightyoung.softwarefactory.platformadmin.digitaltwin;

import com.mightyoung.softwarefactory.authorization.AuthorizationWriteGuard;
import com.mightyoung.softwarefactory.identity.CapturedHumanRequest;
import com.mightyoung.softwarefactory.identity.HumanSessionAuthorityTransaction;
import com.mightyoung.softwarefactory.identity.SessionSensitiveException;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import static com.mightyoung.softwarefactory.platformadmin.digitaltwin.StudioPublicationPolicy.*;

/** Dormant, top-level Studio write owner; not a route, bean or transaction join point. */
final class StudioPublish {
    static final String TENANT_SQL = "select set_config('app.tenant_id', ?, true)";
    static final String TENANT_CHECK_SQL = "select current_setting('app.tenant_id',true)";

    private final DataSource source;
    private final AuthorizationWriteGuard guard;
    private final StudioPublicationCommand command;
    private final TransactionTemplate write;

    StudioPublish(DataSource source, PlatformTransactionManager manager, AuthorizationWriteGuard guard,
                  StudioPublicationCommand command) {
        this.source = Objects.requireNonNull(source);
        this.guard = Objects.requireNonNull(guard);
        this.command = Objects.requireNonNull(command);
        write = new TransactionTemplate(Objects.requireNonNull(manager));
        write.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        write.setReadOnly(false);
        write.setTimeout(10);
    }

    Decision publish(CapturedHumanRequest request, Target target, long expectedVersion, byte[] sourceBytes) {
        if (TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isSynchronizationActive()
                || TransactionSynchronizationManager.hasResource(source)
                || request == null) throw SessionSensitiveException.unavailable();
        var context = request.context();
        if (target == null || !context.tenantId().equals(target.tenantId())) throw new Forbidden();
        var staged = write.execute(status -> {
            if (!status.isNewTransaction() || !context.equals(request.context())
                    || !TransactionSynchronizationManager.isActualTransactionActive()
                    || !TransactionSynchronizationManager.isSynchronizationActive()
                    || TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                    || !Integer.valueOf(TransactionDefinition.ISOLATION_READ_COMMITTED)
                            .equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())
                    || !(TransactionSynchronizationManager.getResource(source) instanceof ConnectionHolder holder)
                    || !holder.isSynchronizedWithTransaction() || holder.isRollbackOnly())
                throw SessionSensitiveException.unavailable();
            bindTenant(holder.getConnection(), context.tenantId());
            guard.forBusinessCommand(context.tenantId());
            var scope = new HumanSessionAuthorityTransaction.WriteScope(request,
                    HumanSessionAuthorityTransaction.Guard.FINDING_COMMAND, List.of(context.tenantId()));
            return command.stage(scope, target, expectedVersion, sourceBytes);
        });
        if (staged == null) throw SessionSensitiveException.unavailable();
        return staged.committedDecision();
    }

    static void bindTenant(Connection connection, UUID tenantId) {
        String tenant = tenantId.toString();
        try {
            if (connection.getAutoCommit() || connection.isReadOnly()
                    || connection.getTransactionIsolation() != Connection.TRANSACTION_READ_COMMITTED)
                throw SessionSensitiveException.unavailable();
            try (var sql = connection.prepareStatement(TENANT_SQL)) {
                sql.setString(1, tenant);
                try (var rows = sql.executeQuery()) {
                    if (!rows.next() || !tenant.equals(rows.getString(1)) || rows.next())
                        throw SessionSensitiveException.unavailable();
                }
            }
            try (var sql = connection.prepareStatement(TENANT_CHECK_SQL); var rows = sql.executeQuery()) {
                if (!rows.next() || !tenant.equals(rows.getString(1)) || rows.next())
                    throw SessionSensitiveException.unavailable();
            }
        } catch (SQLException failure) {
            throw SessionSensitiveException.unavailable();
        }
    }
}
