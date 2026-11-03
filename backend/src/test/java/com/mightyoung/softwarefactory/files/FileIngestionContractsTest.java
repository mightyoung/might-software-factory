package com.mightyoung.softwarefactory.files;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static com.mightyoung.softwarefactory.files.FileIngestionContracts.*;
import static org.assertj.core.api.Assertions.*;

class FileIngestionContractsTest {
    final Instant now=Instant.parse("2026-11-03T10:00:00.123456789Z");
    final UUID tenant=UUID.randomUUID(),intent=UUID.randomUUID(),file=UUID.randomUUID(),owner=UUID.randomUUID(),attempt=UUID.randomUUID();
    AllocationRequest allocation(String name){return new AllocationRequest(tenant,intent,file,owner,attempt,UUID.randomUUID(),
            AllocationKind.QUARANTINE,"a".repeat(64),name,"version-1",4096,1,now);}

    @Test void objectNamesRejectPathsAndBindingsKeepSourceAndScanAttemptsDistinct(){
        for(String name:List.of(".","..","../blob","a/b","a\\b","a\u0000b","a b"))
            assertThatThrownBy(()->allocation(name)).isInstanceOf(IllegalArgumentException.class);
        var request=allocation("server-blob");
        var recorded=new RecordedAllocation(request,0,null,null,4096,null,null,null);
        assertThatThrownBy(()->new ReceiveAllocation(recorded,4097,"image/png","b".repeat(64),UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new ReceiveAllocation(recorded,1,"image/png","b".repeat(64),attempt))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new ScanAllocation(recorded,new TechnicalFence(tenant,intent,attempt,UUID.randomUUID(),0,1),1,"slot-1"))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void durableTimesUseDatabaseMicrosecondsAndCollapsedWindowsFail(){
        assertThat(allocation("blob").createdAt()).isEqualTo(Instant.parse("2026-11-03T10:00:00.123456Z"));
        assertThatThrownBy(()->new AcceptedGeneration(UUID.randomUUID(),"a".repeat(64),"b".repeat(64),new byte[]{1},now,now.plusNanos(1)))
                .isInstanceOf(IllegalArgumentException.class);
        var identity=new ObjectIdentity("a".repeat(64),"blob","v1","store","key",1,2,1,1,now);
        assertThat(identity.durabilityCompletedAt().getNano()%1000).isZero();
    }
    @Test void resourceViewDefensivelyCopiesBoundedActualSlotDescriptors(){
        byte[] manifest={1,2,3};
        var generation=new AcceptedGeneration(UUID.randomUUID(),"a".repeat(64),"b".repeat(64),manifest,now,now.plusSeconds(60));
        manifest[0]=99;byte[] exported=generation.canonicalManifest();exported[0]=88;
        assertThat(generation.canonicalManifest()).containsExactly((byte)1,(byte)2,(byte)3);
        var slots=new ArrayList<>(List.of(new ScanSlot("actual-slot-1",134217728)));
        var resources=new ProducerResources(generation,"c".repeat(64),UUID.randomUUID(),"d".repeat(64),65536,slots);slots.clear();
        assertThat(resources.scanSlots()).hasSize(1);
        assertThatThrownBy(()->resources.scanSlots().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(()->new ProducerResources(generation,"c".repeat(64),UUID.randomUUID(),"d".repeat(64),65536,List.of(new ScanSlot("same",1),new ScanSlot("same",2))))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void invalidPlatformIdentityAndNoncanonicalTextCannotAcquireCanonicalBindings(){
        assertThatThrownBy(()->new ObjectIdentity("a".repeat(64),"blob","v1","store","key",1,2,2,1,now)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new ObjectIdentity("a".repeat(64),"blob","v1","store","bad\u0000key",1,2,1,1,now)).isInstanceOf(IllegalArgumentException.class);
        String lone=String.valueOf((char)0xd800);
        assertThatThrownBy(()->new ObjectIdentity("a".repeat(64),"blob","v1","store",lone,1,2,1,1,now)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void completeObservationKindCannotRelabelAnotherAllocationKind(){
        var request=allocation("blob");var identity=new ObjectIdentity(request.rootGeneration(),request.serverObjectName(),request.storageVersion(),"store","key",1,2,1,1,now);
        assertThatThrownBy(()->new DurableObservation(request,ObservationKind.COMPLETE_IMMUTABLE,identity,"b".repeat(64),1L,"image/png",false,null,false,null,new byte[]{1},"c".repeat(64)))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void diagnosticStringsDoNotExposeLeaseTokensOrPrivateObjectKeys(){
        UUID token=UUID.randomUUID();var fence=new TechnicalFence(tenant,intent,attempt,token,1,1);
        assertThat(fence.toString()).doesNotContain(token.toString());
        var request=allocation("private-server-name");
        var identity=new ObjectIdentity(request.rootGeneration(),request.serverObjectName(),request.storageVersion(),"private-store-key","private-inode-key",1,2,1,1,now);
        assertThat(identity.toString()).doesNotContain("private-server-name","private-store-key","private-inode-key");
        assertThat(new RecordedAllocation(request,0,null,null,4096,null,null,null).toString()).doesNotContain("private-server-name");
    }
    @Test void deniedDefaultsAndRejectionMessagesExposeNoResourceDetails(){
        FileIngestionPort port=new FileIngestionPort(){
            public PreparedSlotLease prepareSlot(ScanPlan p,Deadline d){throw new FileEvidenceTrustUnavailableException();}
            public RecoveryObservation observeRecovery(RecordedAllocation a,java.util.Optional<RecordedExecution> e,RecoveryBinding b,Deadline d){throw new FileEvidenceTrustUnavailableException();}
            public QuarantinedObject receive(ReceiveAllocation a,java.io.InputStream i,Deadline d){throw new FileEvidenceTrustUnavailableException();}
            public ScanOutcome scan(QuarantinedObject q,ScanAllocation a,AcceptedGeneration g,TechnicalFence f,RecordedExecution e,PreparedSlotLease lease,Deadline d){throw new FileEvidenceTrustUnavailableException();}
            public ImmutableObjectAndReceipt publishImmutable(QuarantinedObject q,ScanOutcome p,CopyAllocation c,ReceiptAllocation r,Deadline d){throw new FileEvidenceTrustUnavailableException();}
            public DisposalObservation dispose(DisposalClaim c,RecoveryBinding b,Deadline d){throw new FileEvidenceTrustUnavailableException();}
        };
        assertThatThrownBy(port::currentResources).isInstanceOf(FileEvidenceTrustUnavailableException.class);
        assertThat(port.isCurrentGeneration(null)).isFalse();
        var rejected=new FileIngestionPort.ContentRejected(FileIngestionPort.RejectionReason.SCANNER_REJECTED);
        assertThat(rejected.getMessage()).isEqualTo("File content was rejected");assertThat(rejected.getCause()).isNull();
        assertThatThrownBy(()->Deadline.after(Duration.ofDays(1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new Deadline(System.nanoTime()-1)).isInstanceOf(IllegalArgumentException.class);
    }
}
