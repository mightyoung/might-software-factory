package com.mightyoung.softwarefactory.dualprevention.finding.evidenceupload;

import com.mightyoung.softwarefactory.dualprevention.FindingNotVisibleException;
import com.mightyoung.softwarefactory.files.FileEvidenceTrustUnavailableException;
import com.mightyoung.softwarefactory.files.FileIngestionLedger;
import com.mightyoung.softwarefactory.files.FileIngestionPort;
import com.mightyoung.softwarefactory.files.UploadProducerReadiness;
import com.mightyoung.softwarefactory.identity.CurrentTenantContext;
import com.mightyoung.softwarefactory.identity.SessionContextResolver;
import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import static com.mightyoung.softwarefactory.files.FileIngestionContracts.*;
import static com.mightyoung.softwarefactory.dualprevention.finding.evidenceupload.FindingEvidenceIngestionRepository.*;
import static com.mightyoung.softwarefactory.dualprevention.finding.evidenceupload.FindingEvidenceUploadException.Reason.*;

/** No database transaction spans request streaming, content hashing or private filesystem work. */
@Service
public final class FindingEvidenceReceiveService {
    private final SessionContextResolver sessions;
    private final FindingEvidenceIngestionRepository repository;
    private final FindingEvidenceUploadAccessPolicy access;
    private final FileIngestionLedger files;
    private final FileIngestionPort producer;
    private final UploadProducerReadiness readiness;
    private final FindingEvidenceIngestionProperties limits;
    private final FindingEvidenceTechnicalEligibilityPolicy technical;
    public FindingEvidenceReceiveService(SessionContextResolver sessions,FindingEvidenceIngestionRepository repository,
            FindingEvidenceUploadAccessPolicy access,FileIngestionLedger files,FileIngestionPort producer,
            UploadProducerReadiness readiness,FindingEvidenceIngestionProperties limits,FindingEvidenceTechnicalEligibilityPolicy technical){
        this.sessions=sessions;this.repository=repository;this.access=access;this.files=files;
        this.producer=producer;this.readiness=readiness;this.limits=limits;this.technical=technical;
    }
    public record ReceiveResult(FindingEvidenceUploadContracts.Status status){}
    public static final class AlreadyReceived extends RuntimeException {
        private final String statusUrl;
        public AlreadyReceived(UUID findingId,int roundNo,UUID intentId){
            super("Upload content was already received",null,false,false);
            statusUrl="/api/v1/findings/"+findingId+"/remediation-rounds/"+roundNo+"/evidence-uploads/"+intentId;
        }
        public String statusUrl(){return statusUrl;}
    }
    private record Admission(TechnicalIntent intent,ReceiveAllocation allocation){}
    public ReceiveResult receive(UUID findingId,int roundNo,UUID intentId,String mediaType,long contentLength,InputStream input){
        FindingEvidenceUploadService.requireIndependentTransaction();
        var owner=sessions.currentTenant();
        // Already-received bytes are never reread, replaced or resumed. GET resolves an unknown commit.
        repository.inIntentTransaction(owner.tenantId(),intentId,tx->{
            var i=requireOwner(tx,owner,findingId,roundNo);framing(i,mediaType,contentLength);
            requireNotReceived(tx,i,findingId,roundNo);return null;
        });
        var capability=readiness.requireReady();var resources=producer.currentResources();
        if(contentLength>capability.maxBytes())throw new FindingEvidenceUploadException(DECLARED_SIZE_EXCEEDED);
        if(!capability.allowedMediaTypes().contains(mediaType))throw new FindingEvidenceUploadException(MEDIA_TYPE_NOT_ALLOWED);
        if(capability.streamTimeout().compareTo(Duration.ofSeconds(60))>0)throw new FileEvidenceTrustUnavailableException();
        var admission=repository.inIntentTransaction(owner.tenantId(),intentId,tx->{
            var i=requireOwner(tx,owner,findingId,roundNo);framing(i,mediaType,contentLength);
            requireNotReceived(tx,i,findingId,roundNo);technical.requireService(tx);tx.assertOpen();
            if(i.cancelRequested()||!tx.now().toInstant().isBefore(i.declaration().expiresAt().toInstant())||i.attemptNo()>=3
                    ||!(i.phase()==Phase.INTENT_CREATED||i.phase()==Phase.RETRY_WAIT)
                    ||i.phase()==Phase.RETRY_WAIT&&(i.nextAttemptAt()==null||tx.now().toInstant().isBefore(i.nextAttemptAt())))throw conflict();
            var finding=tx.finding(findingId).orElseThrow(FindingNotVisibleException::new);
            var set=tx.evidenceSet(i.declaration().evidenceSetId()).orElseThrow(FindingNotVisibleException::new);
            if(finding.version()!=i.expectedFindingVersion())throw new FindingEvidenceUploadException(VERSION_CONFLICT);
            if(set.revision()!=i.expectedEvidenceRevision())throw new FindingEvidenceUploadException(EVIDENCE_REVISION_CONFLICT);
            if(!i.producerVersion().equals(capability.configurationVersion())||!i.policyVersion().equals(capability.policyVersion())
                    ||!readiness.isCurrent(capability)||!producer.isCurrentGeneration(resources.generation()))throw new FileEvidenceTrustUnavailableException();
            return files.inCallerTransaction(owner.tenantId(),f->{
                var previous=i.quarantineAllocationId()==null?null:files.load(f,i.quarantineAllocationId());
                if(previous!=null&&previous.baseline()!=null&&!"QUARANTINE_NOT_REUSABLE".equals(i.lastErrorCode()))throw conflict();
                long scratch=resources.scanSlots().stream().mapToLong(ScanSlot::hardCapacityBytes).max().orElseThrow();
                long added=Math.addExact(Math.multiplyExact(contentLength,2),Math.addExact(scratch,resources.receiptReservationBytes()));
                requireCapacity(files.charges(f,owner.memberId()),added,limits);
                UUID receive=UUID.randomUUID(),scan=UUID.randomUUID();long epoch=Math.addExact(i.leaseEpoch(),1);Instant now=tx.now().toInstant();
                UUID file=previous==null?UUID.randomUUID():previous.request().fileId();
                var q=files.reserve(f,allocation(i,file,receive,AllocationKind.QUARANTINE,contentLength,epoch,resources.rootGeneration(),now));
                files.reserve(f,allocation(i,file,scan,AllocationKind.SCANNER_SCRATCH,scratch,epoch,resources.rootGeneration(),now));
                files.reserve(f,allocation(i,file,scan,AllocationKind.IMMUTABLE,contentLength,epoch,resources.rootGeneration(),now));
                files.reserve(f,allocation(i,file,scan,AllocationKind.RECEIPT,resources.receiptReservationBytes(),epoch,resources.rootGeneration(),now));
                var claimed=tx.compareAndSet(i,new Change(Phase.RECEIVING,epoch,i.attemptNo(),receive,scan,UUID.randomUUID(),
                        now.plus(limits.lease()),now,null,q.request().allocationId(),null,null,null,null,null,false,null),owner.actorId(),i.policyVersion());
                return new Admission(claimed,new ReceiveAllocation(q,contentLength,mediaType,resources.generation().generationSha256(),scan));
            });
        });
        try{
            var received=producer.receive(admission.allocation(),input,Deadline.after(capability.streamTimeout()));
            var fresh=sessions.currentTenant();sameOwner(owner,fresh);
            return repository.inIntentTransaction(owner.tenantId(),intentId,tx->{
                var i=requireOwner(tx,fresh,findingId,roundNo);requireFence(i,admission.intent().fence());
                technical.requireService(tx);tx.assertOpen();
                var finding=tx.finding(findingId).orElseThrow(FindingNotVisibleException::new);
                var set=tx.evidenceSet(i.declaration().evidenceSetId()).orElseThrow(FindingNotVisibleException::new);
                if(finding.version()!=i.expectedFindingVersion()||set.revision()!=i.expectedEvidenceRevision()
                        ||i.cancelRequested()||!tx.now().toInstant().isBefore(i.declaration().expiresAt().toInstant()))throw conflict();
                if(!readiness.isCurrent(capability)||!producer.isCurrentGeneration(resources.generation()))throw new FileEvidenceTrustUnavailableException();
                return files.inCallerTransaction(owner.tenantId(),f->{
                    files.recordQuarantine(f,i.fence(),received);
                    var saved=tx.compareAndSet(i,inactive(i,Phase.QUARANTINED,null,null),owner.actorId(),i.policyVersion());
                    return new ReceiveResult(status(saved,tx.now().toInstant()));
                });
            });
        }catch(FileIngestionPort.ContentRejected rejected){
            failReceive(owner,admission.intent(),Phase.FAILED,"MEDIA_REJECTED");throw rejected;
        }catch(FileEvidenceTrustUnavailableException unavailable){
            failReceive(owner,admission.intent(),Phase.RETRY_WAIT,"STORAGE_UNAVAILABLE");throw unavailable;
        }
    }
    public FindingEvidenceUploadContracts.Status cancel(UUID findingId,int roundNo,UUID intentId){
        FindingEvidenceUploadService.requireIndependentTransaction();var owner=sessions.currentTenant();
        return repository.inIntentTransaction(owner.tenantId(),intentId,tx->{
            var i=requireOwner(tx,owner,findingId,roundNo);
            if(i.phase()==Phase.INTENT_CREATED){
                requireEmptyInitial(i);
                var c=Change.unchanged(i);
                i=tx.compareAndSet(i,new Change(Phase.CANCELLED,c.leaseEpoch(),c.attemptNo(),c.attemptId(),c.reservedScanAttemptId(),
                        c.leaseToken(),c.leaseUntil(),c.heartbeatAt(),c.nextAttemptAt(),c.quarantineAllocationId(),c.immutableAllocationId(),
                        c.scanProofId(),c.contentReceiptId(),c.scannerGeneration(),"CANCELLED",true,c.currentExecutionId()),owner.actorId(),i.policyVersion());
            }else if(!i.cancelRequested()&&!i.phase().terminal()){
                var c=Change.unchanged(i);
                i=tx.compareAndSet(i,new Change(c.phase(),c.leaseEpoch(),c.attemptNo(),c.attemptId(),c.reservedScanAttemptId(),
                        c.leaseToken(),c.leaseUntil(),c.heartbeatAt(),c.nextAttemptAt(),c.quarantineAllocationId(),c.immutableAllocationId(),
                        c.scanProofId(),c.contentReceiptId(),c.scannerGeneration(),c.lastErrorCode(),true,c.currentExecutionId()),owner.actorId(),i.policyVersion());
            }
            return status(i,tx.now().toInstant());
        });
    }
    private void failReceive(CurrentTenantContext owner,TechnicalIntent claimed,Phase phase,String code){
        // Failure recording cannot turn a lost commit acknowledgement into a new stream or overwrite a completed source.
        repository.inIntentTransaction(owner.tenantId(),claimed.declaration().id(),tx->{
            var i=tx.current();if(!sameFence(i,claimed.fence()))return null;
            requireOwner(tx,sessions.currentTenant(),i.declaration().findingId(),i.declaration().roundNo());
            tx.compareAndSet(i,inactive(i,phase,code,phase==Phase.RETRY_WAIT?tx.now().toInstant().plusSeconds(5):null),owner.actorId(),i.policyVersion());return null;
        });
    }
    private TechnicalIntent requireOwner(Transaction tx,CurrentTenantContext owner,UUID findingId,int round){
        tx.assertOpen();var i=tx.current();var d=i.declaration();
        if(!owner.tenantId().equals(i.tenantId())||!owner.accountId().equals(d.ownerAccountId())||!owner.actorId().equals(d.ownerActorId())
                ||!owner.memberId().equals(d.ownerMemberId())||!findingId.equals(d.findingId())||round!=d.roundNo())throw new FindingNotVisibleException();
        var finding=tx.finding(findingId).orElseThrow(FindingNotVisibleException::new);
        var notice=tx.notice(findingId,round).orElseThrow(FindingNotVisibleException::new);
        if(!d.noticeId().equals(notice.id()))throw new FindingNotVisibleException();
        access.requireCurrent(owner,finding,round,notice,tx.evidenceSet(d.evidenceSetId()).orElseThrow(FindingNotVisibleException::new));tx.assertOpen();return i;
    }
    static void requireCapacity(PhysicalCharges charges,long added,FindingEvidenceIngestionProperties limits){
        if(added<1||added>limits.ownerBytes()||added>limits.tenantBytes()
                ||charges.ownerTotal()>limits.ownerBytes()-added||charges.tenantTotal()>limits.tenantBytes()-added)throw new FindingEvidenceUploadException(QUOTA_EXCEEDED);
    }
    static AllocationRequest allocation(TechnicalIntent i,UUID file,UUID attempt,AllocationKind kind,long bytes,long epoch,String root,Instant now){
        UUID id=UUID.randomUUID();return new AllocationRequest(i.tenantId(),i.declaration().id(),file,i.declaration().ownerMemberId(),attempt,id,
                kind,root,"object-"+UUID.randomUUID(),"version-"+UUID.randomUUID(),bytes,epoch,now);
    }
    static Change inactive(TechnicalIntent i,Phase phase,String code,Instant next){return new Change(phase,i.leaseEpoch(),i.attemptNo(),i.attemptId(),
            i.reservedScanAttemptId(),null,null,null,next,i.quarantineAllocationId(),i.immutableAllocationId(),i.scanProofId(),i.contentReceiptId(),i.scannerGeneration(),code,i.cancelRequested(),i.currentExecutionId());}
    static void requireEmptyInitial(TechnicalIntent i){
        // SQL independently proves there are no allocation rows while holding the exact parent lock.
        if(i.phase()!=Phase.INTENT_CREATED||i.attemptNo()!=0||i.leaseEpoch()!=0||i.attemptId()!=null
                ||i.reservedScanAttemptId()!=null||i.leaseToken()!=null||i.leaseUntil()!=null||i.heartbeatAt()!=null
                ||i.nextAttemptAt()!=null||i.quarantineAllocationId()!=null||i.immutableAllocationId()!=null
                ||i.scanProofId()!=null||i.contentReceiptId()!=null||i.scannerGeneration()!=null
                ||i.currentExecutionId()!=null||i.lastErrorCode()!=null)throw conflict();
    }
    static boolean sameFence(TechnicalIntent i,TechnicalFence f){return i.phase().active()&&i.tenantId().equals(f.tenantId())&&i.declaration().id().equals(f.intentId())
            &&i.technicalVersion()==f.technicalVersion()&&i.leaseEpoch()==f.leaseEpoch()&&java.util.Objects.equals(i.attemptId(),f.attemptId())&&java.util.Objects.equals(i.leaseToken(),f.leaseToken());}
    static void requireFence(TechnicalIntent i,TechnicalFence f){if(!sameFence(i,f))throw conflict();}
    static QuarantinedObject source(RecordedAllocation a){var o=a.baseline();
        if(o==null||o.kind()!=ObservationKind.COMPLETE_QUARANTINE||a.disposalTombstoneId()!=null||a.finalizedFileId()!=null)throw new FileEvidenceTrustUnavailableException();
        return new QuarantinedObject(a.request().tenantId(),a.request().intentId(),a.request().fileId(),a.request().allocationId(),a.request().attemptId(),o.identity(),o.sha256(),o.mediaType(),o.sizeBytes(),o.observationSha256());}
    static FindingEvidenceUploadContracts.Status status(TechnicalIntent i,Instant now){return new FindingEvidenceUploadContracts.Status(i.declaration().id(),FindingEvidenceUploadContracts.State.valueOf(
            i.phase()==Phase.INTENT_CREATED&&!now.isBefore(i.declaration().expiresAt().toInstant())?"EXPIRED":i.phase().name()),i.declaration().expiresAt(),i.cancelRequested());}
    private static void framing(TechnicalIntent i,String media,long length){
        if(length>i.sizeBytes())throw new FindingEvidenceUploadException(DECLARED_SIZE_EXCEEDED);
        if(length!=i.sizeBytes())throw new FileIngestionPort.ContentRejected(FileIngestionPort.RejectionReason.INVALID_LENGTH);
        if(!i.mediaType().equals(media))throw new FindingEvidenceUploadException(MEDIA_TYPE_NOT_ALLOWED);
    }
    private static void sameOwner(CurrentTenantContext a,CurrentTenantContext b){if(!a.equals(b))throw new FindingNotVisibleException();}
    private void requireNotReceived(Transaction tx,TechnicalIntent i,UUID findingId,int roundNo){
        if(i.quarantineAllocationId()!=null&&(i.phase()==Phase.QUARANTINED||i.phase()==Phase.SCANNING
                ||i.phase()==Phase.VERIFIED_PENDING_FINALIZATION))throw new AlreadyReceived(findingId,roundNo,i.declaration().id());
        if(i.quarantineAllocationId()!=null&&i.phase()==Phase.RETRY_WAIT&&!"QUARANTINE_NOT_REUSABLE".equals(i.lastErrorCode())){
            // A scan retry keeps the original durable stream. Only an incomplete or explicitly
            // unusable source permits a new receive; readiness is irrelevant to this acknowledgement.
            boolean received=files.inCallerTransaction(i.tenantId(),f->files.load(f,i.quarantineAllocationId()).baseline()!=null);
            tx.assertOpen();
            if(received)throw new AlreadyReceived(findingId,roundNo,i.declaration().id());
        }
    }
}
