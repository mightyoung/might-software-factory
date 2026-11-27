package com.mightyoung.softwarefactory.platformadmin.digitaltwin;

import com.mightyoung.softwarefactory.identity.HumanSessionAuthorityTransaction.WriteScope;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Objects;
import java.util.UUID;

import static com.mightyoung.softwarefactory.platformadmin.digitaltwin.StudioPublicationPolicy.*;

/** Dormant guarded composition. The existing owning transaction, not this class, commits. */
final class StudioPublicationCommand {
    private final StudioGuardedPublish guard;

    StudioPublicationCommand(StudioGuardedPublish guard) { this.guard = Objects.requireNonNull(guard); }

    Staged stage(WriteScope scope, Target target, long expectedVersion, byte[] source) {
        return guard.run(scope, target, authorized -> {
            var actor = authorized.caller();
            var unit = new Unit(authorized);
            var decision = StudioPublicationService.stage(actor, target, expectedVersion, source, unit);
            authorized.requireCurrent();
            var staged = new Staged(decision);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCompletion(int status) {
                    staged.committed = status == STATUS_COMMITTED;
                }
            });
            return staged;
        });
    }

    static final class Staged {
        private final Decision decision;
        private volatile boolean committed;

        private Staged(Decision decision) { this.decision = decision; }

        Decision committedDecision() {
            if (!committed) throw new IllegalStateException("Studio publication has not committed");
            return decision;
        }
    }

    private static final class Unit implements StudioPublicationService.PublicationUnit {
        private final StudioGuardedPublish.Authorized authority;
        private final StudioPublicationWrites writes;
        private final StudioPublicationAudit audit = new StudioPublicationAudit();
        private StudioPublicationWrites.Locked locked;
        private StudioPublicationAudit.Record event;

        private Unit(StudioGuardedPublish.Authorized authority) {
            this.authority = authority;
            writes = new StudioPublicationWrites(authority.connection());
        }

        @Override public Head lockHead(Target target) {
            authority.requireTarget(target);
            if (locked != null) throw authority.unavailable();
            locked = writes.lock(target, UUID.randomUUID());
            return locked.head();
        }

        @Override public void append(StudioPublicationService.Snapshot snapshot) {
            authority.requireTarget(snapshot.target());
            if (locked == null || event != null) throw authority.unavailable();
            event = new StudioPublicationAudit.Record(UUID.randomUUID(), locked.streamId(),
                    UUID.randomUUID(), UUID.randomUUID(), snapshot.target());
            writes.append(locked, snapshot, authority.caller(), event.auditId(), authority.observedAt());
        }

        @Override public void audit(Caller caller, StudioPublicationService.Snapshot snapshot) {
            authority.requireTarget(snapshot.target());
            if (caller != authority.caller() || event == null) throw authority.unavailable();
            var receipt = audit.append(authority, event);
            if (!event.auditId().equals(audit.requireMatches(authority, event, receipt))) throw authority.unavailable();
            authority.requireCurrent();
            writes.advance(locked, snapshot);
        }
    }
}
