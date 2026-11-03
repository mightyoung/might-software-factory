package com.mightyoung.softwarefactory.files;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import static com.mightyoung.softwarefactory.files.FileIngestionContracts.*;

/** Canonical, domain-separated files-only issuer. Shape validation is never scanner acceptance. */
final class IngestionProofCodec {
    private final Map<String,byte[]> keys;
    private final String signingKeyId;
    private final Clock clock;

    IngestionProofCodec(Map<String,byte[]> keys,String signingKeyId,Clock clock) {
        required(keys,clock);
        if(keys.isEmpty() || keys.size()>16) throw invalid();
        Map<String,byte[]> copy=new HashMap<>();
        keys.forEach((id,key)->{ident(id);required(key);if(key.length<32||key.length>128)throw invalid();copy.put(id,key.clone());});
        ident(signingKeyId);if(!copy.containsKey(signingKeyId))throw invalid();
        this.keys=Map.copyOf(copy);this.signingKeyId=signingKeyId;this.clock=clock;
    }
    private IngestionProofCodec(){keys=Map.of();signingKeyId=null;clock=Clock.systemUTC();}
    static IngestionProofCodec unavailable(){return new IngestionProofCodec();}

    record ProofPolicy(String policyVersion,String effectiveConfigurationSha256,String verifierId) {
        ProofPolicy {ident(policyVersion);hash(effectiveConfigurationSha256);ident(verifierId);}
    }
    record ScanTimes(Instant startedAt,Instant completedAt,Instant issuedAt,Instant notBefore,Instant expiresAt) {
        ScanTimes {startedAt=instant(startedAt);completedAt=instant(completedAt);issuedAt=instant(issuedAt);notBefore=instant(notBefore);expiresAt=instant(expiresAt);
            times(issuedAt,notBefore,expiresAt);if(completedAt.isBefore(startedAt)||issuedAt.isBefore(completedAt))throw invalid();}
    }
    record ReceiptTimes(Instant issuedAt,Instant notBefore,Instant expiresAt) {
        ReceiptTimes {issuedAt=instant(issuedAt);notBefore=instant(notBefore);expiresAt=instant(expiresAt);times(issuedAt,notBefore,expiresAt);}
    }

    SignedScanProofV1 issueScan(UUID proofId,QuarantinedObject source,TechnicalFence fence,
                               AcceptedGeneration generation,RecordedExecution execution,KernelExecutionFacts facts,ProofPolicy policy,ScanTimes times) {
        required(source,fence,generation,execution,facts,policy,times);requireSigning();verifyGeneration(generation);
        requireCurrent(times.notBefore(),times.expiresAt());
        Instant now=clock.instant();
        if(times.completedAt().isAfter(now)||times.issuedAt().isAfter(now))throw unavailableFailure();
        if(!source.tenantId().equals(fence.tenantId())||!source.intentId().equals(fence.intentId())
                || source.sourceAttemptId().equals(fence.attemptId()) || times.startedAt().isBefore(generation.validatedAt())
                || times.expiresAt().isAfter(generation.validUntil())) throw unavailableFailure();
        validateExecution(execution.descriptor());
        requireExecutionFence(execution.descriptor(),fence);
        SignedScanProofV1 seed=new SignedScanProofV1(proofId,source.tenantId(),source.intentId(),source.fileId(),
                source.sourceAttemptId(),fence.attemptId(),source.allocationId(),generation.generationId(),
                execution.descriptor().executionId(),execution.descriptor().descriptorSha256(),kernelFactsSha256(facts),
                source.identity().storageVersion(),source.observationSha256(),source.sha256(),source.detectedMediaType(),
                generation.generationSha256(),generation.manifestSha256(),policy.policyVersion(),policy.effectiveConfigurationSha256(),
                policy.verifierId(),signingKeyId,source.sizeBytes(),times.startedAt(),times.completedAt(),times.issuedAt(),
                times.notBefore(),times.expiresAt(),new byte[]{1},new byte[32]);
        requireCleanExecution(seed,execution,facts);
        byte[] canonical=scanPayload(seed);
        return copyScan(seed,canonical,hmac(signingKeyId,canonical));
    }

