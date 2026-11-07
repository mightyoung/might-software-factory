package com.mightyoung.softwarefactory.dualprevention.finding.evidenceupload;

import com.mightyoung.softwarefactory.authorization.AuthorizationWriteGuard;
import com.mightyoung.softwarefactory.dualprevention.FindingNotVisibleException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import javax.sql.DataSource;
import org.jooq.DSLContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import static com.mightyoung.softwarefactory.dualprevention.finding.evidenceupload.FindingEvidenceIngestionRepository.*;

@Repository
public final class JooqFindingEvidenceIngestionRepository implements FindingEvidenceIngestionRepository {
    private final DSLContext dsl;
    private final DataSource source;
    private final AuthorizationWriteGuard guard;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate writes,reads;
    public JooqFindingEvidenceIngestionRepository(DSLContext dsl, DataSource source, AuthorizationWriteGuard guard,
            PlatformTransactionManager manager, ApplicationEventPublisher events) {
        this.dsl=dsl;this.source=source;this.guard=guard;this.events=events;
        writes=new TransactionTemplate(manager);writes.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);writes.setTimeout(5);
        reads=new TransactionTemplate(manager);reads.setReadOnly(true);reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);reads.setTimeout(5);
    }
    @Override public <T> T inIntentTransaction(UUID tenantId,UUID intentId,Function<Transaction,T> work) {
        FindingEvidenceUploadService.requireIndependentTransaction();Objects.requireNonNull(tenantId);Objects.requireNonNull(intentId);Objects.requireNonNull(work);
        return writes.execute(status->{
            var tx=new IntentTransaction(tenantId);
            try {
                JooqFindingEvidenceUploadReadRepository.configure(tx.dsl,tenantId);
                guard.forFindingCommand(tenantId);tx.requireOpen();
                tx.dsl.fetchValue("select pg_advisory_xact_lock(?,?)",JooqFindingEvidenceUploadRepository.QUOTA_NAMESPACE,tenantId.hashCode());
                // Locate without an intent lock. Ordered finding/set locks precede the actual intent lock.
                var locator=tx.dsl.fetchOptional("select finding_id,evidence_set_id from public.finding_evidence_upload_intent where tenant_id=cast(? as uuid) and id=cast(? as uuid)",tenantId,intentId)
                        .orElseThrow(FindingNotVisibleException::new);
                var findingId=locator.get("finding_id",UUID.class);var setId=locator.get("evidence_set_id",UUID.class);
                tx.finding(findingId).orElseThrow(FindingNotVisibleException::new);
                tx.evidenceSet(setId).orElseThrow(FindingNotVisibleException::new);
                tx.intent=tx.dsl.fetchOptional("select * from public.finding_evidence_upload_intent where tenant_id=cast(? as uuid) and id=cast(? as uuid) for update",tenantId,intentId)
                        .map(JooqFindingEvidenceIngestionRepository::map).orElseThrow(FindingNotVisibleException::new);
                if(!findingId.equals(tx.intent.declaration().findingId())||!setId.equals(tx.intent.declaration().evidenceSetId()))throw conflict();
                T result=work.apply(tx);tx.requireOpen();
                if(status.isRollbackOnly())throw new org.springframework.transaction.UnexpectedRollbackException("Ingestion transaction was rolled back");
                return result;
            }finally{tx.close();}
        });
    }
    @Override public List<UUID> due(UUID tenantId,UUID afterIntentId,int limit) {
        FindingEvidenceUploadService.requireIndependentTransaction();
        if(limit<1||limit>32)throw new IllegalArgumentException("Ingestion discovery must be bounded");
        return reads.execute(status->{
            var snapshot=new JooqFindingEvidenceUploadSnapshot(dsl,tenantId,false,source,null);
            try{
                JooqFindingEvidenceUploadReadRepository.configure(snapshot.dsl,tenantId);snapshot.requireOpen();
                return List.copyOf(snapshot.dsl.fetch("""
                    select id from public.finding_evidence_upload_intent where tenant_id=cast(? as uuid)
                      and (cast(? as uuid) is null or id>cast(? as uuid))
                      and ((state='INTENT_CREATED' and (cancel_requested or expires_at<=current_timestamp))
                        or state in ('QUARANTINED','VERIFIED_PENDING_FINALIZATION') or (state='RETRY_WAIT' and next_attempt_at<=current_timestamp)
                        or state in ('FAILED','REJECTED','CANCELLED','EXPIRED')
                        or (state='FINALIZED' and exists(select 1 from public.file_ingestion_allocation a
                            left join lateral(select event_kind,retained_bytes from public.file_ingestion_allocation_event
                                where tenant_id=a.tenant_id and allocation_id=a.id order by event_no desc limit 1) e on true
                            where a.tenant_id=finding_evidence_upload_intent.tenant_id and a.intent_id=finding_evidence_upload_intent.id
                              and coalesce(e.retained_bytes,a.reserved_bytes)>0
                              and coalesce(e.event_kind,'RETAIN') not in ('TRANSFER_FINALIZED','DISPOSAL_CONFIRMED')))
                        or (state in ('RECEIVING','SCANNING') and lease_until<=current_timestamp)
                        or (state in ('QUARANTINED','RETRY_WAIT','VERIFIED_PENDING_FINALIZATION')
                            and (cancel_requested or expires_at<=current_timestamp)))
                     order by id limit ?
                    """,tenantId,afterIntentId,afterIntentId,limit).getValues("id",UUID.class));
            }finally{snapshot.close();}
        });
    }
    private final class IntentTransaction extends JooqFindingEvidenceUploadSnapshot implements Transaction {
        private TechnicalIntent intent;
        IntentTransaction(UUID tenantId){super(JooqFindingEvidenceIngestionRepository.this.dsl,tenantId,true,source,guard);}
        @Override public void assertOpen(){requireOpen();}
        @Override public TechnicalIntent current(){requireOpen();return intent;}
        @Override public OffsetDateTime now(){requireOpen();return dsl.fetchOne("select clock_timestamp() as now").get("now",OffsetDateTime.class);}
        @Override public TechnicalIntent compareAndSet(TechnicalIntent expected,Change c,UUID auditActor,String policyVersion){
            try { return persistChange(expected,c,auditActor,policyVersion); }
            catch(RuntimeException failure){invalidate();throw failure;}
        }
        @Override public TechnicalIntent finalizeEvidence(TechnicalIntent expected,
                com.mightyoung.softwarefactory.files.VerifiedContentCheckpoint verified,
                UUID evidenceItemId,UUID fileLinkId,UUID actor){
            try {
                requireOpen();var r=verified.receipt();var declaration=expected.declaration();
                if(intent!=expected||intent.phase()!=Phase.VERIFIED_PENDING_FINALIZATION||intent.cancelRequested()
                        ||!now().isBefore(declaration.expiresAt())||!tenantId.equals(r.tenantId())
                        ||!declaration.id().equals(r.intentId())||!expected.contentReceiptId().equals(r.receiptId())
                        ||!expected.scanProofId().equals(r.scanProofId()))throw conflict();
                var finding=finding(declaration.findingId()).orElseThrow(FindingNotVisibleException::new);
                var set=evidenceSet(declaration.evidenceSetId()).orElseThrow(FindingNotVisibleException::new);
                if(finding.version()!=expected.expectedFindingVersion()||set.revision()!=expected.expectedEvidenceRevision()||set.sealed())throw conflict();
                if(!Boolean.TRUE.equals(dsl.fetchValue("""
                    select exists(select 1 from public.file_ingestion_publication
                        where tenant_id=? and intent_id=? and evidence_item_id=? and file_link_id=?
                          and file_id=? and content_receipt_id=? and publication_actor_id=?
                          and append_transaction_id=pg_current_xact_id())
                    """,tenantId,declaration.id(),evidenceItemId,fileLinkId,r.fileId(),r.receiptId(),actor)))throw conflict();
                if(dsl.execute("""
                    insert into public.evidence_item(tenant_id,id,evidence_set_id,source_kind,file_id,sha256,state,captured_at,verification_level)
                    values(?,?,?,'FILE',?,?,'READY',cast(? as timestamptz),'SCANNED_CONTENT_V2')
                    """,tenantId,evidenceItemId,declaration.evidenceSetId(),r.fileId(),r.sha256(),offset(r.issuedAt()))!=1)throw conflict();
                if(dsl.execute("update public.evidence_set set state='READY',verified_at=cast(? as timestamptz) where tenant_id=? and id=? and submission_sealed_at is null",
                        offset(r.issuedAt()),tenantId,declaration.evidenceSetId())!=1)throw conflict();
                var c=Change.unchanged(expected);
                return persistChange(expected,new Change(Phase.FINALIZED,c.leaseEpoch(),c.attemptNo(),c.attemptId(),c.reservedScanAttemptId(),
                        c.leaseToken(),c.leaseUntil(),c.heartbeatAt(),c.nextAttemptAt(),c.quarantineAllocationId(),c.immutableAllocationId(),
                        c.scanProofId(),c.contentReceiptId(),c.scannerGeneration(),c.lastErrorCode(),c.cancelRequested(),c.currentExecutionId()),actor,expected.policyVersion());
            }catch(RuntimeException failure){invalidate();throw failure;}
        }
        private TechnicalIntent persistChange(TechnicalIntent expected,Change c,UUID auditActor,String policyVersion){
            requireOpen();Objects.requireNonNull(c);Objects.requireNonNull(auditActor);Objects.requireNonNull(policyVersion);
            if(intent!=expected)throw conflict();
            var row=dsl.fetchOptional("""
                update public.finding_evidence_upload_intent set state=?,technical_version=technical_version+1,
                    lease_epoch=?,attempt_no=?,attempt_id=cast(? as uuid),reserved_scan_attempt_id=cast(? as uuid),
                    lease_token=cast(? as uuid),lease_until=cast(? as timestamptz),heartbeat_at=cast(? as timestamptz),
                    next_attempt_at=cast(? as timestamptz),quarantine_allocation_id=cast(? as uuid),
                    immutable_allocation_id=cast(? as uuid),scan_proof_id=cast(? as uuid),content_receipt_id=cast(? as uuid),
                    scanner_generation_sha256=?,last_error_code=?,cancel_requested=?,current_execution_id=cast(? as uuid),technical_updated_at=statement_timestamp()
                  where tenant_id=cast(? as uuid) and id=cast(? as uuid) and state=? and technical_version=?
                    and attempt_id is not distinct from cast(? as uuid) and lease_token is not distinct from cast(? as uuid)
                    and lease_epoch=? returning *
                """,c.phase().name(),c.leaseEpoch(),c.attemptNo(),c.attemptId(),c.reservedScanAttemptId(),c.leaseToken(),
                    offset(c.leaseUntil()),offset(c.heartbeatAt()),offset(c.nextAttemptAt()),c.quarantineAllocationId(),
                    c.immutableAllocationId(),c.scanProofId(),c.contentReceiptId(),c.scannerGeneration(),c.lastErrorCode(),c.cancelRequested(),c.currentExecutionId(),
                    tenantId,expected.declaration().id(),expected.phase().name(),expected.technicalVersion(),expected.attemptId(),
                    expected.leaseToken(),expected.leaseEpoch()).orElseThrow(FindingEvidenceIngestionRepository::conflict);
            intent=map(row);requireOpen();
            if(dsl.execute("""
                insert into public.audit_event(tenant_id,id,actor_id,action,resource_type,resource_id,result,policy_version,occurred_at)
                values(cast(? as uuid),cast(? as uuid),cast(? as uuid),?,'finding_evidence_upload_intent',cast(? as uuid),'SUCCESS',?,statement_timestamp())
                """,tenantId,UUID.randomUUID(),auditActor,"finding.evidence-ingestion."+c.phase().name().toLowerCase(java.util.Locale.ROOT),
                    expected.declaration().id(),policyVersion)!=1)throw new IllegalStateException("Ingestion audit was not persisted");
            if(c.phase()==Phase.QUARANTINED)events.publishEvent(new FindingEvidenceQuarantinedEvent(tenantId,intent.declaration().id(),intent.technicalVersion()));
            return intent;
        }
    }
    private static OffsetDateTime offset(Instant i){return i==null?null:i.atOffset(java.time.ZoneOffset.UTC);}
    private static Instant instant(org.jooq.Record r,String name){var t=r.get(name,OffsetDateTime.class);return t==null?null:t.toInstant();}
    private static TechnicalIntent map(org.jooq.Record r){
        return new TechnicalIntent(r.get("tenant_id",UUID.class),JooqFindingEvidenceUploadSnapshot.toIntent(r),
            r.get("declared_media_type",String.class),r.get("declared_size_bytes",Long.class),
            r.get("expected_finding_version",Long.class),r.get("expected_evidence_revision",Long.class),
            r.get("producer_configuration_version",String.class),r.get("upload_policy_version",String.class),
            Phase.valueOf(r.get("state",String.class)),r.get("technical_version",Long.class),r.get("lease_epoch",Long.class),
            r.get("attempt_no",Integer.class),r.get("attempt_id",UUID.class),r.get("reserved_scan_attempt_id",UUID.class),
            r.get("lease_token",UUID.class),instant(r,"lease_until"),instant(r,"heartbeat_at"),instant(r,"next_attempt_at"),
            r.get("quarantine_allocation_id",UUID.class),r.get("immutable_allocation_id",UUID.class),r.get("scan_proof_id",UUID.class),
            r.get("content_receipt_id",UUID.class),r.get("scanner_generation_sha256",String.class),r.get("last_error_code",String.class),
            r.get("cancel_requested",Boolean.class),instant(r,"technical_updated_at"),r.get("current_execution_id",UUID.class));
    }
}
