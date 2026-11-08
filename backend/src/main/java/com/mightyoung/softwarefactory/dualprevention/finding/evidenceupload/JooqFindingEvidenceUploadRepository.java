package com.mightyoung.softwarefactory.dualprevention.finding.evidenceupload;

import com.mightyoung.softwarefactory.authorization.AuthorizationWriteGuard;
import com.mightyoung.softwarefactory.files.UploadProducerReadiness.Capability;
import com.mightyoung.softwarefactory.identity.CurrentTenantContext;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.OffsetDateTime;
import javax.sql.DataSource;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import static com.mightyoung.softwarefactory.dualprevention.finding.evidenceupload.FindingEvidenceUploadContracts.*;

@Repository
public final class JooqFindingEvidenceUploadRepository implements FindingEvidenceUploadRepository {
    // Two-int advisory namespace is disjoint from the security guard's two-int ROOT namespace
    // and PostgreSQL's separate single-bigint advisory lock space. Hash collisions only serialize tenants.
    static final int QUOTA_NAMESPACE=0x4D535551;
    private final DSLContext dsl;
    private final DataSource source;
    private final AuthorizationWriteGuard guard;
    private final TransactionTemplate transaction;
    public JooqFindingEvidenceUploadRepository(DSLContext dsl,PlatformTransactionManager manager,AuthorizationWriteGuard guard,DataSource source) {
        this.dsl=Objects.requireNonNull(dsl); this.source=Objects.requireNonNull(source); this.guard=Objects.requireNonNull(guard);
        transaction=new TransactionTemplate(manager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED); transaction.setTimeout(5);
    }
    @Override public <T> T inTenantTransaction(UUID tenantId,Function<Transaction,T> work) {
        FindingEvidenceUploadService.requireIndependentTransaction(); Objects.requireNonNull(tenantId); Objects.requireNonNull(work);
        return transaction.execute(status -> {
            var tx=new TenantTransaction(tenantId);
            try {
                JooqFindingEvidenceUploadReadRepository.configure(tx.dsl,tenantId);
                guard.forFindingCommand(tenantId);
                tx.requireOpen();
                tx.dsl.fetchValue("select pg_advisory_xact_lock(?, ?)",QUOTA_NAMESPACE,tenantId.hashCode());
                T result=work.apply(tx);
                if (status.isRollbackOnly()) {
                    throw new org.springframework.transaction.UnexpectedRollbackException("Upload reservation transaction was rolled back");
                }
                return result;
            } finally { tx.close(); }
        });
    }
    private final class TenantTransaction extends JooqFindingEvidenceUploadSnapshot implements Transaction {
        TenantTransaction(UUID tenantId) { super(JooqFindingEvidenceUploadRepository.this.dsl,tenantId,true,source,guard); }
        @Override public Optional<Intent> existing(UUID actorId,UUID key) {
            requireOpen();
            return dsl.fetchOptional("""
                    select * from public.finding_evidence_upload_intent
                     where tenant_id=cast(? as uuid) and owner_actor_id=cast(? as uuid)
                       and operation=? and idempotency_key=cast(? as uuid)
                    """,tenantId,actorId,OPERATION,key).map(JooqFindingEvidenceUploadSnapshot::toIntent);
        }
        @Override public Quota quota(UUID memberId,UUID setId) {
            requireOpen();
            var r=dsl.fetchOne("""
                    select count(*) as tenant_count, coalesce(sum(declared_size_bytes),0)::bigint as tenant_bytes,
                           count(*) filter (where owner_member_id=cast(? as uuid)) as owner_count,
                           coalesce(sum(declared_size_bytes) filter (where owner_member_id=cast(? as uuid)),0)::bigint as owner_bytes,
                           count(*) filter (where evidence_set_id=cast(? as uuid)) +
                               (select count(*) from public.evidence_item where tenant_id=cast(? as uuid)
                                   and evidence_set_id=cast(? as uuid)) as set_count
                      from public.finding_evidence_upload_intent where tenant_id=cast(? as uuid)
                       and state<>'FINALIZED'
                       and (expires_at>current_timestamp or state in
                         ('RECEIVING','QUARANTINED','SCANNING','VERIFIED_PENDING_FINALIZATION','RETRY_WAIT'))
                    """,memberId,memberId,setId,tenantId,setId,tenantId);
            return new Quota(r.get("owner_count",Long.class),r.get("owner_bytes",Long.class),
                    r.get("tenant_count",Long.class),r.get("tenant_bytes",Long.class),r.get("set_count",Long.class));
        }
        @Override public InsertResult insert(CurrentTenantContext owner,ReserveCommand c,Notice notice,String hash,Capability capability) {
            requireOpen();
            if (!tenantId.equals(owner.tenantId())) throw new IllegalArgumentException("Upload tenant mismatch");
            UUID id=UUID.randomUUID(); OffsetDateTime created=now(); OffsetDateTime expires=created.plus(capability.intentTtl());
            int inserted=dsl.execute("""
                    insert into public.finding_evidence_upload_intent
                      (tenant_id,id,owner_account_id,owner_actor_id,owner_member_id,operation,idempotency_key,
                       fingerprint_version,payload_sha256,finding_id,round_no,notice_id,evidence_set_id,
                       expected_finding_version,expected_evidence_revision,filename,declared_media_type,declared_size_bytes,
                       producer_configuration_version,upload_policy_version,state,created_at,expires_at,technical_updated_at)
                    values (cast(? as uuid),cast(? as uuid),cast(? as uuid),cast(? as uuid),cast(? as uuid),?,cast(? as uuid),
                       ?,?,cast(? as uuid),?,cast(? as uuid),cast(? as uuid),?,?,?,?,?,?,?,'INTENT_CREATED',
                       cast(? as timestamptz),cast(? as timestamptz),cast(? as timestamptz))
                    on conflict (tenant_id,owner_actor_id,operation,idempotency_key) do nothing
                    """,tenantId,id,owner.accountId(),owner.actorId(),owner.memberId(),OPERATION,c.idempotencyKey(),
                    FINGERPRINT_VERSION,hash,c.findingId(),c.roundNo(),notice.id(),c.evidenceSetId(),c.expectedFindingVersion(),
                    c.expectedEvidenceRevision(),c.filename(),c.declaredMediaType(),c.declaredSizeBytes(),
                    capability.configurationVersion(),capability.policyVersion(),created,expires,created);
            if (inserted==0) {
                var replay=existing(owner.actorId(),c.idempotencyKey()).orElseThrow(() -> new IllegalStateException("Upload collision unresolved"));
                if (!hash.equals(replay.payloadSha256())) throw new FindingEvidenceUploadException(FindingEvidenceUploadException.Reason.IDEMPOTENCY_CONFLICT);
                return new InsertResult(replay,false);
            }
            if (inserted!=1) throw new IllegalStateException("Upload reservation was not persisted");
            int audited=dsl.execute("""
                    insert into public.audit_event
                      (tenant_id,id,actor_id,action,resource_type,resource_id,result,policy_version,occurred_at)
                    values (cast(? as uuid),cast(? as uuid),cast(? as uuid),'finding.evidence-upload.reserve',
                      'finding_evidence_upload_intent',cast(? as uuid),'SUCCESS',?,cast(? as timestamptz))
                    """,tenantId,UUID.randomUUID(),owner.actorId(),id,capability.policyVersion(),created);
            if (audited!=1) throw new IllegalStateException("Upload audit was not persisted");
            return new InsertResult(new Intent(id,owner.accountId(),owner.actorId(),owner.memberId(),c.findingId(),c.roundNo(),
                    notice.id(),c.evidenceSetId(),hash,created,expires),true);
        }
    }
}
