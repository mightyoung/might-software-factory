package com.mightyoung.softwarefactory.platformadmin.digitaltwin;

import com.mightyoung.softwarefactory.authorization.AuthorizationWriteGuard;
import com.mightyoung.softwarefactory.identity.CapturedHumanRequest;
import com.mightyoung.softwarefactory.identity.HumanSessionAuthorityTransaction;
import com.mightyoung.softwarefactory.identity.SessionSensitiveException;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.mightyoung.softwarefactory.platformadmin.digitaltwin.StudioPublicationPolicy.*;

/** Dormant Player transaction owner. This does not register a bean, route or cache. */
final class StudioPlayerRead {
    private final DataSource source;
    private final AuthorizationWriteGuard guard;
    private final StudioGuardedPlayerRead guarded;
    private final TransactionTemplate transaction;

    StudioPlayerRead(DataSource source, PlatformTransactionManager manager, AuthorizationWriteGuard guard,
                     StudioGuardedPlayerRead guarded) {
        this.source = Objects.requireNonNull(source);
        this.guard = Objects.requireNonNull(guard);
        this.guarded = Objects.requireNonNull(guarded);
        transaction = new TransactionTemplate(Objects.requireNonNull(manager));
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        // The authority guard requires the managed read-write scope, even when the Player only reads rows.
        transaction.setReadOnly(false);
        transaction.setTimeout(10);
    }

    Optional<StudioPlayerPublication.Published> read(CapturedHumanRequest request, Target target, Long version) {
        if (request == null || TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isSynchronizationActive()
                || TransactionSynchronizationManager.hasResource(source))
            throw SessionSensitiveException.unavailable();
        var context = request.context();
        if (target == null || !context.tenantId().equals(target.tenantId())) throw new Forbidden();

        var committed = new AtomicBoolean();
        var projection = transaction.execute(status -> {
            if (!status.isNewTransaction() || !context.equals(request.context())
                    || !TransactionSynchronizationManager.isActualTransactionActive()
                    || !TransactionSynchronizationManager.isSynchronizationActive()
                    || TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                    || !Integer.valueOf(TransactionDefinition.ISOLATION_READ_COMMITTED)
                            .equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())
                    || !(TransactionSynchronizationManager.getResource(source) instanceof ConnectionHolder holder)
                    || !holder.isSynchronizedWithTransaction() || holder.isRollbackOnly())
                throw SessionSensitiveException.unavailable();

            StudioPublish.bindTenant(holder.getConnection(), context.tenantId());
            guard.forBusinessCommand(context.tenantId());
            var scope = new HumanSessionAuthorityTransaction.WriteScope(request,
                    HumanSessionAuthorityTransaction.Guard.FINDING_COMMAND, List.of(context.tenantId()));
            var result = guarded.read(scope, target, version);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCompletion(int completionStatus) {
                    committed.set(completionStatus == STATUS_COMMITTED);
                }
            });
            return result;
        });
        if (!committed.get() || projection == null) throw SessionSensitiveException.unavailable();
        return projection;
    }
}
