package com.mightyoung.softwarefactory.platformadmin.digitaltwin;

import java.sql.SQLException;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.UUID;

import com.mightyoung.softwarefactory.platformadmin.digitaltwin.StudioGuardedPublish.Authorized;
import com.mightyoung.softwarefactory.platformadmin.digitaltwin.StudioPublicationPolicy.Target;

/** Dormant Studio-specific success audit. No active action registry or other-business scopes. */
final class StudioPublicationAudit {
    static final String INSERT_SQL = """
            insert into public.audit_event(tenant_id,id,actor_id,action,resource_type,resource_id,
                result,diff_redacted,trace_id,policy_version,occurred_at)
            values(?,?,?,'studio_scene.publish','studio_scene',?,'SUCCESS',
                jsonb_build_object('schemaVersion',1,'commandId',cast(? as text),'changeKind','PUBLISH',
                    'changedFieldCategories',jsonb_build_array('PUBLICATION')),?,'studio-publication-v1',?)
            """;

    /** IDs allocated by the owning command. Actor and timestamp are never inputs. */
    record Record(UUID auditId, UUID streamId, UUID commandId, UUID traceId, Target target) {
        Record {
            Objects.requireNonNull(auditId);
            Objects.requireNonNull(streamId);
            Objects.requireNonNull(commandId);
            Objects.requireNonNull(traceId);
            Objects.requireNonNull(target);
        }
    }

    final class Receipt {
        private final Authorized scope;
        private final Record record;

        private Receipt(Authorized scope, Record record) {
            this.scope = scope;
            this.record = record;
        }

        private StudioPublicationAudit issuer() { return StudioPublicationAudit.this; }
    }

    Receipt append(Authorized scope, Record record) {
        scope.requireTarget(record.target());
        scope.startAudit();
        var actor = scope.caller();
        var observedAt = scope.observedAt();
        try (var sql = scope.connection().prepareStatement(INSERT_SQL)) {
            sql.setObject(1, actor.verifiedTenantId());
            sql.setObject(2, record.auditId());
            sql.setObject(3, actor.actorId());
            sql.setObject(4, record.streamId());
            sql.setString(5, record.commandId().toString());
            sql.setString(6, record.traceId().toString());
            sql.setObject(7, observedAt.atOffset(ZoneOffset.UTC));
            if (sql.executeUpdate() != 1) throw scope.unavailable();
            scope.requireCurrent();
            return new Receipt(scope, record);
        } catch (SQLException failure) {
            throw scope.unavailable();
        }
    }

    /** Verifies the expected reserved ID and record; not an acknowledgment of commit. */
    UUID requireMatches(Authorized scope, Record expected, Receipt receipt) {
        scope.requireTarget(expected.target());
        if (receipt == null || receipt.issuer() != this || receipt.scope != scope || !receipt.record.equals(expected))
            throw scope.unavailable();
        scope.requireCurrent();
        return expected.auditId();
    }
}
