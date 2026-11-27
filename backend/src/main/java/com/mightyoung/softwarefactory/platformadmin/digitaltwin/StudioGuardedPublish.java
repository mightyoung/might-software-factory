package com.mightyoung.softwarefactory.platformadmin.digitaltwin;

import com.mightyoung.softwarefactory.identity.*;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Set;
import java.time.Instant;
import java.util.function.Function;

import static com.mightyoung.softwarefactory.identity.SessionSensitiveContracts.Enforcement.ENFORCING;
import static com.mightyoung.softwarefactory.platformadmin.digitaltwin.StudioPublicationPolicy.*;

/** Dormant authorization step in the owner's already-guarded command transaction. */
final class StudioGuardedPublish {
    private final DataSource source;
    private final HumanSessionAuthorityTransaction callers;
    private final CurrentHumanSessionAuthority human;

    StudioGuardedPublish(DataSource source, HumanSessionAuthorityTransaction callers, CurrentHumanSessionAuthority human) {
        this.source = Objects.requireNonNull(source);
        this.callers = Objects.requireNonNull(callers);
        this.human = Objects.requireNonNull(human);
    }

    /** The trusted callback must use this connection and complete synchronously; the outer owner commits. */
    <T> T run(HumanSessionAuthorityTransaction.WriteScope scope, Target target, Function<Authorized, T> publish) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()
                || !(TransactionSynchronizationManager.getResource(source) instanceof ConnectionHolder holder)
                || !holder.isSynchronizedWithTransaction() || holder.isRollbackOnly()) {
            throw SessionSensitiveException.unavailable();
        }
        try {
            // Legacy name: this proves the shared tenant business COMMAND lock, not a finding grant.
            // Future Studio grant and retirement writes must take the exclusive authorization guard.
            if (scope == null || scope.guard() != HumanSessionAuthorityTransaction.Guard.FINDING_COMMAND)
                throw SessionSensitiveException.unavailable();
            var context = scope.request().context();
            if (target == null || !context.tenantId().equals(target.tenantId())) throw new Forbidden();
            var connection = holder.getConnection();
            return callers.withGuarded(scope, boundary -> {
                callers.requireGuarded(scope, boundary, source, connection);
                var checked = human.requireGuardedCheckedCurrent(scope, boundary);
                if (checked == null || checked.enforcement() != ENFORCING) throw SessionSensitiveException.unavailable();
                var observedAt = checked.observedAt(scope, boundary);
                if (observedAt == null) throw SessionSensitiveException.unavailable();
                try {
                    if (!StudioAuthorityQueries.mayPublish(connection, context)) throw new Forbidden();
                } catch (SQLException failure) {
                    throw SessionSensitiveException.unavailable();
                }
                var actor = new Caller(context.tenantId(), context.actorId(), context.memberId(), true, Set.of());
                var authorized = new Authorized(actor, target, connection, observedAt, holder, () -> {
                    if (TransactionSynchronizationManager.getResource(source) != holder || holder.getConnection() != connection)
                        throw SessionSensitiveException.unavailable();
                    callers.requireGuarded(scope, boundary, source, connection);
                });
                try {
                    authorized.requireCurrent();
                    var result = publish.apply(authorized);
                    authorized.requireCurrent();
                    return result;
                } finally {
                    authorized.open = false;
                }
            });
        } catch (RuntimeException | Error failure) {
            holder.setRollbackOnly();
            throw failure;
        }
    }

    static final class Authorized {
        private final Caller caller;
        private final Target target;
        private final Connection connection;
        private final Instant observedAt;
        private final ConnectionHolder holder;
        private final Runnable proof;
        private final Thread thread = Thread.currentThread();
        private boolean open = true;
        private boolean auditStarted;

        private Authorized(Caller caller, Target target, Connection connection, Instant observedAt,
                           ConnectionHolder holder, Runnable proof) {
            this.caller = caller;
            this.target = target;
            this.connection = connection;
            this.observedAt = observedAt;
            this.holder = holder;
            this.proof = proof;
        }

        Caller caller() { requireCurrent(); return caller; }
        Connection connection() { requireCurrent(); return connection; }
        Instant observedAt() { requireCurrent(); return observedAt; }

        void requireTarget(Target expected) {
            requireCurrent();
            if (!target.equals(expected)) throw unavailable();
        }

        void startAudit() {
            requireCurrent();
            if (auditStarted) throw unavailable();
            auditStarted = true;
        }

        void requireCurrent() {
            if (Thread.currentThread() != thread || !open) throw SessionSensitiveException.unavailable();
            if (holder.isRollbackOnly()) throw SessionSensitiveException.unavailable();
            try { proof.run(); }
            catch (RuntimeException failure) { throw unavailable(); }
        }

        SessionSensitiveException unavailable() {
            if (Thread.currentThread() == thread && open) holder.setRollbackOnly();
            return SessionSensitiveException.unavailable();
        }
    }

}
