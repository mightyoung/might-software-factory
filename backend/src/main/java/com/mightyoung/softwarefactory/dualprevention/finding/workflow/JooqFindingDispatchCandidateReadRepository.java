package com.mightyoung.softwarefactory.dualprevention.finding.workflow;

import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.time.Duration;

/** Read-only RR; this hint deliberately acquires no command/advisory or row write locks. */
@Repository
public final class JooqFindingDispatchCandidateReadRepository implements FindingDispatchCandidateReadRepository {
    private final DSLContext dsl;
    private final TransactionTemplate transaction;
    private final String statementTimeout;

    public JooqFindingDispatchCandidateReadRepository(DSLContext dsl, PlatformTransactionManager manager,
            @Value("${software-factory.findings.dispatch-candidates.statement-timeout:2s}") Duration timeout) {
        this.dsl = Objects.requireNonNull(dsl);
        long millis=Objects.requireNonNull(timeout).toMillis();
        if(millis<1||millis>5000) throw new IllegalArgumentException("Candidate statement timeout must be 1ms to 5s");
        statementTimeout=Long.toString(millis);
        transaction = new TransactionTemplate(Objects.requireNonNull(manager));
        transaction.setReadOnly(true);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        transaction.setTimeout(5);
    }

    @Override public <T> T inTenantReadSnapshot(UUID tenantId, Function<Snapshot, T> work) {
        Objects.requireNonNull(tenantId); Objects.requireNonNull(work);
        if (TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isSynchronizationActive()
                || !TransactionSynchronizationManager.getResourceMap().isEmpty()) {
            throw new IllegalStateException("Candidate reads require their own tenant snapshot");
        }
        return transaction.execute(status -> {
            String applied = (String)dsl.fetchValue("select set_config('app.tenant_id', ?, true)", tenantId.toString());
            if (!tenantId.toString().equals(applied)) throw new IllegalStateException("Candidate tenant snapshot unavailable");
            if(!Boolean.TRUE.equals(dsl.fetchValue("select set_config('statement_timeout', ?, true) is not null",statementTimeout))) {
                throw new IllegalStateException("Candidate statement timeout unavailable");
            }
            var snapshot = new TenantSnapshot(tenantId);
            try { return work.apply(snapshot); }
            finally { snapshot.open = false; }
        });
    }

    private final class TenantSnapshot implements Snapshot {
        private final UUID tenantId;
        private final Thread owner = Thread.currentThread();
        private boolean open = true;
        TenantSnapshot(UUID tenantId) { this.tenantId = tenantId; }

        private void requireOpen() {
            if (!open || Thread.currentThread() != owner
                    || !TransactionSynchronizationManager.isActualTransactionActive()
                    || !TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                    || !Integer.valueOf(TransactionDefinition.ISOLATION_REPEATABLE_READ)
                            .equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())) {
                throw new IllegalStateException("Candidate snapshot is not active");
            }
        }

        @Override public Optional<Finding> find(UUID findingId) {
            requireOpen();
            return dsl.fetchOptional("""
                    select id, organization_id, state, round_no, version from public.finding
                     where tenant_id=cast(? as uuid) and id=cast(? as uuid)
                    """, tenantId, Objects.requireNonNull(findingId)).map(row -> new Finding(
                    row.get("id", UUID.class), row.get("organization_id", UUID.class),
                    FindingWorkflowState.valueOf(row.get("state", String.class)),
                    row.get("round_no", Integer.class), row.get("version", Long.class)));
        }

        @Override public boolean hasNotice(UUID findingId, int roundNo) {
            requireOpen();
            return Boolean.TRUE.equals(dsl.fetchValue("""
                    select exists(select 1 from public.remediation_notice
                     where tenant_id=cast(? as uuid) and finding_id=cast(? as uuid) and round_no=?)
                    """, tenantId, findingId, roundNo));
        }
    }
}
