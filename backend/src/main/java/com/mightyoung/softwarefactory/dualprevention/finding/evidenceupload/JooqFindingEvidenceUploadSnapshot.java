package com.mightyoung.softwarefactory.dualprevention.finding.evidenceupload;

import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.jooq.impl.DefaultConnectionProvider;
import com.mightyoung.softwarefactory.authorization.AuthorizationWriteGuard;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import static com.mightyoung.softwarefactory.dualprevention.finding.evidenceupload.FindingEvidenceUploadContracts.*;

/** Callback-scoped SQL reads; write callbacks lock finding before evidence set. */
class JooqFindingEvidenceUploadSnapshot implements FindingEvidenceUploadReadRepository.Snapshot {
    protected final DSLContext dsl;
    protected final UUID tenantId;
    private final boolean write;
    private final DataSource source;
    private final ConnectionHolder holder;
    private final Connection connection;
    private final AuthorizationWriteGuard guard;
    private final Thread owner = Thread.currentThread();
    private boolean open = true;
    private boolean invalidated;
    private final TransactionSynchronization lifetime = new TransactionSynchronization() {
        @Override public void savepointRollback(Object savepoint) {
            invalidated=true;
            holder.setRollbackOnly();
        }
        @Override public void beforeCommit(boolean readOnly) {
            if (invalidated) {
                holder.setRollbackOnly();
                throw inactive();
            }
        }
    };
    JooqFindingEvidenceUploadSnapshot(DSLContext configuredDsl, UUID tenantId, boolean write,
            DataSource source, AuthorizationWriteGuard guard) {
        this.tenantId=tenantId; this.write=write; this.source=source; this.guard=guard;
        Object resource=TransactionSynchronizationManager.getResource(source);
        if (!(resource instanceof ConnectionHolder current) || !current.isSynchronizedWithTransaction()
                || current.isRollbackOnly() || !TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) throw inactive();
        holder=current;
        connection=DataSourceUtils.getTargetConnection(holder.getConnection());
        // All owned SQL uses the exact connection captured from the transaction manager's datasource.
        dsl=DSL.using(configuredDsl.configuration().derive(new DefaultConnectionProvider(connection)));
        TransactionSynchronizationManager.registerSynchronization(lifetime);
    }
    final void close() { open=false; }
    final void invalidate() { invalidated=true; holder.setRollbackOnly(); }
    private static IllegalStateException inactive() {
        return new IllegalStateException("Evidence upload transaction context is no longer active");
    }
    final void requireOpen() {
        int isolation=write ? TransactionDefinition.ISOLATION_READ_COMMITTED : TransactionDefinition.ISOLATION_REPEATABLE_READ;
        if (!open || invalidated || owner!=Thread.currentThread()
                || TransactionSynchronizationManager.getResource(source)!=holder || !holder.isSynchronizedWithTransaction()
                || holder.isRollbackOnly() || DataSourceUtils.getTargetConnection(holder.getConnection())!=connection
                || !TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()
                || !TransactionSynchronizationManager.getSynchronizations().contains(lifetime)
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()==write
                || !Integer.valueOf(isolation).equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())) {
            throw inactive();
        }
        try {
            if (connection.getAutoCommit() || connection.isReadOnly()==write
                    || connection.getTransactionIsolation()!=isolation) throw inactive();
            try (var statement=connection.prepareStatement("""
                    select current_setting('app.tenant_id', true),
                           current_setting('transaction_isolation'), current_setting('transaction_read_only')
                    """); var row=statement.executeQuery()) {
                if (!row.next() || !tenantId.toString().equals(row.getString(1))
                        || !(write ? "read committed" : "repeatable read").equals(row.getString(2))
                        || !(write ? "off" : "on").equals(row.getString(3))) throw inactive();
            }
            if (write) guard.requireFindingCommand(tenantId);
        } catch (SQLException failure) {
            holder.setRollbackOnly();
            throw new IllegalStateException("Evidence upload transaction validation failed",failure);
        }
    }
    @Override public Optional<Finding> finding(UUID id) {
        requireOpen();
        return dsl.fetchOptional("""
                select id, organization_id, state, round_no, version from public.finding
                 where tenant_id=cast(? as uuid) and id=cast(? as uuid)
                """+(write ? " for update" : ""),tenantId,id).map(r -> new Finding(r.get("id",UUID.class),
                r.get("organization_id",UUID.class),r.get("state",String.class),r.get("round_no",Integer.class),r.get("version",Long.class)));
    }
    @Override public Optional<Notice> notice(UUID findingId,int roundNo) {
        requireOpen();
        return dsl.fetchOptional("""
                select n.id, n.assignee_id, r.state='OPEN' as open_round
                  from public.remediation_notice n join public.remediation_round r
                    on r.tenant_id=n.tenant_id and r.finding_id=n.finding_id and r.round_no=n.round_no
                 where n.tenant_id=cast(? as uuid) and n.finding_id=cast(? as uuid) and n.round_no=?
                """,tenantId,findingId,roundNo).map(r -> new Notice(r.get("id",UUID.class),
                r.get("assignee_id",UUID.class),r.get("open_round",Boolean.class)));
    }
    @Override public Optional<EvidenceSet> evidenceSet(UUID id) {
        requireOpen();
        return dsl.fetchOptional("""
                select id, finding_id, round_no, owner_member_id, evidence_revision, submission_sealed_at is not null as sealed
                  from public.evidence_set where tenant_id=cast(? as uuid) and id=cast(? as uuid)
                """+(write ? " for update" : ""),tenantId,id).map(r -> new EvidenceSet(r.get("id",UUID.class),
                r.get("finding_id",UUID.class),r.get("round_no",Integer.class),r.get("owner_member_id",UUID.class),
                r.get("evidence_revision",Long.class),r.get("sealed",Boolean.class)));
    }
    @Override public Optional<Intent> intent(UUID id) {
        requireOpen();
        return dsl.fetchOptional("select * from public.finding_evidence_upload_intent where tenant_id=cast(? as uuid) and id=cast(? as uuid)",
                tenantId,id).map(JooqFindingEvidenceUploadSnapshot::toIntent);
    }
    @Override public Optional<EvidenceSet> openSetOf(UUID findingId,int roundNo,UUID ownerMemberId) {
        requireOpen();
        return dsl.fetchOptional("""
                select id, finding_id, round_no, owner_member_id, evidence_revision, submission_sealed_at is not null as sealed
                  from public.evidence_set
                 where tenant_id=cast(? as uuid) and finding_id=cast(? as uuid) and round_no=? and owner_member_id=cast(? as uuid)
                   and submission_sealed_at is null
                 order by created_at desc, id desc limit 1
                """,tenantId,findingId,roundNo,ownerMemberId).map(r -> new EvidenceSet(r.get("id",UUID.class),
                r.get("finding_id",UUID.class),r.get("round_no",Integer.class),r.get("owner_member_id",UUID.class),
                r.get("evidence_revision",Long.class),r.get("sealed",Boolean.class)));
    }
    @Override public java.util.List<Intent> intentsOf(UUID evidenceSetId) {
        requireOpen();
        return dsl.fetch("select * from public.finding_evidence_upload_intent where tenant_id=cast(? as uuid) and evidence_set_id=cast(? as uuid) order by created_at, id limit 100",
                tenantId,evidenceSetId).map(JooqFindingEvidenceUploadSnapshot::toIntent);
    }
    @Override public OffsetDateTime now() {
        requireOpen(); return dsl.fetchOne("select current_timestamp as now").get("now",OffsetDateTime.class);
    }
    private static State state(String value) {
        try { return State.valueOf(value); }
        catch (IllegalArgumentException failure) { throw new com.mightyoung.softwarefactory.files.FileEvidenceTrustUnavailableException(); }
    }
    static Intent toIntent(org.jooq.Record r) {
        return new Intent(r.get("id",UUID.class),r.get("owner_account_id",UUID.class),r.get("owner_actor_id",UUID.class),
                r.get("owner_member_id",UUID.class),r.get("finding_id",UUID.class),r.get("round_no",Integer.class),
                r.get("notice_id",UUID.class),r.get("evidence_set_id",UUID.class),r.get("payload_sha256",String.class),
                r.get("created_at",OffsetDateTime.class),r.get("expires_at",OffsetDateTime.class),state(r.get("state",String.class)),r.get("cancel_requested",Boolean.class));
    }
}
