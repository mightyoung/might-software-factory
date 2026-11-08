package com.mightyoung.softwarefactory.dualprevention.finding.evidenceupload;

import com.mightyoung.softwarefactory.dualprevention.FindingNotVisibleException;
import com.mightyoung.softwarefactory.files.*;
import com.mightyoung.softwarefactory.identity.*;
import java.io.InputStream;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static com.mightyoung.softwarefactory.files.FileIngestionContracts.*;
import static com.mightyoung.softwarefactory.dualprevention.finding.evidenceupload.FindingEvidenceIngestionRepository.*;
import static com.mightyoung.softwarefactory.dualprevention.finding.evidenceupload.FindingEvidenceUploadContracts.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Controlled port seams prove orchestration only, never real storage, scanner, PostgreSQL or HTTP acceptance. */
class FindingEvidenceReceiveServiceTest {
    private final UUID tenant=UUID.randomUUID(),finding=UUID.randomUUID(),intent=UUID.randomUUID(),set=UUID.randomUUID(),notice=UUID.randomUUID();
    private final CurrentTenantContext owner=new CurrentTenantContext(UUID.randomUUID(),UUID.randomUUID(),tenant,UUID.randomUUID(),1,1,1);
    private final Instant now=Instant.parse("2026-11-03T01:00:00Z");
    private final SessionContextResolver sessions=mock(SessionContextResolver.class);
    private final FindingEvidenceIngestionRepository repository=mock(FindingEvidenceIngestionRepository.class);
    private final FindingEvidenceIngestionRepository.Transaction tx=mock(FindingEvidenceIngestionRepository.Transaction.class);
    private final FindingEvidenceUploadAccessPolicy access=mock(FindingEvidenceUploadAccessPolicy.class);
    private final FileIngestionLedger files=mock(FileIngestionLedger.class);
    private final FileIngestionTxContext fileTx=mock(FileIngestionTxContext.class);
    private final FileIngestionPort producer=mock(FileIngestionPort.class);
    private final UploadProducerReadiness readiness=mock(UploadProducerReadiness.class);
    private final FindingEvidenceTechnicalEligibilityPolicy technical=mock(FindingEvidenceTechnicalEligibilityPolicy.class);
    private final AtomicReference<TechnicalIntent> current=new AtomicReference<>();
    private final AtomicBoolean insideTransaction=new AtomicBoolean();
    private FindingEvidenceReceiveService service;
    @BeforeEach @SuppressWarnings("unchecked") void setup(){
        service=new FindingEvidenceReceiveService(sessions,repository,access,files,producer,readiness,
                new FindingEvidenceIngestionProperties(false,"","",1_073_741_824L,8_589_934_592L,""),technical);
        when(sessions.currentTenant()).thenReturn(owner);
        when(tx.current()).thenAnswer(a->current.get());
        when(tx.finding(finding)).thenReturn(Optional.of(new Finding(finding,UUID.randomUUID(),"RECTIFYING",1,1)));
        when(tx.notice(finding,1)).thenReturn(Optional.of(new Notice(notice,owner.memberId(),true)));
        when(tx.evidenceSet(set)).thenReturn(Optional.of(new EvidenceSet(set,finding,1,owner.memberId(),0,false)));
        when(tx.now()).thenReturn(now.atOffset(ZoneOffset.UTC));
        doAnswer(a->{assertThat(insideTransaction.compareAndSet(false,true)).isTrue();try{return ((Function<FindingEvidenceIngestionRepository.Transaction,Object>)a.getArgument(2)).apply(tx);}finally{insideTransaction.set(false);}})
                .when(repository).inIntentTransaction(eq(tenant),eq(intent),any());
        doAnswer(a->((Function<FileIngestionTxContext,Object>)a.getArgument(1)).apply(fileTx)).when(files).inCallerTransaction(eq(tenant),any());
        when(tx.compareAndSet(any(),any(),any(),any())).thenAnswer(a->{
            var expected=(TechnicalIntent)a.getArgument(0);var c=(Change)a.getArgument(1);assertThat(current.get()).isSameAs(expected);
            var next=new TechnicalIntent(tenant,expected.declaration(),expected.mediaType(),expected.sizeBytes(),1,0,"config","policy",c.phase(),expected.technicalVersion()+1,
                    c.leaseEpoch(),c.attemptNo(),c.attemptId(),c.reservedScanAttemptId(),c.leaseToken(),c.leaseUntil(),c.heartbeatAt(),c.nextAttemptAt(),
                    c.quarantineAllocationId(),c.immutableAllocationId(),c.scanProofId(),c.contentReceiptId(),c.scannerGeneration(),c.lastErrorCode(),c.cancelRequested(),now,c.currentExecutionId());
            current.set(next);return next;
        });
        current.set(intent(Phase.INTENT_CREATED,null));
    }
    @ParameterizedTest @EnumSource(value=Phase.class,names={"QUARANTINED","SCANNING","VERIFIED_PENDING_FINALIZATION"})
    void alreadyReceivedIsAuthorizedConflictWithoutReadingOrAllocating(Phase phase){
        current.set(intent(phase,UUID.randomUUID()));
        InputStream unread=new InputStream(){public int read(){throw new AssertionError("Durable PUT must not read bytes");}};
        assertThatThrownBy(()->service.receive(finding,1,intent,"image/png",64,unread))
                .isInstanceOfSatisfying(FindingEvidenceReceiveService.AlreadyReceived.class,
                    failure->assertThat(failure.statusUrl()).endsWith("/evidence-uploads/"+intent));
        verify(access).requireCurrent(eq(owner),any(),eq(1),any(),any());
        verifyNoInteractions(files,readiness,producer);verify(tx,never()).compareAndSet(any(),any(),any(),any());
    }
    @Test void durableQuarantineInRetryWaitReturnsStatusEvenWhenProducerIsUnavailable(){
        UUID quarantine=UUID.randomUUID();retry(quarantine,"SCANNER_UNAVAILABLE");
        when(files.load(fileTx,quarantine)).thenReturn(retainedQuarantine(quarantine,true));
        when(readiness.requireReady()).thenThrow(new FileEvidenceTrustUnavailableException());
        InputStream unread=new InputStream(){public int read(){throw new AssertionError("Durable retry must not read bytes");}};
        assertThatThrownBy(()->service.receive(finding,1,intent,"image/png",64,unread))
                .isInstanceOfSatisfying(FindingEvidenceReceiveService.AlreadyReceived.class,
                    failure->assertThat(failure.statusUrl()).endsWith("/evidence-uploads/"+intent));
        verify(access).requireCurrent(eq(owner),any(),eq(1),any(),any());
        verifyNoInteractions(readiness,producer);verify(files,never()).reserve(any(),any());
        verify(tx,never()).compareAndSet(any(),any(),any(),any());
    }
    @Test void incompleteRetryStillRequiresProducerReadinessBeforeAnyReplacementOrBodyRead(){
        UUID quarantine=UUID.randomUUID();retry(quarantine,"STORAGE_UNAVAILABLE");
        when(files.load(fileTx,quarantine)).thenReturn(retainedQuarantine(quarantine,false));
        when(readiness.requireReady()).thenThrow(new FileEvidenceTrustUnavailableException());
        InputStream unread=new InputStream(){public int read(){throw new AssertionError("Unavailable producer must not read bytes");}};
        assertThatThrownBy(()->service.receive(finding,1,intent,"image/png",64,unread))
                .isInstanceOf(FileEvidenceTrustUnavailableException.class);
        verify(readiness).requireReady();verify(files,never()).reserve(any(),any());
        verifyNoInteractions(producer);verify(tx,never()).compareAndSet(any(),any(),any(),any());
    }
    @Test void controlledUnusableSourceStillRequiresFreshProducerAdmissionWithoutReadingBody(){
        UUID quarantine=UUID.randomUUID();retry(quarantine,"QUARANTINE_NOT_REUSABLE");
        when(readiness.requireReady()).thenThrow(new FileEvidenceTrustUnavailableException());
        InputStream unread=new InputStream(){public int read(){throw new AssertionError("Unavailable replacement must not read bytes");}};
        assertThatThrownBy(()->service.receive(finding,1,intent,"image/png",64,unread))
                .isInstanceOf(FileEvidenceTrustUnavailableException.class);
        verify(readiness).requireReady();verifyNoInteractions(files,producer);
        verify(tx,never()).compareAndSet(any(),any(),any(),any());
    }
    @Test void anotherOwnerCannotProbeReceivedStateOrProducer(){
        when(sessions.currentTenant()).thenReturn(new CurrentTenantContext(owner.actorId(),owner.accountId(),tenant,UUID.randomUUID(),1,1,1));
        current.set(intent(Phase.QUARANTINED,UUID.randomUUID()));
        assertThatThrownBy(()->service.receive(finding,1,intent,"image/png",64,InputStream.nullInputStream())).isInstanceOf(FindingNotVisibleException.class);
        verifyNoInteractions(access,files,readiness,producer);
    }
    @Test void reservationsCommitBeforeExternalInputAndCheckpointRechecksAuthority(){
        resources();
        when(files.charges(fileTx,owner.memberId())).thenReturn(new PhysicalCharges(0,0,0,0));
        when(files.reserve(eq(fileTx),any())).thenAnswer(a->{var r=(AllocationRequest)a.getArgument(1);return new RecordedAllocation(r,0,null,null,r.reservedBytes(),null,null,null);});
        when(producer.receive(any(),any(),any())).thenAnswer(a->{
            assertThat(insideTransaction).isFalse();assertThat(current.get().phase()).isEqualTo(Phase.RECEIVING);
            var r=((ReceiveAllocation)a.getArgument(0)).quarantine().request();
            return new QuarantinedObject(tenant,intent,r.fileId(),r.allocationId(),r.attemptId(),
                    new ObjectIdentity(r.rootGeneration(),r.serverObjectName(),r.storageVersion(),"store","file-key",1,2,1,64,now),"a".repeat(64),"image/png",64,"b".repeat(64));
        });
        var result=service.receive(finding,1,intent,"image/png",64,InputStream.nullInputStream());
        assertThat(result.status().state()).isEqualTo(State.QUARANTINED);
        verify(files,times(4)).reserve(eq(fileTx),any());verify(files).recordQuarantine(eq(fileTx),any(),any());
        verify(access,times(3)).requireCurrent(eq(owner),any(),eq(1),any(),any());
        assertThat(current.get().attemptNo()).isZero();assertThat(current.get().technicalVersion()).isEqualTo(2);
    }
    @Test void physicalCapacityIncludesFinalizedAndNeverAllocatesOnDenial(){
        resources();when(files.charges(fileTx,owner.memberId())).thenReturn(new PhysicalCharges(0,0,1_073_741_824L,1_073_741_824L));
        assertThatThrownBy(()->service.receive(finding,1,intent,"image/png",64,InputStream.nullInputStream()))
                .isInstanceOfSatisfying(FindingEvidenceUploadException.class,f->assertThat(f.reason()).isEqualTo(FindingEvidenceUploadException.Reason.QUOTA_EXCEEDED));
        verify(files,never()).reserve(any(),any());verify(producer,never()).receive(any(),any(),any());
    }
    @Test void cancellationOnlyMarksRequestAndRetainsTechnicalPhase(){
        current.set(intent(Phase.SCANNING,UUID.randomUUID()));
        var status=service.cancel(finding,1,intent);
        assertThat(status.state()).isEqualTo(State.SCANNING);assertThat(status.cancelRequested()).isTrue();
        service.cancel(finding,1,intent);
        verify(tx).compareAndSet(any(),any(),eq(owner.actorId()),eq("policy"));verifyNoInteractions(files,producer,readiness);
    }
    @Test void emptyInitialCancellationIsTerminalWithoutProducerAndRepeatedCancelHasNoEffect(){
        var original=current.get();
        var result=service.cancel(finding,1,intent);
        assertThat(result.state()).isEqualTo(State.CANCELLED);assertThat(result.cancelRequested()).isTrue();
        assertThat(current.get().technicalVersion()).isEqualTo(1);
        assertThat(current.get().declaration()).isEqualTo(original.declaration());
        assertThat(current.get().leaseEpoch()).isZero();assertThat(current.get().attemptNo()).isZero();
        assertThat(current.get().lastErrorCode()).isEqualTo("CANCELLED");
        service.cancel(finding,1,intent);
        verify(tx).compareAndSet(any(),any(),eq(owner.actorId()),eq("policy"));
        verifyNoInteractions(files,producer,readiness,technical);
    }
    @Test void alreadyMarkedEmptyInitialIntentCompletesWithoutResettingVersion(){
        var i=current.get();current.set(new TechnicalIntent(i.tenantId(),i.declaration(),i.mediaType(),i.sizeBytes(),1,0,"config","policy",
                Phase.INTENT_CREATED,1,0,0,null,null,null,null,null,null,null,null,null,null,null,null,true,now,null));
        service.cancel(finding,1,intent);
        assertThat(current.get().phase()).isEqualTo(Phase.CANCELLED);assertThat(current.get().technicalVersion()).isEqualTo(2);
        verifyNoInteractions(files,producer,readiness,technical);
    }
    @Test void emptyInitialCancellationStillRequiresCurrentAuthority(){
        doThrow(new FindingNotVisibleException()).when(access).requireCurrent(eq(owner),any(),eq(1),any(),any());
        assertThatThrownBy(()->service.cancel(finding,1,intent)).isInstanceOf(FindingNotVisibleException.class);
        verify(tx,never()).compareAndSet(any(),any(),any(),any());verifyNoInteractions(files,producer,readiness,technical);
    }
    @Test void initialIntentWithAllocationReferenceCannotUseEmptyTerminalEdge(){
        current.set(intent(Phase.INTENT_CREATED,UUID.randomUUID()));
        assertThatThrownBy(()->service.cancel(finding,1,intent)).isInstanceOf(FindingEvidenceUploadException.class);
        verify(tx,never()).compareAndSet(any(),any(),any(),any());verifyNoInteractions(files,producer,readiness,technical);
    }
    @Test void exhaustedIntentCannotReserveAnotherFreshStream(){
        resources();var i=intent(Phase.RETRY_WAIT,null);
        current.set(new TechnicalIntent(i.tenantId(),i.declaration(),i.mediaType(),i.sizeBytes(),1,0,"config","policy",i.phase(),4,4,3,
                UUID.randomUUID(),UUID.randomUUID(),null,null,null,now,null,null,null,null,null,"STORAGE_UNAVAILABLE",false,now,null));
        assertThatThrownBy(()->service.receive(finding,1,intent,"image/png",64,InputStream.nullInputStream())).isInstanceOf(FindingEvidenceUploadException.class);
        verifyNoInteractions(files);verify(producer,never()).receive(any(),any(),any());
    }
    private TechnicalIntent intent(Phase phase,UUID quarantine){
        var d=new Intent(intent,owner.accountId(),owner.actorId(),owner.memberId(),finding,1,notice,set,"f".repeat(64),now.atOffset(ZoneOffset.UTC),now.plusSeconds(1800).atOffset(ZoneOffset.UTC));
        return new TechnicalIntent(tenant,d,"image/png",64,1,0,"config","policy",phase,0,phase.active()?1:0,0,
                phase.active()?UUID.randomUUID():null,null,phase.active()?UUID.randomUUID():null,phase.active()?now.plusSeconds(120):null,
                phase.active()?now:null,null,quarantine,null,null,null,null,null,false,now,null);
    }
    private void retry(UUID quarantine,String code){
        var i=intent(Phase.RETRY_WAIT,quarantine);
        current.set(new TechnicalIntent(i.tenantId(),i.declaration(),i.mediaType(),i.sizeBytes(),1,0,"config","policy",i.phase(),4,4,1,
                UUID.randomUUID(),UUID.randomUUID(),null,null,null,now,quarantine,null,null,null,null,code,false,now,null));
    }
    private RecordedAllocation retainedQuarantine(UUID id,boolean complete){
        var request=new AllocationRequest(tenant,intent,UUID.randomUUID(),owner.memberId(),UUID.randomUUID(),id,
                AllocationKind.QUARANTINE,"c".repeat(64),"retained-source","v1",64,1,now.minusSeconds(60));
        if(!complete)return new RecordedAllocation(request,0,null,null,64,null,null,null);
        var identity=new ObjectIdentity(request.rootGeneration(),request.serverObjectName(),request.storageVersion(),"store","key",1,2,1,64,now);
        var observation=new DurableObservation(request,ObservationKind.COMPLETE_QUARANTINE,identity,"a".repeat(64),64L,
                "image/png",false,null,false,null,new byte[]{1},"b".repeat(64));
        UUID event=UUID.randomUUID();return new RecordedAllocation(request,1,event,"b".repeat(64),64,observation,event,null,null,null);
    }
    private void resources(){
        var capability=new UploadProducerReadiness.Capability("config","policy","a".repeat(64),Set.of("image/png"),1024,20,8,8192,128,131072,Duration.ofMinutes(30),Duration.ofSeconds(60),2);
        var generation=new AcceptedGeneration(UUID.randomUUID(),"a".repeat(64),"b".repeat(64),new byte[]{1},now,now.plusSeconds(1800));
        var resources=new ProducerResources(generation,"c".repeat(64),UUID.randomUUID(),"d".repeat(64),65536,List.of(new ScanSlot("scan-0",134217728)));
        when(readiness.requireReady()).thenAnswer(a->{assertThat(insideTransaction).isFalse();return capability;});
        when(producer.currentResources()).thenAnswer(a->{assertThat(insideTransaction).isFalse();return resources;});
        when(readiness.isCurrent(capability)).thenReturn(true);when(producer.isCurrentGeneration(generation)).thenReturn(true);
    }
}
