package com.mightyoung.softwarefactory.dualprevention.finding.evidenceupload;

import com.mightyoung.softwarefactory.authorization.AuthorizationWriteGuard;
import com.mightyoung.softwarefactory.identity.CurrentTenantContext;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import static com.mightyoung.softwarefactory.dualprevention.finding.evidenceupload.FindingEvidenceSetInitializationContracts.*;
import com.mightyoung.softwarefactory.dualprevention.finding.evidenceupload.FindingEvidenceUploadContracts.Notice;

@Repository
public final class JooqFindingEvidenceSetInitializationRepository implements FindingEvidenceSetInitializationRepository {
    private final DSLContext dsl;
    private final DataSource source;
    private final AuthorizationWriteGuard guard;
    private final TransactionTemplate transaction;
    public JooqFindingEvidenceSetInitializationRepository(DSLContext dsl,PlatformTransactionManager manager,
            AuthorizationWriteGuard guard,DataSource source) {
        this.dsl=Objects.requireNonNull(dsl);this.source=Objects.requireNonNull(source);this.guard=Objects.requireNonNull(guard);
        transaction=new TransactionTemplate(manager);transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setTimeout(5);
    }
    @Override public <T> T inTenantTransaction(UUID tenantId,Function<Transaction,T> work) {
        FindingEvidenceUploadService.requireIndependentTransaction();Objects.requireNonNull(tenantId);Objects.requireNonNull(work);
        return transaction.execute(status -> {
            var tx=new TenantTransaction(tenantId);
            try {
                JooqFindingEvidenceUploadReadRepository.configure(tx.dsl,tenantId);
                guard.forFindingCommand(tenantId);tx.requireOpen();
                tx.dsl.fetchValue("select pg_advisory_xact_lock(?, ?)",JooqFindingEvidenceUploadRepository.QUOTA_NAMESPACE,tenantId.hashCode());
                T result=work.apply(tx);
                if(status.isRollbackOnly())throw new org.springframework.transaction.UnexpectedRollbackException("Evidence set initialization rolled back");
                return result;
            }finally{tx.close();}
        });
    }
    private final class TenantTransaction extends JooqFindingEvidenceUploadSnapshot implements Transaction {
        TenantTransaction(UUID tenantId){super(JooqFindingEvidenceSetInitializationRepository.this.dsl,tenantId,true,source,guard);}
        @Override public Optional<Receipt> existing(UUID actorId,UUID key) {
            requireOpen();
            return dsl.fetchOptional("""
                    select * from public.finding_evidence_set_initialization_receipt
                     where tenant_id=cast(? as uuid) and owner_actor_id=cast(? as uuid)
                       and operation=? and idempotency_key=cast(? as uuid)
                    """,tenantId,actorId,OPERATION,key).map(row -> new Receipt(row.get("evidence_set_id",UUID.class),
                    row.get("owner_account_id",UUID.class),row.get("owner_actor_id",UUID.class),row.get("owner_member_id",UUID.class),
                    row.get("finding_id",UUID.class),row.get("round_no",Integer.class),row.get("notice_id",UUID.class),
                    row.get("payload_sha256",String.class),row.get("created_at",OffsetDateTime.class)));
        }
        @Override public Quota quota(UUID memberId) {
            requireOpen();
            var row=dsl.fetchOne("""
                    select count(*) as tenant_count, count(*) filter (where s.owner_member_id=cast(? as uuid)) as owner_count
                      from public.evidence_set s
                      join public.finding f on f.tenant_id=s.tenant_id and f.id=s.finding_id and f.round_no=s.round_no
                      join public.remediation_round r on r.tenant_id=s.tenant_id and r.finding_id=s.finding_id and r.round_no=s.round_no
                     where s.tenant_id=cast(? as uuid) and s.submission_sealed_at is null
                       and s.state in ('UPLOADING','QUARANTINED','READY') and f.state='RECTIFYING' and r.state='OPEN'
                    """,memberId,tenantId);
            return new Quota(row.get("owner_count",Long.class),row.get("tenant_count",Long.class));
        }
        @Override public Receipt initialize(CurrentTenantContext owner,InitializeCommand command,Notice notice,String hash) {
            requireOpen();
            if(!tenantId.equals(owner.tenantId()))throw new IllegalArgumentException("Evidence set tenant mismatch");
            UUID setId=UUID.randomUUID();OffsetDateTime created=now();
            int inserted=dsl.execute("""
                    insert into public.evidence_set
                        (tenant_id,id,finding_id,round_no,owner_member_id,state,created_at,evidence_revision)
                    values (cast(? as uuid),cast(? as uuid),cast(? as uuid),?,cast(? as uuid),'UPLOADING',cast(? as timestamptz),0)
                    """,tenantId,setId,command.findingId(),command.roundNo(),owner.memberId(),created);
            if(inserted!=1)throw new IllegalStateException("Empty evidence set was not persisted");
            int claimed=dsl.execute("""
                    insert into public.finding_evidence_set_initialization_receipt
                        (tenant_id,id,owner_account_id,owner_actor_id,owner_member_id,operation,idempotency_key,
                         fingerprint_version,payload_sha256,finding_id,round_no,notice_id,evidence_set_id,
                         expected_finding_version,initial_evidence_revision,created_at)
                    values (cast(? as uuid),cast(? as uuid),cast(? as uuid),cast(? as uuid),cast(? as uuid),?,cast(? as uuid),
                            ?,?,cast(? as uuid),?,cast(? as uuid),cast(? as uuid),?,0,cast(? as timestamptz))
                    on conflict (tenant_id,owner_actor_id,operation,idempotency_key) do nothing
                    """,tenantId,UUID.randomUUID(),owner.accountId(),owner.actorId(),owner.memberId(),OPERATION,command.idempotencyKey(),
                    FINGERPRINT_VERSION,hash,command.findingId(),command.roundNo(),notice.id(),setId,command.expectedFindingVersion(),created);
            // Normal callers serialize under the same tenant-quota lock. An out-of-protocol collision
            // must roll back this provisional set; a fresh authorized request resolves the durable key.
            if(claimed!=1)throw new FindingEvidenceSetInitializationException(FindingEvidenceSetInitializationException.Reason.IDEMPOTENCY_CONFLICT);
            int audited=dsl.execute("""
                    insert into public.audit_event
                        (tenant_id,id,actor_id,action,resource_type,resource_id,result,policy_version,occurred_at)
                    values (cast(? as uuid),cast(? as uuid),cast(? as uuid),'finding.evidence-set.initialize',
                            'evidence_set',cast(? as uuid),'SUCCESS','evidence-set-init-v1',cast(? as timestamptz))
                    """,tenantId,UUID.randomUUID(),owner.actorId(),setId,created);
            if(audited!=1)throw new IllegalStateException("Evidence set initialization audit was not persisted");
            return new Receipt(setId,owner.accountId(),owner.actorId(),owner.memberId(),command.findingId(),command.roundNo(),notice.id(),hash,created);
        }
    }
}
