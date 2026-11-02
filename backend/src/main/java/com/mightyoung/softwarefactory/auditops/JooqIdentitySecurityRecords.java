package com.mightyoung.softwarefactory.auditops;

import com.mightyoung.softwarefactory.identity.CurrentTenantContext;
import com.mightyoung.softwarefactory.identity.IdentitySecurityAudit;
import com.mightyoung.softwarefactory.identity.IdentitySecurityEvents;
import com.mightyoung.softwarefactory.identity.IdentitySecurityTransaction;
import com.mightyoung.softwarefactory.identity.PasswordChangeFailureReason;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.UUID;

/** Append-only compact password diagnostics, never credential or request material. */
@Repository
public final class JooqIdentitySecurityRecords implements IdentitySecurityAudit, IdentitySecurityEvents {
    private final DSLContext dsl;
    private final IdentitySecurityTransaction securityTransaction;
    private final TransactionTemplate failedTransaction;

    public JooqIdentitySecurityRecords(DSLContext dsl, IdentitySecurityTransaction securityTransaction,
                                       PlatformTransactionManager manager) {
        this.dsl = Objects.requireNonNull(dsl);
        this.securityTransaction = Objects.requireNonNull(securityTransaction);
        failedTransaction = new TransactionTemplate(Objects.requireNonNull(manager));
        failedTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        failedTransaction.setReadOnly(false);
        failedTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    @Override public void passwordChanged(CurrentTenantContext context, long previousVersion, long newVersion,
                                          UUID traceId, Instant occurredAt) {
        requireFields(context, traceId, occurredAt);
        if (previousVersion < 1 || previousVersion == Long.MAX_VALUE || newVersion != previousVersion + 1) {
            throw new IllegalArgumentException("Password audit requires one credential generation advance");
        }
        securityTransaction.requireActive(context);
        int inserted = dsl.execute("""
                insert into public.audit_event
                    (tenant_id,id,actor_id,action,resource_type,resource_id,result,reason,diff_redacted,
                     trace_id,policy_version,occurred_at)
                values (cast(? as uuid),cast(? as uuid),cast(? as uuid),'account.password.change','account',
                        cast(? as uuid),'SUCCESS',null,
                        jsonb_build_object('oldCredentialVersion',cast(? as bigint),'newCredentialVersion',cast(? as bigint)),
                        cast(? as varchar),'password-change-v1',cast(? as timestamptz))
                """,context.tenantId(),UUID.randomUUID(),context.actorId(),context.accountId(),
                previousVersion,newVersion,traceId.toString(),timestamp(occurredAt));
        if (inserted != 1) throw new IllegalStateException("Identity security audit did not persist");
        insertSecurityEvent(context,"SUCCESS",null,traceId,occurredAt);
    }

    @Override public void passwordChangeFailed(CurrentTenantContext context, PasswordChangeFailureReason reason,
                                               UUID traceId, Instant occurredAt) {
        requireFields(context, traceId, occurredAt);
        Objects.requireNonNull(reason,"reason");
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Failed identity security events must be recorded after transaction completion");
        }
        failedTransaction.executeWithoutResult(status -> {
            String applied = (String) dsl.fetchValue("select set_config('app.tenant_id', ?, true)",context.tenantId().toString());
            if (!context.tenantId().toString().equals(applied)) {
                throw new IllegalStateException("Unable to scope the failed identity security event");
            }
            insertSecurityEvent(context,reason.eventResult(),reason.name(),traceId,occurredAt);
        });
    }

    private void insertSecurityEvent(CurrentTenantContext context,String result,String reason,UUID traceId,Instant occurredAt) {
        int inserted = dsl.execute("""
                insert into public.security_event
                    (tenant_id,id,account_id,actor_id,type,result,reason_code,trace_id,policy_version,occurred_at)
                values (cast(? as uuid),cast(? as uuid),cast(? as uuid),cast(? as uuid),'PASSWORD_CHANGE',?,?,
                        cast(? as uuid),'password-change-v1',cast(? as timestamptz))
                """,context.tenantId(),UUID.randomUUID(),context.accountId(),context.actorId(),result,reason,traceId,timestamp(occurredAt));
        if (inserted != 1) throw new IllegalStateException("Identity security event did not persist");
    }
    private static OffsetDateTime timestamp(Instant value) { return value.atOffset(ZoneOffset.UTC); }
    private static void requireFields(CurrentTenantContext context,UUID traceId,Instant occurredAt) {
        Objects.requireNonNull(context,"context"); Objects.requireNonNull(traceId,"traceId"); Objects.requireNonNull(occurredAt,"occurredAt");
    }
}
