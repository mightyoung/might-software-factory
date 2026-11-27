package com.mightyoung.softwarefactory.platformadmin.digitaltwin;

import com.mightyoung.softwarefactory.identity.*;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiFunction;

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
    <T> T run(HumanSessionAuthorityTransaction.WriteScope scope, Target target, BiFunction<Caller, Connection, T> publish) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()
                || !(TransactionSynchronizationManager.getResource(source) instanceof ConnectionHolder holder)
                || !holder.isSynchronizedWithTransaction() || holder.isRollbackOnly()) {
            throw SessionSensitiveException.unavailable();
        }
        try {
            if (scope == null || scope.guard() != HumanSessionAuthorityTransaction.Guard.FINDING_COMMAND)
                throw SessionSensitiveException.unavailable();
            var context = scope.request().context();
            if (target == null || !context.tenantId().equals(target.tenantId())) throw new Forbidden();
            var connection = holder.getConnection();
            return callers.withGuarded(scope, boundary -> {
                callers.requireGuarded(scope, boundary, source, connection);
                var checked = human.requireGuardedCheckedCurrent(scope, boundary);
                if (checked == null || checked.enforcement() != ENFORCING
                        || checked.observedAt(scope, boundary) == null) throw SessionSensitiveException.unavailable();
                try {
                    if (!StudioAuthorityQueries.mayPublish(connection, context)) throw new Forbidden();
                } catch (SQLException failure) {
                    throw SessionSensitiveException.unavailable();
                }
                callers.requireGuarded(scope, boundary, source, connection);
                var result = publish.apply(new Caller(context.tenantId(), context.actorId(), context.memberId(), true, Set.of()), connection);
                callers.requireGuarded(scope, boundary, source, connection);
                return result;
            });
        } catch (RuntimeException | Error failure) {
            holder.setRollbackOnly();
            throw failure;
        }
    }
}
