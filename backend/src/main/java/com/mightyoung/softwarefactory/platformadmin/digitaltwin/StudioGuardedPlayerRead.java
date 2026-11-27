package com.mightyoung.softwarefactory.platformadmin.digitaltwin;

import com.mightyoung.softwarefactory.identity.CurrentHumanSessionAuthority;
import com.mightyoung.softwarefactory.identity.HumanSessionAuthorityTransaction;
import com.mightyoung.softwarefactory.identity.SessionSensitiveException;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import static com.mightyoung.softwarefactory.identity.SessionSensitiveContracts.Enforcement.ENFORCING;
import static com.mightyoung.softwarefactory.platformadmin.digitaltwin.StudioPublicationPolicy.*;

/** Dormant, SELECT-only Player work inside its owner's managed business-command transaction. */
final class StudioGuardedPlayerRead {
    private final DataSource source;
    private final HumanSessionAuthorityTransaction callers;
    private final CurrentHumanSessionAuthority human;

    StudioGuardedPlayerRead(DataSource source, HumanSessionAuthorityTransaction callers, CurrentHumanSessionAuthority human) {
        this.source = Objects.requireNonNull(source);
        this.callers = Objects.requireNonNull(callers);
        this.human = Objects.requireNonNull(human);
    }

    /**
     * The owner takes the shared business-command guard before authority SQL; Studio grant and
     * retirement mutations must take the corresponding exclusive guard. The owner must commit
     * before disclosing this in-transaction projection; no response is constructed here.
     */
    Optional<StudioPlayerPublication.Published> read(HumanSessionAuthorityTransaction.WriteScope scope, Target target, Long version) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()
                || !(TransactionSynchronizationManager.getResource(source) instanceof ConnectionHolder holder)
                || !holder.isSynchronizedWithTransaction() || holder.isRollbackOnly()) {
            throw SessionSensitiveException.unavailable();
        }
        try {
            // FINDING_COMMAND is the legacy proof name for the shared business-command advisory scope.
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
                    if (!StudioAuthorityQueries.mayRead(connection, context, target)) throw new Forbidden();
                } catch (SQLException failure) {
                    throw SessionSensitiveException.unavailable();
                }
                callers.requireGuarded(scope, boundary, source, connection);
                var reader = new Caller(context.tenantId(), context.actorId(), context.memberId(), false, Set.of(target.screenKey()));
                var result = new StudioPlayerPublication(new StudioPublishedLookup(connection)).read(reader, target, version);
                callers.requireGuarded(scope, boundary, source, connection);
                return result;
            });
        } catch (RuntimeException | Error failure) {
            holder.setRollbackOnly();
            throw failure;
        }
    }
}
