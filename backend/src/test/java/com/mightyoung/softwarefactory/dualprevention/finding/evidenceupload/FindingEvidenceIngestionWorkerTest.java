package com.mightyoung.softwarefactory.dualprevention.finding.evidenceupload;

import com.mightyoung.softwarefactory.dualprevention.FindingNotVisibleException;
import com.mightyoung.softwarefactory.files.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import static com.mightyoung.softwarefactory.files.FileIngestionContracts.*;
import static com.mightyoung.softwarefactory.dualprevention.finding.evidenceupload.FindingEvidenceIngestionRepository.*;
import static com.mightyoung.softwarefactory.dualprevention.finding.evidenceupload.FindingEvidenceUploadContracts.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Port-seam evidence only. These tests do not exercise PostgreSQL, a kernel, a scanner, or real storage. */
class FindingEvidenceIngestionWorkerTest {
    private final UUID tenant=UUID.randomUUID(),intent=UUID.randomUUID(),actor=UUID.randomUUID();
    private final UUID sourceAttempt=UUID.randomUUID(),scanAttempt=UUID.randomUUID(),file=UUID.randomUUID(),qId=UUID.randomUUID(),executionId=UUID.randomUUID();
    private final Instant now=Instant.parse("2026-11-03T01:00:00Z");
    private final FindingEvidenceIngestionRepository repository=mock(FindingEvidenceIngestionRepository.class);
    private final Transaction tx=mock(Transaction.class);
    private final FindingEvidenceTechnicalEligibilityPolicy eligibility=mock(FindingEvidenceTechnicalEligibilityPolicy.class);
    private final FileIngestionLedger files=mock(FileIngestionLedger.class);
    private final FileIngestionTxContext fileTx=mock(FileIngestionTxContext.class);
    private final FileIngestionPort producer=mock(FileIngestionPort.class);
    private final UploadProducerReadiness readiness=mock(UploadProducerReadiness.class);
    private final AtomicReference<TechnicalIntent> current=new AtomicReference<>();
    private final AtomicBoolean inTx=new AtomicBoolean();
    private FindingEvidenceIngestionWorker worker;
    private RecordedAllocation quarantine,scratch,immutable,receipt;
    private ProducerResources resources;
    @BeforeEach @SuppressWarnings("unchecked") void setup(){
        var properties=new FindingEvidenceIngestionProperties(true,actor.toString(),tenant.toString(),1073741824,8589934592L,"");
        worker=new FindingEvidenceIngestionWorker(repository,eligibility,properties,new FindingEvidenceIngestionWakeListener(properties),files,producer,readiness);
        when(tx.current()).thenAnswer(a->current.get());when(tx.now()).thenReturn(now.atOffset(ZoneOffset.UTC));
        when(eligibility.requireService(tx)).thenReturn(actor);
        doAnswer(a->{assertThat(inTx.compareAndSet(false,true)).isTrue();try{return ((Function<Transaction,Object>)a.getArgument(2)).apply(tx);}finally{inTx.set(false);}})
            .when(repository).inIntentTransaction(eq(tenant),eq(intent),any());
        doAnswer(a->((Function<FileIngestionTxContext,Object>)a.getArgument(1)).apply(fileTx)).when(files).inCallerTransaction(eq(tenant),any());
        when(tx.compareAndSet(any(),any(),eq(actor),eq("policy"))).thenAnswer(a->{
            var i=(TechnicalIntent)a.getArgument(0);var c=(Change)a.getArgument(1);assertThat(i).isSameAs(current.get());
            var saved=new TechnicalIntent(tenant,i.declaration(),i.mediaType(),i.sizeBytes(),1,0,"config","policy",c.phase(),i.technicalVersion()+1,
                c.leaseEpoch(),c.attemptNo(),c.attemptId(),c.reservedScanAttemptId(),c.leaseToken(),c.leaseUntil(),c.heartbeatAt(),c.nextAttemptAt(),
                c.quarantineAllocationId(),c.immutableAllocationId(),c.scanProofId(),c.contentReceiptId(),c.scannerGeneration(),c.lastErrorCode(),c.cancelRequested(),now,c.currentExecutionId());
            current.set(saved);return saved;
        });
        current.set(state(Phase.QUARANTINED,0,2,1,sourceAttempt,null,null,null));
    }
    @Test void expiredLeaseIsFencedBeforeAnyRecoveryAndDoesNotReleaseCapacity(){
        current.set(state(Phase.SCANNING,1,4,2,scanAttempt,UUID.randomUUID(),now.minusSeconds(1),null));
        worker.process(tenant,intent);
        assertThat(current.get().phase()).isEqualTo(Phase.RETRY_WAIT);assertThat(current.get().leaseEpoch()).isEqualTo(3);
        assertThat(current.get().attemptNo()).isEqualTo(1);assertThat(current.get().lastErrorCode()).isEqualTo("LEASE_LOST");
        assertThat(current.get().nextAttemptAt()).isEqualTo(now.plusSeconds(5));verifyNoInteractions(producer,files,readiness);
    }
    @Test void markedEmptyInitialIntentCancelsEvenWhenAlsoExpiredWithoutExternalWork(){
        initial(true,now,1);var declaration=current.get().declaration();
        worker.process(tenant,intent);
        assertThat(current.get().phase()).isEqualTo(Phase.CANCELLED);assertThat(current.get().lastErrorCode()).isEqualTo("CANCELLED");
        assertThat(current.get().technicalVersion()).isEqualTo(2);assertThat(current.get().declaration()).isEqualTo(declaration);
        assertThat(current.get().attemptNo()).isZero();assertThat(current.get().leaseEpoch()).isZero();
        verify(eligibility).requireService(tx);verify(eligibility,never()).requireOwner(any());
        verifyNoInteractions(files,producer,readiness);
    }
    @Test void unmarkedExpiredEmptyInitialIntentExpiresWithItsHistoryAndFlagUnchanged(){
        initial(false,now,0);var declaration=current.get().declaration();
        worker.process(tenant,intent);
        assertThat(current.get().phase()).isEqualTo(Phase.EXPIRED);assertThat(current.get().lastErrorCode()).isEqualTo("EXPIRED");
        assertThat(current.get().technicalVersion()).isEqualTo(1);assertThat(current.get().declaration()).isEqualTo(declaration);
        assertThat(current.get().cancelRequested()).isFalse();assertThat(current.get().attemptNo()).isZero();assertThat(current.get().leaseEpoch()).isZero();
        verify(eligibility).requireService(tx);verify(eligibility,never()).requireOwner(any());
        verifyNoInteractions(files,producer,readiness);
    }
    @Test void freshEmptyInitialIntentIsNotChangedByStaleDiscoveryHint(){
        initial(false,now.plusSeconds(1),0);worker.process(tenant,intent);
        verify(tx,never()).compareAndSet(any(),any(),any(),any());verify(eligibility).requireService(tx);
        verify(eligibility,never()).requireOwner(any());verifyNoInteractions(files,producer,readiness);
    }
    @Test void emptyInitialWorkerStillRequiresApprovedServiceIdentity(){
        initial(true,now,1);when(eligibility.requireService(tx)).thenThrow(new FileEvidenceTrustUnavailableException());
        assertThatThrownBy(()->worker.process(tenant,intent)).isInstanceOf(FileEvidenceTrustUnavailableException.class);
        verify(tx,never()).compareAndSet(any(),any(),any(),any());verifyNoInteractions(files,producer,readiness);
    }
    @Test void thirdAttemptWaitsItsFinalObservationWindowWithoutFourthScan(){
        current.set(state(Phase.RETRY_WAIT,3,8,4,scanAttempt,null,null,now.plusSeconds(120)));
        worker.process(tenant,intent);verify(tx,never()).compareAndSet(any(),any(),any(),any());
        when(tx.now()).thenReturn(now.plusSeconds(120).atOffset(ZoneOffset.UTC));worker.process(tenant,intent);
        assertThat(current.get().phase()).isEqualTo(Phase.FAILED);assertThat(current.get().lastErrorCode()).isEqualTo("ATTEMPTS_EXHAUSTED");
        assertThat(current.get().attemptNo()).isEqualTo(3);verifyNoInteractions(producer,files,readiness);
    }
    @Test void heartbeatUsesFreshVersionButPreservesOriginalExecutionTuple(){
        var claim=state(Phase.SCANNING,1,3,2,scanAttempt,UUID.randomUUID(),now.plusSeconds(120),null);current.set(claim);
        worker.heartbeat(claim);worker.heartbeat(claim);
        assertThat(current.get().technicalVersion()).isEqualTo(5);assertThat(current.get().leaseToken()).isEqualTo(claim.leaseToken());
        assertThat(current.get().leaseEpoch()).isEqualTo(claim.leaseEpoch());assertThat(current.get().currentExecutionId()).isEqualTo(executionId);
        verify(eligibility,times(2)).requireOwner(tx);verifyNoInteractions(files,producer,readiness);
    }
    @Test void staleEpochCannotHeartbeatNewExecution(){
        var claim=state(Phase.SCANNING,1,3,2,scanAttempt,UUID.randomUUID(),now.plusSeconds(120),null);
        current.set(state(Phase.SCANNING,2,5,3,scanAttempt,claim.leaseToken(),now.plusSeconds(120),null));
        assertThatThrownBy(()->worker.heartbeat(claim)).isInstanceOf(FindingEvidenceUploadException.class);
        verify(tx,never()).compareAndSet(any(),any(),any(),any());
    }
    @Test void busyPreparationDoesNotConsumeAttemptOrWriteSql(){
        resources();when(producer.prepareSlot(any(),any())).thenAnswer(a->{assertThat(inTx).isFalse();throw new FileEvidenceTrustUnavailableException();});
        worker.process(tenant,intent);
        assertThat(current.get().attemptNo()).isZero();verify(files,never()).reserve(any(),any());verify(files,never()).recordExecution(any(),any());
        verify(tx,never()).compareAndSet(any(),any(),any(),any());verify(producer,never()).scan(any(),any(),any(),any(),any(),any(),any());
    }
    @Test void pendingPublicationReauthorizesAndPassesStableIdsInsideTheSameTransaction(){
        pending();resources();var verified=mock(VerifiedContentCheckpoint.class);
        when(files.publishVerified(eq(fileTx),any(),eq(resources.generation()))).thenAnswer(a->{assertThat(inTx).isTrue();return verified;});
        worker.process(tenant,intent);
        var request=ArgumentCaptor.forClass(IngestionPublicationRequest.class);
        var ordered=inOrder(files,tx);
        ordered.verify(files).publishVerified(eq(fileTx),request.capture(),eq(resources.generation()));
        UUID item=FindingEvidenceIngestionWorker.publicationId("item",tenant,intent),link=FindingEvidenceIngestionWorker.publicationId("link",tenant,intent);
        ordered.verify(tx).finalizeEvidence(current.get(),verified,item,link,actor);
        assertThat(request.getValue().evidenceItemId()).isEqualTo(item);assertThat(request.getValue().fileLinkId()).isEqualTo(link);
        assertThat(item).isNotEqualTo(link);verify(eligibility,times(2)).requireOwner(tx);
        verify(producer,never()).scan(any(),any(),any(),any(),any(),any(),any());
        verify(producer,never()).prepareSlot(any(),any());
    }
    @Test void ownerRevokedBetweenDiscoveryAndPublicationCannotPublishAnyMetadata(){
        pending();resources();doNothing().doThrow(new FindingNotVisibleException()).when(eligibility).requireOwner(tx);
        assertThatThrownBy(()->worker.process(tenant,intent)).isInstanceOf(FindingNotVisibleException.class);
        verify(files,never()).publishVerified(any(),any(),any());verify(tx,never()).finalizeEvidence(any(),any(),any(),any(),any());
    }
    @Test void staleEvidenceRevisionRejectsPendingPublicationBeforeFilesWrite(){
        pending();resources();var i=current.get();
        when(tx.evidenceSet(i.declaration().evidenceSetId())).thenReturn(Optional.of(new EvidenceSet(i.declaration().evidenceSetId(),
                i.declaration().findingId(),1,i.declaration().ownerMemberId(),1,false)));
        assertThatThrownBy(()->worker.process(tenant,intent)).isInstanceOf(FindingEvidenceUploadException.class);
        verify(files,never()).publishVerified(any(),any(),any());
    }
    @Test void staleCheckpointTrustSchedulesFreshVerificationWithoutPublishingBusinessEvidence(){
        pending();resources();when(files.publishVerified(eq(fileTx),any(),any())).thenThrow(new FileEvidenceTrustUnavailableException());
        worker.process(tenant,intent);
        assertThat(current.get().phase()).isEqualTo(Phase.RETRY_WAIT);assertThat(current.get().attemptNo()).isEqualTo(1);
        assertThat(current.get().nextAttemptAt()).isEqualTo(now.plusSeconds(5));assertThat(current.get().lastErrorCode()).isEqualTo("SCANNER_UNAVAILABLE");
        verify(tx,never()).finalizeEvidence(any(),any(),any(),any(),any());
    }
    @Test void finalizedIntentRecoversOnlyAllocationsThatWereNotTransferred(){
        var finalized=state(Phase.FINALIZED,1,5,2,scanAttempt,null,null,null);
        current.set(finalized);resources();
        for(var allocation:List.of(quarantine,scratch,immutable,receipt))
            assertThat(FindingEvidenceIngestionWorker.recoveryReason(finalized,allocation,Optional.empty())).isEqualTo(RecoveryReason.TERMINAL);
        var transferred=new RecordedAllocation(immutable.request(),immutable.eventNo(),immutable.eventId(),immutable.eventSha256(),
                immutable.retainedBytes(),immutable.baseline(),immutable.baselineEventId(),null,file,null);
        assertThat(FindingEvidenceIngestionWorker.recoveryReason(finalized,transferred,Optional.empty())).isNull();
    }
    @Test void lateBusinessPublicationFailureEscapesTheFilesTransactionCallback(){
        pending();resources();var verified=mock(VerifiedContentCheckpoint.class);
        when(files.publishVerified(eq(fileTx),any(),any())).thenReturn(verified);
        when(tx.finalizeEvidence(any(),eq(verified),any(),any(),any())).thenThrow(new IllegalStateException("late audit failure"));
        assertThatThrownBy(()->worker.process(tenant,intent)).isInstanceOf(IllegalStateException.class).hasMessage("late audit failure");
        verify(tx,never()).compareAndSet(any(),any(),any(),any());
    }
    @Test void schedulerBoundaryReportsSafeFinalizationCategoryAndCorrelationWithoutRawFailure(){
        pending();resources();
        when(files.publishVerified(eq(fileTx),any(),any())).thenThrow(new org.jooq.exception.DataAccessException("secret-token /private/scanner/raw-output"));
        var logger=(ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(FindingEvidenceIngestionWorker.class);
        var appender=new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();appender.start();logger.addAppender(appender);
        try{
            assertThatCode(()->worker.processSafely(tenant,intent)).doesNotThrowAnyException();
            assertThat(current.get().phase()).isEqualTo(Phase.VERIFIED_PENDING_FINALIZATION);
            assertThat(appender.list).anySatisfy(event->{
                assertThat(event.getFormattedMessage()).contains("stage=FINALIZE","category=PERSISTENCE",tenant.toString(),intent.toString());
                assertThat(event.getThrowableProxy()).isNull();
            });
            assertThat(appender.list).allSatisfy(event->assertThat(event.getFormattedMessage()).doesNotContain("secret-token","/private","raw-output"));
            verify(tx,never()).finalizeEvidence(any(),any(),any(),any(),any());
        }finally{logger.detachAppender(appender);appender.stop();}
    }
    private void pending(){
        var i=state(Phase.VERIFIED_PENDING_FINALIZATION,1,4,2,scanAttempt,null,null,null);
        current.set(new TechnicalIntent(tenant,i.declaration(),i.mediaType(),i.sizeBytes(),i.expectedFindingVersion(),i.expectedEvidenceRevision(),
                i.producerVersion(),i.policyVersion(),i.phase(),i.technicalVersion(),i.leaseEpoch(),i.attemptNo(),i.attemptId(),i.reservedScanAttemptId(),
                null,null,null,null,qId,UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"a".repeat(64),null,false,now,executionId));
    }
    @Test void failedOrUnknownClaimCommitNeverStartsScannerAndClosesOutsideTransaction(){
        resources();var lease=mock(PreparedSlotLease.class);when(lease.isValidFor(any())).thenReturn(true);
        when(producer.prepareSlot(any(),any())).thenAnswer(a->{assertThat(inTx).isFalse();return lease;});
        when(files.recordExecution(eq(fileTx),any())).thenAnswer(a->new RecordedExecution(a.getArgument(1)));
        when(tx.compareAndSet(any(),any(),any(),any())).thenThrow(new IllegalStateException("unknown commit acknowledgement"));
        doAnswer(a->{assertThat(inTx).isFalse();return null;}).when(lease).close();
        assertThatThrownBy(()->worker.process(tenant,intent)).isInstanceOf(IllegalStateException.class);
        verify(lease).close();verify(producer,never()).scan(any(),any(),any(),any(),any(),any(),any());
    }
    @ParameterizedTest @EnumSource(FileIngestionPort.RejectionReason.class)
    void contentFailurePersistsOnlyClosedSchemaCodesAndNoVerifiedCheckpoint(FileIngestionPort.RejectionReason reason){
        prepareClaim();
        when(producer.scan(any(),any(),any(),any(),any(),any(),any())).thenAnswer(a->{
            assertThat(inTx).isFalse();assertThat(current.get().phase()).isEqualTo(Phase.SCANNING);
            throw new FileIngestionPort.ContentRejected(reason);
        });
        worker.process(tenant,intent);
        assertFailureCheckpoint(Phase.REJECTED,reason==FileIngestionPort.RejectionReason.SCANNER_REJECTED?"SCAN_DETECTED":"MEDIA_REJECTED");
        verify(producer,never()).publishImmutable(any(),any(),any(),any(),any());
    }
    @Test void revokedOwnerAtFinalAuthorizationFailsContextWithoutPublishingOrDisposal(){
        prepareClaim();
        // Opaque mocked return objects only reach the current-authority check, never proof validation.
        var outcome=mock(ScanOutcome.class);var copied=mock(ImmutableObjectAndReceipt.class);
        when(producer.scan(any(),any(),any(),any(),any(),any(),any())).thenAnswer(a->{assertThat(inTx).isFalse();return outcome;});
        when(producer.publishImmutable(any(),eq(outcome),any(),any(),any())).thenAnswer(a->{assertThat(inTx).isFalse();return copied;});
        doAnswer(a->{if(current.get().phase()==Phase.SCANNING)throw new FindingNotVisibleException();return null;})
                .when(eligibility).requireOwner(tx);
        worker.process(tenant,intent);
        assertFailureCheckpoint(Phase.FAILED,"CONTEXT_CHANGED");
        verify(files,never()).executionForAttempt(any(),any(),any());
    }
    @Test void unavailableScannerKeepsRetryDelayAndAllReservedCharges(){
        prepareClaim();when(producer.scan(any(),any(),any(),any(),any(),any(),any()))
                .thenThrow(new FileEvidenceTrustUnavailableException());
        worker.process(tenant,intent);
        assertFailureCheckpoint(Phase.RETRY_WAIT,"SCANNER_UNAVAILABLE");
        assertThat(current.get().nextAttemptAt()).isEqualTo(now.plusSeconds(5));
        verify(producer,never()).publishImmutable(any(),any(),any(),any(),any());
    }
    @ParameterizedTest @EnumSource(value=Phase.class,names={"CANCELLED","EXPIRED"})
    void cancellationAndExpiryTakePriorityOverContentFailure(Phase expected){
        prepareClaim();
        var original=current.get();var d=original.declaration();
        var shorter=new Intent(d.id(),d.ownerAccountId(),d.ownerActorId(),d.ownerMemberId(),d.findingId(),d.roundNo(),d.noticeId(),
                d.evidenceSetId(),d.payloadSha256(),d.createdAt(),now.plusSeconds(1).atOffset(ZoneOffset.UTC));
        current.set(copyState(original,shorter,original.technicalVersion(),false));
        when(producer.scan(any(),any(),any(),any(),any(),any(),any())).thenAnswer(a->{
            assertThat(inTx).isFalse();when(tx.now()).thenReturn(now.plusSeconds(1).atOffset(ZoneOffset.UTC));
            if(expected==Phase.CANCELLED){var i=current.get();current.set(copyState(i,i.declaration(),i.technicalVersion()+1,true));}
            throw new FileIngestionPort.ContentRejected(FileIngestionPort.RejectionReason.SCANNER_REJECTED);
        });
        worker.process(tenant,intent);
        assertFailureCheckpoint(expected,expected.name());
        verify(producer,never()).publishImmutable(any(),any(),any(),any(),any());
    }
    @Test void scannerAndCopyRunAfterClaimAndOnlyFreshVerifiedCheckpointPublishesTechnicalState(){
        resources();var lease=mock(PreparedSlotLease.class);when(lease.isValidFor(any())).thenReturn(true);
        when(producer.prepareSlot(any(),any())).thenReturn(lease);
        when(files.recordExecution(eq(fileTx),any())).thenAnswer(a->new RecordedExecution(a.getArgument(1)));
        when(files.executionForAttempt(eq(fileTx),eq(intent),eq(scanAttempt))).thenAnswer(a->{
            var descriptor=ExecutionDescriptor.forClaim(new ScanPlan(tenant,intent,file,sourceAttempt,scanAttempt,qId,scratch.request().allocationId(),
                current.get().currentExecutionId(),resources.physicalResourceRealmId(),resources.profileSha256(),"scan-0",2,2,"a".repeat(64),"b".repeat(64)),current.get().fence(),now);
            return Optional.of(new RecordedExecution(descriptor));
        });
        var outcome=mock(ScanOutcome.class);var verified=mock(ImmutableObjectAndReceipt.class);
        when(producer.scan(any(),any(),any(),any(),any(),eq(lease),any())).thenAnswer(a->{
            assertThat(inTx).isFalse();assertThat(current.get().phase()).isEqualTo(Phase.SCANNING);assertThat(current.get().attemptNo()).isEqualTo(1);return outcome;
        });
        when(producer.publishImmutable(any(),eq(outcome),any(),any(),any())).thenAnswer(a->{assertThat(inTx).isFalse();return verified;});
        var checkpoint=new VerifiedCheckpoint(UUID.randomUUID(),UUID.randomUUID(),immutable.request().allocationId());
        when(files.recordVerified(eq(fileTx),any(),any(),eq(verified),any(),eq(outcome))).thenReturn(checkpoint);
        doAnswer(a->{assertThat(inTx).isFalse();return null;}).when(lease).close();
        worker.process(tenant,intent);
        assertThat(current.get().phase()).isEqualTo(Phase.VERIFIED_PENDING_FINALIZATION);assertThat(current.get().scanProofId()).isEqualTo(checkpoint.proofId());
        verify(files,never()).reserve(any(),any());verify(lease).close();
    }
    @Test void currentCompleteQuarantineCannotBeDisposedToMakeRetryQuota(){
        resources();var i=state(Phase.RETRY_WAIT,1,4,4,scanAttempt,null,null,now);
        assertThat(FindingEvidenceIngestionWorker.recoveryReason(i,quarantine,Optional.empty())).isNull();
    }
    @Test void futureOrNullBackoffNeverAdmitsScan(){
        assertThat(FindingEvidenceIngestionWorker.scanDue(state(Phase.RETRY_WAIT,1,4,2,scanAttempt,null,null,null),now)).isFalse();
        assertThat(FindingEvidenceIngestionWorker.scanDue(state(Phase.RETRY_WAIT,1,4,2,scanAttempt,null,null,now.plusSeconds(1)),now)).isFalse();
    }
    @Test void unknownRetainedAllocationDoesNotStarveLaterPagesOrReleaseCharges(){
        var properties=new FindingEvidenceIngestionProperties(true,actor.toString(),tenant.toString(),1073741824,8589934592L,"e".repeat(64));
        worker=new FindingEvidenceIngestionWorker(repository,eligibility,properties,new FindingEvidenceIngestionWakeListener(properties),files,producer,readiness);
        current.set(state(Phase.FAILED,1,4,2,scanAttempt,null,null,null));
        UUID firstId=UUID.fromString("00000000-0000-0000-0000-000000000001"),secondId=UUID.fromString("00000000-0000-0000-0000-000000000002");
        var first=allocation(AllocationKind.QUARANTINE,sourceAttempt,64,firstId);
        var second=allocation(AllocationKind.IMMUTABLE,scanAttempt,64,secondId);
        when(files.retainedForIntent(eq(fileTx),eq(intent),isNull(),eq(32)))
                .thenReturn(new RetainedAllocationPage(List.of(first),firstId));
        when(files.retainedForIntent(fileTx,intent,firstId,32))
                .thenReturn(new RetainedAllocationPage(List.of(second),null));
        when(files.load(fileTx,firstId)).thenReturn(first);when(files.load(fileTx,secondId)).thenReturn(second);
        when(files.executionForAttempt(eq(fileTx),eq(intent),any())).thenReturn(Optional.empty());
        when(files.currentDisposalClaim(eq(fileTx),any())).thenReturn(Optional.empty());
        when(producer.observeRecovery(any(),any(),any(),any())).thenAnswer(a->{
            assertThat(inTx).isFalse();var retained=(RecordedAllocation)a.getArgument(0);
            assertThat(retained.retainedBytes()).isEqualTo(64);
            return new RecoveryObservation(a.getArgument(2),retained.request(),RecoveryPresence.UNKNOWN,ProcessState.UNKNOWN,
                    false,null,null,null,null,null,null,null,new byte[]{1},"b".repeat(64),now);
        });
        worker.process(tenant,intent);worker.process(tenant,intent);worker.process(tenant,intent);
        verify(files,times(2)).retainedForIntent(fileTx,intent,null,32);
        verify(files).retainedForIntent(fileTx,intent,firstId,32);
        verify(producer,times(2)).observeRecovery(eq(first),any(),any(),any());
        verify(producer).observeRecovery(eq(second),any(),any(),any());
        verify(files,never()).recordRecoveryObservation(any(),any(),any());
        verify(files,never()).claimDisposal(any(),any(),any(),any());
        verify(files,never()).confirmDisposal(any(),any(),any(),anyLong());
        verify(tx,never()).compareAndSet(any(),any(),any(),any());verifyNoInteractions(readiness);
    }
    @Test void terminalNeverCreatedFileUsesTruthfulAbsenceTombstoneAndFreshPostClaimBinding(){
        var properties=new FindingEvidenceIngestionProperties(true,actor.toString(),tenant.toString(),1073741824,8589934592L,"e".repeat(64));
        worker=new FindingEvidenceIngestionWorker(repository,eligibility,properties,new FindingEvidenceIngestionWakeListener(properties),files,producer,readiness);
        current.set(state(Phase.FAILED,1,4,2,scanAttempt,null,null,null));
        UUID allocationId=UUID.randomUUID();var request=new AllocationRequest(tenant,intent,file,current.get().declaration().ownerMemberId(),sourceAttempt,
            allocationId,AllocationKind.QUARANTINE,"c".repeat(64),"never-created","v1",64,1,now.minusSeconds(7200));
        var allocation=new AtomicReference<>(new RecordedAllocation(request,0,null,null,64,null,null,null));
        var claim=new AtomicReference<DisposalClaim>();
        when(files.retainedForIntent(eq(fileTx),eq(intent),isNull(),eq(32))).thenAnswer(a->new RetainedAllocationPage(List.of(allocation.get()),null));
        when(files.load(fileTx,allocationId)).thenAnswer(a->allocation.get());
        when(files.executionForAttempt(fileTx,intent,sourceAttempt)).thenReturn(Optional.empty());
        when(files.currentDisposalClaim(fileTx,allocationId)).thenAnswer(a->Optional.ofNullable(claim.get()));
        when(producer.observeRecovery(any(),any(),any(),any())).thenAnswer(a->{
            assertThat(inTx).isFalse();assertThat(allocation.get().retainedBytes()).isEqualTo(64);
            return absent(a.getArgument(2),request);
        });
        when(files.recordRecoveryObservation(eq(fileTx),any(),any())).thenAnswer(a->{
            assertThat(inTx).isTrue();var observed=(RecoveryObservation)a.getArgument(2);assertThat(observed.identity()).isNull();
            var saved=new RecordedAllocation(request,1,UUID.randomUUID(),"b".repeat(64),64,null,null,null,null,observed);allocation.set(saved);return saved;
        });
        when(files.claimDisposal(eq(fileTx),any(),eq(allocationId),anyString())).thenAnswer(a->{
            var eligibility=(DisposalEligibility)a.getArgument(1);UUID tombstone=UUID.randomUUID();
            var saved=new RecordedAllocation(request,2,tombstone,"b".repeat(64),64,null,null,tombstone,null,allocation.get().lastRecoveryObservation());
            allocation.set(saved);var original=new DisposalClaim(saved,tombstone,UUID.randomUUID(),1,eligibility.technicalVersion(),"e".repeat(64));claim.set(original);return original;
        });
        when(producer.dispose(any(),any(),any())).thenAnswer(a->{
            assertThat(inTx).isFalse();var original=(DisposalClaim)a.getArgument(0);var fresh=(RecoveryBinding)a.getArgument(1);
            assertThat(fresh.expectedCurrentTechnicalVersion()).isEqualTo(original.technicalVersion()+1);
            assertThat(fresh.targetEventNo()).isEqualTo(2);assertThat(original.allocation().lastRecoveryObservation().binding().targetEventNo()).isZero();
            assertThat(allocation.get().retainedBytes()).isEqualTo(64);return new DisposalObservation(original,absent(fresh,request));
        });
        when(files.confirmDisposal(eq(fileTx),any(),any(),anyLong())).thenAnswer(a->{
            assertThat(inTx).isTrue();var original=(DisposalClaim)a.getArgument(1);var absence=(DisposalObservation)a.getArgument(2);
            assertThat(original.token()).isEqualTo(claim.get().token());assertThat((Long)a.getArgument(3)).isEqualTo(original.technicalVersion()+1);
            assertThat(absence.observation().presence()).isEqualTo(RecoveryPresence.ABSENT);assertThat(absence.observation().identity()).isNull();
            return new RecordedAllocation(request,3,UUID.randomUUID(),"b".repeat(64),0,null,null,original.tombstoneId(),null,absence.observation());
        });
        worker.process(tenant,intent);
        assertThat(current.get().phase()).isEqualTo(Phase.FAILED);assertThat(current.get().technicalVersion()).isEqualTo(6);
        verify(files).confirmDisposal(eq(fileTx),any(),any(),eq(5L));verifyNoInteractions(readiness);
        verify(producer,never()).currentResources();verify(producer,never()).scan(any(),any(),any(),any(),any(),any(),any());
    }
    private RecoveryObservation absent(RecoveryBinding binding,AllocationRequest request){
        return new RecoveryObservation(binding,request,RecoveryPresence.ABSENT,ProcessState.NONE_STARTED,true,"a".repeat(64),now,
            null,null,null,null,"d".repeat(64),new byte[]{1},"b".repeat(64),now);
    }
    private void prepareClaim(){
        resources();var lease=mock(PreparedSlotLease.class);when(lease.isValidFor(any())).thenReturn(true);
        when(producer.prepareSlot(any(),any())).thenAnswer(a->{assertThat(inTx).isFalse();return lease;});
        when(files.recordExecution(eq(fileTx),any())).thenAnswer(a->new RecordedExecution(a.getArgument(1)));
        doAnswer(a->{assertThat(inTx).isFalse();return null;}).when(lease).close();
    }
    private void assertFailureCheckpoint(Phase phase,String code){
        var changes=ArgumentCaptor.forClass(Change.class);
        verify(tx,times(2)).compareAndSet(any(),changes.capture(),eq(actor),eq("policy"));
        assertThat(changes.getAllValues().getFirst().phase()).isEqualTo(Phase.SCANNING);
        var persisted=changes.getValue();assertThat(persisted.phase()).isEqualTo(phase);assertThat(persisted.lastErrorCode()).isEqualTo(code);
        assertThat(current.get().phase()).isEqualTo(phase);assertThat(current.get().lastErrorCode()).isEqualTo(code);
        assertThat(current.get().attemptNo()).isEqualTo(1);assertThat(current.get().leaseEpoch()).isEqualTo(2);
        assertThat(current.get().leaseToken()).isNull();assertThat(current.get().leaseUntil()).isNull();
        if(phase!=Phase.RETRY_WAIT)assertThat(current.get().nextAttemptAt()).isNull();
        assertThat(quarantine.retainedBytes()).isEqualTo(64);assertThat(scratch.retainedBytes()).isEqualTo(134217728);
        assertThat(immutable.retainedBytes()).isEqualTo(64);assertThat(receipt.retainedBytes()).isEqualTo(65536);
        verify(files,never()).recordVerified(any(),any(),any(),any(),any(),any());
        verify(files,never()).claimDisposal(any(),any(),any(),any());verify(files,never()).confirmDisposal(any(),any(),any(),anyLong());
        verify(producer,never()).dispose(any(),any(),any());
    }
    private TechnicalIntent copyState(TechnicalIntent i,Intent declaration,long version,boolean cancel){
        return new TechnicalIntent(i.tenantId(),declaration,i.mediaType(),i.sizeBytes(),i.expectedFindingVersion(),i.expectedEvidenceRevision(),
                i.producerVersion(),i.policyVersion(),i.phase(),version,i.leaseEpoch(),i.attemptNo(),i.attemptId(),i.reservedScanAttemptId(),
                i.leaseToken(),i.leaseUntil(),i.heartbeatAt(),i.nextAttemptAt(),i.quarantineAllocationId(),i.immutableAllocationId(),i.scanProofId(),
                i.contentReceiptId(),i.scannerGeneration(),i.lastErrorCode(),cancel,i.updatedAt(),i.currentExecutionId());
    }
    private void initial(boolean cancel,Instant expiry,long version){
        var properties=new FindingEvidenceIngestionProperties(true,actor.toString(),tenant.toString(),1073741824,8589934592L,"e".repeat(64));
        worker=new FindingEvidenceIngestionWorker(repository,eligibility,properties,new FindingEvidenceIngestionWakeListener(properties),files,producer,readiness);
        var d=current.get().declaration();
        var declaration=new Intent(d.id(),d.ownerAccountId(),d.ownerActorId(),d.ownerMemberId(),d.findingId(),d.roundNo(),d.noticeId(),
                d.evidenceSetId(),d.payloadSha256(),now.minusSeconds(1800).atOffset(ZoneOffset.UTC),expiry.atOffset(ZoneOffset.UTC));
        current.set(new TechnicalIntent(tenant,declaration,"image/png",64,1,0,"config","policy",Phase.INTENT_CREATED,version,0,0,
                null,null,null,null,null,null,null,null,null,null,null,null,cancel,now,null));
    }
    private TechnicalIntent state(Phase phase,int n,long version,long epoch,UUID attempt,UUID token,Instant until,Instant next){
        var d=new Intent(intent,UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),1,UUID.randomUUID(),UUID.randomUUID(),"f".repeat(64),now.atOffset(ZoneOffset.UTC),now.plusSeconds(1800).atOffset(ZoneOffset.UTC));
        return new TechnicalIntent(tenant,d,"image/png",64,1,0,"config","policy",phase,version,epoch,n,attempt,scanAttempt,token,until,
            phase.active()?now.minusSeconds(15):null,next,qId,null,null,null,null,null,false,now,phase==Phase.SCANNING?executionId:null);
    }
    private RecordedAllocation allocation(AllocationKind kind,UUID attempt,long size,UUID id){
        var request=new AllocationRequest(tenant,intent,file,current.get().declaration().ownerMemberId(),attempt,id,kind,"c".repeat(64),"object-"+id,"v1",size,1,now);
        return new RecordedAllocation(request,0,null,null,size,null,null,null);
    }
    private void resources(){
        var generation=new AcceptedGeneration(UUID.randomUUID(),"a".repeat(64),"d".repeat(64),new byte[]{1},now,now.plusSeconds(1800));
        resources=new ProducerResources(generation,"c".repeat(64),UUID.randomUUID(),"e".repeat(64),65536,List.of(new ScanSlot("scan-0",134217728)));
        var capability=new UploadProducerReadiness.Capability("config","policy","scanner",Set.of("image/png"),1024,20,8,8192,128,131072,Duration.ofMinutes(30),Duration.ofSeconds(60),2);
        when(readiness.requireReady()).thenAnswer(a->{assertThat(inTx).isFalse();return capability;});
        when(producer.currentResources()).thenAnswer(a->{assertThat(inTx).isFalse();return resources;});
        when(readiness.isCurrent(capability)).thenReturn(true);when(producer.isCurrentGeneration(generation)).thenReturn(true);
        var i=current.get();when(tx.finding(i.declaration().findingId())).thenReturn(Optional.of(new Finding(i.declaration().findingId(),UUID.randomUUID(),"RECTIFYING",1,1)));
        when(tx.evidenceSet(i.declaration().evidenceSetId())).thenReturn(Optional.of(new EvidenceSet(i.declaration().evidenceSetId(),i.declaration().findingId(),1,i.declaration().ownerMemberId(),0,false)));
        var q=allocation(AllocationKind.QUARANTINE,sourceAttempt,64,qId);var request=q.request();
        var identity=new ObjectIdentity(request.rootGeneration(),request.serverObjectName(),request.storageVersion(),"store","key",1,2,1,64,now);
        var observation=new DurableObservation(request,ObservationKind.COMPLETE_QUARANTINE,identity,"a".repeat(64),64L,"image/png",false,null,false,null,new byte[]{1},"b".repeat(64));
        UUID event=UUID.randomUUID();quarantine=new RecordedAllocation(request,1,event,"b".repeat(64),64,observation,event,null,null,null);
        scratch=allocation(AllocationKind.SCANNER_SCRATCH,scanAttempt,134217728,UUID.randomUUID());
        immutable=allocation(AllocationKind.IMMUTABLE,scanAttempt,64,UUID.randomUUID());receipt=allocation(AllocationKind.RECEIPT,scanAttempt,65536,UUID.randomUUID());
        when(files.load(fileTx,qId)).thenReturn(quarantine);
        when(files.loadForAttempt(fileTx,intent,scanAttempt,AllocationKind.SCANNER_SCRATCH)).thenReturn(scratch);
        when(files.loadForAttempt(fileTx,intent,scanAttempt,AllocationKind.IMMUTABLE)).thenReturn(immutable);
        when(files.loadForAttempt(fileTx,intent,scanAttempt,AllocationKind.RECEIPT)).thenReturn(receipt);
    }
}
