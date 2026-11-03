package com.mightyoung.softwarefactory.identity;

import org.junit.jupiter.api.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import java.net.URI;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Supplier;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static com.mightyoung.softwarefactory.identity.IdentityLifecycleContracts.*;

/** Synthetic selector/queue tests; no external provider or database is used. */
class IdentityLifecycleResetPrivacyTest {
    final IdentityLifecycleStore store=mock(IdentityLifecycleStore.class);
    final IdentityLifecycleSecurityTransaction writer=mock(IdentityLifecycleSecurityTransaction.class);
    final CredentialCapabilityDeliveryPort delivery=mock(CredentialCapabilityDeliveryPort.class);
    final IdentityLifecycleProperties policy=new IdentityLifecycleProperties();
    final Instant now=Instant.parse("2026-11-03T12:00:00Z");
    final Map<String,AccountSnapshot> accounts=new ConcurrentHashMap<>();
    final Map<UUID,ChallengeSnapshot> challenges=new ConcurrentHashMap<>();
    final Map<UUID,DeliveryAttempt> attempts=new ConcurrentHashMap<>();
    final CountDownLatch release=new CountDownLatch(1),started=new CountDownLatch(2),unknown=new CountDownLatch(1);
    final Object globalGuard=new Object();
    IdentityLifecycleService service;
    @BeforeEach void setup(){
        String key=Base64.getEncoder().encodeToString(new byte[32]);policy.setFingerprintKeyId("retained");policy.setFingerprintKeys(Map.of("retained",key));policy.setRateKey(key);policy.setConsumeOrigin(URI.create("https://identity.example.invalid"));
        var ready=new CredentialCapabilityDeliveryPort.Readiness("provider-1","template-1",policy.getConsumeOrigin());when(delivery.readiness()).thenReturn(ready);when(delivery.isCurrent(ready)).thenReturn(true);
        when(writer.execute(any(),any())).thenAnswer(call->{synchronized(globalGuard){return ((Supplier<?>)call.getArgument(1)).get();}});
        when(store.databaseNow(any())).thenReturn(now);when(store.reserveRate(any(),anyString(),any(),anyInt(),anyInt())).thenReturn(IdentityLifecycleStore.RateDecision.ADMITTED);
        when(store.readLocalAccount(anyString())).thenAnswer(call->Optional.ofNullable(accounts.get(call.getArgument(0))));
        when(store.localAccount(any(),any())).thenAnswer(call->accounts.values().stream().filter(account->account.accountId().equals(call.getArgument(1))).findFirst());
        when(store.recovery(any(),any())).thenAnswer(call->Optional.of(new RecoveryBinding(call.getArgument(1),call.getArgument(1),new Destination("EMAIL","verified@example.invalid"),1,true)));
        when(store.readChallenge(any())).thenAnswer(call->Optional.ofNullable(challenges.get(call.getArgument(0))));when(store.lockChallenge(any(),any())).thenAnswer(call->challenges.get(call.getArgument(1)));
        when(store.delivery(any(),any())).thenAnswer(call->Optional.ofNullable(attempts.get(call.getArgument(1))));
        when(store.activeHuman(any(),any(),any())).thenReturn(true);
        doAnswer(call->{ChallengeSnapshot c=call.getArgument(1);challenges.put(c.id(),c);attempts.put(c.id(),call.getArgument(3));return null;}).when(store).issue(any(),any(),anyString(),any());
        doAnswer(call->{DeliveryAttempt old=call.getArgument(1);DeliveryState next=call.getArgument(2);attempts.put(old.challengeId(),new DeliveryAttempt(old.id(),old.challengeId(),old.providerGeneration(),old.templateVersion(),next,old.version()+1,old.originInstanceId(),old.deliveryDeadlineAt(),old.createdAt(),call.getArgument(3)));if(next==DeliveryState.UNKNOWN)unknown.countDown();return null;}).when(store).deliveryTransition(any(),any(),any(),any());
        when(delivery.dispatch(any(),any())).thenAnswer(call->{started.countDown();if(!release.await(10,TimeUnit.SECONDS))throw IdentityLifecycleException.unavailable();return DeliveryOutcome.UNKNOWN;});
        service=new IdentityLifecycleService(store,writer,mock(IdentityLifecycleAuthorization.class),mock(InvitationMembershipPort.class),mock(IdentityLifecycleAudit.class),mock(IdentityLifecycleSession.class),delivery,policy,mock(PasswordEncoder.class),Clock.fixed(now,ZoneOffset.UTC));
    }
    @AfterEach void close(){release.countDown();service.close();}
    private AccountSnapshot account(String login,String status){AccountSnapshot a=new AccountSnapshot(UUID.randomUUID(),UUID.randomUUID(),login,status,1,"hash");accounts.put(login,a);return a;}
    private void reset(String source,String login){service.requestReset(source,()->login,UUID.randomUUID());}
    @Test void selectorBudgetDenialIsGenericAcceptanceForEligibleDisabledAndUnknownSelectors(){
        account("eligible","ACTIVE");account("disabled","DISABLED");
        when(store.reserveRate(any(),anyString(),any(),anyInt(),anyInt())).thenReturn(IdentityLifecycleStore.RateDecision.SELECTOR_LIMIT);
        for(String login:List.of("eligible","disabled","unknown"))assertThatCode(()->reset("source-"+login,login)).doesNotThrowAnyException();
        verify(store,never()).localAccount(any(),any());verify(store,never()).expireResets(any(),any(),any());verify(store,never()).issue(any(),any(),anyString(),any());verify(delivery,never()).dispatch(any(),any());
    }
    @Test void durableGlobalCapacityFailureIsUniformUnavailableRatherThanSelectorAcceptance(){
        account("eligible","ACTIVE");account("disabled","DISABLED");
        when(store.reserveRate(any(),anyString(),any(),anyInt(),anyInt())).thenReturn(IdentityLifecycleStore.RateDecision.CAPACITY);
        for(String login:List.of("eligible","disabled","unknown"))assertThatThrownBy(()->reset("source-"+login,login)).isInstanceOfSatisfying(IdentityLifecycleException.class,e->assertThat(e.reason()).isEqualTo(IdentityLifecycleException.Reason.UNAVAILABLE));
        verify(store,never()).issue(any(),any(),anyString(),any());
    }
    @Test void oneRealAccountBudgetIsSharedAcrossDifferentSources(){
        AccountSnapshot a=account("person","ACTIVE");Map<String,Integer> counters=new HashMap<>();
        when(store.reserveRate(any(),anyString(),any(),anyInt(),anyInt())).thenAnswer(call->{String selector=call.getArgument(1);int count=counters.merge(selector,1,Integer::sum);return count<=5?IdentityLifecycleStore.RateDecision.ADMITTED:IdentityLifecycleStore.RateDecision.SELECTOR_LIMIT;});
        ChallengeSnapshot live=new ChallengeSnapshot(UUID.randomUUID(),Purpose.PASSWORD_RESET,a.accountId(),null,null,null,null,new Destination("EMAIL","verified@example.invalid"),a.accountId(),1L,1L,"a".repeat(64),State.ISSUED,1,now,now.plusSeconds(900),null);
        when(store.liveReset(any(),eq(a.accountId()),any())).thenReturn(Optional.of(live));
        for(int i=0;i<6;i++){int source=i;assertThatCode(()->reset("source-"+source,"person")).doesNotThrowAnyException();}
        assertThat(counters).hasSize(1);assertThat(counters.values()).containsExactly(6);verify(store,times(5)).localAccount(any(),eq(a.accountId()));verify(store,never()).issue(any(),any(),anyString(),any());
    }
    @Test void unusedUnknownSelectorReservationsAreReleased(){
        for(int i=0;i<36;i++)reset("source-"+i,"unknown-"+i);
        when(store.reserveRate(any(),anyString(),any(),anyInt(),anyInt())).thenReturn(IdentityLifecycleStore.RateDecision.SELECTOR_LIMIT);
        for(int i=0;i<36;i++)reset("limited-source-"+i,"limited-unknown-"+i);
        verify(store,times(72)).readLocalAccount(anyString());verify(delivery,never()).dispatch(any(),any());
    }
    @Test void budgetDatabaseFailureIsUniformUnavailableAcrossSelectors(){
        account("eligible","ACTIVE");account("disabled","DISABLED");
        when(store.reserveRate(any(),anyString(),any(),anyInt(),anyInt())).thenThrow(new IllegalStateException("private SQL selector failure"));
        for(String login:List.of("eligible","disabled","unknown"))assertThatThrownBy(()->reset("source-"+login,login)).isInstanceOfSatisfying(IdentityLifecycleException.class,e->assertThat(e.reason()).isEqualTo(IdentityLifecycleException.Reason.UNAVAILABLE));
        verify(store,never()).issue(any(),any(),anyString(),any());
    }
    @Test void saturatedGlobalMemoryQueueRejectsAllSelectorKindsBeforeLookupOrBudget()throws Exception {
        for(int i=0;i<18;i++){String login="eligible-"+i;account(login,"ACTIVE");reset("source-"+i,login);}
        assertThat(started.await(2,TimeUnit.SECONDS)).isTrue();account("disabled","DISABLED");
        when(store.reserveRate(any(),anyString(),any(),anyInt(),anyInt())).thenReturn(IdentityLifecycleStore.RateDecision.SELECTOR_LIMIT);
        for(String login:List.of("eligible-0","disabled","unknown","limited-unknown"))assertThatThrownBy(()->reset("full-"+login,login)).isInstanceOfSatisfying(IdentityLifecycleException.class,e->assertThat(e.reason()).isEqualTo(IdentityLifecycleException.Reason.UNAVAILABLE));
        verify(store,times(18)).readLocalAccount(anyString());verify(store,times(18)).reserveRate(any(),anyString(),any(),anyInt(),anyInt());verify(store,times(18)).issue(any(),any(),anyString(),any());
    }
    @Test void unexpectedSelectorReadAndObserverFailuresRemainVisibleWithoutLeakingRawDiagnostics(){
        var logger=(ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(IdentityLifecycleDiagnostics.class);
        var appender=new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();appender.start();logger.addAppender(appender);
        try {
            when(store.readLocalAccount("person")).thenThrow(new IllegalStateException("SELECT secret_hash password=private recipient@example.invalid"));
            assertThatThrownBy(()->reset("source","person")).isInstanceOfSatisfying(IdentityLifecycleException.class,e->assertThat(e.reason()).isEqualTo(IdentityLifecycleException.Reason.UNAVAILABLE));
            when(store.expiredDeliveries(32)).thenThrow(new IllegalStateException("observer private-token SQL"));
            assertThatThrownBy(service::observeExpiredDeliveries).isInstanceOf(IdentityLifecycleException.class);
            assertThat(appender.list).extracting(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage).anyMatch(message->message.contains("REQUEST")).anyMatch(message->message.contains("DEADLINE_OBSERVATION"));
            assertThat(appender.list).allSatisfy(event->{assertThat(event.getFormattedMessage()).doesNotContain("SELECT","secret_hash","private","recipient@example.invalid","SQL");assertThat(event.getThrowableProxy()).isNull();});
        }finally{logger.detachAppender(appender);appender.stop();}
    }
    @Test void fakeProviderFailureIsObservableAndStillCheckpointsUnknownWithoutSecretLogs()throws Exception {
        var logger=(ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(IdentityLifecycleDiagnostics.class);
        var appender=new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();appender.start();logger.addAppender(appender);
        try {
            doThrow(new IllegalStateException("provider token=private raw@example.invalid")).when(delivery).dispatch(any(),any());account("person","ACTIVE");
            reset("source","person");assertThat(unknown.await(2,TimeUnit.SECONDS)).isTrue();
            assertThat(attempts.values()).allSatisfy(attempt->assertThat(attempt.state()).isEqualTo(DeliveryState.UNKNOWN));
            assertThat(appender.list).extracting(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage).anyMatch(message->message.contains("PROVIDER"));
            assertThat(appender.list).allSatisfy(event->{assertThat(event.getFormattedMessage()).doesNotContain("private","raw@example.invalid","token=");assertThat(event.getThrowableProxy()).isNull();});
        }finally{logger.detachAppender(appender);appender.stop();}
    }

}
