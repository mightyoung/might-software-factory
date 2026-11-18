package com.mightyoung.softwarefactory.files;

import java.lang.reflect.RecordComponent;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static com.mightyoung.softwarefactory.files.FileIngestionContracts.*;
import static org.assertj.core.api.Assertions.*;

/** Synthetic signature tests; no actual scanner/resource acceptance is inferred. */
class IngestionProofCodecTest {
    static final Instant NOW=Instant.parse("2026-11-03T10:00:00Z");
    final byte[] key=new byte[32];
    final IngestionProofCodec codec=new IngestionProofCodec(Map.of("key1",key),"key1",Clock.fixed(NOW,ZoneOffset.UTC));
    final UUID tenant=UUID.fromString("00000000-0000-0000-0000-000000000001"),intent=UUID.fromString("00000000-0000-0000-0000-000000000002"),
            file=UUID.fromString("00000000-0000-0000-0000-000000000003"),sourceAttempt=UUID.fromString("00000000-0000-0000-0000-000000000004"),
            scanAttempt=UUID.fromString("00000000-0000-0000-0000-000000000005");
    AllocationRequest allocation(AllocationKind kind,UUID attempt,String name){return new AllocationRequest(tenant,intent,file,
            UUID.fromString("00000000-0000-0000-0000-000000000006"),attempt,UUID.nameUUIDFromBytes(name.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
            kind,"a".repeat(64),name,"v1",65536,1,NOW.minusSeconds(2));}
    ObjectIdentity identity(AllocationRequest a,long size){return new ObjectIdentity(a.rootGeneration(),a.serverObjectName(),a.storageVersion(),"store","inode-1",1,2,1,size,NOW.minusSeconds(1));}
    AcceptedGeneration generation(){byte[] bytes="synthetic-manifest-v1".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return new AcceptedGeneration(UUID.fromString("00000000-0000-0000-0000-000000000007"),"b".repeat(64),IngestionProofCodec.sha256(bytes),bytes,NOW.minusSeconds(60),NOW.plusSeconds(900));}
    QuarantinedObject quarantine(){var a=allocation(AllocationKind.QUARANTINE,sourceAttempt,"quarantine");var identity=identity(a,3);
        var observation=codec.observe(a,ObservationKind.COMPLETE_QUARANTINE,identity,"c".repeat(64),3L,"image/png");
        return new QuarantinedObject(tenant,intent,file,a.allocationId(),sourceAttempt,identity,"c".repeat(64),"image/png",3,observation.observationSha256());}
    static UUID id(long id){return new UUID(0,id);}
    TechnicalFence scanFence(){return new TechnicalFence(tenant,intent,scanAttempt,id(15),2,2);}
    RecordedExecution execution(){
        var plan=new ScanPlan(tenant,intent,file,sourceAttempt,scanAttempt,quarantine().allocationId(),
                allocation(AllocationKind.SCANNER_SCRATCH,scanAttempt,"scratch").allocationId(),id(10),id(11),"e".repeat(64),"slot1",1,2,
                generation().generationSha256(),quarantine().observationSha256());
        return new RecordedExecution(ExecutionDescriptor.forClaim(plan,scanFence(),NOW.minusSeconds(2)));
    }
    KernelExecutionFacts facts(){var d=execution().descriptor();return new KernelExecutionFacts(d.executionId(),d.descriptorSha256(),"1".repeat(64),
            d.physicalResourceRealmId(),d.slotId(),d.profileSha256(),id(13),100,1000,101L,1001L,null,
            1L,2L,3L,4L,5L,d.executionId(),1,2,"f".repeat(64),0,false,true,true,true,"a".repeat(64),
            false,null,true,true,true,true,true,true,false,ProcessState.REAPED,"b".repeat(64),"c".repeat(64),NOW);}
    ScanOutcome outcome(){return new ScanOutcome(proof(),execution(),facts());}
    SignedScanProofV1 proof(){return codec.issueScan(id(8),quarantine(),scanFence(),generation(),execution(),facts(),
            new IngestionProofCodec.ProofPolicy("policy-v1","d".repeat(64),"verifier-v1"),
            new IngestionProofCodec.ScanTimes(NOW.minusSeconds(1),NOW,NOW,NOW,NOW.plusSeconds(600)));}
    IngestionContentReceiptV2 receipt(SignedScanProofV1 proof){var a=allocation(AllocationKind.IMMUTABLE,scanAttempt,"immutable");var identity=identity(a,3);
        var immutable=new ImmutableObject(tenant,intent,file,a.allocationId(),sourceAttempt,scanAttempt,identity,"c".repeat(64),"image/png",3,proof.quarantineObservationSha256());
        var observation=codec.observe(a,ObservationKind.COMPLETE_IMMUTABLE,identity,"c".repeat(64),3L,"image/png");
        return codec.issueReceipt(UUID.fromString("00000000-0000-0000-0000-000000000009"),immutable,proof,observation.observationSha256(),
                new IngestionProofCodec.ReceiptTimes(NOW,NOW,NOW.plusSeconds(600)));}

    @Test void canonicalProofAndReceiptHaveStableDomainSeparatedSignatures(){
        var proof=proof();var receipt=receipt(proof);
        codec.verifyCurrentScan(proof,generation());codec.verifyCurrentReceipt(receipt,proof);
        assertThat(IngestionProofCodec.sha256(proof.canonicalPayload())).isEqualTo("4b5db95620a4fa9d724e6a6dea55180ecdc0a38aa5436a79d759fade9bbace28");
        assertThat(java.util.HexFormat.of().formatHex(proof.signature())).isEqualTo("d03104cd26f22ea6b0459af4d6079a35dcb20b0bad0ec1f0d61fad15e2a19f55");
        assertThat(IngestionProofCodec.sha256(receipt.canonicalPayload())).isEqualTo("edf7752cb2e6900fd9ad6b5f89f6ed8dd60232f35daa7690a38741bec10eacad");
        assertThat(java.util.HexFormat.of().formatHex(receipt.signature())).isEqualTo("6a85fcaa2fc56775389a0a4e5423c28aa6b0c41c57d44ee9fca5dfbf3b49382e");
        assertThat(proof.canonicalPayload()).isEqualTo(proof().canonicalPayload());assertThat(proof.signature()).isEqualTo(proof().signature());
        assertThat(proof.toString()).doesNotContain("key1","quarantine","[B@");
        byte[] exported=proof.signature();exported[0]^=1;codec.verifyScan(proof);
    }
    @Test void everySignedProjectionAndBothPayloadsRejectTampering() throws Exception {
        var proof=proof();
        for(RecordComponent field:SignedScanProofV1.class.getRecordComponents()){
            Object original=field.getAccessor().invoke(proof);Object altered=alter(original);
            assertThatThrownBy(()->codec.verifyScan(copy(proof,field.getName(),altered)))
                    .as("scan field %s",field.getName()).isInstanceOfAny(FileEvidenceTrustUnavailableException.class,IllegalArgumentException.class);
        }
        var receipt=receipt(proof);
        for(RecordComponent field:IngestionContentReceiptV2.class.getRecordComponents()){
            Object original=field.getAccessor().invoke(receipt);
            assertThatThrownBy(()->codec.verifyReceipt(copy(receipt,field.getName(),alter(original)),proof))
                    .as("receipt field %s",field.getName()).isInstanceOfAny(FileEvidenceTrustUnavailableException.class,IllegalArgumentException.class);
        }
    }
    @Test void keyRotationRetainsVerificationWithoutGrantingAnUnknownKey(){
        byte[] next=new byte[32];Arrays.fill(next,(byte)7);
        var rotated=new IngestionProofCodec(Map.of("key1",key,"key2",next),"key2",Clock.fixed(NOW,ZoneOffset.UTC));
        rotated.verifyScan(proof());rotated.verifyReceipt(receipt(proof()),proof());
        assertThatThrownBy(()->new IngestionProofCodec(Map.of("key2",next),"key2",Clock.fixed(NOW,ZoneOffset.UTC)).verifyScan(proof()))
                .isInstanceOf(FileEvidenceTrustUnavailableException.class);
        assertThatThrownBy(()->IngestionProofCodec.unavailable().requireSigningAvailable()).isInstanceOf(FileEvidenceTrustUnavailableException.class);
    }
    @Test void retainedSignatureVerificationIsSeparateFromNewCheckpointFreshness(){
        var proof=proof();var receipt=receipt(proof);
        var later=new IngestionProofCodec(Map.of("key1",key),"key1",Clock.fixed(NOW.plusSeconds(1000),ZoneOffset.UTC));
        later.verifyScan(proof);later.verifyReceipt(receipt,proof);
        assertThatThrownBy(()->later.verifyCurrentScan(proof,generation())).isInstanceOf(FileEvidenceTrustUnavailableException.class);
        assertThatThrownBy(()->later.verifyCurrentReceipt(receipt,proof)).isInstanceOf(FileEvidenceTrustUnavailableException.class);
    }
    @Test void canonicalObservationSurvivesDatabaseMicrosecondReloadAndRejectsProjectionMismatch() throws Exception {
        var a=allocation(AllocationKind.QUARANTINE,sourceAttempt,"quarantine");
        var highPrecision=new ObjectIdentity(a.rootGeneration(),a.serverObjectName(),a.storageVersion(),"store","inode-1",1,2,1,3,NOW.minusSeconds(1).plusNanos(123456789));
        var observed=codec.observe(a,ObservationKind.COMPLETE_QUARANTINE,highPrecision,"c".repeat(64),3L,"image/png");
        var reloaded=new ObjectIdentity(a.rootGeneration(),a.serverObjectName(),a.storageVersion(),"store","inode-1",1,2,1,3,Instant.parse(highPrecision.durabilityCompletedAt().toString()));
        var equivalent=codec.observe(a,ObservationKind.COMPLETE_QUARANTINE,reloaded,"c".repeat(64),3L,"image/png");
        assertThat(equivalent.canonicalObservation()).isEqualTo(observed.canonicalObservation());codec.validateObservation(equivalent);
        assertThatThrownBy(()->codec.validateObservation(copy(observed,"sha256","e".repeat(64))))
                .isInstanceOf(FileEvidenceTrustUnavailableException.class);
        assertThatThrownBy(()->codec.validateObservation(copy(observed,"canonicalObservation",new byte[]{1,2})))
                .isInstanceOf(FileEvidenceTrustUnavailableException.class);
    }
    @Test void newProofAndReceiptIssuanceRejectFutureServerTimes(){
        for(var times:new IngestionProofCodec.ScanTimes[]{
                new IngestionProofCodec.ScanTimes(NOW.minusSeconds(1),NOW,NOW.plusSeconds(1),NOW.minusSeconds(10),NOW.plusSeconds(600)),
                new IngestionProofCodec.ScanTimes(NOW,NOW.plusSeconds(1),NOW.plusSeconds(1),NOW.minusSeconds(10),NOW.plusSeconds(600))}) {
            assertThatThrownBy(()->codec.issueScan(UUID.randomUUID(),quarantine(),scanFence(),
                    generation(),execution(),facts(),new IngestionProofCodec.ProofPolicy("policy-v1","d".repeat(64),"verifier-v1"),times))
                    .isInstanceOf(FileEvidenceTrustUnavailableException.class);
        }
        var proof=proof();var a=allocation(AllocationKind.IMMUTABLE,scanAttempt,"immutable");
        var immutable=new ImmutableObject(tenant,intent,file,a.allocationId(),sourceAttempt,scanAttempt,identity(a,3),proof.sha256(),proof.detectedMediaType(),3,proof.quarantineObservationSha256());
        var observed=codec.observe(a,ObservationKind.COMPLETE_IMMUTABLE,identity(a,3),proof.sha256(),3L,proof.detectedMediaType());
        assertThatThrownBy(()->codec.issueReceipt(UUID.randomUUID(),immutable,proof,observed.observationSha256(),
                new IngestionProofCodec.ReceiptTimes(NOW.plusSeconds(1),NOW,NOW.plusSeconds(600))))
                .isInstanceOf(FileEvidenceTrustUnavailableException.class);
    }
    @Test void clockRegressionRefusesNewIssuanceWithoutDiscardingHistoricSignatures(){
        var regressed=new IngestionProofCodec(Map.of("key1",key),"key1",Clock.fixed(NOW.minusSeconds(2),ZoneOffset.UTC));
        regressed.verifyScan(proof());
        assertThatThrownBy(()->regressed.issueScan(UUID.randomUUID(),quarantine(),scanFence(),generation(),execution(),facts(),
                new IngestionProofCodec.ProofPolicy("policy-v1","d".repeat(64),"verifier-v1"),
                new IngestionProofCodec.ScanTimes(NOW.minusSeconds(1),NOW,NOW,NOW.minusSeconds(10),NOW.plusSeconds(600))))
                .isInstanceOf(FileEvidenceTrustUnavailableException.class);
    }
    @Test void microsecondNormalizationCannotExtendCollapsedSignedWindows() throws Exception {
        var proof=proof();var receipt=receipt(proof);
        assertThatThrownBy(()->copy(proof,"expiresAt",proof.issuedAt().plusNanos(1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->copy(receipt,"expiresAt",receipt.issuedAt().plusNanos(1))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void executionCanonicalReloadUsesContentAndRejectsEveryIdentityClaimMutation() throws Exception {
        var original=execution().descriptor();IngestionProofCodec.validateExecution(original);
        var reloaded=copy(original,"canonicalDescriptor",original.canonicalDescriptor());
        assertThat(reloaded.canonicalDescriptor()).isEqualTo(original.canonicalDescriptor());IngestionProofCodec.validateExecution(reloaded);
        for(var field:ExecutionDescriptor.class.getRecordComponents()){
            assertThatThrownBy(()->IngestionProofCodec.validateExecution(copy(original,field.getName(),alter(field.getAccessor().invoke(original)))))
                    .as("execution field %s",field.getName()).isInstanceOfAny(IllegalArgumentException.class,FileEvidenceTrustUnavailableException.class);
        }
    }
    @Test void kernelAbsentWithNoChildCanRecoverButNeverProduceCleanProof() throws Exception {
        var d=execution().descriptor();
        var absent=new KernelExecutionFacts(d.executionId(),d.descriptorSha256(),"1".repeat(64),d.physicalResourceRealmId(),d.slotId(),d.profileSha256(),id(13),
                100,1000,null,null,"CHILD_NOT_RECORDED",null,null,null,null,null,d.executionId(),1,2,"f".repeat(64),0,false,
                true,true,true,"a".repeat(64),true,"d".repeat(64),false,false,false,false,false,false,true,ProcessState.KERNEL_ABSENT,
                "b".repeat(64),"c".repeat(64),NOW);
        assertThat(absent.quiescent()).isTrue();assertThat(absent.normalClean()).isFalse();
        assertThatThrownBy(()->new ScanOutcome(proof(),execution(),absent)).isInstanceOf(FileEvidenceTrustUnavailableException.class);
        assertThatThrownBy(()->copy(absent,"pidsCurrent",1L)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->copy(absent,"pidsControllerContinuous",false)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->copy(absent,"noMigrationOrFutureEntry",false)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->copy(absent,"stdoutDrained",true)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->copy(absent,"permanentStop",false)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void everyKernelFactIsBoundToSuccessfulScanIncludingControlsAndDrains() throws Exception {
        var facts=facts();var proof=proof();
        for(var field:KernelExecutionFacts.class.getRecordComponents()){
            Object original=field.getAccessor().invoke(facts);
            Object altered=original==null?"f".repeat(64):original instanceof Boolean b?!b:
                    original instanceof ProcessState?ProcessState.UNKNOWN:alter(original);
            assertThatThrownBy(()->new ScanOutcome(proof,execution(),copy(facts,field.getName(),altered)))
                    .as("kernel field %s",field.getName()).isInstanceOfAny(IllegalArgumentException.class,FileEvidenceTrustUnavailableException.class);
        }
    }
    @Test void recoveryAbsentHasNoIdentityAndBindsEveryProjectionWithoutSigningAvailability() throws Exception {
        var a=allocation(AllocationKind.QUARANTINE,sourceAttempt,"quarantine");
        var binding=new RecoveryBinding(tenant,intent,a.allocationId(),id(16),3,3,0,null,null,null,RecoveryReason.TERMINAL,"f".repeat(64));
        var recovery=IngestionProofCodec.unavailable().observeRecovery(binding,a,RecoveryPresence.ABSENT,ProcessState.NONE_STARTED,
                true,"a".repeat(64),NOW,null,null,null,null,"b".repeat(64),NOW);
        assertThat(recovery.identity()).isNull();assertThat(recovery.quiescent()).isTrue();codec.validateRecovery(recovery);
        codec.validateRecovery(copy(recovery,"canonicalObservation",recovery.canonicalObservation()));
        assertThatThrownBy(()->copy(recovery,"identity",identity(a,3))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->codec.validateRecovery(copy(recovery,"stopWriteFenceSha256","c".repeat(64)))).isInstanceOf(FileEvidenceTrustUnavailableException.class);
        assertThatThrownBy(()->codec.validateRecovery(copy(recovery,"binding",copy(binding,"expectedCurrentTechnicalVersion",4L))))
                .isInstanceOf(FileEvidenceTrustUnavailableException.class);
    }
    private static Object alter(Object value){
        if(value instanceof UUID)return UUID.randomUUID();
        if(value instanceof String s)return s.matches("[0-9a-f]{64}")?(s.charAt(0)=='f'?"e":"f").repeat(64):s+"-x";
        if(value instanceof Long l)return l+1;
        if(value instanceof Instant i)return i.plusSeconds(1);
        if(value instanceof byte[] bytes){byte[] changed=bytes.clone();changed[0]^=1;return changed;}
        throw new AssertionError("Uncovered signed field type");
    }
    @SuppressWarnings("unchecked") static <T> T copy(T record,String field,Object value) throws Exception {
        RecordComponent[] fields=record.getClass().getRecordComponents();Class<?>[] types=new Class<?>[fields.length];Object[] values=new Object[fields.length];
        for(int i=0;i<fields.length;i++){types[i]=fields[i].getType();values[i]=fields[i].getName().equals(field)?value:fields[i].getAccessor().invoke(record);}
        try{return (T)record.getClass().getDeclaredConstructor(types).newInstance(values);}
        catch(java.lang.reflect.InvocationTargetException failure){if(failure.getCause() instanceof RuntimeException e)throw e;throw failure;}
    }
}
