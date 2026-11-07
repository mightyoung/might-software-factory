package com.mightyoung.softwarefactory.dualprevention.finding.evidenceupload;

import com.mightyoung.softwarefactory.dualprevention.FindingNotVisibleException;
import com.mightyoung.softwarefactory.files.*;
import com.mightyoung.softwarefactory.identity.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import static com.mightyoung.softwarefactory.dualprevention.finding.evidenceupload.FindingEvidenceUploadContracts.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class FindingEvidenceUploadServiceTest {
    final UUID tenant=UUID.randomUUID(), actor=UUID.randomUUID(), account=UUID.randomUUID(), member=UUID.randomUUID();
    final UUID findingId=UUID.randomUUID(),setId=UUID.randomUUID(),noticeId=UUID.randomUUID(),intentId=UUID.randomUUID();
    final CurrentTenantContext owner=new CurrentTenantContext(actor,account,tenant,member,1,2,3);
    final OffsetDateTime now=OffsetDateTime.parse("2026-11-03T00:00:00Z");
    final ReserveCommand command=new ReserveCommand(UUID.randomUUID(),findingId,2,setId,7,3,"证据.pdf","application/pdf",100);
    final SessionContextResolver sessions=mock(SessionContextResolver.class);
    final UploadProducerReadiness producer=mock(UploadProducerReadiness.class);
    final FindingEvidenceUploadRepository repository=mock(FindingEvidenceUploadRepository.class);
    final FindingEvidenceUploadReadRepository reads=mock(FindingEvidenceUploadReadRepository.class);
    final FindingEvidenceUploadRepository.Transaction tx=mock(FindingEvidenceUploadRepository.Transaction.class);
    final FindingEvidenceUploadAccessPolicy access=mock(FindingEvidenceUploadAccessPolicy.class);
    final Finding finding=new Finding(findingId,UUID.randomUUID(),"RECTIFYING",2,7);
    final Notice notice=new Notice(noticeId,member,true);
    final EvidenceSet set=new EvidenceSet(setId,findingId,2,member,3,false);
    final UploadProducerReadiness.Capability capability=capability();
    FindingEvidenceUploadService service;

    static UploadProducerReadiness.Capability capability() {
        return new UploadProducerReadiness.Capability("producer-v1","policy-v1","definitions-v1",Set.of("application/pdf"),
                67_108_864,20,20,134_217_728,200,1_342_177_280,Duration.ofMinutes(15),Duration.ofMinutes(2),4);
    }
    @BeforeEach @SuppressWarnings("unchecked") void setup() {
        service=new FindingEvidenceUploadService(sessions,producer,repository,reads,access);
        when(sessions.currentTenant()).thenReturn(owner);
        when(producer.requireReady()).thenReturn(capability); when(producer.isCurrent(capability)).thenReturn(true);
        when(repository.inTenantTransaction(eq(tenant),any())).thenAnswer(call -> ((Function<FindingEvidenceUploadRepository.Transaction,Object>)call.getArgument(1)).apply(tx));
        when(tx.finding(findingId)).thenReturn(Optional.of(finding)); when(tx.notice(findingId,2)).thenReturn(Optional.of(notice));
        when(tx.evidenceSet(setId)).thenReturn(Optional.of(set)); when(tx.existing(actor,command.idempotencyKey())).thenReturn(Optional.empty());
        when(tx.quota(member,setId)).thenReturn(new Quota(0,0,0,0,0)); when(tx.now()).thenReturn(now);
        when(tx.insert(owner,command,notice,command.fingerprint(owner,noticeId),capability))
                .thenReturn(new FindingEvidenceUploadRepository.InsertResult(intent(now.plusMinutes(15)),true));
    }
    @AfterEach void clear() { TransactionSynchronizationManager.clear(); }
    Intent intent(OffsetDateTime expiry) {
        return new Intent(intentId,account,actor,member,findingId,2,noticeId,setId,command.fingerprint(owner,noticeId),now,expiry);
    }
    @Test void unconfiguredProducerRejectsBeforeAnyRepositoryOrAuthorityMutation() {
        service=new FindingEvidenceUploadService(sessions,new UnconfiguredUploadProducerReadiness(),repository,reads,access);
        assertThatThrownBy(() -> service.reserve(command)).isInstanceOf(FileEvidenceTrustUnavailableException.class);
        verifyNoInteractions(repository,reads,tx,access);
    }
    @Test void configuredSizeAndMediaPolicyRejectBeforeRepositoryOrAuthority() {
        var limited = new UploadProducerReadiness.Capability("producer-v1","policy-v1","definitions-v1",Set.of("application/pdf"),
                99,20,20,134_217_728,200,1_342_177_280,Duration.ofMinutes(15),Duration.ofMinutes(2),4);
        when(producer.requireReady()).thenReturn(limited);
        assertThatThrownBy(() -> service.reserve(command)).isInstanceOfSatisfying(FindingEvidenceUploadException.class,
                e -> assertThat(e.reason()).isEqualTo(FindingEvidenceUploadException.Reason.DECLARED_SIZE_EXCEEDED));
        when(producer.requireReady()).thenReturn(capability);
        var wrongMedia = new ReserveCommand(command.idempotencyKey(),findingId,2,setId,7,3,"证据.png","image/png",100);
        assertThatThrownBy(() -> service.reserve(wrongMedia)).isInstanceOfSatisfying(FindingEvidenceUploadException.class,
                e -> assertThat(e.reason()).isEqualTo(FindingEvidenceUploadException.Reason.MEDIA_TYPE_NOT_ALLOWED));
        verifyNoInteractions(repository,reads,tx,access);
    }
    @Test void newIntentUsesDerivedOwnerAndAuthorizesBeforeIdempotencyQuotaAndInsert() {
        assertThat(service.reserve(command)).isEqualTo(new Reservation(intent(now.plusMinutes(15)).status(now),false));
        var order=inOrder(access,tx,producer);
        order.verify(producer).requireReady(); order.verify(access).requireCurrent(owner,finding,2,notice,set);
        order.verify(tx).existing(actor,command.idempotencyKey()); order.verify(tx).quota(member,setId);
        order.verify(tx).insert(owner,command,notice,command.fingerprint(owner,noticeId),capability);
        order.verify(producer).isCurrent(capability);
    }
    @Test void authorizedExpiredReplayRetainsIdAndIgnoresAdvancedVersionsAndQuota() {
        when(tx.finding(findingId)).thenReturn(Optional.of(new Finding(findingId,finding.organizationId(),"RECTIFYING",2,9)));
        when(tx.evidenceSet(setId)).thenReturn(Optional.of(new EvidenceSet(setId,findingId,2,member,10,false)));
        when(tx.existing(actor,command.idempotencyKey())).thenReturn(Optional.of(intent(now)));
        assertThat(service.reserve(command)).isEqualTo(new Reservation(new Status(intentId,State.EXPIRED,now),true));
        verify(tx,never()).quota(any(),any()); verify(tx,never()).insert(any(),any(),any(),any(),any());
    }
    @Test void currentAuthorizationFailureCannotReplay() {
        doThrow(new FindingNotVisibleException()).when(access).requireCurrent(owner,finding,2,notice,set);
        assertThatThrownBy(() -> service.reserve(command)).isInstanceOf(FindingNotVisibleException.class);
        verify(tx,never()).existing(any(),any()); verify(tx,never()).insert(any(),any(),any(),any(),any());
    }
    @Test void changedBodyConflictsWithoutQuotaOrWrite() {
        var prior=intent(now.plusMinutes(15));
        when(tx.existing(actor,command.idempotencyKey())).thenReturn(Optional.of(new Intent(prior.id(),account,actor,member,findingId,2,
                noticeId,setId,"0".repeat(64),now,prior.expiresAt())));
        assertThatThrownBy(() -> service.reserve(command)).isInstanceOfSatisfying(FindingEvidenceUploadException.class,
                e -> assertThat(e.reason()).isEqualTo(FindingEvidenceUploadException.Reason.IDEMPOTENCY_CONFLICT));
        verify(tx,never()).quota(any(),any());
    }
    @Test void newIntentVersionMismatchRefusesBeforeQuotaAndWrites() {
        when(tx.finding(findingId)).thenReturn(Optional.of(new Finding(findingId,finding.organizationId(),"RECTIFYING",2,8)));
        assertThatThrownBy(() -> service.reserve(command)).isInstanceOfSatisfying(FindingEvidenceUploadException.class,
                e -> assertThat(e.reason()).isEqualTo(FindingEvidenceUploadException.Reason.VERSION_CONFLICT));
        verify(tx,never()).quota(any(),any()); verify(tx,never()).insert(any(),any(),any(),any(),any());
    }
    @Test void newIntentEvidenceRevisionMismatchRefusesBeforeQuotaAndWrites() {
        when(tx.evidenceSet(setId)).thenReturn(Optional.of(new EvidenceSet(setId,findingId,2,member,4,false)));
        assertThatThrownBy(() -> service.reserve(command)).isInstanceOfSatisfying(FindingEvidenceUploadException.class,
                e -> assertThat(e.reason()).isEqualTo(FindingEvidenceUploadException.Reason.EVIDENCE_REVISION_CONFLICT));
        verify(tx,never()).quota(any(),any()); verify(tx,never()).insert(any(),any(),any(),any(),any());
    }
    @Test void pendingReservationsAndExistingItemsParticipateInQuota() {
        for (Quota quota : new Quota[]{new Quota(20,0,20,0,0),new Quota(0,134_217_629,0,0,0),
                new Quota(0,0,200,0,0),new Quota(0,0,0,1_342_177_181,0),new Quota(0,0,0,0,20)}) {
            when(tx.quota(member,setId)).thenReturn(quota);
            assertThatThrownBy(() -> service.reserve(command)).isInstanceOfSatisfying(FindingEvidenceUploadException.class,
                    e -> assertThat(e.reason()).isEqualTo(FindingEvidenceUploadException.Reason.QUOTA_EXCEEDED));
        }
        verify(tx,never()).insert(any(),any(),any(),any(),any());
    }
    @Test void generationChangeFailsCallbackRatherThanReturningDurableSuccess() {
        when(producer.isCurrent(capability)).thenReturn(false);
        assertThatThrownBy(() -> service.reserve(command)).isInstanceOf(FileEvidenceTrustUnavailableException.class);
    }
    @Test void commitFailurePropagatesAndCannotBecomeReservation() {
        when(repository.inTenantTransaction(eq(tenant),any())).thenThrow(new IllegalStateException("commit failed"));
        assertThatThrownBy(() -> service.reserve(command)).isInstanceOf(IllegalStateException.class).hasMessage("commit failed");
    }
    @Test void ambientTransactionOrSynchronizationIsRejectedBeforeSessionOrProducer() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        assertThatThrownBy(() -> service.reserve(command)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(sessions,producer,repository);
        TransactionSynchronizationManager.setActualTransactionActive(false);
        TransactionSynchronizationManager.initSynchronization();
        try { assertThatThrownBy(() -> service.status(findingId,2,intentId)).isInstanceOf(IllegalStateException.class); }
        finally { TransactionSynchronizationManager.clearSynchronization(); }
    }
    @Test @SuppressWarnings("unchecked") void statusDeniesCurrentActionBeforeIntentExistenceLookup() {
        when(reads.inTenantReadSnapshot(eq(tenant),any())).thenAnswer(call -> ((Function<FindingEvidenceUploadReadRepository.Snapshot,Object>)call.getArgument(1)).apply(tx));
        doThrow(new org.springframework.security.access.AccessDeniedException("revoked"))
                .when(access).requireAction(owner,finding);
        for (Optional<Intent> existing : java.util.List.of(Optional.of(intent(now)),Optional.<Intent>empty())) {
            when(tx.intent(intentId)).thenReturn(existing);
            assertThatThrownBy(() -> service.status(findingId,2,intentId)).isInstanceOf(FindingNotVisibleException.class);
        }
        verify(tx,never()).intent(any()); verifyNoInteractions(producer,repository);
    }
    @Test @SuppressWarnings("unchecked") void statusDependencyUnavailableRemainsUnavailable() {
        when(reads.inTenantReadSnapshot(eq(tenant),any())).thenAnswer(call -> ((Function<FindingEvidenceUploadReadRepository.Snapshot,Object>)call.getArgument(1)).apply(tx));
        doThrow(new IllegalStateException("authorization unavailable")).when(access).requireAction(owner,finding);
        assertThatThrownBy(() -> service.status(findingId,2,intentId)).isInstanceOf(IllegalStateException.class)
                .hasMessage("authorization unavailable");
        verify(tx,never()).intent(any());
    }
    @Test @SuppressWarnings("unchecked") void statusDoesNotProbeProducerDuringOutage() {
        when(reads.inTenantReadSnapshot(eq(tenant),any())).thenAnswer(call -> ((Function<FindingEvidenceUploadReadRepository.Snapshot,Object>)call.getArgument(1)).apply(tx));
        when(tx.intent(intentId)).thenReturn(Optional.of(intent(now)));
        assertThat(service.status(findingId,2,intentId)).isEqualTo(new Status(intentId,State.EXPIRED,now));
        verifyNoInteractions(producer,repository); verify(access).requireOwnedContext(owner,finding,2,notice,set);
    }
}
