package com.mightyoung.softwarefactory.dualprevention.finding.evidenceupload;

import com.mightyoung.softwarefactory.dualprevention.FindingNotVisibleException;
import com.mightyoung.softwarefactory.files.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import static com.mightyoung.softwarefactory.files.FileIngestionContracts.*;
import static com.mightyoung.softwarefactory.dualprevention.finding.evidenceupload.FindingEvidenceIngestionRepository.*;
import static com.mightyoung.softwarefactory.dualprevention.finding.evidenceupload.FindingEvidenceReceiveService.*;

/** Bounded intent polling. All producer calls and lease close operations occur after SQL callbacks return. */
@Component
@ConditionalOnProperty(name="software-factory.findings.evidence-ingestion.enabled",havingValue="true")
public final class FindingEvidenceIngestionWorker implements SmartLifecycle {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(FindingEvidenceIngestionWorker.class);
    private final FindingEvidenceIngestionRepository repository;
    private final FindingEvidenceTechnicalEligibilityPolicy eligibility;
    private final FindingEvidenceIngestionProperties properties;
    private final FindingEvidenceIngestionWakeListener wake;
    private final FileIngestionLedger files;
    private final FileIngestionPort producer;
    private final UploadProducerReadiness readiness;
    private final AtomicBoolean running=new AtomicBoolean();
    private final Set<WorkKey> submitted=ConcurrentHashMap.newKeySet();
    private final Map<WorkKey,UUID> recoveryCursors=new ConcurrentHashMap<>();
    private final Map<UUID,UUID> discoveryCursors=new ConcurrentHashMap<>();
    private int nextTenant;
    private ScheduledExecutorService timer;
    private ThreadPoolExecutor workers;
    private record WorkKey(UUID tenant,UUID intent){}
    private enum NextWork { NONE, SCAN, RECOVER, FINALIZE }
    private record Candidate(TechnicalIntent intent,QuarantinedObject source,RecordedAllocation scratch,
                             RecordedAllocation immutable,RecordedAllocation receipt,ScanPlan plan,boolean unused){}
    private record Claimed(TechnicalIntent intent,QuarantinedObject source,ScanAllocation scan,
                           CopyAllocation copy,ReceiptAllocation receipt,RecordedExecution execution){}

