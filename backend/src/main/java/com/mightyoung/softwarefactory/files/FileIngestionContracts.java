package com.mightyoung.softwarefactory.files;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.List;
import java.util.UUID;

/** Files-owned values. None of these values grants application authorization or scanner readiness. */
public final class FileIngestionContracts {
    public static final long MAX_CONTENT_BYTES = 67_108_864L;
    public static final int MAX_CANONICAL_BYTES = 32_768;
    private FileIngestionContracts() {}

    public enum AllocationKind { QUARANTINE, SCANNER_SCRATCH, IMMUTABLE, RECEIPT }
    public enum ObservationKind { COMPLETE_QUARANTINE, COMPLETE_IMMUTABLE, RECEIPT, SCRATCH, UNTRUSTED_PARTIAL }
    public enum EventKind { RETAIN, RESIZE_CONFIRMED, DISPOSAL_CLAIMED, DISPOSAL_CONFIRMED, TRANSFER_FINALIZED, RECOVERY_OBSERVED }

    public enum ProcessState { NONE_STARTED, REAPED, KERNEL_ABSENT, UNKNOWN }
    public enum RecoveryPresence { ABSENT, PRESENT_UNTRUSTED_PARTIAL, PRESENT_SCRATCH, PRESENT_COMPLETE_UNCHANGED, UNKNOWN }
    public enum RecoveryReason { TERMINAL, SUPERSEDED, EXPIRED_WORK }
    public record ScanPlan(UUID tenantId, UUID intentId, UUID fileId, UUID sourceAttemptId, UUID scanAttemptId,
                           UUID quarantineAllocationId, UUID scratchAllocationId, UUID plannedExecutionId,
                           UUID physicalResourceRealmId, String profileSha256, String slotId,
                           long expectedOldTechnicalVersion, long plannedClaimEpoch,
                           String generationSha256, String quarantineObservationSha256) {
        public ScanPlan {
            required(tenantId,intentId,fileId,sourceAttemptId,scanAttemptId,quarantineAllocationId,scratchAllocationId,
                    plannedExecutionId,physicalResourceRealmId);hash(profileSha256);ident(slotId);nonnegative(expectedOldTechnicalVersion);
            positive(plannedClaimEpoch);hash(generationSha256);hash(quarantineObservationSha256);
            if(sourceAttemptId.equals(scanAttemptId)||quarantineAllocationId.equals(scratchAllocationId))throw invalid();
        }
        @Override public String toString(){return "ScanPlan[executionId="+plannedExecutionId+"]";}
    }
    public record ExecutionDescriptor(UUID executionId, UUID tenantId, UUID intentId, UUID fileId,
                                      UUID sourceAttemptId, UUID scanAttemptId, UUID quarantineAllocationId,
                                      UUID scratchAllocationId, UUID physicalResourceRealmId, String profileSha256,
                                      String slotId, long claimEpoch, UUID claimToken, long claimTechnicalVersion,
                                      byte[] canonicalDescriptor, String descriptorSha256, Instant createdAt) {
        public ExecutionDescriptor {
            required(executionId,tenantId,intentId,fileId,sourceAttemptId,scanAttemptId,quarantineAllocationId,
                    scratchAllocationId,physicalResourceRealmId,claimToken);hash(profileSha256);ident(slotId);
            positive(claimEpoch);positive(claimTechnicalVersion);canonicalDescriptor=bytes(canonicalDescriptor,MAX_CANONICAL_BYTES);
            hash(descriptorSha256);createdAt=instant(createdAt);
            if(sourceAttemptId.equals(scanAttemptId)||quarantineAllocationId.equals(scratchAllocationId))throw invalid();
        }
        @Override public byte[] canonicalDescriptor(){return canonicalDescriptor.clone();}
        @Override public String toString(){return "ExecutionDescriptor[executionId="+executionId+"]";}
        /** Pure prospective encoding; SQL and the broker independently validate the actual committed claim. */
        public static ExecutionDescriptor forClaim(ScanPlan plan, TechnicalFence claim, Instant createdAt){
            required(plan,claim);if(!plan.tenantId().equals(claim.tenantId())||!plan.intentId().equals(claim.intentId())
                ||!plan.scanAttemptId().equals(claim.attemptId())||claim.leaseEpoch()!=plan.plannedClaimEpoch()
                ||claim.technicalVersion()!=Math.addExact(plan.expectedOldTechnicalVersion(),1))throw invalid();
            return IngestionProofCodec.executionDescriptor(plan,claim,createdAt);
        }
    }
    public record RecordedExecution(ExecutionDescriptor descriptor) {
        public RecordedExecution {required(descriptor);}
        @Override public String toString(){return "RecordedExecution[executionId="+descriptor.executionId()+"]";}
    }
    /** Kernel values are observations from the pinned broker, never self-authenticating admission. */
    public record KernelExecutionFacts(UUID executionId, String descriptorSha256, String challenge,
                                       UUID physicalResourceRealmId, String slotId, String profileSha256, UUID bootId,
                                       long supervisorPid, long supervisorStartTicks, Long childPid, Long childStartTicks,
                                       String childAbsentReason, Long pidNamespace, Long mountNamespace, Long netNamespace,
                                       Long ipcNamespace, Long utsNamespace, UUID cgroupName, long cgroupMountId,
                                       long cgroupInode, String pidsPolicySha256, long pidsCurrent, boolean populated,
                                       boolean pidsControllerContinuous, boolean noMigrationOrFutureEntry,
                                       boolean slotLockExclusive, String slotLockIdentitySha256,
                                       boolean permanentStop, String stopSha256, boolean childObserved, boolean imageVerified,
                                       boolean waitReaped, boolean inputDrained, boolean stdoutDrained, boolean stderrDrained,
                                       boolean interruptedOutputDiscarded, ProcessState processState,
                                       String kernelControlSha256, String reportSha256, Instant observedAt) {
        public KernelExecutionFacts {
            required(executionId,physicalResourceRealmId,bootId,cgroupName,processState);hash(descriptorSha256);hash(challenge);
            hash(profileSha256);ident(slotId);positive(supervisorPid);positive(supervisorStartTicks);
            if((childPid==null)!=(childStartTicks==null))throw invalid();
            if(childPid!=null){positive(childPid);positive(childStartTicks);if(childAbsentReason!=null)throw invalid();}
            else ident(childAbsentReason);
            for(Long namespace:java.util.Arrays.asList(pidNamespace,mountNamespace,netNamespace,ipcNamespace,utsNamespace))
                if(namespace!=null)positive(namespace);
            positive(cgroupMountId);positive(cgroupInode);hash(pidsPolicySha256);nonnegative(pidsCurrent);
            if(pidsCurrent>1_000_000)throw invalid();hash(slotLockIdentitySha256);hash(kernelControlSha256);hash(reportSha256);
            if(permanentStop!=(stopSha256!=null))throw invalid();if(stopSha256!=null)hash(stopSha256);observedAt=instant(observedAt);
            if(processState==ProcessState.NONE_STARTED&&(childObserved||childPid!=null||waitReaped||imageVerified))throw invalid();
            if(processState==ProcessState.REAPED&&(!childObserved||childPid==null||!waitReaped||!inputDrained||!stdoutDrained
                    ||!stderrDrained||interruptedOutputDiscarded||pidsCurrent!=0||populated))throw invalid();
            if(processState==ProcessState.KERNEL_ABSENT&&(!permanentStop||!slotLockExclusive||!pidsControllerContinuous
                    ||!noMigrationOrFutureEntry||pidsCurrent!=0||populated||!interruptedOutputDiscarded||waitReaped
                    ||inputDrained||stdoutDrained||stderrDrained||imageVerified))throw invalid();
        }
        public boolean quiescent(){return permanentStop&&slotLockExclusive&&(processState==ProcessState.REAPED||processState==ProcessState.KERNEL_ABSENT
                ||(processState==ProcessState.NONE_STARTED&&permanentStop&&slotLockExclusive&&pidsCurrent==0&&!populated));}
        public boolean normalClean(){return processState==ProcessState.REAPED&&childObserved&&imageVerified&&childPid!=null
                &&pidsControllerContinuous&&noMigrationOrFutureEntry&&slotLockExclusive
                &&pidNamespace!=null&&mountNamespace!=null&&netNamespace!=null&&ipcNamespace!=null&&utsNamespace!=null;}
        @Override public String toString(){return "KernelExecutionFacts[executionId="+executionId+",state="+processState+"]";}
    }
    public record RecoveryBinding(UUID tenantId, UUID intentId, UUID allocationId, UUID recoveryId,
                                  long expectedCurrentTechnicalVersion, long expectedIntentLeaseEpoch,
                                  long targetEventNo, String targetEventSha256, UUID executionId, String descriptorSha256,
                                  RecoveryReason reason, String retentionPolicySha256) {
        public RecoveryBinding {
            required(tenantId,intentId,allocationId,recoveryId,reason);nonnegative(expectedCurrentTechnicalVersion);
            nonnegative(expectedIntentLeaseEpoch);nonnegative(targetEventNo);
            if((targetEventNo==0)!=(targetEventSha256==null)||(executionId==null)!=(descriptorSha256==null))throw invalid();
            if(targetEventSha256!=null)hash(targetEventSha256);if(descriptorSha256!=null)hash(descriptorSha256);hash(retentionPolicySha256);
        }
        @Override public String toString(){return "RecoveryBinding[recoveryId="+recoveryId+"]";}
    }
    public record RecoveryObservation(RecoveryBinding binding, AllocationRequest allocation, RecoveryPresence presence,
                                      ProcessState processState, boolean writerQuiescent, String writerQuiescenceSha256,
                                      Instant writerQuiescenceObservedAt, KernelExecutionFacts kernelFacts,
                                      ObjectIdentity identity, UUID baselineEventId, String baselineObservationSha256,
                                      String stopWriteFenceSha256, byte[] canonicalObservation, String observationSha256,
                                      Instant observedAt) {
        public RecoveryObservation {
            required(binding,allocation,presence,processState);if(!binding.tenantId().equals(allocation.tenantId())
                ||!binding.intentId().equals(allocation.intentId())||!binding.allocationId().equals(allocation.allocationId()))throw invalid();
            if(writerQuiescent!=(writerQuiescenceSha256!=null)||writerQuiescent!=(writerQuiescenceObservedAt!=null))throw invalid();
            if(writerQuiescent){hash(writerQuiescenceSha256);writerQuiescenceObservedAt=instant(writerQuiescenceObservedAt);}
            if((baselineEventId==null)!=(baselineObservationSha256==null))throw invalid();
            if(baselineObservationSha256!=null)hash(baselineObservationSha256);
            if(identity!=null)requireIdentity(allocation,identity);
            if((presence==RecoveryPresence.ABSENT||presence==RecoveryPresence.UNKNOWN)&&identity!=null)throw invalid();
            if(presence!=RecoveryPresence.ABSENT&&presence!=RecoveryPresence.UNKNOWN&&identity==null)throw invalid();
            if(presence==RecoveryPresence.PRESENT_COMPLETE_UNCHANGED&&baselineEventId==null)throw invalid();
            if(presence==RecoveryPresence.PRESENT_SCRATCH&&allocation.kind()!=AllocationKind.SCANNER_SCRATCH)throw invalid();
            if(stopWriteFenceSha256!=null)hash(stopWriteFenceSha256);
            if(kernelFacts!=null){if(binding.executionId()==null||!binding.executionId().equals(kernelFacts.executionId())
                ||!binding.descriptorSha256().equals(kernelFacts.descriptorSha256())||processState!=kernelFacts.processState())throw invalid();}
            else if(processState!=ProcessState.NONE_STARTED&&processState!=ProcessState.UNKNOWN)throw invalid();
            canonicalObservation=bytes(canonicalObservation,MAX_CANONICAL_BYTES);hash(observationSha256);observedAt=instant(observedAt);
            if(writerQuiescenceObservedAt!=null&&writerQuiescenceObservedAt.isAfter(observedAt)
                    ||kernelFacts!=null&&kernelFacts.observedAt().isAfter(observedAt))throw invalid();
        }
        @Override public byte[] canonicalObservation(){return canonicalObservation.clone();}
        public boolean quiescent(){return stopWriteFenceSha256!=null&&(allocation.kind()==AllocationKind.SCANNER_SCRATCH
                ?kernelFacts!=null&&kernelFacts.quiescent()||binding.executionId()==null&&processState==ProcessState.NONE_STARTED&&writerQuiescent
                :writerQuiescent);}
        @Override public String toString(){return "RecoveryObservation[recoveryId="+binding.recoveryId()+",presence="+presence+"]";}
    }
    public record TechnicalFence(UUID tenantId, UUID intentId, UUID attemptId, UUID leaseToken,
                                 long technicalVersion, long leaseEpoch) {
        public TechnicalFence { required(tenantId,intentId,attemptId,leaseToken); nonnegative(technicalVersion); positive(leaseEpoch); }
        @Override public String toString(){return "TechnicalFence[version="+technicalVersion+",epoch="+leaseEpoch+"]";}
    }
    public record AllocationRequest(UUID tenantId, UUID intentId, UUID fileId, UUID ownerMemberId,
                                    UUID attemptId, UUID allocationId, AllocationKind kind,
                                    String rootGeneration, String serverObjectName, String storageVersion,
                                    long reservedBytes, long reservationLeaseEpoch, Instant createdAt) {
        public AllocationRequest {
            required(tenantId,intentId,fileId,ownerMemberId,attemptId,allocationId,kind,createdAt);
            hash(rootGeneration); token(serverObjectName); token(storageVersion);
            positive(reservedBytes); positive(reservationLeaseEpoch); createdAt=instant(createdAt);
        }
        @Override public String toString(){return "AllocationRequest[kind="+kind+"]";}
    }
    public record ObjectIdentity(String rootGeneration, String serverObjectName, String storageVersion,
                                 String fileStoreIdentity, String fileKey, long unixDevice, long unixInode,
                                 long linkCount, long measuredBytes, Instant durabilityCompletedAt) {
        public ObjectIdentity {
            hash(rootGeneration); token(serverObjectName); token(storageVersion);
            text(fileStoreIdentity,255); text(fileKey,255); nonnegative(unixDevice); nonnegative(unixInode);
            if(linkCount!=1) throw invalid(); nonnegative(measuredBytes); required(durabilityCompletedAt); durabilityCompletedAt=instant(durabilityCompletedAt);
        }
        @Override public String toString(){return "ObjectIdentity[redacted]";}
    }
    public record DurableObservation(AllocationRequest allocation, ObservationKind kind,
                                     ObjectIdentity identity, String sha256, Long sizeBytes, String mediaType,
                                     boolean objectAbsent, Instant absenceDurableAt,
                                     boolean childReaped, String childReapEvidenceSha256,
                                     byte[] canonicalObservation, String observationSha256) {
        public DurableObservation {
            required(allocation,kind);
            AllocationKind expected=switch(kind){case COMPLETE_QUARANTINE->AllocationKind.QUARANTINE;case COMPLETE_IMMUTABLE->AllocationKind.IMMUTABLE;
                case RECEIPT->AllocationKind.RECEIPT;case SCRATCH->AllocationKind.SCANNER_SCRATCH;case UNTRUSTED_PARTIAL->allocation.kind();};
            if(allocation.kind()!=expected)throw invalid();
            canonicalObservation=bytes(canonicalObservation,MAX_CANONICAL_BYTES);
            hash(observationSha256);
            if(identity!=null) requireIdentity(allocation,identity);
            if(sha256!=null) hash(sha256); if(sizeBytes!=null) nonnegative(sizeBytes);
            if(mediaType!=null) media(mediaType);
            if(kind!=ObservationKind.UNTRUSTED_PARTIAL && kind!=ObservationKind.SCRATCH) {
                required(identity,sha256,sizeBytes,mediaType);
                if(sizeBytes<1 || sizeBytes>MAX_CONTENT_BYTES || sizeBytes!=identity.measuredBytes()) throw invalid();
            }
            if(objectAbsent!=(absenceDurableAt!=null) || childReaped!=(childReapEvidenceSha256!=null)) throw invalid();
            if(childReapEvidenceSha256!=null) hash(childReapEvidenceSha256);
            if(absenceDurableAt!=null) absenceDurableAt=instant(absenceDurableAt);
        }
        @Override public byte[] canonicalObservation(){ return canonicalObservation.clone(); }
        @Override public String toString(){ return "DurableObservation[kind="+kind+"]"; }
    }
    public record RecordedAllocation(AllocationRequest request, long eventNo, UUID eventId, String eventSha256,
                                     long retainedBytes, DurableObservation baseline, UUID baselineEventId,
                                     UUID disposalTombstoneId, UUID finalizedFileId, RecoveryObservation lastRecoveryObservation) {
        public RecordedAllocation(AllocationRequest request,long eventNo,UUID eventId,String eventSha256,long retainedBytes,
                                  DurableObservation baseline,UUID disposalTombstoneId,UUID finalizedFileId){
            this(request,eventNo,eventId,eventSha256,retainedBytes,baseline,null,disposalTombstoneId,finalizedFileId,null);
            if(baseline!=null||eventNo!=0)throw invalid();
        }
        public RecordedAllocation {
            required(request); nonnegative(eventNo); nonnegative(retainedBytes);
            if(retainedBytes>request.reservedBytes()) throw invalid();
            if((eventNo==0)!=(eventId==null) || (eventNo==0)!=(eventSha256==null)) throw invalid();
            if(eventSha256!=null) hash(eventSha256);
            if((baseline==null)!=(baselineEventId==null)||baseline!=null&&!request.equals(baseline.allocation())) throw invalid();
            if(lastRecoveryObservation!=null&&!request.equals(lastRecoveryObservation.allocation()))throw invalid();
            if(disposalTombstoneId!=null && finalizedFileId!=null) throw invalid();
        }
        @Override public String toString(){return "RecordedAllocation[kind="+request.kind()+",eventNo="+eventNo+"]";}
    }
    public record RetainedAllocationPage(List<RecordedAllocation> records, UUID nextCursor) {
        public RetainedAllocationPage {
            required(records);records=List.copyOf(records);
            if(records.size()>32||nextCursor!=null&&(records.isEmpty()||!nextCursor.equals(records.getLast().request().allocationId())))throw invalid();
        }
        @Override public String toString(){return "RetainedAllocationPage[count="+records.size()+"]";}
    }
    public record ReceiveAllocation(RecordedAllocation quarantine, long declaredSizeBytes,
                                    String declaredMediaType, String expectedProducerGenerationSha256,
                                    UUID reservedScanAttemptId) {
        public ReceiveAllocation {
            kind(quarantine,AllocationKind.QUARANTINE); contentSize(declaredSizeBytes); media(declaredMediaType);
            hash(expectedProducerGenerationSha256); required(reservedScanAttemptId);
            if(declaredSizeBytes>quarantine.request().reservedBytes()
                    || reservedScanAttemptId.equals(quarantine.request().attemptId())) throw invalid();
        }
    }
    public record ScanAllocation(RecordedAllocation scratch, TechnicalFence fence, long hardCapacityBytes,
                                 String slotDescriptorId) {
        public ScanAllocation {
            kind(scratch,AllocationKind.SCANNER_SCRATCH); requireFence(scratch.request(),fence);
            positive(hardCapacityBytes); text(slotDescriptorId,128);
            if(hardCapacityBytes>scratch.request().reservedBytes()) throw invalid();
        }
    }
    public record CopyAllocation(RecordedAllocation immutable) {
        public CopyAllocation { kind(immutable,AllocationKind.IMMUTABLE); }
    }
    public record ReceiptAllocation(RecordedAllocation receipt) {
        public ReceiptAllocation { kind(receipt,AllocationKind.RECEIPT); }
    }
    public record QuarantinedObject(UUID tenantId, UUID intentId, UUID fileId, UUID allocationId,
                                    UUID sourceAttemptId, ObjectIdentity identity, String sha256,
                                    String detectedMediaType, long sizeBytes, String observationSha256) {
        public QuarantinedObject {
            required(tenantId,intentId,fileId,allocationId,sourceAttemptId,identity);
            hash(sha256); media(detectedMediaType); contentSize(sizeBytes); hash(observationSha256);
            if(sizeBytes!=identity.measuredBytes()) throw invalid();
        }
        @Override public String toString(){ return "QuarantinedObject[allocationId="+allocationId+"]"; }
    }
    public record ImmutableObject(UUID tenantId, UUID intentId, UUID fileId, UUID allocationId,
                                  UUID sourceAttemptId, UUID scanAttemptId, ObjectIdentity identity,
                                  String sha256, String detectedMediaType, long sizeBytes,
                                  String sourceQuarantineObservationSha256) {
        public ImmutableObject {
            required(tenantId,intentId,fileId,allocationId,sourceAttemptId,scanAttemptId,identity);
            hash(sha256); media(detectedMediaType); contentSize(sizeBytes); hash(sourceQuarantineObservationSha256);
            if(sizeBytes!=identity.measuredBytes() || sourceAttemptId.equals(scanAttemptId)) throw invalid();
        }
        @Override public String toString(){ return "ImmutableObject[allocationId="+allocationId+"]"; }
    }
    public record AcceptedGeneration(UUID generationId, String generationSha256, String manifestSha256,
                                     byte[] canonicalManifest, Instant validatedAt, Instant validUntil) {
        public AcceptedGeneration {
            required(generationId,validatedAt,validUntil); hash(generationSha256); hash(manifestSha256);
            canonicalManifest=bytes(canonicalManifest,MAX_CANONICAL_BYTES); validatedAt=instant(validatedAt);validUntil=instant(validUntil);
            if(!validUntil.isAfter(validatedAt)) throw invalid();
        }
        @Override public byte[] canonicalManifest(){ return canonicalManifest.clone(); }
        @Override public String toString(){ return "AcceptedGeneration[generationId="+generationId+"]"; }
    }
    public record ScanSlot(String descriptorId,long hardCapacityBytes) {
        public ScanSlot {ident(descriptorId);if(hardCapacityBytes<1||hardCapacityBytes>134_217_728L)throw invalid();}
    }
    public record ProducerResources(AcceptedGeneration generation,String rootGeneration, UUID physicalResourceRealmId, String profileSha256,
                                    long receiptReservationBytes,List<ScanSlot> scanSlots) {
        public ProducerResources {
            required(generation,scanSlots,physicalResourceRealmId);hash(profileSha256);hash(rootGeneration);scanSlots=List.copyOf(scanSlots);
            if(receiptReservationBytes<1||receiptReservationBytes>65_536L||scanSlots.isEmpty()||scanSlots.size()>2
                    ||scanSlots.stream().map(ScanSlot::descriptorId).distinct().count()!=scanSlots.size())throw invalid();
        }
    }
    public record SignedScanProofV1(UUID proofId, UUID tenantId, UUID intentId, UUID fileId,
                                    UUID sourceAttemptId, UUID scanAttemptId, UUID quarantineAllocationId,
                                    UUID generationId, UUID executionId, String descriptorSha256, String kernelResultSha256, String quarantineStorageVersion,
                                    String quarantineObservationSha256, String sha256, String detectedMediaType,
                                    String generationSha256, String manifestSha256, String policyVersion,
                                    String effectiveConfigurationSha256, String verifierId, String keyId,
                                    long sizeBytes, Instant startedAt, Instant completedAt, Instant issuedAt,
                                    Instant notBefore, Instant expiresAt, byte[] canonicalPayload, byte[] signature) {
        public SignedScanProofV1 {
            required(proofId,tenantId,intentId,fileId,sourceAttemptId,scanAttemptId,quarantineAllocationId,generationId,executionId);hash(descriptorSha256);hash(kernelResultSha256);
            if(sourceAttemptId.equals(scanAttemptId)) throw invalid();
            token(quarantineStorageVersion); hash(quarantineObservationSha256); hash(sha256); media(detectedMediaType);
            hash(generationSha256); hash(manifestSha256); ident(policyVersion); hash(effectiveConfigurationSha256);
            ident(verifierId); ident(keyId); contentSize(sizeBytes); times(issuedAt,notBefore,expiresAt);
            required(startedAt,completedAt); startedAt=instant(startedAt); completedAt=instant(completedAt);
            issuedAt=instant(issuedAt);notBefore=instant(notBefore);expiresAt=instant(expiresAt); times(issuedAt,notBefore,expiresAt);
            if(completedAt.isBefore(startedAt)||issuedAt.isBefore(completedAt)) throw invalid();
            canonicalPayload=bytes(canonicalPayload,MAX_CANONICAL_BYTES); signature=FileIngestionContracts.signature(signature);
        }
        @Override public byte[] canonicalPayload(){ return canonicalPayload.clone(); }
        @Override public byte[] signature(){ return signature.clone(); }
        @Override public String toString(){ return "SignedScanProofV1[proofId="+proofId+"]"; }
    }
    public record ImmutableObjectAndReceipt(ImmutableObject immutable, SignedScanProofV1 proof,
                                           IngestionContentReceiptV2 receipt, DurableObservation receiptObservation) {
        public ImmutableObjectAndReceipt {
            required(immutable,proof,receipt,receiptObservation);
            if(!immutable.tenantId().equals(proof.tenantId()) || !immutable.intentId().equals(proof.intentId())
                    || !immutable.fileId().equals(proof.fileId()) || !immutable.sourceAttemptId().equals(proof.sourceAttemptId())
                    || !immutable.scanAttemptId().equals(proof.scanAttemptId()) || !immutable.sha256().equals(proof.sha256())
                    || immutable.sizeBytes()!=proof.sizeBytes() || !immutable.detectedMediaType().equals(proof.detectedMediaType())
                    || !immutable.sourceQuarantineObservationSha256().equals(proof.quarantineObservationSha256())
                    || !receipt.immutableAllocationId().equals(immutable.allocationId())
                    || !receipt.scanProofId().equals(proof.proofId())
                    || receiptObservation.kind()!=ObservationKind.RECEIPT
                    || !receiptObservation.allocation().attemptId().equals(immutable.scanAttemptId())
                    || !receiptObservation.allocation().fileId().equals(immutable.fileId())) throw invalid();
        }
    }
    public record PhysicalCharges(long ownerPending, long tenantPending, long ownerFinalized, long tenantFinalized) {
        public PhysicalCharges { nonnegative(ownerPending);nonnegative(tenantPending);nonnegative(ownerFinalized);nonnegative(tenantFinalized); }
        public long ownerTotal(){return Math.addExact(ownerPending,ownerFinalized);}
        public long tenantTotal(){return Math.addExact(tenantPending,tenantFinalized);}
    }
    public record VerifiedCheckpoint(UUID proofId, UUID receiptId, UUID immutableAllocationId) {
        public VerifiedCheckpoint { required(proofId,receiptId,immutableAllocationId); }
    }
    public record DisposalEligibility(UUID tenantId, UUID intentId, UUID attemptId, long technicalVersion,
                                       boolean terminalOrSuperseded,
                                       String eligibilityPolicySha256, Instant eligibleAfter) {
        public DisposalEligibility { required(tenantId,intentId,attemptId,eligibleAfter);nonnegative(technicalVersion);hash(eligibilityPolicySha256);eligibleAfter=instant(eligibleAfter); }
    }
    public record DisposalClaim(RecordedAllocation allocation, UUID tombstoneId, UUID token, long epoch,
                                long technicalVersion, String eligibilityPolicySha256) {
        public DisposalClaim {
            required(allocation,tombstoneId,token);positive(epoch);nonnegative(technicalVersion);hash(eligibilityPolicySha256);
            if(allocation.lastRecoveryObservation()==null || !allocation.lastRecoveryObservation().quiescent() || allocation.finalizedFileId()!=null) throw invalid();
        }
        public UUID tenantId(){return allocation.request().tenantId();}
        public UUID intentId(){return allocation.request().intentId();}
        public UUID allocationId(){return allocation.request().allocationId();}
        public UUID attemptId(){return allocation.request().attemptId();}
        public ObjectIdentity identity(){return allocation.lastRecoveryObservation().identity();}
        public String observationSha256(){return allocation.lastRecoveryObservation().observationSha256();}
        @Override public String toString(){return "DisposalClaim[allocationId="+allocationId()+"]";}
    }
    public record DisposalObservation(DisposalClaim claim, RecoveryObservation observation) {
        public DisposalObservation {
            required(claim,observation);
            if(!claim.allocation().request().equals(observation.allocation())||observation.presence()!=RecoveryPresence.ABSENT
                    ||!observation.quiescent())throw invalid();
        }
    }
    public record Deadline(long deadlineNanos) {
        public Deadline {long left=deadlineNanos-System.nanoTime();if(left<=0||left>Duration.ofMinutes(5).toNanos())throw invalid();}
        public static Deadline after(Duration timeout) {
            required(timeout);
            long nanos=timeout.toNanos();
            if(nanos<=0 || nanos>Duration.ofMinutes(5).toNanos()) throw invalid();
            return new Deadline(System.nanoTime()+nanos);
        }
        public long remainingNanos(){long left=deadlineNanos-System.nanoTime();if(left<=0)throw new FileEvidenceTrustUnavailableException();return left;}
    }
    static void requireIdentity(AllocationRequest a,ObjectIdentity i){
        if(!a.rootGeneration().equals(i.rootGeneration()) || !a.serverObjectName().equals(i.serverObjectName())
                || !a.storageVersion().equals(i.storageVersion()) || i.measuredBytes()>a.reservedBytes()) throw invalid();
    }
    static void requireFence(AllocationRequest a,TechnicalFence f){
        required(f);if(!a.tenantId().equals(f.tenantId())||!a.intentId().equals(f.intentId())||!a.attemptId().equals(f.attemptId())) throw invalid();
    }
    static void kind(RecordedAllocation a,AllocationKind kind){
        required(a);if(a.request().kind()!=kind)throw invalid();
        if(a.disposalTombstoneId()!=null||a.finalizedFileId()!=null)throw new FileEvidenceTrustUnavailableException();
    }
    static void times(Instant issuedAt,Instant notBefore,Instant expiresAt){required(issuedAt,notBefore,expiresAt);if(notBefore.isAfter(issuedAt)||!expiresAt.isAfter(issuedAt))throw invalid();}
    static byte[] bytes(byte[] value,int maximum){required(value);if(value.length==0||value.length>maximum)throw invalid();return value.clone();}
    static byte[] signature(byte[] value){required(value);if(value.length!=32)throw invalid();return value.clone();}
    static void required(Object... values){for(Object value:values)Objects.requireNonNull(value);}
    static void positive(long value){if(value<=0)throw invalid();}
    static void nonnegative(long value){if(value<0)throw invalid();}
    static void contentSize(long value){if(value<1||value>MAX_CONTENT_BYTES)throw invalid();}
    static void hash(String value){if(value==null||!value.matches("[0-9a-f]{64}"))throw invalid();}
    static void ident(String value){if(value==null||!value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,63}"))throw invalid();}
    static void token(String value){if(value==null||!value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}"))throw invalid();}
    static void media(String value){if(value==null||value.length()>255||!value.matches("[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+"))throw invalid();}
    static Instant instant(Instant value){required(value);return value.truncatedTo(java.time.temporal.ChronoUnit.MICROS);}
    static void text(String value,int max){
        if(value==null||value.isEmpty()||value.length()>max||value.chars().anyMatch(c->Character.isISOControl(c)))throw invalid();
        for(int i=0;i<value.length();i++){char c=value.charAt(i);if(Character.isHighSurrogate(c)){if(++i==value.length()||!Character.isLowSurrogate(value.charAt(i)))throw invalid();}else if(Character.isLowSurrogate(c))throw invalid();}
    }
    static IllegalArgumentException invalid(){return new IllegalArgumentException("Invalid file ingestion binding");}
}
