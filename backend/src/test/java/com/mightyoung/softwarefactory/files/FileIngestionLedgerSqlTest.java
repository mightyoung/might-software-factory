package com.mightyoung.softwarefactory.files;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.jooq.*;
import org.jooq.impl.DSL;
import org.jooq.tools.jdbc.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static com.mightyoung.softwarefactory.files.FileIngestionContracts.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** Real jOOQ/JDBC mapping over a scripted connection; does not execute PG triggers or prove DB rollback. */
class FileIngestionLedgerSqlTest {
    final IngestionProofCodecTest values=new IngestionProofCodecTest();
    Harness active;
    Harness harness(){active=new Harness();active.bind();return active;}
    @AfterEach void cleanup(){if(active!=null)active.close();}

    @Test void reservationsReplayOnlyExactImmutableFieldsAndWrongPayloadMarksRollbackOnly(){
        var h=harness();var request=values.allocation(AllocationKind.QUARANTINE,values.sourceAttempt,"quarantine");
        var first=h.ledger.inCallerTransaction(values.tenant,tx->h.ledger.reserve(tx,request));
        assertThat(first.retainedBytes()).isEqualTo(request.reservedBytes());assertThat(first.eventNo()).isZero();
        assertThat(h.ledger.inCallerTransaction(values.tenant,tx->h.ledger.reserve(tx,request)).request()).isEqualTo(request);
        var changed=new AllocationRequest(request.tenantId(),request.intentId(),request.fileId(),request.ownerMemberId(),request.attemptId(),request.allocationId(),
                request.kind(),request.rootGeneration(),request.serverObjectName(),request.storageVersion(),request.reservedBytes()+1,request.reservationLeaseEpoch(),request.createdAt());
        assertThatThrownBy(()->h.ledger.inCallerTransaction(values.tenant,tx->h.ledger.reserve(tx,changed))).isInstanceOf(FileEvidenceTrustUnavailableException.class);
        assertThat(h.holder.isRollbackOnly()).isTrue();assertThat(h.tables.get("file_ingestion_allocation")).hasSize(1);
    }
    @Test void exactReservationReplayUsesOnlySelectWhileNewInsertsAreRejected(){
        var h=harness();var request=values.allocation(AllocationKind.QUARANTINE,values.sourceAttempt,"quarantine");
        var original=h.ledger.inCallerTransaction(values.tenant,tx->h.ledger.reserve(tx,request));
        h.failInsert="file_ingestion_allocation";int before=h.sql.size();
        // A synthetic INSERT rejection represents the admission boundary; actual terminal SQL is a separate PG gate.
        var replay=h.ledger.inCallerTransaction(values.tenant,tx->h.ledger.reserve(tx,request));
        assertThat(replay.request()).isEqualTo(original.request());assertThat(replay.retainedBytes()).isEqualTo(original.retainedBytes());
        assertThat(h.sql.subList(before,h.sql.size())).allMatch(q->q.startsWith("select "));
        assertThat(h.holder.isRollbackOnly()).isFalse();assertThat(h.events()).isEmpty();
        var next=values.allocation(AllocationKind.QUARANTINE,UUID.randomUUID(),"new-after-admission-closed");
        assertThatThrownBy(()->h.ledger.inCallerTransaction(values.tenant,tx->h.ledger.reserve(tx,next)))
                .isInstanceOf(org.jooq.exception.DataAccessException.class);
        assertThat(h.holder.isRollbackOnly()).isTrue();assertThat(h.tables.get("file_ingestion_allocation")).hasSize(1);
    }
    @Test void mismatchedExistingReservationRejectsBeforeAnyInsertEvenWhenAdmissionWouldReject(){
        var h=harness();var request=values.allocation(AllocationKind.QUARANTINE,values.sourceAttempt,"quarantine");
        h.ledger.inCallerTransaction(values.tenant,tx->h.ledger.reserve(tx,request));
        h.failInsert="file_ingestion_allocation";int before=h.sql.size();
        var changed=new AllocationRequest(request.tenantId(),request.intentId(),request.fileId(),request.ownerMemberId(),request.attemptId(),request.allocationId(),
                request.kind(),request.rootGeneration(),request.serverObjectName(),request.storageVersion(),request.reservedBytes()+1,request.reservationLeaseEpoch(),request.createdAt());
        assertThatThrownBy(()->h.ledger.inCallerTransaction(values.tenant,tx->h.ledger.reserve(tx,changed)))
                .isInstanceOf(FileEvidenceTrustUnavailableException.class);
        assertThat(h.sql.subList(before,h.sql.size())).allMatch(q->q.startsWith("select "));
        assertThat(h.holder.isRollbackOnly()).isTrue();assertThat(h.tables.get("file_ingestion_allocation")).hasSize(1);assertThat(h.events()).isEmpty();
    }
    @Test void attemptLookupUsesExactTenantIntentAttemptAndKindWithoutBusinessSql(){
        var h=harness();var request=values.allocation(AllocationKind.QUARANTINE,values.sourceAttempt,"quarantine");
        h.ledger.inCallerTransaction(values.tenant,tx->{h.ledger.reserve(tx,request);
            assertThat(h.ledger.loadForAttempt(tx,values.intent,values.sourceAttempt,AllocationKind.QUARANTINE).request()).isEqualTo(request);return null;});
        assertThat(h.sql).anyMatch(q->q.contains("where tenant_id=? and intent_id=? and attempt_id=? and kind=?"));
        assertThat(h.sql).noneMatch(q->q.contains("public.finding")||q.contains("public.evidence_set")||q.contains("public.account"));
    }
    @Test void privateCodecBuildsQuarantineBaselineAndReloadRejectsTypedPayloadMismatch(){
        var h=harness();var request=values.allocation(AllocationKind.QUARANTINE,values.sourceAttempt,"quarantine");
        var source=values.quarantine();
        h.ledger.inCallerTransaction(values.tenant,tx->{h.ledger.reserve(tx,request);var saved=h.ledger.recordQuarantine(tx,fence(),source);
            assertThat(saved.baseline().observationSha256()).isEqualTo(source.observationSha256());
            assertThat(saved.retainedBytes()).isEqualTo(request.reservedBytes());return null;});
        h.events().getFirst().put("observed_sha256","e".repeat(64));
        assertThatThrownBy(()->h.ledger.inCallerTransaction(values.tenant,tx->h.ledger.load(tx,request.allocationId())))
                .isInstanceOf(FileEvidenceTrustUnavailableException.class);
        assertThat(h.holder.isRollbackOnly()).isTrue();
    }
    @Test void unknownPersistedEnumFailsUnavailableWithoutTreatingServerCorruptionAsClientInput(){
        var h=harness();var request=values.allocation(AllocationKind.QUARANTINE,values.sourceAttempt,"quarantine");
        h.ledger.inCallerTransaction(values.tenant,tx->h.ledger.reserve(tx,request));
        h.tables.get("file_ingestion_allocation").getFirst().put("kind","FUTURE_UNKNOWN_KIND");
        assertThatThrownBy(()->h.ledger.inCallerTransaction(values.tenant,tx->h.ledger.load(tx,request.allocationId())))
                .isInstanceOf(FileEvidenceTrustUnavailableException.class);
    }
    RecoveryObservation recovery(RecordedAllocation a,long version,RecoveryPresence presence){
        var binding=new RecoveryBinding(values.tenant,values.intent,a.request().allocationId(),UUID.randomUUID(),version,3,a.eventNo(),a.eventSha256(),
                null,null,RecoveryReason.TERMINAL,"f".repeat(64));
        return values.codec.observeRecovery(binding,a.request(),presence,ProcessState.NONE_STARTED,true,"a".repeat(64),IngestionProofCodecTest.NOW,
                null,presence==RecoveryPresence.ABSENT?null:a.baseline()!=null?a.baseline().identity():values.identity(a.request(),2),
                a.baseline()==null?null:harnessBaselineId(a),a.baseline()==null?null:a.baseline().observationSha256(),"b".repeat(64),IngestionProofCodecTest.NOW);
    }
    UUID harnessBaselineId(RecordedAllocation a){return active.events().stream().filter(r->r.get("allocation_id").equals(a.request().allocationId())
            &&r.get("event_kind").equals("RETAIN")).findFirst().map(r->(UUID)r.get("id")).orElse(null);}
    RecordedAllocation checkpointRecovery(Harness h,RecordedAllocation allocation,long version,RecoveryPresence presence){
        var observed=recovery(allocation,version,presence);
        return h.ledger.inCallerTransaction(values.tenant,tx->h.ledger.recordRecoveryObservation(tx,observed.binding(),observed));
    }
    @Test void recoveryEventZeroAbsentAndPartialKeepFullChargeWithoutInventingCompleteBaseline(){
        var h=harness();
        for(var presence:new RecoveryPresence[]{RecoveryPresence.ABSENT,RecoveryPresence.PRESENT_UNTRUSTED_PARTIAL}){
            var request=values.allocation(AllocationKind.QUARANTINE,UUID.randomUUID(),"recovery-"+presence.name());
            var allocation=h.ledger.inCallerTransaction(values.tenant,tx->h.ledger.reserve(tx,request));
            var observed=checkpointRecovery(h,allocation,1,presence);
            assertThat(observed.eventNo()).isEqualTo(1);assertThat(observed.baseline()).isNull();
            assertThat(observed.retainedBytes()).isEqualTo(request.reservedBytes());assertThat(observed.lastRecoveryObservation().presence()).isEqualTo(presence);
            if(presence==RecoveryPresence.ABSENT)assertThat(observed.lastRecoveryObservation().identity()).isNull();
        }
    }
    @Test void recoveryRejectsStaleTargetAndTypedProjectionTamperingWithoutLosingCharges() throws Exception {
        var h=harness();var request=values.allocation(AllocationKind.QUARANTINE,values.sourceAttempt,"quarantine");
        var allocation=h.ledger.inCallerTransaction(values.tenant,tx->h.ledger.reserve(tx,request));
        var first=checkpointRecovery(h,allocation,1,RecoveryPresence.ABSENT);
        var stale=recovery(allocation,1,RecoveryPresence.ABSENT);
        assertThatThrownBy(()->h.ledger.inCallerTransaction(values.tenant,tx->h.ledger.recordRecoveryObservation(tx,stale.binding(),stale)))
                .isInstanceOf(FileEvidenceTrustUnavailableException.class);
        assertThat(first.retainedBytes()).isEqualTo(request.reservedBytes());
        h.beginFreshScriptedTransactionAfterRejection();
        h.events().getFirst().put("writer_quiescence_sha256","e".repeat(64));
        assertThatThrownBy(()->h.ledger.inCallerTransaction(values.tenant,tx->h.ledger.load(tx,request.allocationId())))
                .isInstanceOf(FileEvidenceTrustUnavailableException.class);
    }
    @Test void disposalConfirmationUsesFreshCheckpointVersionWithoutRebindingOriginalClaim(){
        var h=harness();var request=values.allocation(AllocationKind.QUARANTINE,values.sourceAttempt,"quarantine");
        var allocation=h.ledger.inCallerTransaction(values.tenant,tx->{h.ledger.reserve(tx,request);return h.ledger.recordQuarantine(tx,fence(),values.quarantine());});
        var observed=checkpointRecovery(h,allocation,1,RecoveryPresence.PRESENT_COMPLETE_UNCHANGED);
        var eligibility=new DisposalEligibility(values.tenant,values.intent,values.sourceAttempt,1,true,"a".repeat(64),IngestionProofCodecTest.NOW.minusSeconds(1));
        var claim=h.ledger.inCallerTransaction(values.tenant,tx->h.ledger.claimDisposal(tx,eligibility,request.allocationId(),observed.lastRecoveryObservation().observationSha256()));
        var absence=recovery(claim.allocation(),2,RecoveryPresence.ABSENT);
        var result=h.ledger.inCallerTransaction(values.tenant,tx->h.ledger.confirmDisposal(tx,claim,values.codec.observeDisposal(claim,absence),2));
        assertThat(claim.technicalVersion()).isEqualTo(1);assertThat(result.disposalTombstoneId()).isEqualTo(claim.tombstoneId());
        assertThat(result.retainedBytes()).isZero();assertThat(result.baseline().observationSha256()).isEqualTo(values.quarantine().observationSha256());
        assertThat(h.events().getLast().get("technical_version")).isEqualTo(2L);
        assertThat(h.events().getLast().get("disposal_claim_technical_version")).isEqualTo(1L);
        assertThatThrownBy(()->h.ledger.inCallerTransaction(values.tenant,tx->h.ledger.recordQuarantine(tx,fence(),values.quarantine())))
                .isInstanceOf(FileEvidenceTrustUnavailableException.class);
    }
    @Test void absentEventZeroCanDisposeOnlyAfterTombstoneAndExactFreshConfirmation(){
        var h=harness();var request=values.allocation(AllocationKind.QUARANTINE,values.sourceAttempt,"never-created");
        var allocation=h.ledger.inCallerTransaction(values.tenant,tx->h.ledger.reserve(tx,request));
        var observed=checkpointRecovery(h,allocation,1,RecoveryPresence.ABSENT);
        var eligibility=new DisposalEligibility(values.tenant,values.intent,values.sourceAttempt,1,true,"a".repeat(64),IngestionProofCodecTest.NOW.minusSeconds(1));
        var claim=h.ledger.inCallerTransaction(values.tenant,tx->h.ledger.claimDisposal(tx,eligibility,request.allocationId(),observed.lastRecoveryObservation().observationSha256()));
        assertThat(claim.identity()).isNull();assertThat(claim.allocation().retainedBytes()).isEqualTo(request.reservedBytes());
        var absence=recovery(claim.allocation(),2,RecoveryPresence.ABSENT);
        var disposed=h.ledger.inCallerTransaction(values.tenant,tx->h.ledger.confirmDisposal(tx,claim,new DisposalObservation(claim,absence),2));
        assertThat(disposed.retainedBytes()).isZero();assertThat(disposed.baseline()).isNull();
    }
    @Test void neverAdmittedScratchChecksExecutionAgainAtClaimAndTombstoneRejectsLaterExecution(){
        var h=harness();var q=values.allocation(AllocationKind.QUARANTINE,values.sourceAttempt,"quarantine");
        var scratch=values.allocation(AllocationKind.SCANNER_SCRATCH,values.scanAttempt,"scratch");
        var allocation=h.ledger.inCallerTransaction(values.tenant,tx->{h.ledger.reserve(tx,q);h.ledger.recordQuarantine(tx,fence(),values.quarantine());return h.ledger.reserve(tx,scratch);});
        var observed=checkpointRecovery(h,allocation,1,RecoveryPresence.ABSENT);
        var eligibility=new DisposalEligibility(values.tenant,values.intent,values.scanAttempt,1,true,"a".repeat(64),IngestionProofCodecTest.NOW.minusSeconds(1));
        var claim=h.ledger.inCallerTransaction(values.tenant,tx->h.ledger.claimDisposal(tx,eligibility,scratch.allocationId(),observed.lastRecoveryObservation().observationSha256()));
        assertThat(claim.allocation().retainedBytes()).isEqualTo(scratch.reservedBytes());
        assertThatThrownBy(()->h.ledger.inCallerTransaction(values.tenant,tx->h.ledger.recordExecution(tx,values.execution().descriptor())))
                .isInstanceOf(FileEvidenceTrustUnavailableException.class);
        assertThat(h.tables.getOrDefault("file_ingestion_execution",List.of())).isEmpty();
    }
    @Test void executionBeforeNeverAdmittedScratchRecoveryDeniesObservationAndRetainsReservation(){
        var h=harness();var q=values.allocation(AllocationKind.QUARANTINE,values.sourceAttempt,"quarantine");
        var scratch=values.allocation(AllocationKind.SCANNER_SCRATCH,values.scanAttempt,"scratch");
        var allocation=h.ledger.inCallerTransaction(values.tenant,tx->{h.ledger.reserve(tx,q);h.ledger.recordQuarantine(tx,fence(),values.quarantine());
            var reserved=h.ledger.reserve(tx,scratch);h.ledger.recordExecution(tx,values.execution().descriptor());return reserved;});
        assertThatThrownBy(()->checkpointRecovery(h,allocation,1,RecoveryPresence.ABSENT)).isInstanceOf(FileEvidenceTrustUnavailableException.class);
        assertThat(h.events()).noneMatch(e->e.get("allocation_id").equals(scratch.allocationId()));
        assertThat(allocation.retainedBytes()).isEqualTo(scratch.reservedBytes());
        h.beginFreshScriptedTransactionAfterRejection();
        var loaded=h.ledger.inCallerTransaction(values.tenant,tx->h.ledger.executionForAttempt(tx,values.intent,values.scanAttempt)).orElseThrow();
        assertThat(loaded.descriptor().canonicalDescriptor()).isEqualTo(values.execution().descriptor().canonicalDescriptor());
    }
    @Test void retainedPagingIncludesEventZeroAndAdvancesPastUnknownEarlyRows(){
        var h=harness();var ids=new ArrayList<UUID>();
        for(int i=0;i<40;i++){
            var request=values.allocation(AllocationKind.QUARANTINE,UUID.randomUUID(),"page-"+i);ids.add(request.allocationId());
            h.ledger.inCallerTransaction(values.tenant,tx->h.ledger.reserve(tx,request));
        }
        ids.sort(java.util.Comparator.comparing(UUID::toString));
        var first=h.ledger.inCallerTransaction(values.tenant,tx->h.ledger.retainedForIntent(tx,values.intent,null,32));
        assertThat(first.records()).hasSize(32).allSatisfy(a->{assertThat(a.eventNo()).isZero();assertThat(a.retainedBytes()).isPositive();});
        assertThat(first.nextCursor()).isEqualTo(ids.get(31));
        var next=h.ledger.inCallerTransaction(values.tenant,tx->h.ledger.retainedForIntent(tx,values.intent,first.nextCursor(),32));
        assertThat(next.records()).extracting(a->a.request().allocationId()).containsExactlyElementsOf(ids.subList(32,40));
        assertThat(next.nextCursor()).isNull();
        assertThatThrownBy(()->first.records().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(h.sql).anyMatch(q->q.contains("order by a.id limit ?")&&q.contains("coalesce(e.retained_bytes,a.reserved_bytes)>0"));
    }
    @Test void retainedPagingRejectsUnboundedLimitAndFiltersTenantAndIntent(){
        var h=harness();var request=values.allocation(AllocationKind.QUARANTINE,values.sourceAttempt,"quarantine");
        h.ledger.inCallerTransaction(values.tenant,tx->h.ledger.reserve(tx,request));
        var other=h.ledger.inCallerTransaction(values.tenant,tx->h.ledger.retainedForIntent(tx,UUID.randomUUID(),null,1));
        assertThat(other.records()).isEmpty();assertThat(other.nextCursor()).isNull();
        assertThatThrownBy(()->h.ledger.inCallerTransaction(values.tenant,tx->h.ledger.retainedForIntent(tx,values.intent,null,33)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(h.holder.isRollbackOnly()).isTrue();
    }
    @Test void restartReloadsExactOriginalActiveClaimThenConfirmedAllocationDisappearsFromPage(){
        var h=harness();var request=values.allocation(AllocationKind.QUARANTINE,values.sourceAttempt,"restart-absence");
        var allocation=h.ledger.inCallerTransaction(values.tenant,tx->h.ledger.reserve(tx,request));
        assertThat(h.ledger.<Optional<DisposalClaim>>inCallerTransaction(values.tenant,tx->h.ledger.currentDisposalClaim(tx,request.allocationId()))).isEmpty();
        var observed=checkpointRecovery(h,allocation,1,RecoveryPresence.ABSENT);
        var eligibility=new DisposalEligibility(values.tenant,values.intent,values.sourceAttempt,1,true,"a".repeat(64),IngestionProofCodecTest.NOW.minusSeconds(1));
        var claim=h.ledger.inCallerTransaction(values.tenant,tx->h.ledger.claimDisposal(tx,eligibility,request.allocationId(),observed.lastRecoveryObservation().observationSha256()));
        var restored=h.ledger.inCallerTransaction(values.tenant,tx->h.ledger.currentDisposalClaim(tx,request.allocationId())).orElseThrow();
        assertThat(restored.token()).isEqualTo(claim.token());assertThat(restored.epoch()).isEqualTo(claim.epoch());
        assertThat(restored.tombstoneId()).isEqualTo(claim.tombstoneId());assertThat(restored.technicalVersion()).isEqualTo(1);
        assertThat(restored.allocation().baselineEventId()).isNull();
        var page=h.ledger.inCallerTransaction(values.tenant,tx->h.ledger.retainedForIntent(tx,values.intent,null,32));
        assertThat(page.records()).extracting(a->a.request().allocationId()).containsExactly(request.allocationId());
        var absence=recovery(restored.allocation(),2,RecoveryPresence.ABSENT);
        h.ledger.inCallerTransaction(values.tenant,tx->h.ledger.confirmDisposal(tx,restored,new DisposalObservation(restored,absence),2));
        assertThat(h.ledger.<Optional<DisposalClaim>>inCallerTransaction(values.tenant,tx->h.ledger.currentDisposalClaim(tx,request.allocationId()))).isEmpty();
        assertThat(h.ledger.inCallerTransaction(values.tenant,tx->h.ledger.retainedForIntent(tx,values.intent,null,32)).records()).isEmpty();
    }
    @Test void originalPreClaimObservationCannotBeCanonicalReboundAsPostClaimAbsence(){
        var h=harness();var request=values.allocation(AllocationKind.QUARANTINE,values.sourceAttempt,"binding-absence");
        var allocation=h.ledger.inCallerTransaction(values.tenant,tx->h.ledger.reserve(tx,request));
        var observed=checkpointRecovery(h,allocation,1,RecoveryPresence.ABSENT);
        var eligibility=new DisposalEligibility(values.tenant,values.intent,values.sourceAttempt,1,true,"a".repeat(64),IngestionProofCodecTest.NOW.minusSeconds(1));
        var claim=h.ledger.inCallerTransaction(values.tenant,tx->h.ledger.claimDisposal(tx,eligibility,request.allocationId(),observed.lastRecoveryObservation().observationSha256()));
        assertThatThrownBy(()->h.ledger.inCallerTransaction(values.tenant,tx->h.ledger.confirmDisposal(tx,claim,
                new DisposalObservation(claim,observed.lastRecoveryObservation()),2))).isInstanceOf(FileEvidenceTrustUnavailableException.class);
        assertThat(claim.allocation().retainedBytes()).isEqualTo(request.reservedBytes());assertThat(h.holder.isRollbackOnly()).isTrue();
    }
    @Test void brokenEventPredecessorCannotBecomeAReadableBaseline(){
        var h=harness();var request=values.allocation(AllocationKind.QUARANTINE,values.sourceAttempt,"quarantine");
        h.ledger.inCallerTransaction(values.tenant,tx->{h.ledger.reserve(tx,request);h.ledger.recordQuarantine(tx,fence(),values.quarantine());return null;});
        h.events().getFirst().put("predecessor_event_id",UUID.randomUUID());
        assertThatThrownBy(()->h.ledger.inCallerTransaction(values.tenant,tx->h.ledger.load(tx,request.allocationId())))
                .isInstanceOf(FileEvidenceTrustUnavailableException.class);
    }
    @Test void chargesKeepPendingAndFinalizedSeparateAndRejectOverflow(){
        var h=harness();h.charges=new BigDecimal[]{BigDecimal.valueOf(10),BigDecimal.valueOf(20),BigDecimal.valueOf(30),BigDecimal.valueOf(40)};
        var charges=h.ledger.inCallerTransaction(values.tenant,tx->h.ledger.charges(tx,values.file));
        assertThat(charges).isEqualTo(new PhysicalCharges(10,20,30,40));assertThat(charges.ownerTotal()).isEqualTo(40);assertThat(charges.tenantTotal()).isEqualTo(60);
        h.charges[0]=BigDecimal.valueOf(Long.MAX_VALUE).add(BigDecimal.ONE);
        assertThatThrownBy(()->h.ledger.inCallerTransaction(values.tenant,tx->h.ledger.charges(tx,values.file))).isInstanceOf(ArithmeticException.class);
        assertThat(h.holder.isRollbackOnly()).isTrue();
    }
    @Test void failedActualJdbcWriteMarksCallerAndCannotReturnCallbackSuccess(){
        var h=harness();h.failInsert="file_ingestion_allocation";
        assertThatThrownBy(()->h.ledger.inCallerTransaction(values.tenant,tx->{h.ledger.reserve(tx,values.allocation(AllocationKind.QUARANTINE,values.sourceAttempt,"quarantine"));return "success";}))
                .isInstanceOf(org.jooq.exception.DataAccessException.class);
        assertThat(h.holder.isRollbackOnly()).isTrue();
    }
    @Test void contextCannotEscapeOrBeBorrowedByAnotherLedgerInstance(){
        var h=harness();var escaped=new AtomicReference<FileIngestionTxContext>();
        h.ledger.inCallerTransaction(values.tenant,tx->{escaped.set(tx);return null;});
        assertThatThrownBy(()->h.ledger.charges(escaped.get(),values.file)).isInstanceOf(IllegalStateException.class);
        h.holder.resetRollbackOnly();
        assertThatThrownBy(()->h.ledger.inCallerTransaction(values.tenant,tx->new JooqFileIngestionLedger(h.dsl,h.source,values.codec).charges(tx,values.file)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(h.holder.isRollbackOnly()).isTrue();
    }
    @Test void generationReplayRequiresExactRetainedDescriptor(){
        var h=harness();var generation=values.generation();
        assertThat(h.ledger.<UUID>inCallerTransaction(values.tenant,tx->h.ledger.recordGeneration(tx,generation))).isEqualTo(generation.generationId());
        assertThat(h.ledger.<UUID>inCallerTransaction(values.tenant,tx->h.ledger.recordGeneration(tx,generation))).isEqualTo(generation.generationId());
        var changed=new AcceptedGeneration(generation.generationId(),generation.generationSha256(),generation.manifestSha256(),generation.canonicalManifest(),
                generation.validatedAt(),generation.validUntil().plusSeconds(1));
        assertThatThrownBy(()->h.ledger.inCallerTransaction(values.tenant,tx->h.ledger.recordGeneration(tx,changed))).isInstanceOf(FileEvidenceTrustUnavailableException.class);
    }
    @Test void scanCheckpointStoresOnlyFilesProofAndReceiptMappings(){
        var h=harness();var proof=values.proof();var receipt=values.receipt(proof);
        var q=values.allocation(AllocationKind.QUARANTINE,values.sourceAttempt,"quarantine");
        var copy=values.allocation(AllocationKind.IMMUTABLE,values.scanAttempt,"immutable");
        var receiptAllocation=values.allocation(AllocationKind.RECEIPT,values.scanAttempt,"receipt");
        var immutable=new ImmutableObject(values.tenant,values.intent,values.file,copy.allocationId(),values.sourceAttempt,values.scanAttempt,
                values.identity(copy,3),proof.sha256(),proof.detectedMediaType(),3,proof.quarantineObservationSha256());
        var receiptObservation=values.codec.observe(receiptAllocation,ObservationKind.RECEIPT,values.identity(receiptAllocation,100),"f".repeat(64),100L,"application/octet-stream");
        var checkpoint=h.ledger.inCallerTransaction(values.tenant,tx->{
            h.ledger.reserve(tx,q);h.ledger.recordQuarantine(tx,fence(),values.quarantine());
            h.ledger.reserve(tx,values.allocation(AllocationKind.SCANNER_SCRATCH,values.scanAttempt,"scratch"));
            h.ledger.recordExecution(tx,values.execution().descriptor());h.ledger.reserve(tx,copy);h.ledger.reserve(tx,receiptAllocation);
            return h.ledger.recordVerified(tx,values.scanFence(),values.quarantine(),
                    new ImmutableObjectAndReceipt(immutable,proof,receipt,receiptObservation),values.generation(),values.outcome());
        });
        assertThat(checkpoint).isEqualTo(new VerifiedCheckpoint(proof.proofId(),receipt.receiptId(),copy.allocationId()));
        assertThat(h.tables.get("file_ingestion_scan_proof")).hasSize(1);assertThat(h.tables.get("file_ingestion_content_receipt")).hasSize(1);
        assertThat(h.sql).noneMatch(query->query.contains("file_object")||query.contains("file_link")||query.contains("evidence_item")||query.contains("update public.finding"));
    }
    TechnicalFence fence(){return new TechnicalFence(values.tenant,values.intent,values.sourceAttempt,UUID.randomUUID(),1,1);}

    final class Harness implements AutoCloseable {
        final DataSource source=mock(DataSource.class);
        final Map<String,List<Map<String,Object>>> tables=new HashMap<>();
        final List<String> sql=new ArrayList<>();
        BigDecimal[] charges={BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO};
        String failInsert;
        final MockConnection jdbc=new MockConnection(context->response(context)){
            @Override public boolean getAutoCommit(){return false;}
            @Override public boolean isReadOnly(){return false;}
            @Override public int getTransactionIsolation(){return Connection.TRANSACTION_READ_COMMITTED;}
            @Override public void commit(){throw new AssertionError("Files ledger cannot commit caller transaction");}
            @Override public void rollback(){throw new AssertionError("Files ledger only marks caller rollback-only");}
        };
        final DSLContext dsl=DSL.using(jdbc,SQLDialect.POSTGRES);
        ConnectionHolder holder=new ConnectionHolder(jdbc);
        final JooqFileIngestionLedger ledger=new JooqFileIngestionLedger(dsl,source,values.codec);
        void bind(){holder.setSynchronizedWithTransaction(true);TransactionSynchronizationManager.bindResource(source,holder);
            TransactionSynchronizationManager.setActualTransactionActive(true);TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(Connection.TRANSACTION_READ_COMMITTED);
            TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);TransactionSynchronizationManager.initSynchronization();}
        void beginFreshScriptedTransactionAfterRejection(){
            assertThat(holder.isRollbackOnly()).isTrue();
            // No writes occurred in the rejected callback. This models only a new caller holder/lifetime,
            // and deliberately makes no claim that the scripted database implements physical rollback.
            for(var synchronization:TransactionSynchronizationManager.getSynchronizations())
                synchronization.afterCompletion(org.springframework.transaction.support.TransactionSynchronization.STATUS_ROLLED_BACK);
            close();holder=new ConnectionHolder(jdbc);bind();
        }
        List<Map<String,Object>> events(){return tables.computeIfAbsent("file_ingestion_allocation_event",ignored->new ArrayList<>());}
        MockResult[] response(MockExecuteContext context)throws SQLException{
            String query=context.sql();sql.add(query);Object[] binds=context.bindings();
            if(query.startsWith("select current_setting"))return rows(List.of(row("tenant",values.tenant.toString(),"isolation","read committed","readonly","off")));
            if(query.equals("select statement_timestamp() as now"))return rows(List.of(row("now",OffsetDateTime.ofInstant(IngestionProofCodecTest.NOW,ZoneOffset.UTC))));
            if(query.startsWith("insert into public.")){
                int start=query.indexOf("public.")+7,end=query.indexOf(' ',start);String table=query.substring(start,end);
                if(table.equals(failInsert))throw new SQLException("synthetic files write failure");
                int left=query.indexOf('('),right=query.indexOf(')',left);String[] names=query.substring(left+1,right).split(",");
                Map<String,Object> row=new LinkedHashMap<>();for(int i=0;i<names.length;i++)row.put(names[i].trim(),binds[i]);
                if(table.equals("file_ingestion_allocation_event")){
                    row.putIfAbsent("recovery_id",null);
                    row.putIfAbsent("expected_current_technical_version",null);
                    row.putIfAbsent("expected_intent_lease_epoch",null);
                    row.putIfAbsent("target_event_no",null);
                    row.putIfAbsent("target_event_sha256",null);
                    row.putIfAbsent("execution_id",null);
                    row.putIfAbsent("descriptor_sha256",null);
                    row.putIfAbsent("recovery_reason",null);
                    row.putIfAbsent("retention_policy_sha256",null);
                    row.putIfAbsent("recovery_presence",null);
                    row.putIfAbsent("process_state",null);
                    row.putIfAbsent("writer_quiescence_sha256",null);
                    row.putIfAbsent("writer_quiescence_observed_at",null);
                    row.putIfAbsent("stop_write_fence_sha256",null);
                    row.putIfAbsent("kernel_challenge",null);
                    row.putIfAbsent("kernel_physical_resource_realm_id",null);
                    row.putIfAbsent("kernel_slot_id",null);
                    row.putIfAbsent("kernel_profile_sha256",null);
                    row.putIfAbsent("kernel_boot_id",null);
                    row.putIfAbsent("kernel_supervisor_pid",null);
                    row.putIfAbsent("kernel_supervisor_start_ticks",null);
                    row.putIfAbsent("kernel_child_pid",null);
                    row.putIfAbsent("kernel_child_start_ticks",null);
                    row.putIfAbsent("kernel_child_absent_reason",null);
                    row.putIfAbsent("kernel_pid_namespace",null);
                    row.putIfAbsent("kernel_mount_namespace",null);
                    row.putIfAbsent("kernel_net_namespace",null);
                    row.putIfAbsent("kernel_ipc_namespace",null);
                    row.putIfAbsent("kernel_uts_namespace",null);
                    row.putIfAbsent("kernel_cgroup_name",null);
                    row.putIfAbsent("kernel_cgroup_mount_id",null);
                    row.putIfAbsent("kernel_cgroup_inode",null);
                    row.putIfAbsent("kernel_pids_policy_sha256",null);
                    row.putIfAbsent("kernel_pids_current",null);
                    row.putIfAbsent("kernel_populated",null);
                    row.putIfAbsent("kernel_pids_controller_continuous",null);
                    row.putIfAbsent("kernel_no_migration_or_future_entry",null);
                    row.putIfAbsent("kernel_slot_lock_exclusive",null);
                    row.putIfAbsent("kernel_slot_lock_identity_sha256",null);
                    row.putIfAbsent("kernel_permanent_stop",null);
                    row.putIfAbsent("kernel_stop_sha256",null);
                    row.putIfAbsent("kernel_child_observed",null);
                    row.putIfAbsent("kernel_image_verified",null);
                    row.putIfAbsent("kernel_wait_reaped",null);
                    row.putIfAbsent("kernel_input_drained",null);
                    row.putIfAbsent("kernel_stdout_drained",null);
                    row.putIfAbsent("kernel_stderr_drained",null);
                    row.putIfAbsent("kernel_interrupted_output_discarded",null);
                    row.putIfAbsent("kernel_process_state",null);
                    row.putIfAbsent("kernel_control_sha256",null);
                    row.putIfAbsent("kernel_report_sha256",null);
                    row.putIfAbsent("kernel_observed_at",null);
                    row.putIfAbsent("disposal_claim_technical_version",null);
                    row.putIfAbsent("writer_quiescent",false);row.putIfAbsent("kernel_facts_present",false);
                }
                var existing=tables.computeIfAbsent(table,ignored->new ArrayList<>());
                if(!query.contains("on conflict")||existing.stream().noneMatch(r->r.get("id").equals(row.get("id"))))existing.add(row);
                return new MockResult[]{new MockResult(1)};
            }
            if(query.startsWith("select a.id from public.file_ingestion_allocation a")){
                var selected=tables.getOrDefault("file_ingestion_allocation",List.of()).stream()
                    .filter(a->a.get("tenant_id").equals(binds[0])&&a.get("intent_id").equals(binds[1]))
                    .filter(a->binds[2]==null||a.get("id").toString().compareTo(binds[2].toString())>0)
                    .filter(a->{var last=events().stream().filter(e->e.get("allocation_id").equals(a.get("id"))&&e.get("tenant_id").equals(a.get("tenant_id")))
                        .max(java.util.Comparator.comparingLong(e->((Number)e.get("event_no")).longValue()));
                        if(last.isEmpty())return ((Number)a.get("reserved_bytes")).longValue()>0;
                        String kind=(String)last.get().get("event_kind");return !Set.of("TRANSFER_FINALIZED","DISPOSAL_CONFIRMED").contains(kind)
                            &&(((Number)last.get().get("retained_bytes")).longValue()>0||kind.equals("DISPOSAL_CLAIMED"));})
                    .sorted(java.util.Comparator.comparing(a->a.get("id").toString())).limit(((Number)binds[4]).longValue()).map(a->row("id",a.get("id"))).toList();
                // JDBC metadata retains SELECT a.id's UUID column even when the result has no rows.
                if(selected.isEmpty())return new MockResult[]{new MockResult(0,DSL.using(SQLDialect.POSTGRES).newResult(DSL.field("id",UUID.class)))};
                return rows(selected);
            }
            if(query.contains("as owner_pending"))return rows(List.of(row("owner_pending",charges[0],"tenant_pending",charges[1],"owner_finalized",charges[2],"tenant_finalized",charges[3])));
            if(query.contains("from public.file_ingestion_execution"))return rows(tables.getOrDefault("file_ingestion_execution",List.of()).stream()
                    .filter(r->r.get("tenant_id").equals(binds[0])).filter(r->query.contains("scan_attempt_id=?")?
                        r.get("intent_id").equals(binds[1])&&r.get("scan_attempt_id").equals(binds[2])&&(binds.length<4||r.get("scratch_allocation_id").equals(binds[3])):r.get("execution_id").equals(binds[1])).toList());
            if(query.contains("from public.file_ingestion_allocation_event")){
                var selected=events().stream().filter(r->r.get("tenant_id").equals(binds[0])&&r.get("allocation_id").equals(binds[1]))
                        .sorted(java.util.Comparator.comparingLong(r->((Number)r.get("event_no")).longValue())).toList();return rows(selected);
            }
            if(query.contains("from public.file_ingestion_allocation")){
                var selected=tables.getOrDefault("file_ingestion_allocation",List.of()).stream().filter(r->r.get("tenant_id").equals(binds[0]))
                        .filter(r->query.startsWith("select id")?r.get("intent_id").equals(binds[1])&&r.get("attempt_id").equals(binds[2])&&r.get("kind").equals(binds[3]):r.get("id").equals(binds[1])).toList();return rows(selected);
            }
            if(query.contains("from public.file_scanner_generation"))return rows(tables.getOrDefault("file_scanner_generation",List.of()).stream()
                    .filter(r->r.get("tenant_id").equals(binds[0])&&r.get("generation_sha256").equals(binds[1])).toList());
            throw new SQLException("Unexpected scripted SQL");
        }
        @SuppressWarnings({"rawtypes","unchecked"}) MockResult[] rows(List<Map<String,Object>> rows){
            if(rows.isEmpty())return new MockResult[]{new MockResult(0,DSL.using(SQLDialect.POSTGRES).newResult(DSL.field("unused",String.class)))};
            var first=rows.getFirst();Field<?>[] fields=first.entrySet().stream().map(e->DSL.field(e.getKey(),(Class)(e.getValue()==null?String.class:e.getValue().getClass()))).toArray(Field[]::new);
            var result=DSL.using(SQLDialect.POSTGRES).newResult(fields);
            for(var data:rows){var record=DSL.using(SQLDialect.POSTGRES).newRecord(fields);for(Field field:fields)record.set(field,data.get(field.getName()));result.add(record);}
            return new MockResult[]{new MockResult(rows.size(),result)};
        }
        Map<String,Object> row(Object... pairs){Map<String,Object> values=new LinkedHashMap<>();for(int i=0;i<pairs.length;i+=2)values.put((String)pairs[i],pairs[i+1]);return values;}
        @Override public void close(){if(TransactionSynchronizationManager.isSynchronizationActive())TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.unbindResourceIfPossible(source);TransactionSynchronizationManager.clear();}
    }
}