    public FindingEvidenceIngestionWorker(FindingEvidenceIngestionRepository repository,
            FindingEvidenceTechnicalEligibilityPolicy eligibility,FindingEvidenceIngestionProperties properties,
            FindingEvidenceIngestionWakeListener wake,FileIngestionLedger files,FileIngestionPort producer,
            UploadProducerReadiness readiness){
        this.repository=repository;this.eligibility=eligibility;this.properties=properties;
        this.wake=wake;this.files=files;this.producer=producer;this.readiness=readiness;
    }
    @Override public synchronized void start(){
        if(!properties.enabled()||!running.compareAndSet(false,true))return;
        timer=Executors.newScheduledThreadPool(2,Thread.ofPlatform().daemon().name("evidence-ingestion-clock-",0).factory());
        workers=new ThreadPoolExecutor(2,2,0,TimeUnit.SECONDS,new SynchronousQueue<>(),
                Thread.ofPlatform().daemon().name("evidence-ingestion-",0).factory(),new ThreadPoolExecutor.AbortPolicy());
        timer.scheduleWithFixedDelay(this::pollSafely,0,5,TimeUnit.SECONDS);
    }
    @Override public synchronized void stop(){
        running.set(false);
        if(timer!=null)timer.shutdownNow();
        if(workers!=null)workers.shutdownNow();
        // Interrupting a Java task never establishes native quiescence or discounts retained bytes.
    }
    @Override public boolean isRunning(){return running.get();}
    private void pollSafely(){try{pollOnce();}catch(RuntimeException unavailable){reportFailure("DISCOVERY",null,null,unavailable);}}
    void pollOnce(){
        if(!properties.enabled()||workers==null||!running.get())return;
        int remaining=32;
        var tenants=properties.tenants().stream().sorted().toList();
        for(int n=0;n<tenants.size()&&remaining>0;n++){
            UUID tenant=tenants.get(nextTenant%tenants.size());nextTenant=(nextTenant+1)%tenants.size();
            // Hints do not replace due discovery. Per-tenant UUID cursors wrap after a bounded page.
            var due=repository.due(tenant,discoveryCursors.get(tenant),remaining);
            if(due.isEmpty())discoveryCursors.remove(tenant);
            wake.drain(tenant,remaining); // Publications are hints; every dispatched item must be a persisted due candidate.
            for(UUID intent:due){
                if(remaining--==0)return;
                var key=new WorkKey(tenant,intent);
                if(!submitted.add(key)){discoveryCursors.put(tenant,intent);continue;}
                try{workers.execute(()->{try{processSafely(tenant,intent);}finally{submitted.remove(key);}});discoveryCursors.put(tenant,intent);}
                catch(RejectedExecutionException busy){submitted.remove(key);return;}
            }
        }
    }
    void processSafely(UUID tenant,UUID intent){
        try{process(tenant,intent);}catch(RuntimeException unavailable){reportFailure("PROCESS",tenant,intent,unavailable);}
    }
    private static void reportFailure(String stage,UUID tenant,UUID intent,RuntimeException failure){
        String category=failure instanceof FileEvidenceTrustUnavailableException?"TRUST_UNAVAILABLE":
                failure instanceof FindingNotVisibleException?"CONTEXT_REVOKED":
                failure instanceof FindingEvidenceUploadException?"CONFLICT":
                failure instanceof org.jooq.exception.DataAccessException||failure instanceof org.springframework.dao.DataAccessException?"PERSISTENCE":"INTERNAL";
        // Never pass the exception object, message, scanner output, or filesystem paths to the logger.
        LOG.warn("Evidence ingestion failure stage={} category={} tenant={} intent={}",stage,category,tenant,intent);
    }
    void process(UUID tenant,UUID intentId){
        FindingEvidenceUploadService.requireIndependentTransaction();
        if(!properties.enabled()||!properties.tenants().contains(tenant))return;
        NextWork next=repository.inIntentTransaction(tenant,intentId,tx->{
            UUID actor=eligibility.requireService(tx);var i=tx.current();Instant now=tx.now().toInstant();
            if(i.phase()==Phase.INTENT_CREATED){
                if(i.cancelRequested()||!now.isBefore(i.declaration().expiresAt().toInstant())){
                    requireEmptyInitial(i);
                    Phase end=i.cancelRequested()?Phase.CANCELLED:Phase.EXPIRED;
                    tx.compareAndSet(i,inactive(i,end,end.name(),null),actor,i.policyVersion());
                }
                return NextWork.NONE;
            }
            if(i.phase().active()){
                if(i.leaseUntil()!=null&&!now.isBefore(i.leaseUntil())){
                    var c=inactive(i,Phase.RETRY_WAIT,"LEASE_LOST",now.plusSeconds(backoff(i.attemptNo())));
                    tx.compareAndSet(i,withEpoch(c,Math.addExact(i.leaseEpoch(),1)),actor,i.policyVersion());
                }
                return NextWork.RECOVER;
            }
            if(i.phase().terminal()||i.phase()==Phase.FINALIZED)return NextWork.RECOVER;
            if(i.cancelRequested()||!now.isBefore(i.declaration().expiresAt().toInstant())){
                Phase end=i.cancelRequested()?Phase.CANCELLED:Phase.EXPIRED;
                tx.compareAndSet(i,inactive(i,end,end.name(),null),actor,i.policyVersion());return NextWork.RECOVER;
            }
            if(i.phase()==Phase.RETRY_WAIT&&i.attemptNo()==3){
                if(i.nextAttemptAt()!=null&&!now.isBefore(i.nextAttemptAt()))
                    tx.compareAndSet(i,inactive(i,Phase.FAILED,"ATTEMPTS_EXHAUSTED",null),actor,i.policyVersion());
                return NextWork.RECOVER;
            }
            if(i.phase()==Phase.VERIFIED_PENDING_FINALIZATION){eligibility.requireOwner(tx);return NextWork.FINALIZE;}
            if(!scanDue(i,now))return NextWork.RECOVER;
            eligibility.requireOwner(tx);return NextWork.SCAN;
        });
        if(next==NextWork.NONE)return;
        if(next==NextWork.RECOVER){recoverRetained(tenant,intentId);return;}
        var capability=readiness.requireReady();var resources=producer.currentResources();
        if(next==NextWork.FINALIZE){
            try{finalizePending(tenant,intentId,resources,capability);}
            catch(FileEvidenceTrustUnavailableException stale){
                reportFailure("FINALIZE",tenant,intentId,stale);
                retryFinalization(tenant,intentId);
            }
            catch(RuntimeException failure){reportFailure("FINALIZE",tenant,intentId,failure);throw failure;}
            return;
        }
        for(ScanSlot slot:resources.scanSlots()){
            Candidate candidate=preflight(tenant,intentId,resources,slot,capability);
            if(candidate==null)return;
            final PreparedSlotLease prepared;
            try{prepared=producer.prepareSlot(candidate.plan(),Deadline.after(java.time.Duration.ofSeconds(5)));}
            catch(FileEvidenceTrustUnavailableException busy){continue;}
            // Both try-with-resources boundaries are outside SQL, including unknown commit acknowledgement.
            try(prepared){
                Claimed claim=claim(candidate,resources,capability,prepared);
                if(claim==null)return;
                runClaim(claim,resources,capability,prepared);
                return;
            }
        }
    }
    private void retryFinalization(UUID tenant,UUID intent){
        repository.inIntentTransaction(tenant,intent,tx->{
            UUID actor=eligibility.requireService(tx);var i=tx.current();var now=tx.now().toInstant();
            if(i.phase()==Phase.VERIFIED_PENDING_FINALIZATION&&!i.cancelRequested()&&now.isBefore(i.declaration().expiresAt().toInstant()))
                tx.compareAndSet(i,inactive(i,Phase.RETRY_WAIT,"SCANNER_UNAVAILABLE",now.plusSeconds(backoff(i.attemptNo()))),actor,i.policyVersion());
            return null;
        });
    }
    private void finalizePending(UUID tenant,UUID intentId,ProducerResources resources,UploadProducerReadiness.Capability capability){
        repository.inIntentTransaction(tenant,intentId,tx->{
            UUID actor=eligibility.requireService(tx);var i=tx.current();
            if(i.phase()!=Phase.VERIFIED_PENDING_FINALIZATION||i.cancelRequested()
                    ||!tx.now().toInstant().isBefore(i.declaration().expiresAt().toInstant()))return null;
            eligibility.requireOwner(tx);requireCurrent(tx,i,resources,capability);
            UUID item=publicationId("item",tenant,intentId),link=publicationId("link",tenant,intentId);
            var request=new IngestionPublicationRequest(tenant,intentId,new VerifiedCheckpoint(i.scanProofId(),i.contentReceiptId(),i.immutableAllocationId()),
                    i.declaration().findingId(),i.declaration().roundNo(),i.declaration().evidenceSetId(),item,link,
                    i.declaration().ownerMemberId(),i.declaration().ownerActorId(),actor,i.technicalVersion(),i.expectedFindingVersion(),i.expectedEvidenceRevision());
            return files.inCallerTransaction(tenant,f->{
                var verified=files.publishVerified(f,request,resources.generation());
                tx.finalizeEvidence(i,verified,item,link,actor);return null;
            });
        });
    }
    static UUID publicationId(String kind,UUID tenant,UUID intent){
        return UUID.nameUUIDFromBytes(("finding-evidence-publication-v1:"+kind+":"+tenant+":"+intent).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    private Candidate preflight(UUID tenant,UUID intent,ProducerResources resources,ScanSlot slot,
                                 UploadProducerReadiness.Capability capability){
        return repository.inIntentTransaction(tenant,intent,tx->{
            eligibility.requireService(tx);eligibility.requireOwner(tx);var i=tx.current();
            if(!scanDue(i,tx.now().toInstant()))return null;
            requireCurrent(tx,i,resources,capability);
            return files.inCallerTransaction(tenant,f->{
                var quarantine=files.load(f,i.quarantineAllocationId());var source=source(quarantine);
                boolean unused=Objects.equals(i.attemptId(),source.sourceAttemptId());
                UUID attempt=unused?i.reservedScanAttemptId():UUID.randomUUID();
                if(attempt==null||attempt.equals(source.sourceAttemptId()))throw conflict();
                long epoch=Math.addExact(i.leaseEpoch(),1);Instant now=tx.now().toInstant();
                RecordedAllocation scratch,copy,receipt;
                if(unused){
                    scratch=files.loadForAttempt(f,intent,attempt,AllocationKind.SCANNER_SCRATCH);
                    copy=files.loadForAttempt(f,intent,attempt,AllocationKind.IMMUTABLE);
                    receipt=files.loadForAttempt(f,intent,attempt,AllocationKind.RECEIPT);
                    if(scratch.request().reservedBytes()<slot.hardCapacityBytes()
                            ||receipt.request().reservedBytes()<resources.receiptReservationBytes())throw new FileEvidenceTrustUnavailableException();
                }else{
                    scratch=prospective(allocation(i,source.fileId(),attempt,AllocationKind.SCANNER_SCRATCH,slot.hardCapacityBytes(),epoch,resources.rootGeneration(),now));
                    copy=prospective(allocation(i,source.fileId(),attempt,AllocationKind.IMMUTABLE,i.sizeBytes(),epoch,resources.rootGeneration(),now));
                    receipt=prospective(allocation(i,source.fileId(),attempt,AllocationKind.RECEIPT,resources.receiptReservationBytes(),epoch,resources.rootGeneration(),now));
                }
                var plan=new ScanPlan(tenant,intent,source.fileId(),source.sourceAttemptId(),attempt,source.allocationId(),
                    scratch.request().allocationId(),UUID.randomUUID(),resources.physicalResourceRealmId(),resources.profileSha256(),
                    slot.descriptorId(),i.technicalVersion(),epoch,resources.generation().generationSha256(),source.observationSha256());
                return new Candidate(i,source,scratch,copy,receipt,plan,unused);
            });
        });
    }
    private Claimed claim(Candidate candidate,ProducerResources resources,UploadProducerReadiness.Capability capability,PreparedSlotLease lease){
        var plan=candidate.plan();
        return repository.inIntentTransaction(plan.tenantId(),plan.intentId(),tx->{
            UUID actor=eligibility.requireService(tx);eligibility.requireOwner(tx);var i=tx.current();Instant now=tx.now().toInstant();
            if(i.technicalVersion()!=plan.expectedOldTechnicalVersion()||!scanDue(i,now)||!lease.isValidFor(plan))throw conflict();
            requireCurrent(tx,i,resources,capability);
            return files.inCallerTransaction(i.tenantId(),f->{
                var source=source(files.load(f,plan.quarantineAllocationId()));
                if(!source.equals(candidate.source()))throw conflict();
                RecordedAllocation scratch,copy,receipt;
                if(candidate.unused()){
                    if(!Objects.equals(i.attemptId(),plan.sourceAttemptId())||!Objects.equals(i.reservedScanAttemptId(),plan.scanAttemptId()))throw conflict();
                    scratch=files.loadForAttempt(f,plan.intentId(),plan.scanAttemptId(),AllocationKind.SCANNER_SCRATCH);
                    copy=files.loadForAttempt(f,plan.intentId(),plan.scanAttemptId(),AllocationKind.IMMUTABLE);
                    receipt=files.loadForAttempt(f,plan.intentId(),plan.scanAttemptId(),AllocationKind.RECEIPT);
                    requireUnused(scratch,candidate.scratch());requireUnused(copy,candidate.immutable());requireUnused(receipt,candidate.receipt());
                }else{
                    long added=Math.addExact(candidate.scratch().retainedBytes(),Math.addExact(candidate.immutable().retainedBytes(),candidate.receipt().retainedBytes()));
                    requireCapacity(files.charges(f,i.declaration().ownerMemberId()),added,properties);
                    scratch=files.reserve(f,candidate.scratch().request());copy=files.reserve(f,candidate.immutable().request());receipt=files.reserve(f,candidate.receipt().request());
                }
                var fence=new TechnicalFence(i.tenantId(),plan.intentId(),plan.scanAttemptId(),UUID.randomUUID(),Math.addExact(i.technicalVersion(),1),plan.plannedClaimEpoch());
                var execution=files.recordExecution(f,ExecutionDescriptor.forClaim(plan,fence,now));
                var claimed=tx.compareAndSet(i,new Change(Phase.SCANNING,fence.leaseEpoch(),i.attemptNo()+1,plan.scanAttemptId(),plan.scanAttemptId(),
                    fence.leaseToken(),now.plus(properties.lease()),now,null,i.quarantineAllocationId(),i.immutableAllocationId(),i.scanProofId(),
                    i.contentReceiptId(),i.scannerGeneration(),null,i.cancelRequested(),execution.descriptor().executionId()),actor,i.policyVersion());
                return new Claimed(claimed,source,new ScanAllocation(scratch,claimed.fence(),resources.scanSlots().stream().filter(slot->slot.descriptorId().equals(plan.slotId())).findFirst().orElseThrow().hardCapacityBytes(),plan.slotId()),
                    new CopyAllocation(copy),new ReceiptAllocation(receipt),execution);
            });
        });
    }
    private void runClaim(Claimed claim,ProducerResources resources,UploadProducerReadiness.Capability capability,PreparedSlotLease lease){
        ScheduledFuture<?> heartbeat=timer==null?null:timer.scheduleWithFixedDelay(()->{
            try{heartbeat(claim.intent());}catch(RuntimeException noAuthority){/* Old execution can no longer checkpoint; native deadline still applies. */}
        },properties.heartbeat().toSeconds(),properties.heartbeat().toSeconds(),TimeUnit.SECONDS);
        try{
            var outcome=producer.scan(claim.source(),claim.scan(),resources.generation(),claim.intent().fence(),claim.execution(),lease,Deadline.after(properties.scanDeadline()));
            var verified=producer.publishImmutable(claim.source(),outcome,claim.copy(),claim.receipt(),Deadline.after(java.time.Duration.ofSeconds(60)));
            repository.inIntentTransaction(claim.intent().tenantId(),claim.intent().declaration().id(),tx->{
                UUID actor=eligibility.requireService(tx);eligibility.requireOwner(tx);var i=tx.current();requireLiveExecution(tx,i,claim.intent());
                requireCurrent(tx,i,resources,capability);
                return files.inCallerTransaction(i.tenantId(),f->{
                    var persisted=files.executionForAttempt(f,i.declaration().id(),i.attemptId()).orElseThrow(FileEvidenceTrustUnavailableException::new);
                    if(!persisted.descriptor().executionId().equals(claim.execution().descriptor().executionId())
                            ||!persisted.descriptor().descriptorSha256().equals(claim.execution().descriptor().descriptorSha256()))throw conflict();
                    var checkpoint=files.recordVerified(f,i.fence(),claim.source(),verified,resources.generation(),outcome);
                    tx.compareAndSet(i,new Change(Phase.VERIFIED_PENDING_FINALIZATION,i.leaseEpoch(),i.attemptNo(),i.attemptId(),i.reservedScanAttemptId(),
                        null,null,null,null,i.quarantineAllocationId(),checkpoint.immutableAllocationId(),checkpoint.proofId(),checkpoint.receiptId(),
                        resources.generation().generationSha256(),null,i.cancelRequested(),i.currentExecutionId()),actor,i.policyVersion());return null;
                });
            });
        }catch(FileIngestionPort.QuarantineNotReusable mismatch){finishFailure(claim.intent(),Phase.RETRY_WAIT,"QUARANTINE_NOT_REUSABLE");}
        catch(FileIngestionPort.ContentRejected rejected){
            String code=switch(rejected.reason()){
                case SCANNER_REJECTED -> "SCAN_DETECTED";
                case INVALID_LENGTH,UNSUPPORTED_MEDIA,MALFORMED_CONTENT -> "MEDIA_REJECTED";
            };
            finishFailure(claim.intent(),Phase.REJECTED,code);
        }
        catch(FileEvidenceTrustUnavailableException unavailable){finishFailure(claim.intent(),Phase.RETRY_WAIT,"SCANNER_UNAVAILABLE");}
        catch(FindingNotVisibleException revoked){finishFailure(claim.intent(),Phase.FAILED,"CONTEXT_CHANGED");}
        finally{if(heartbeat!=null)heartbeat.cancel(false);}
    }
    void heartbeat(TechnicalIntent claim){
        repository.inIntentTransaction(claim.tenantId(),claim.declaration().id(),tx->{
            UUID actor=eligibility.requireService(tx);eligibility.requireOwner(tx);var i=tx.current();requireLiveExecution(tx,i,claim);
            Instant now=tx.now().toInstant();var c=Change.unchanged(i);
            tx.compareAndSet(i,new Change(c.phase(),c.leaseEpoch(),c.attemptNo(),c.attemptId(),c.reservedScanAttemptId(),c.leaseToken(),
                now.plus(properties.lease()),now,c.nextAttemptAt(),c.quarantineAllocationId(),c.immutableAllocationId(),c.scanProofId(),c.contentReceiptId(),
                c.scannerGeneration(),c.lastErrorCode(),c.cancelRequested(),c.currentExecutionId()),actor,i.policyVersion());return null;
        });
    }
    private void finishFailure(TechnicalIntent claim,Phase failurePhase,String code){
        repository.inIntentTransaction(claim.tenantId(),claim.declaration().id(),tx->{
            UUID actor=eligibility.requireService(tx);var i=tx.current();
            if(!sameExecution(i,claim)||!tx.now().toInstant().isBefore(i.leaseUntil()))return null;
            Phase phase=i.cancelRequested()?Phase.CANCELLED:!tx.now().toInstant().isBefore(i.declaration().expiresAt().toInstant())?Phase.EXPIRED:
                failurePhase;
            String safe=phase==Phase.CANCELLED||phase==Phase.EXPIRED?phase.name():code;
            tx.compareAndSet(i,inactive(i,phase,safe,phase==Phase.RETRY_WAIT?tx.now().toInstant().plusSeconds(backoff(i.attemptNo())):null),actor,i.policyVersion());return null;
        });
    }
    private void requireCurrent(Transaction tx,TechnicalIntent i,ProducerResources resources,UploadProducerReadiness.Capability capability){
        tx.assertOpen();var finding=tx.finding(i.declaration().findingId()).orElseThrow(FindingNotVisibleException::new);
        var set=tx.evidenceSet(i.declaration().evidenceSetId()).orElseThrow(FindingNotVisibleException::new);
        if(finding.version()!=i.expectedFindingVersion()||set.revision()!=i.expectedEvidenceRevision())throw conflict();
        if(!i.producerVersion().equals(capability.configurationVersion())||!i.policyVersion().equals(capability.policyVersion())
                ||!readiness.isCurrent(capability)||!producer.isCurrentGeneration(resources.generation()))throw new FileEvidenceTrustUnavailableException();
        tx.assertOpen();
    }
    private static void requireLiveExecution(Transaction tx,TechnicalIntent current,TechnicalIntent claim){
        if(!sameExecution(current,claim)||current.cancelRequested()||!tx.now().toInstant().isBefore(current.leaseUntil())
                ||!tx.now().toInstant().isBefore(current.declaration().expiresAt().toInstant()))throw conflict();
    }
    static boolean sameExecution(TechnicalIntent current,TechnicalIntent claim){
        return current.phase()==Phase.SCANNING&&current.tenantId().equals(claim.tenantId())&&current.declaration().id().equals(claim.declaration().id())
            &&Objects.equals(current.currentExecutionId(),claim.currentExecutionId())&&current.currentExecutionId()!=null
            &&Objects.equals(current.attemptId(),claim.attemptId())&&Objects.equals(current.leaseToken(),claim.leaseToken())&&current.leaseEpoch()==claim.leaseEpoch();
    }
    static boolean scanDue(TechnicalIntent i,Instant now){
        return (i.phase()==Phase.QUARANTINED||i.phase()==Phase.RETRY_WAIT&&i.nextAttemptAt()!=null&&!now.isBefore(i.nextAttemptAt()))
            &&i.quarantineAllocationId()!=null&&i.attemptNo()<3&&!i.cancelRequested()&&now.isBefore(i.declaration().expiresAt().toInstant())
            &&!"QUARANTINE_NOT_REUSABLE".equals(i.lastErrorCode());
    }
    static long backoff(int attempts){return attempts>=3?120:attempts==2?30:5;}
    private static RecordedAllocation prospective(AllocationRequest request){return new RecordedAllocation(request,0,null,null,request.reservedBytes(),null,null,null);}
    private static void requireUnused(RecordedAllocation current,RecordedAllocation planned){
        if(!current.request().equals(planned.request())||current.eventNo()!=0||current.disposalTombstoneId()!=null||current.finalizedFileId()!=null)throw conflict();
    }
    private static Change withEpoch(Change c,long epoch){return new Change(c.phase(),epoch,c.attemptNo(),c.attemptId(),c.reservedScanAttemptId(),
        c.leaseToken(),c.leaseUntil(),c.heartbeatAt(),c.nextAttemptAt(),c.quarantineAllocationId(),c.immutableAllocationId(),c.scanProofId(),
        c.contentReceiptId(),c.scannerGeneration(),c.lastErrorCode(),c.cancelRequested(),c.currentExecutionId());}
    private record RecoveryTarget(RecordedAllocation allocation,Optional<RecordedExecution> execution,
                                  RecoveryBinding binding,DisposalClaim claim){}
    private void recoverRetained(UUID tenant,UUID intent){
        if(properties.retentionPolicySha256().isEmpty())return;
        WorkKey key=new WorkKey(tenant,intent);
        var page=repository.inIntentTransaction(tenant,intent,tx->{
            eligibility.requireService(tx);var i=tx.current();
            if(!(i.phase()==Phase.RETRY_WAIT||i.phase().terminal()))return null;
            return files.inCallerTransaction(tenant,f->files.retainedForIntent(f,intent,recoveryCursors.get(key),32));
        });
        if(page==null)return;
        // Advance even when every row is UNKNOWN; a retained early allocation must not starve later rows.
        if(page.nextCursor()==null)recoveryCursors.remove(key);else recoveryCursors.put(key,page.nextCursor());
        for(RecordedAllocation allocation:page.records()){
            try{recoverAllocation(tenant,intent,allocation.request().allocationId());}
            catch(RuntimeException unavailable){/* Exact retained descriptor remains charged and is revisited on wrap. */}
        }
    }
    private RecoveryTarget recoveryTarget(Transaction tx,UUID allocationId){
        eligibility.requireService(tx);var i=tx.current();
        return files.inCallerTransaction(i.tenantId(),f->{
            var allocation=files.load(f,allocationId);
            if(allocation.finalizedFileId()!=null||allocation.retainedBytes()==0)return null;
            var execution=files.executionForAttempt(f,i.declaration().id(),allocation.request().attemptId());
            RecoveryReason reason=recoveryReason(i,allocation,execution);
            if(reason==null)return null;
            var activeClaim=files.currentDisposalClaim(f,allocationId);
            if(activeClaim.isEmpty()&&allocation.lastRecoveryObservation()!=null&&allocation.lastRecoveryObservation().quiescent()
                    &&tx.now().toInstant().isBefore(allocation.request().createdAt().plus(properties.orphanGrace())))return null;
            var binding=new RecoveryBinding(i.tenantId(),i.declaration().id(),allocationId,UUID.randomUUID(),i.technicalVersion(),i.leaseEpoch(),
                allocation.eventNo(),allocation.eventSha256(),execution.map(e->e.descriptor().executionId()).orElse(null),
                execution.map(e->e.descriptor().descriptorSha256()).orElse(null),reason,properties.retentionPolicySha256());
            return new RecoveryTarget(allocation,execution,binding,activeClaim.orElse(null));
        });
    }
    private void recoverAllocation(UUID tenant,UUID intent,UUID allocationId){
        RecoveryTarget target=repository.inIntentTransaction(tenant,intent,tx->recoveryTarget(tx,allocationId));
        if(target==null)return;
        if(target.claim()==null){
            var observed=producer.observeRecovery(target.allocation(),target.execution(),target.binding(),Deadline.after(java.time.Duration.ofSeconds(10)));
            if(!observed.quiescent()||observed.presence()==RecoveryPresence.UNKNOWN)return;
            RecoveryTarget observedTarget=target;
            repository.inIntentTransaction(tenant,intent,tx->{
                UUID actor=eligibility.requireService(tx);var i=tx.current();requireBinding(i,observedTarget.binding());
                return files.inCallerTransaction(tenant,f->{
                    var allocation=files.load(f,allocationId);
                    if(recoveryReason(i,allocation,observedTarget.execution())!=observedTarget.binding().reason())throw conflict();
                    files.recordRecoveryObservation(f,observedTarget.binding(),observed);
                    Instant eligibleAfter=allocation.request().createdAt().plus(properties.orphanGrace());
                    if(!tx.now().toInstant().isBefore(eligibleAfter))
                        files.claimDisposal(f,new DisposalEligibility(tenant,intent,allocation.request().attemptId(),i.technicalVersion(),true,
                            properties.retentionPolicySha256(),eligibleAfter),allocationId,observed.observationSha256());
                    tx.compareAndSet(i,Change.unchanged(i),actor,i.policyVersion());return null;
                });
            });
            // Do not reuse the pre-observation binding or assume the claim committed. Reload exact persisted truth.
            target=repository.inIntentTransaction(tenant,intent,tx->recoveryTarget(tx,allocationId));
            if(target==null||target.claim()==null)return;
        }
        RecoveryTarget disposing=target;
        var absence=producer.dispose(disposing.claim(),disposing.binding(),Deadline.after(java.time.Duration.ofSeconds(10)));
        repository.inIntentTransaction(tenant,intent,tx->{
            UUID actor=eligibility.requireService(tx);var i=tx.current();requireBinding(i,disposing.binding());
            return files.inCallerTransaction(tenant,f->{
                var current=files.load(f,allocationId);
                if(recoveryReason(i,current,disposing.execution())!=disposing.binding().reason())throw conflict();
                var actualClaim=files.currentDisposalClaim(f,allocationId).orElseThrow(FileEvidenceTrustUnavailableException::new);
                if(!sameDisposalClaim(actualClaim,disposing.claim()))throw conflict();
                files.confirmDisposal(f,disposing.claim(),absence,i.technicalVersion());
                tx.compareAndSet(i,Change.unchanged(i),actor,i.policyVersion());return null;
            });
        });
    }
    static boolean sameDisposalClaim(DisposalClaim a,DisposalClaim b){
        return a.allocationId().equals(b.allocationId())&&a.tombstoneId().equals(b.tombstoneId())&&a.token().equals(b.token())
            &&a.epoch()==b.epoch()&&a.technicalVersion()==b.technicalVersion()&&a.observationSha256().equals(b.observationSha256());
    }
    private static void requireBinding(TechnicalIntent i,RecoveryBinding binding){
        if(!i.tenantId().equals(binding.tenantId())||!i.declaration().id().equals(binding.intentId())
                ||i.technicalVersion()!=binding.expectedCurrentTechnicalVersion()||i.leaseEpoch()!=binding.expectedIntentLeaseEpoch())throw conflict();
    }
    static RecoveryReason recoveryReason(TechnicalIntent i,RecordedAllocation allocation,Optional<RecordedExecution> execution){
        if(allocation.finalizedFileId()!=null)return null;
        if(i.phase().terminal())return RecoveryReason.TERMINAL;
        if(i.phase()!=Phase.RETRY_WAIT)return null;
        UUID id=allocation.request().allocationId(),attempt=allocation.request().attemptId();
        if(!id.equals(i.quarantineAllocationId())&&!id.equals(i.immutableAllocationId())&&!attempt.equals(i.attemptId())
                &&!attempt.equals(i.reservedScanAttemptId()))return RecoveryReason.SUPERSEDED;
        // An existing complete source remains available for its next scan, including after expired lease recovery.
        if(id.equals(i.quarantineAllocationId())&&allocation.baseline()!=null)return null;
        long priorEpoch=execution.map(e->e.descriptor().claimEpoch()).orElse(allocation.request().reservationLeaseEpoch());
        return i.leaseToken()==null&&i.leaseEpoch()>priorEpoch?RecoveryReason.EXPIRED_WORK:null;
    }
}
