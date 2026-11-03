package com.mightyoung.softwarefactory.identity;

import org.junit.jupiter.api.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import java.net.URI;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.function.Supplier;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static com.mightyoung.softwarefactory.identity.IdentityLifecycleContracts.*;

/** In-memory protocol evidence only: not PostgreSQL, actual delivery or HTTP acceptance. */
class IdentityLifecycleServiceTest {
    final IdentityLifecycleStore store=mock(IdentityLifecycleStore.class);
    final IdentityLifecycleSecurityTransaction writer=mock(IdentityLifecycleSecurityTransaction.class);
    final IdentityLifecycleAuthorization authorization=mock(IdentityLifecycleAuthorization.class);
    final InvitationMembershipPort members=mock(InvitationMembershipPort.class);
    final IdentityLifecycleAudit audit=mock(IdentityLifecycleAudit.class);
    final IdentityLifecycleSession sessions=mock(IdentityLifecycleSession.class);
    final CredentialCapabilityDeliveryPort delivery=mock(CredentialCapabilityDeliveryPort.class);
    final PasswordEncoder encoder=mock(PasswordEncoder.class);
    final AtomicReference<Instant> now=new AtomicReference<>(Instant.parse("2026-11-03T12:00:00Z"));
    final Clock clock=new Clock(){public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}public Instant instant(){return now.get();}};
    final IdentityLifecycleProperties policy=new IdentityLifecycleProperties();
    final UUID accountId=UUID.randomUUID(),actorId=UUID.randomUUID(),challengeId=UUID.randomUUID(),contactId=UUID.randomUUID(),key=UUID.randomUUID();
    final Destination destination=new Destination("EMAIL","verified@example.invalid");
    final String token="AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",password="a sufficiently long password";
    final AtomicReference<AccountSnapshot> account=new AtomicReference<>(new AccountSnapshot(accountId,actorId,"person","ACTIVE",1,"old-hash"));
    final AtomicReference<Receipt> completed=new AtomicReference<>();
    final AtomicReference<ChallengeSnapshot> challenge=new AtomicReference<>();
    final AtomicReference<RecoveryBinding> contact=new AtomicReference<>(new RecoveryBinding(contactId,accountId,destination,1,true));
    IdentityLifecycleService service;
    @BeforeEach void setup(){
        byte[] secret=new byte[32];Arrays.fill(secret,(byte)7);String encoded=Base64.getEncoder().encodeToString(secret);
        policy.setFingerprintKeyId("retained-1");policy.setFingerprintKeys(Map.of("retained-1",encoded));policy.setRateKey(encoded);policy.setConsumeOrigin(URI.create("https://identity.example.invalid"));
        try(var secretToken=OneUseSecret.parse(token)) {challenge.set(new ChallengeSnapshot(challengeId,Purpose.PASSWORD_RESET,accountId,null,null,null,null,destination,contactId,1L,1L,secretToken.digest(),State.ISSUED,1,now.get(),now.get().plusSeconds(900),null));}
        when(writer.execute(any(),any())).thenAnswer(call->((Supplier<?>)call.getArgument(1)).get());
        when(store.databaseNow(any())).thenAnswer(call->now.get());
        when(store.reserveRate(any(),anyString(),any(),anyInt(),anyInt())).thenReturn(IdentityLifecycleStore.RateDecision.ADMITTED);
        when(store.readChallenge(challengeId)).thenAnswer(call->Optional.of(challenge.get()));when(store.lockChallenge(any(),eq(challengeId))).thenAnswer(call->challenge.get());
        when(store.localAccount(any(),eq(accountId))).thenAnswer(call->Optional.of(account.get()));when(store.activeHuman(any(),eq(accountId),eq(actorId))).thenReturn(true);
        when(store.recovery(any(),eq(accountId))).thenAnswer(call->Optional.ofNullable(contact.get()));
        when(store.readReceipt(any(),any(),any(),any())).thenAnswer(call->Optional.ofNullable(completed.get()));
        when(store.receipt(any(),any(),any(),any(),any())).thenAnswer(call->Optional.ofNullable(completed.get()));
        doAnswer(call->{completed.set(call.getArgument(1));return null;}).when(store).receipt(any(),any(Receipt.class));
        doAnswer(call->{AccountSnapshot old=account.get();account.set(new AccountSnapshot(accountId,actorId,old.loginKey(),old.status(),old.credentialVersion()+1,call.getArgument(2)));return null;}).when(store).resetPassword(any(),any(),anyString(),any());
        doAnswer(call->{ChallengeSnapshot c=challenge.get();challenge.set(new ChallengeSnapshot(c.id(),c.purpose(),c.accountId(),c.tenantId(),c.issuer(),c.intendedLogin(),c.displayName(),c.destination(),c.recoveryContactId(),c.contactVersion(),c.issuedCredentialVersion(),c.secretDigest(),call.getArgument(2),c.version()+1,c.createdAt(),c.expiresAt(),call.getArgument(3)));return null;}).when(store).terminal(any(),any(),any(),any());
        when(encoder.encode(any())).thenReturn("{bcrypt}$2a$12$"+"a".repeat(53));
        service=new IdentityLifecycleService(store,writer,authorization,members,audit,sessions,delivery,policy,encoder,clock);
    }
    @AfterEach void close(){service.close();}
    private void consume(UUID commandKey,String body){service.consume("source",()->new ConsumeCommand(challengeId,commandKey,OneUseSecret.parse(token),body.toCharArray()),UUID.randomUUID());}
    @Test void exactCommittedResetReplaySurvivesOwnGenerationLaterPasswordChangeAndExpiry(){
        consume(key,password);assertThat(account.get().credentialVersion()).isEqualTo(2);
        account.set(new AccountSnapshot(accountId,actorId,"person","ACTIVE",8,"a later password"));now.set(now.get().plusSeconds(7200));
        consume(key,password);
        verify(encoder,times(1)).encode(any());verify(store,times(1)).resetPassword(any(),any(),anyString(),any());
        verify(store,times(1)).terminal(any(),any(),eq(State.CONSUMED),any());verify(audit,times(1)).success(any(),any());
        verifyNoInteractions(sessions,delivery,members,authorization);
    }
    @Test void replayWithChangedBodyConflictsAndDifferentKeyCannotConsumeAgain(){
        consume(key,password);
        assertThatThrownBy(()->consume(key,"a different sufficiently long password")).isInstanceOfSatisfying(IdentityLifecycleException.class,e->assertThat(e.reason()).isEqualTo(IdentityLifecycleException.Reason.CONFLICT));
        when(store.readReceipt(any(),any(),any(),any())).thenReturn(Optional.empty());when(store.receipt(any(),any(),any(),any(),any())).thenReturn(Optional.empty());
        assertThatThrownBy(()->consume(UUID.randomUUID(),password)).isInstanceOf(IdentityLifecycleException.class);
        verify(encoder,times(1)).encode(any());verify(store,times(1)).resetPassword(any(),any(),anyString(),any());
    }
    @Test void revokedOrReplacedContactDeniesReplayWithoutUndoingOriginalEffect(){
        consume(key,password);contact.set(new RecoveryBinding(UUID.randomUUID(),accountId,destination,1,true));
        assertThatThrownBy(()->consume(key,password)).isInstanceOf(IdentityLifecycleException.class);
        assertThat(account.get().credentialVersion()).isEqualTo(2);assertThat(completed.get()).isNotNull();
        verify(audit,times(1)).success(any(),any());verify(audit).failure(any());
    }
    @Test void disabledAccountDeniesCommittedAcknowledgment(){
        consume(key,password);account.set(new AccountSnapshot(accountId,actorId,"person","DISABLED",2,"new-hash"));
        assertThatThrownBy(()->consume(key,password)).isInstanceOf(IdentityLifecycleException.class);verify(store,times(1)).resetPassword(any(),any(),anyString(),any());
    }
    @Test void unavailableProviderStopsResetAdmissionBeforeAccountLookup(){
        when(delivery.readiness()).thenThrow(IdentityLifecycleException.unavailable());
        assertThatThrownBy(()->service.requestReset("source",()->"person",UUID.randomUUID())).isInstanceOf(IdentityLifecycleException.class);
        verify(store,never()).readLocalAccount(anyString());verify(store,never()).issue(any(),any(),anyString(),any());
    }
    @Test void liveUnknownResetCoalescesWithoutNewTokenOrDelivery(){
        var ready=new CredentialCapabilityDeliveryPort.Readiness("provider-1","template-1",policy.getConsumeOrigin());when(delivery.readiness()).thenReturn(ready);when(delivery.isCurrent(ready)).thenReturn(true);
        when(store.readLocalAccount("person")).thenReturn(Optional.of(account.get()));when(store.liveReset(any(),eq(accountId),any())).thenReturn(Optional.of(challenge.get()));
        service.requestReset("source",()->"person",UUID.randomUUID());
        verify(store,never()).issue(any(),any(),anyString(),any());verify(delivery,never()).dispatch(any(),any());
    }
    @Test void boundedSourceAdmissionRunsBeforeLazyParsingAndDurableWrites(){
        // A separate service with one source request/minute proves parser order without timing assumptions.
        policy.setRequestsPerSourceMinute(1);try(var limited=new IdentityLifecycleService(store,writer,authorization,members,audit,sessions,delivery,policy,encoder,clock)) {
            AtomicInteger parsed=new AtomicInteger();Supplier<String> parser=()->{parsed.incrementAndGet();throw invalid();};
            assertThatThrownBy(()->limited.requestReset("bounded-source",parser,UUID.randomUUID())).isInstanceOf(IdentityLifecycleException.class);
            assertThatThrownBy(()->limited.requestReset("bounded-source",parser,UUID.randomUUID())).isInstanceOfSatisfying(IdentityLifecycleException.class,e->assertThat(e.reason()).isEqualTo(IdentityLifecycleException.Reason.RATE_LIMITED));
            assertThat(parsed).hasValue(1);verifyNoInteractions(store);
        }
    }
    @Test void deadlineObserverCannotDeclareAnotherNodesLiveAttemptDead(){
        DeliveryAttempt future=new DeliveryAttempt(UUID.randomUUID(),challengeId,"provider-1","template-1",DeliveryState.DISPATCHING,2,UUID.randomUUID(),now.get().plusSeconds(100),now.get().minusSeconds(1),now.get());
        when(store.expiredDeliveries(32)).thenReturn(List.of(future));when(store.delivery(any(),eq(challengeId))).thenReturn(Optional.of(future));
        service.observeExpiredDeliveries();verify(store,never()).deliveryTransition(any(),any(),any(),any());
        now.set(now.get().plusSeconds(101));service.observeExpiredDeliveries();
        verify(store).deliveryTransition(any(),eq(future),eq(DeliveryState.UNKNOWN),eq(now.get()));verifyNoInteractions(delivery);
    }
    @Test void terminalUnknownIsNeverUpgradedByTheObserver(){
        DeliveryAttempt terminal=new DeliveryAttempt(UUID.randomUUID(),challengeId,"provider-1","template-1",DeliveryState.UNKNOWN,3,UUID.randomUUID(),now.get().minusSeconds(1),now.get().minusSeconds(100),now.get());
        when(store.expiredDeliveries(32)).thenReturn(List.of(terminal));when(store.delivery(any(),eq(challengeId))).thenReturn(Optional.of(terminal));
        service.observeExpiredDeliveries();verify(store,never()).deliveryTransition(any(),any(),any(),any());
    }
    @Test void lateFakeProviderAcceptanceCannotUpgradeAnObservedUnknownAttempt()throws Exception {
        var ready=new CredentialCapabilityDeliveryPort.Readiness("provider-1","template-1",policy.getConsumeOrigin());
        when(delivery.readiness()).thenReturn(ready);when(delivery.isCurrent(ready)).thenReturn(true);
        when(store.readLocalAccount("person")).thenReturn(Optional.of(account.get()));
        AtomicReference<DeliveryAttempt> attempt=new AtomicReference<>();
        when(store.readChallenge(any())).thenAnswer(call->Optional.of(challenge.get()));
        when(store.lockChallenge(any(),any())).thenAnswer(call->challenge.get());
        when(store.delivery(any(),any())).thenAnswer(call->Optional.ofNullable(attempt.get()));
        when(store.expiredDeliveries(32)).thenAnswer(call->List.of(attempt.get()));
        doAnswer(call->{challenge.set(call.getArgument(1));attempt.set(call.getArgument(3));return null;}).when(store).issue(any(),any(),anyString(),any());
        doAnswer(call->{DeliveryAttempt old=call.getArgument(1);attempt.set(new DeliveryAttempt(old.id(),old.challengeId(),old.providerGeneration(),old.templateVersion(),call.getArgument(2),old.version()+1,old.originInstanceId(),old.deliveryDeadlineAt(),old.createdAt(),call.getArgument(3)));return null;}).when(store).deliveryTransition(any(),any(),any(),any());
        var started=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        when(delivery.dispatch(any(),any())).thenAnswer(call->{started.countDown();if(!release.await(2,java.util.concurrent.TimeUnit.SECONDS))throw IdentityLifecycleException.unavailable();return DeliveryOutcome.ACCEPTED;});
        try {
            service.requestReset("source",()->"person",UUID.randomUUID());assertThat(started.await(2,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            now.set(attempt.get().deliveryDeadlineAt().plusSeconds(1));service.observeExpiredDeliveries();
            assertThat(attempt.get().state()).isEqualTo(DeliveryState.UNKNOWN);release.countDown();
            verify(store,timeout(2000).atLeast(3)).delivery(any(),any());
            verify(store,never()).deliveryTransition(any(),any(),eq(DeliveryState.ACCEPTED),any());
            assertThat(attempt.get().state()).isEqualTo(DeliveryState.UNKNOWN);
        }finally{release.countDown();}
    }
    @Test void newAccountCommittedReplayDoesNotTestNowExistingLoginOrRehash(){
        UUID tenant=UUID.randomUUID(),member=UUID.randomUUID();IssuerBinding issuer=new IssuerBinding(UUID.randomUUID(),UUID.randomUUID(),tenant,UUID.randomUUID(),1,0,0);
        String digest;try(var parsed=OneUseSecret.parse(token)){digest=parsed.digest();}
        Receipt r=new Receipt(UUID.randomUUID(),Operation.CONSUME_INVITATION,OwnerKind.CAPABILITY,challengeId,key,challengeId,"retained-1",
                policy.crypto().prepare(Operation.CONSUME_INVITATION,challengeId,Purpose.NEW_ACCOUNT,null,tenant,password.toCharArray()).activeFingerprint(),
                accountId,actorId,member,contactId,null,1,null,null,now.get());completed.set(r);
        challenge.set(new ChallengeSnapshot(challengeId,Purpose.NEW_ACCOUNT,null,tenant,issuer,"person","Person",destination,null,null,null,digest,State.CONSUMED,2,now.get().minusSeconds(7200),now.get().minusSeconds(3600),r.id()));
        when(members.find(any(),eq(accountId))).thenReturn(Optional.of(new InvitationMembershipPort.Membership(member,accountId,"ACTIVE",0)));
        account.set(new AccountSnapshot(accountId,actorId,"person","ACTIVE",5,"later password"));consume(key,password);
        verify(authorization).requireIssuerStillAuthorized(any(),eq(issuer),eq(IdentityLifecycleAuthorization.Action.CREATE));
        verify(store,never()).loginExists(any(),anyString());verifyNoInteractions(encoder);verify(audit,never()).success(any(),any());
    }
    @Test void newOnboardingCreatesExactlyOneIdentityContactAndPlainMemberBeforeReceiptAndConsume(){
        UUID tenant=UUID.randomUUID();IssuerBinding issuer=new IssuerBinding(UUID.randomUUID(),UUID.randomUUID(),tenant,UUID.randomUUID(),1,0,0);
        String digest;try(var parsed=OneUseSecret.parse(token)){digest=parsed.digest();}
        challenge.set(new ChallengeSnapshot(challengeId,Purpose.NEW_ACCOUNT,null,tenant,issuer,"person","Person",destination,null,null,null,digest,State.ISSUED,1,now.get(),now.get().plusSeconds(900),null));
        when(store.createAccount(any(),any(),any(),eq("person"),anyString(),any())).thenAnswer(call->new AccountSnapshot(call.getArgument(1),call.getArgument(2),"person","ACTIVE",1,call.getArgument(4)));
        consume(key,password);
        var order=inOrder(store,members,audit);
        order.verify(store).createAccount(any(),any(),any(),eq("person"),anyString(),any());
        order.verify(store).createRecovery(any(),any(),eq(challengeId),any());
        order.verify(members).create(any(),any(),any(),eq("Person"));
        order.verify(store).receipt(any(),any(Receipt.class));order.verify(store).terminal(any(),any(),eq(State.CONSUMED),any());order.verify(audit).success(any(),any());
        assertThat(completed.get().resultAccountId()).isNotNull();assertThat(completed.get().resultActorId()).isNotNull();assertThat(completed.get().resultContactId()).isNotNull();assertThat(completed.get().resultMemberId()).isNotNull();
        verifyNoInteractions(sessions,delivery);
    }
}