    IngestionContentReceiptV2 issueReceipt(UUID receiptId,ImmutableObject immutable,SignedScanProofV1 proof,
                                           String immutableObservationSha256,ReceiptTimes times) {
        required(immutable,proof,times);hash(immutableObservationSha256);requireSigning();verifyScan(proof);
        requireCurrent(proof.notBefore(),proof.expiresAt());requireCurrent(times.notBefore(),times.expiresAt());
        if(times.issuedAt().isAfter(clock.instant()))throw unavailableFailure();
        requireImmutableBinding(immutable,proof);
        if(times.issuedAt().isBefore(proof.issuedAt())||times.expiresAt().isAfter(proof.expiresAt()))throw unavailableFailure();
        IngestionContentReceiptV2 seed=new IngestionContentReceiptV2(receiptId,immutable.tenantId(),immutable.intentId(),immutable.fileId(),
                immutable.sourceAttemptId(),immutable.scanAttemptId(),immutable.allocationId(),proof.proofId(),immutable.identity().storageVersion(),
                immutableObservationSha256,proof.quarantineStorageVersion(),sha256(proof.canonicalPayload()),immutable.sha256(),
                immutable.detectedMediaType(),proof.generationSha256(),proof.policyVersion(),proof.verifierId(),signingKeyId,
                immutable.sizeBytes(),times.issuedAt(),times.notBefore(),times.expiresAt(),new byte[]{1},new byte[32]);
        byte[] canonical=receiptPayload(seed);
        return copyReceipt(seed,canonical,hmac(signingKeyId,canonical));
    }

    /** Signature/projection validation only: retained history may outlive the receipt's freshness window. */
    void verifyScan(SignedScanProofV1 proof) {
        required(proof);verifySigned(proof.keyId(),scanPayload(proof),proof.canonicalPayload(),proof.signature());
    }
    void verifyCurrentScan(SignedScanProofV1 proof,AcceptedGeneration generation) {
        verifyScan(proof);verifyGeneration(generation);requireCurrent(proof.notBefore(),proof.expiresAt());
        if(!proof.generationId().equals(generation.generationId())||!proof.generationSha256().equals(generation.generationSha256())
                || !proof.manifestSha256().equals(generation.manifestSha256()))throw unavailableFailure();
    }
    void verifyReceipt(IngestionContentReceiptV2 receipt,SignedScanProofV1 proof) {
        required(receipt,proof);verifyScan(proof);
        verifySigned(receipt.keyId(),receiptPayload(receipt),receipt.canonicalPayload(),receipt.signature());
        if(!receipt.scanProofId().equals(proof.proofId())||!receipt.tenantId().equals(proof.tenantId())
                || !receipt.intentId().equals(proof.intentId())||!receipt.fileId().equals(proof.fileId())
                || !receipt.sourceAttemptId().equals(proof.sourceAttemptId())||!receipt.scanAttemptId().equals(proof.scanAttemptId())
                || !receipt.sha256().equals(proof.sha256())||receipt.sizeBytes()!=proof.sizeBytes()
                || !receipt.detectedMediaType().equals(proof.detectedMediaType())
                || !receipt.sourceQuarantineStorageVersion().equals(proof.quarantineStorageVersion())
                || !receipt.scanProofPayloadSha256().equals(sha256(proof.canonicalPayload()))
                || !receipt.generationSha256().equals(proof.generationSha256())||!receipt.policyVersion().equals(proof.policyVersion())
                || !receipt.verifierId().equals(proof.verifierId())||receipt.issuedAt().isBefore(proof.issuedAt())
                || receipt.expiresAt().isAfter(proof.expiresAt()))throw unavailableFailure();
    }
    void verifyCurrentReceipt(IngestionContentReceiptV2 receipt,SignedScanProofV1 proof){
        verifyReceipt(receipt,proof);requireCurrent(receipt.notBefore(),receipt.expiresAt());
    }
    void verifyGeneration(AcceptedGeneration generation) {
        required(generation);
        if(!generation.manifestSha256().equals(sha256(generation.canonicalManifest()))
                || clock.instant().isBefore(generation.validatedAt())||!clock.instant().isBefore(generation.validUntil()))throw unavailableFailure();
    }

