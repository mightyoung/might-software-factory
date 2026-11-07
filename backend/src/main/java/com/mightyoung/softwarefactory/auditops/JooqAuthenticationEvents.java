package com.mightyoung.softwarefactory.auditops;

import com.mightyoung.softwarefactory.identity.AuthenticationEvent;
import com.mightyoung.softwarefactory.identity.AuthenticationEvents;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.ZoneOffset;
import java.util.Objects;

/** One independent append after authentication and session transaction completion. */
@Repository
public final class JooqAuthenticationEvents implements AuthenticationEvents {
    private final DSLContext dsl;
    private final TransactionTemplate transaction;

    public JooqAuthenticationEvents(DSLContext dsl, PlatformTransactionManager manager) {
        this.dsl = Objects.requireNonNull(dsl);
        transaction = new TransactionTemplate(Objects.requireNonNull(manager));
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setReadOnly(false);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    @Override public void record(AuthenticationEvent event) {
        Objects.requireNonNull(event, "event");
        if (TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isSynchronizationActive()
                || !TransactionSynchronizationManager.getResourceMap().isEmpty()) {
            throw new IllegalStateException("Authentication events require completed authentication and session transactions");
        }
        AuthenticationEvent.Subject subject = event.subject();
        transaction.executeWithoutResult(status -> {
            int inserted = dsl.execute("""
                    insert into public.authentication_event
                        (attempt_id,outcome,account_id,actor_id,tenant_id,member_id,occurred_at,policy_version)
                    values (cast(? as uuid),cast(? as varchar),cast(? as uuid),cast(? as uuid),
                            cast(? as uuid),cast(? as uuid),cast(? as timestamptz),'login-audit-v1')
                    """, event.attemptId(), event.outcome().name(),
                    subject == null ? null : subject.accountId(), subject == null ? null : subject.actorId(),
                    subject == null ? null : subject.tenantId(), subject == null ? null : subject.memberId(),
                    event.occurredAt().atOffset(ZoneOffset.UTC));
            if (inserted != 1) throw new IllegalStateException("Authentication event did not persist");
        });
    }
}
