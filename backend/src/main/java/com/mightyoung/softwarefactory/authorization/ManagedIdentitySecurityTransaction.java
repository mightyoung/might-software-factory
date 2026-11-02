package com.mightyoung.softwarefactory.authorization;

import com.mightyoung.softwarefactory.identity.CurrentTenantContext;
import com.mightyoung.softwarefactory.identity.IdentitySecurityTransaction;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import java.util.function.Supplier;

/** Binds identity security writes to one top-level, globally serialized JDBC transaction. */
@Component
public final class ManagedIdentitySecurityTransaction implements IdentitySecurityTransaction {
    private final DataSource dataSource;
    private final AuthorizationWriteGuard guard;
    private final TransactionTemplate transaction;

    public ManagedIdentitySecurityTransaction(DataSource dataSource, PlatformTransactionManager manager,
                                              AuthorizationWriteGuard guard) {
        this.dataSource = Objects.requireNonNull(dataSource);
        this.guard = Objects.requireNonNull(guard);
        transaction = new TransactionTemplate(Objects.requireNonNull(manager));
        transaction.setReadOnly(false);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    @Override public <T> T execute(CurrentTenantContext context, Supplier<T> work) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(work, "work");
        if (TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.hasResource(dataSource)) {
            throw new AuthorizationWriteGuardException("Identity security writes require a top-level transaction");
        }
        return transaction.execute(status -> {
            guard.forGlobalSecurityChange();
            ConnectionHolder holder = activeHolder();
            Connection connection = holder.getConnection();
            try (var statement = connection.prepareStatement("select set_config('app.tenant_id', ?, true)")) {
                statement.setString(1, context.tenantId().toString());
                try (var result = statement.executeQuery()) {
                    if (!result.next() || !context.tenantId().toString().equals(result.getString(1))) {
                        throw new AuthorizationWriteGuardException("Unable to scope the identity security transaction");
                    }
                }
            } catch (SQLException failure) {
                throw new AuthorizationWriteGuardException("Unable to scope the identity security transaction", failure);
            }
            TransactionSynchronizationManager.registerSynchronization(new SecurityScope(context, holder, connection));
            return work.get();
        });
    }

    @Override public void requireActive(CurrentTenantContext context) {
        ConnectionHolder holder = activeHolder();
        for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
            if (synchronization instanceof SecurityScope scope && scope.owner() == this) {
                if (scope.invalidated || !scope.context.equals(context) || scope.holder != holder
                        || scope.connection != holder.getConnection()) {
                    holder.setRollbackOnly();
                    throw new AuthorizationWriteGuardException("Identity security transaction context is not valid");
                }
                // Reentry validates the actual isolation/read-only state and any savepoint invalidation.
                guard.forGlobalSecurityChange();
                return;
            }
        }
        holder.setRollbackOnly();
        throw new AuthorizationWriteGuardException("An identity security transaction marker is required");
    }

    private ConnectionHolder activeHolder() {
        Object resource = TransactionSynchronizationManager.getResource(dataSource);
        if (!(resource instanceof ConnectionHolder holder) || !holder.isSynchronizedWithTransaction()
                || holder.isRollbackOnly() || !TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new AuthorizationWriteGuardException("A managed identity security datasource transaction is required");
        }
        return holder;
    }

    private final class SecurityScope implements TransactionSynchronization {
        private final CurrentTenantContext context;
        private final ConnectionHolder holder;
        private final Connection connection;
        private boolean invalidated;
        private SecurityScope(CurrentTenantContext context, ConnectionHolder holder, Connection connection) {
            this.context = context; this.holder = holder; this.connection = connection;
        }
        private ManagedIdentitySecurityTransaction owner() { return ManagedIdentitySecurityTransaction.this; }
        @Override public void savepointRollback(Object savepoint) { invalidated = true; holder.setRollbackOnly(); }
        @Override public void beforeCommit(boolean readOnly) {
            if (invalidated) {
                holder.setRollbackOnly();
                throw new AuthorizationWriteGuardException("Identity security writes cannot commit after savepoint rollback");
            }
        }
    }
}