    DurableObservation observe(AllocationRequest allocation,ObservationKind kind,ObjectIdentity identity,
                                String sha256,Long sizeBytes,String mediaType) {
        return observation(allocation,kind,identity,sha256,sizeBytes,mediaType,false,null,false,null);
    }
    DisposalObservation observeDisposal(DisposalClaim claim,RecoveryObservation absence){
        required(claim,absence);validateRecovery(absence);return new DisposalObservation(claim,absence);
    }
    static ExecutionDescriptor executionDescriptor(ScanPlan p,TechnicalFence f,Instant createdAt){
        ExecutionDescriptor seed=new ExecutionDescriptor(p.plannedExecutionId(),p.tenantId(),p.intentId(),p.fileId(),
                p.sourceAttemptId(),p.scanAttemptId(),p.quarantineAllocationId(),p.scratchAllocationId(),p.physicalResourceRealmId(),
                p.profileSha256(),p.slotId(),f.leaseEpoch(),f.leaseToken(),f.technicalVersion(),new byte[]{1},"0".repeat(64),createdAt);
        byte[] bytes=executionPayload(seed);
        return new ExecutionDescriptor(seed.executionId(),seed.tenantId(),seed.intentId(),seed.fileId(),seed.sourceAttemptId(),
                seed.scanAttemptId(),seed.quarantineAllocationId(),seed.scratchAllocationId(),seed.physicalResourceRealmId(),
                seed.profileSha256(),seed.slotId(),seed.claimEpoch(),seed.claimToken(),seed.claimTechnicalVersion(),bytes,sha256(bytes),seed.createdAt());
    }
    static void validateExecution(ExecutionDescriptor d){
        if(!MessageDigest.isEqual(executionPayload(d),d.canonicalDescriptor())||!sha256(d.canonicalDescriptor()).equals(d.descriptorSha256()))throw unavailableFailure();
    }
    static void requireExecutionFence(ExecutionDescriptor d,TechnicalFence f){
        if(!d.tenantId().equals(f.tenantId())||!d.intentId().equals(f.intentId())||!d.scanAttemptId().equals(f.attemptId())
                ||d.claimEpoch()!=f.leaseEpoch()||!d.claimToken().equals(f.leaseToken())||f.technicalVersion()<d.claimTechnicalVersion())throw unavailableFailure();
    }
    static void requireCleanExecution(SignedScanProofV1 p,RecordedExecution execution,KernelExecutionFacts k){
        ExecutionDescriptor d=execution.descriptor();validateExecution(d);
        if(!k.normalClean()||!p.executionId().equals(d.executionId())||!p.descriptorSha256().equals(d.descriptorSha256())
                ||!k.executionId().equals(d.executionId())||!k.descriptorSha256().equals(d.descriptorSha256())
                ||!k.physicalResourceRealmId().equals(d.physicalResourceRealmId())||!k.slotId().equals(d.slotId())
                ||!k.profileSha256().equals(d.profileSha256())||!p.kernelResultSha256().equals(kernelFactsSha256(k))
                ||!p.tenantId().equals(d.tenantId())||!p.intentId().equals(d.intentId())||!p.fileId().equals(d.fileId())
                ||!p.sourceAttemptId().equals(d.sourceAttemptId())||!p.scanAttemptId().equals(d.scanAttemptId())
                ||!p.quarantineAllocationId().equals(d.quarantineAllocationId())||k.observedAt().isAfter(p.issuedAt()))throw unavailableFailure();
    }
    RecoveryObservation observeRecovery(RecoveryBinding binding,AllocationRequest allocation,RecoveryPresence presence,
            ProcessState processState,boolean writerQuiescent,String writerHash,Instant writerAt,KernelExecutionFacts facts,
            ObjectIdentity identity,UUID baselineId,String baselineHash,String stopHash,Instant observedAt){
        RecoveryObservation seed=new RecoveryObservation(binding,allocation,presence,processState,writerQuiescent,writerHash,writerAt,
                facts,identity,baselineId,baselineHash,stopHash,new byte[]{1},"0".repeat(64),observedAt);
        byte[] canonical=recoveryPayload(seed);
        return new RecoveryObservation(binding,allocation,presence,processState,writerQuiescent,writerHash,writerAt,facts,identity,
                baselineId,baselineHash,stopHash,canonical,sha256(canonical),observedAt);
    }
    void validateRecovery(RecoveryObservation observation){
        if(!MessageDigest.isEqual(recoveryPayload(observation),observation.canonicalObservation())
                ||!sha256(observation.canonicalObservation()).equals(observation.observationSha256()))throw unavailableFailure();
    }
    static String kernelFactsSha256(KernelExecutionFacts facts){return sha256(kernelPayload(facts));}
    private static byte[] executionPayload(ExecutionDescriptor d){
        return encode(out->put(out,"INGESTION_EXECUTION_V1",d.executionId(),d.tenantId(),d.intentId(),d.fileId(),d.sourceAttemptId(),
                d.scanAttemptId(),d.quarantineAllocationId(),d.scratchAllocationId(),d.physicalResourceRealmId(),d.profileSha256(),d.slotId(),
                d.claimEpoch(),d.claimToken(),d.claimTechnicalVersion(),d.createdAt()));
    }
    private static byte[] kernelPayload(KernelExecutionFacts k){
        return encode(out->put(out,"KERNEL_EXECUTION_V1",k.executionId(),k.descriptorSha256(),k.challenge(),k.physicalResourceRealmId(),
                k.slotId(),k.profileSha256(),k.bootId(),k.supervisorPid(),k.supervisorStartTicks(),k.childPid(),k.childStartTicks(),
                k.childAbsentReason(),k.pidNamespace(),k.mountNamespace(),k.netNamespace(),k.ipcNamespace(),k.utsNamespace(),k.cgroupName(),
                k.cgroupMountId(),k.cgroupInode(),k.pidsPolicySha256(),k.pidsCurrent(),k.populated(),k.pidsControllerContinuous(),
                k.noMigrationOrFutureEntry(),k.slotLockExclusive(),k.slotLockIdentitySha256(),k.permanentStop(),k.stopSha256(),
                k.childObserved(),k.imageVerified(),k.waitReaped(),k.inputDrained(),k.stdoutDrained(),k.stderrDrained(),
                k.interruptedOutputDiscarded(),k.processState().name(),k.kernelControlSha256(),k.reportSha256(),k.observedAt()));
    }
    private static byte[] recoveryPayload(RecoveryObservation o){
        return encode(out->{
            RecoveryBinding b=o.binding();AllocationRequest a=o.allocation();
            put(out,"INGESTION_RECOVERY_V1",b.tenantId(),b.intentId(),b.allocationId(),b.recoveryId(),b.expectedCurrentTechnicalVersion(),
                    b.expectedIntentLeaseEpoch(),b.targetEventNo(),b.targetEventSha256(),b.executionId(),b.descriptorSha256(),b.reason().name(),
                    b.retentionPolicySha256(),a.fileId(),a.ownerMemberId(),a.attemptId(),a.kind().name(),a.rootGeneration(),a.serverObjectName(),
                    a.storageVersion(),a.reservedBytes(),a.reservationLeaseEpoch(),a.createdAt(),o.presence().name(),o.processState().name(),
                    o.writerQuiescent(),o.writerQuiescenceSha256(),o.writerQuiescenceObservedAt(),o.kernelFacts()!=null);
            if(o.kernelFacts()!=null)put(out,kernelFactsSha256(o.kernelFacts()));
            ObjectIdentity i=o.identity();put(out,i!=null);
            if(i!=null)put(out,i.rootGeneration(),i.serverObjectName(),i.storageVersion(),i.fileStoreIdentity(),i.fileKey(),i.unixDevice(),
                    i.unixInode(),i.linkCount(),i.measuredBytes(),i.durabilityCompletedAt());
            put(out,o.baselineEventId(),o.baselineObservationSha256(),o.stopWriteFenceSha256(),o.observedAt());
        });
    }
    private DurableObservation observation(AllocationRequest allocation,ObservationKind kind,ObjectIdentity identity,
                                           String hash,Long size,String media,boolean absent,Instant absentAt,
                                           boolean reaped,String reapHash) {
        DurableObservation seed=new DurableObservation(allocation,kind,identity,hash,size,media,absent,absentAt,reaped,reapHash,new byte[]{1},"0".repeat(64));
        byte[] canonical=observationPayload(seed);
        return new DurableObservation(allocation,kind,identity,hash,size,media,absent,absentAt,reaped,reapHash,canonical,sha256(canonical));
    }
    void validateObservation(DurableObservation observation) {
        required(observation);
        if(!MessageDigest.isEqual(observationPayload(observation),observation.canonicalObservation())
                || !observation.observationSha256().equals(sha256(observation.canonicalObservation())))throw unavailableFailure();
    }

    static String sha256(byte[] bytes) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
        catch(GeneralSecurityException impossible){throw new IllegalStateException("Required digest unavailable",impossible);}
    }
    void requireSigningAvailable(){requireSigning();hmac(signingKeyId,new byte[]{1});}
    private void requireSigning(){if(signingKeyId==null)throw unavailableFailure();}
    private void requireCurrent(Instant notBefore,Instant expiresAt){Instant now=clock.instant();if(now.isBefore(notBefore)||!now.isBefore(expiresAt))throw unavailableFailure();}
    private byte[] hmac(String keyId,byte[] payload) {
        byte[] key=keys.get(keyId);if(key==null)throw unavailableFailure();
        try{Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(key,"HmacSHA256"));return mac.doFinal(payload);}
        catch(GeneralSecurityException failure){throw new IllegalStateException("Required signature unavailable",failure);}
    }
    private void verifySigned(String keyId,byte[] expected,byte[] actual,byte[] signature) {
        if(!MessageDigest.isEqual(expected,actual)||!MessageDigest.isEqual(hmac(keyId,actual),signature))throw unavailableFailure();
    }
    private static void requireImmutableBinding(ImmutableObject object,SignedScanProofV1 proof){
        if(!object.tenantId().equals(proof.tenantId())||!object.intentId().equals(proof.intentId())||!object.fileId().equals(proof.fileId())
                ||!object.sourceAttemptId().equals(proof.sourceAttemptId())||!object.scanAttemptId().equals(proof.scanAttemptId())
                ||!object.sha256().equals(proof.sha256())||object.sizeBytes()!=proof.sizeBytes()
                ||!object.detectedMediaType().equals(proof.detectedMediaType())
                ||!object.sourceQuarantineObservationSha256().equals(proof.quarantineObservationSha256()))throw unavailableFailure();
    }
    private static SignedScanProofV1 copyScan(SignedScanProofV1 p,byte[] payload,byte[] signature){
        return new SignedScanProofV1(p.proofId(),p.tenantId(),p.intentId(),p.fileId(),p.sourceAttemptId(),p.scanAttemptId(),p.quarantineAllocationId(),
                p.generationId(),p.executionId(),p.descriptorSha256(),p.kernelResultSha256(),p.quarantineStorageVersion(),p.quarantineObservationSha256(),p.sha256(),p.detectedMediaType(),p.generationSha256(),
                p.manifestSha256(),p.policyVersion(),p.effectiveConfigurationSha256(),p.verifierId(),p.keyId(),p.sizeBytes(),p.startedAt(),p.completedAt(),
                p.issuedAt(),p.notBefore(),p.expiresAt(),payload,signature);
    }
    private static IngestionContentReceiptV2 copyReceipt(IngestionContentReceiptV2 r,byte[] payload,byte[] signature){
        return new IngestionContentReceiptV2(r.receiptId(),r.tenantId(),r.intentId(),r.fileId(),r.sourceAttemptId(),r.scanAttemptId(),r.immutableAllocationId(),
                r.scanProofId(),r.immutableStorageVersion(),r.immutableObservationSha256(),r.sourceQuarantineStorageVersion(),r.scanProofPayloadSha256(),
                r.sha256(),r.detectedMediaType(),r.generationSha256(),r.policyVersion(),r.verifierId(),r.keyId(),r.sizeBytes(),r.issuedAt(),r.notBefore(),r.expiresAt(),payload,signature);
    }
    private static byte[] scanPayload(SignedScanProofV1 p){
        return encode(out->put(out,"SCAN_PROOF_V1","CLEAN",p.proofId(),p.tenantId(),p.intentId(),p.fileId(),p.sourceAttemptId(),p.scanAttemptId(),
                p.quarantineAllocationId(),p.generationId(),p.executionId(),p.descriptorSha256(),p.kernelResultSha256(),p.quarantineStorageVersion(),p.quarantineObservationSha256(),p.sha256(),p.detectedMediaType(),
                p.generationSha256(),p.manifestSha256(),p.policyVersion(),p.effectiveConfigurationSha256(),p.verifierId(),p.keyId(),p.sizeBytes(),
                p.startedAt(),p.completedAt(),p.issuedAt(),p.notBefore(),p.expiresAt()));
    }
    private static byte[] receiptPayload(IngestionContentReceiptV2 r){
        return encode(out->put(out,"SCANNED_CONTENT_V2",r.receiptId(),r.tenantId(),r.intentId(),r.fileId(),r.sourceAttemptId(),r.scanAttemptId(),
                r.immutableAllocationId(),r.scanProofId(),r.immutableStorageVersion(),r.immutableObservationSha256(),r.sourceQuarantineStorageVersion(),
                r.scanProofPayloadSha256(),r.sha256(),r.detectedMediaType(),r.generationSha256(),r.policyVersion(),r.verifierId(),r.keyId(),r.sizeBytes(),
                r.issuedAt(),r.notBefore(),r.expiresAt()));
    }
    private static byte[] observationPayload(DurableObservation o){
        return encode(out->{
            AllocationRequest a=o.allocation();
            put(out,"INGESTION_OBSERVATION_V1",a.tenantId(),a.intentId(),a.fileId(),a.ownerMemberId(),a.attemptId(),a.allocationId(),a.kind().name(),
                    a.rootGeneration(),a.serverObjectName(),a.storageVersion(),a.reservedBytes(),a.reservationLeaseEpoch(),a.createdAt(),o.kind().name());
            ObjectIdentity i=o.identity();put(out,i!=null);
            if(i!=null)put(out,i.rootGeneration(),i.serverObjectName(),i.storageVersion(),i.fileStoreIdentity(),i.fileKey(),i.unixDevice(),i.unixInode(),
                    i.linkCount(),i.measuredBytes(),i.durabilityCompletedAt());
            put(out,o.sha256(),o.sizeBytes(),o.mediaType(),o.objectAbsent(),o.absenceDurableAt(),o.childReaped(),o.childReapEvidenceSha256());
        });
    }
    @FunctionalInterface private interface Encoder {void encode(DataOutputStream out)throws IOException;}
    private static byte[] encode(Encoder encoder){
        try{ByteArrayOutputStream buffer=new ByteArrayOutputStream();try(DataOutputStream out=new DataOutputStream(buffer)){encoder.encode(out);}
            byte[] result=buffer.toByteArray();if(result.length>MAX_CANONICAL_BYTES)throw unavailableFailure();return result;
        }catch(IOException impossible){throw new IllegalStateException("In-memory encoding failed",impossible);}
    }
    private static void put(DataOutputStream out,Object... fields)throws IOException{
        for(Object field:fields){
            if(field==null){out.writeByte(0);}
            else if(field instanceof String value){out.writeByte(1);byte[] bytes=value.getBytes(StandardCharsets.UTF_8);out.writeInt(bytes.length);out.write(bytes);}
            else if(field instanceof UUID value){out.writeByte(2);out.writeLong(value.getMostSignificantBits());out.writeLong(value.getLeastSignificantBits());}
            else if(field instanceof Long value){out.writeByte(3);out.writeLong(value);}
            else if(field instanceof Instant value){out.writeByte(4);out.writeLong(value.getEpochSecond());out.writeInt(value.getNano());}
            else if(field instanceof Boolean value){out.writeByte(5);out.writeBoolean(value);}
            else throw new IllegalArgumentException("Unsupported canonical value");
        }
    }
    private static FileEvidenceTrustUnavailableException unavailableFailure(){return new FileEvidenceTrustUnavailableException();}
}
