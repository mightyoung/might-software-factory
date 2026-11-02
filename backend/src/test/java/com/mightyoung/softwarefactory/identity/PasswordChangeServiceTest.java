package com.mightyoung.softwarefactory.identity;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PasswordChangeServiceTest {
    private final CurrentTenantContext context=new CurrentTenantContext(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),1,1,4);
    private final SessionContextResolver contexts=mock(SessionContextResolver.class);
    private final CurrentSessionService sessions=mock(CurrentSessionService.class);
    private final PasswordCredentialStore store=mock(PasswordCredentialStore.class);
    private final PasswordEncoder encoder=mock(PasswordEncoder.class);
    private final IdentitySecurityAudit audit=mock(IdentitySecurityAudit.class);
    private final IdentitySecurityEvents events=mock(IdentitySecurityEvents.class);
    private final MutableClock clock=new MutableClock();
    private final Transaction transaction=new Transaction();
    private final PasswordCredentialState previous=new PasswordCredentialState(4,"{bcrypt}old-secret-hash");
    private final UUID trace=UUID.randomUUID();
    private PasswordChangeService service;
    @BeforeEach void setup() {
        when(contexts.currentTenant()).thenReturn(context);
        when(store.findOwn(context)).thenReturn(Optional.of(previous));
        when(encoder.matches(any(),anyString())).thenAnswer(call->{assertThat(transaction.active).isFalse();return true;});
        when(encoder.encode(any())).thenAnswer(call->{assertThat(transaction.active).isFalse();return "{bcrypt}new-secret-hash";});
        doAnswer(call->{assertThat(transaction.active).isTrue();return null;}).when(store).replace(any(),any(),anyString(),any());
        doAnswer(call->{assertThat(transaction.active).isFalse();return null;}).when(events).passwordChangeFailed(any(),any(),any(),any());
        service=new PasswordChangeService(contexts,sessions,store,encoder,transaction,audit,events,
                new LoginAttemptLimiter(LoginAttemptLimiter.Config.defaults(),System::nanoTime),
                new StaticListableBeanFactory(Map.of("clock",clock)).getBeanProvider(Clock.class));
    }
    @Test void expensiveWorkPrecedesGlobalTransactionAndAuditJoinsWrite() {
        doAnswer(call->{assertThat(transaction.active).isTrue();return null;}).when(audit).passwordChanged(any(),anyLong(),anyLong(),any(),any());
        var request=request("old password","new password 123456");
        service.change(request,trace);
        verify(store).replace(eq(context),eq(previous),eq("{bcrypt}new-secret-hash"),any());
        verify(audit).passwordChanged(eq(context),eq(4L),eq(5L),eq(trace),any());
        verify(sessions,times(2)).current();verifyNoInteractions(events);
        assertThat(request.toString()).doesNotContain("old password","new password");
        assertThat(request.currentPassword().toString()).isEqualTo("\0".repeat("old password".length()));
    }
    @Test void contextResolutionFailureStillErasesRequestWithoutInventingFailureIdentity() {
        var request=request("old password","new password 123456");
        when(contexts.currentTenant()).thenThrow(new UnauthenticatedSessionException());
        assertThatThrownBy(()->service.change(request,trace)).isInstanceOf(UnauthenticatedSessionException.class);
        assertThat(request.currentPassword().toString()).isEqualTo("\0".repeat("old password".length()));
        assertThat(request.newPassword().toString()).isEqualTo("\0".repeat("new password 123456".length()));
        verifyNoInteractions(events,encoder,store,audit);
    }
    @Test void wrongCurrentPasswordNeverHashesOrStartsWriteAndFailureIsOutsideTransaction() {
        when(encoder.matches(any(),anyString())).thenReturn(false);
        assertFailure(request("incorrect","new password 123456"),PasswordChangeFailureReason.CURRENT_PASSWORD_REJECTED);
        verify(encoder,never()).encode(any());assertThat(transaction.calls).isZero();verifyNoInteractions(audit);
    }
    @Test void malformedShortOversizedAndSameSecretsRejectBeforeBcrypt() {
        for(var input:List.of(request("old","short"),request("old","你".repeat(25)),
                request("old","valid-new-password\uD800"),request("","new password 123456"),request("same password 12345","same password 12345"))) {
            assertFailure(input,PasswordChangeFailureReason.INVALID_NEW_PASSWORD);
        }
        verifyNoInteractions(encoder);assertThat(transaction.calls).isZero();
    }
    @Test void unicodeCodePointsAndRawWhitespaceArePreserved() {
        when(encoder.matches(any(),anyString())).thenAnswer(call->{assertThat(call.<CharSequence>getArgument(0).toString()).isEqualTo(" ");return true;});
        when(encoder.encode(any())).thenAnswer(call->{assertThat(call.<CharSequence>getArgument(0).toString()).isEqualTo("你".repeat(15));return "new-encoded-password-value";});
        service.change(request(" ","你".repeat(15)),trace);
    }
    @Test void preparationOlderThanSixtySecondsAndFutureProofBothRejectInsideTransaction() {
        transaction.before=()->clock.now=clock.now.plusSeconds(61);
        assertFailure(request("old","new password 123456"),PasswordChangeFailureReason.PREPARATION_EXPIRED);
        transaction.before=()->clock.now=clock.now.minusSeconds(1);
        assertFailure(request("old","new password 123456"),PasswordChangeFailureReason.PREPARATION_EXPIRED);
        verify(store,never()).replace(any(),any(),anyString(),any());
    }
    @Test void exactOldHashMustStillMatchAfterLock() {
        when(store.findOwn(context)).thenReturn(Optional.of(previous),Optional.of(new PasswordCredentialState(4,"different-old-hash")));
        assertFailure(request("old","new password 123456"),PasswordChangeFailureReason.STALE_IDENTITY);
        verify(store,never()).replace(any(),any(),anyString(),any());
    }
    @Test void completeTrustedContextMustRemainIdentical() {
        var other=new CurrentTenantContext(context.actorId(),context.accountId(),context.tenantId(),context.memberId(),2,1,4);
        when(contexts.currentTenant()).thenReturn(context,context,other);
        assertFailure(request("old","new password 123456"),PasswordChangeFailureReason.STALE_IDENTITY);
    }
    @Test void failedAuditEscapesTransactionAndRecordsIndependentFailure() {
        doThrow(new IllegalStateException("do not expose this detail")).when(audit).passwordChanged(any(),anyLong(),anyLong(),any(),any());
        assertFailure(request("old","new password 123456"),PasswordChangeFailureReason.AUDIT_UNAVAILABLE);
        assertThat(transaction.active).isFalse();
    }
    @Test void callbackSuccessThenCommitFailureIsUnknownRatherThanFalseRollback() {
        transaction.failCommit=true;
        assertFailure(request("old","new password 123456"),PasswordChangeFailureReason.COMMIT_OUTCOME_UNKNOWN);
    }
    @Test void failureRecorderOutageReturnsRedacted503WithoutPretendingPersistence() {
        when(encoder.matches(any(),anyString())).thenReturn(false);
        doThrow(new IllegalStateException("database details")).when(events).passwordChangeFailed(any(),any(),any(),any());
        assertThatThrownBy(()->service.change(request("old","new password 123456"),trace))
                .isInstanceOfSatisfying(PasswordChangeException.class,error->{assertThat(error.status()).isEqualTo(503);assertThat(error.getMessage()).doesNotContain("database","old","password 123456");});
    }
    @Test void accountWideCoolingPrecedesFurtherBcryptWork() {
        when(encoder.matches(any(),anyString())).thenReturn(false);
        for(int i=0;i<5;i++)assertFailure(request("old","new password 123456"),PasswordChangeFailureReason.CURRENT_PASSWORD_REJECTED);
        assertFailure(request("old","new password 123456"),PasswordChangeFailureReason.RATE_LIMITED);
        verify(encoder,times(5)).matches(any(),anyString());
    }
    @Test void twoPreparedAttemptsCanAdvanceOnlyOneGenerationAndProduceOneSuccessAudit() throws Exception {
        var state=new java.util.concurrent.atomic.AtomicReference<>(previous);
        var active=ThreadLocal.withInitial(()->false);
        Object lock=new Object();
        var bothPrepared=new java.util.concurrent.CountDownLatch(2);
        var guarded=new IdentitySecurityTransaction(){
            public <T>T execute(CurrentTenantContext trusted,Supplier<T> work){synchronized(lock){active.set(true);try{return work.get();}finally{active.set(false);}}}
            public void requireActive(CurrentTenantContext trusted){if(!active.get())throw new IllegalStateException();}
        };
        when(store.findOwn(context)).thenAnswer(call->Optional.of(state.get()));
        when(encoder.encode(any())).thenAnswer(call->{
            assertThat(active.get()).isFalse();bothPrepared.countDown();
            if(!bothPrepared.await(3,java.util.concurrent.TimeUnit.SECONDS))throw new IllegalStateException("Preparation barrier timed out");
            return "new-encoded-password-value";
        });
        doAnswer(call->{assertThat(active.get()).isTrue();state.set(new PasswordCredentialState(5,"new-encoded-password-value"));return null;})
                .when(store).replace(any(),any(),anyString(),any());
        doAnswer(call->{assertThat(active.get()).isFalse();return null;}).when(events).passwordChangeFailed(any(),any(),any(),any());
        var concurrent=new PasswordChangeService(contexts,sessions,store,encoder,guarded,audit,events,
                new LoginAttemptLimiter(LoginAttemptLimiter.Config.defaults(),System::nanoTime),
                new StaticListableBeanFactory(Map.of("clock",clock)).getBeanProvider(Clock.class));
        var workers=java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var first=workers.submit(()->catchThrowable(()->concurrent.change(request("old","new password 123456"),UUID.randomUUID())));
            var second=workers.submit(()->catchThrowable(()->concurrent.change(request("old","new password 123456"),UUID.randomUUID())));
            var outcomes=java.util.Arrays.asList(first.get(5,java.util.concurrent.TimeUnit.SECONDS),second.get(5,java.util.concurrent.TimeUnit.SECONDS));
            assertThat(outcomes.stream().filter(java.util.Objects::isNull).count()).isEqualTo(1);
            assertThat(outcomes.stream().filter(java.util.Objects::nonNull)).allSatisfy(failure->assertThat(failure).isInstanceOfSatisfying(PasswordChangeException.class,error->assertThat(error.reason()).isEqualTo(PasswordChangeFailureReason.STALE_IDENTITY)));
            verify(audit,times(1)).passwordChanged(any(),eq(4L),eq(5L),any(),any());
            assertThat(state.get().credentialVersion()).isEqualTo(5);
        } finally {workers.shutdownNow();assertThat(workers.awaitTermination(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();}
    }

    @Test void heavyPasswordWorkIsHardCappedAtEightEvenIfLoginConfigurationAllowsMore() throws Exception {
        var entered=new java.util.concurrent.CountDownLatch(8);
        var release=new java.util.concurrent.CountDownLatch(1);
        when(encoder.encode(any())).thenAnswer(call->{entered.countDown();if(!release.await(3,java.util.concurrent.TimeUnit.SECONDS))throw new IllegalStateException();return "new-encoded-password-value";});
        var serialized=new IdentitySecurityTransaction(){
            public <T>T execute(CurrentTenantContext trusted,Supplier<T> work){synchronized(transaction){transaction.active=true;try{return work.get();}finally{transaction.active=false;}}}
            public void requireActive(CurrentTenantContext trusted){}
        };
        var broadConfig=new LoginAttemptLimiter.Config(600,600,Duration.ofMinutes(1),100,Duration.ofMinutes(5),Duration.ofSeconds(30),16,100,100);
        var bounded=new PasswordChangeService(contexts,sessions,store,encoder,serialized,audit,events,
                new LoginAttemptLimiter(broadConfig,System::nanoTime),new StaticListableBeanFactory(Map.of("clock",clock)).getBeanProvider(Clock.class));
        var workers=java.util.concurrent.Executors.newFixedThreadPool(8);
        try {
            var futures=new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for(int i=0;i<8;i++)futures.add(workers.submit(()->bounded.change(request("old","new password 123456"),UUID.randomUUID())));
            assertThat(entered.await(3,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(()->bounded.change(request("old","new password 123456"),trace))
                    .isInstanceOfSatisfying(PasswordChangeException.class,error->assertThat(error.reason()).isEqualTo(PasswordChangeFailureReason.RATE_LIMITED));
            verify(encoder,times(8)).encode(any());
            release.countDown();for(var future:futures)future.get(5,java.util.concurrent.TimeUnit.SECONDS);
        } finally {release.countDown();workers.shutdownNow();assertThat(workers.awaitTermination(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();}
    }

    @Test void exhaustedSourceBudgetSkipsLazyParserKdfAndFailureInsert() {
        var limiter=limitedAttempts(1,8);
        limiter.admitSource("password-change:"+context.accountId());
        var bounded=withLimiter(limiter);
        var parsed=new java.util.concurrent.atomic.AtomicInteger();
        for(int i=0;i<10;i++)assertThatThrownBy(()->bounded.change(()->{parsed.incrementAndGet();throw PasswordChangeRequestParser.invalid();},trace))
                .isInstanceOfSatisfying(PasswordChangeException.class,error->assertThat(error.status()).isEqualTo(429));
        assertThat(parsed).hasValue(0);verifyNoInteractions(events,encoder,store,audit);
    }
    @Test void exhaustedReservationSkipsLazyParserAndDoesNotPersistRejections() {
        var limiter=limitedAttempts(100,1);var bounded=withLimiter(limiter);
        try(var reservation=limiter.reserve("password-change:"+context.accountId(),context.accountId().toString())) {
            assertThatThrownBy(()->bounded.change(()->{throw new AssertionError("Parser must not execute");},trace))
                    .isInstanceOfSatisfying(PasswordChangeException.class,error->assertThat(error.status()).isEqualTo(429));
        }
        verifyNoInteractions(events,encoder,store,audit);
    }
    @Test void admittedMalformedBodiesProduceOnlyTheBoundedIndependentEvents() {
        var bounded=withLimiter(limitedAttempts(2,8));var parsed=new java.util.concurrent.atomic.AtomicInteger();
        for(int i=0;i<12;i++)assertThatThrownBy(()->bounded.change(()->{parsed.incrementAndGet();throw PasswordChangeRequestParser.invalid();},trace))
                .isInstanceOf(PasswordChangeException.class);
        assertThat(parsed).hasValue(2);
        verify(events,times(2)).passwordChangeFailed(eq(context),eq(PasswordChangeFailureReason.INVALID_NEW_PASSWORD),eq(trace),any());
        verifyNoMoreInteractions(events);verifyNoInteractions(encoder,store,audit);
    }
    private LoginAttemptLimiter limitedAttempts(int sourceAttempts,int concurrency) {
        return new LoginAttemptLimiter(new LoginAttemptLimiter.Config(600,sourceAttempts,Duration.ofMinutes(1),100,
                Duration.ofMinutes(5),Duration.ofSeconds(30),concurrency,100,100),System::nanoTime);
    }
    private PasswordChangeService withLimiter(LoginAttemptLimiter limiter) {
        return new PasswordChangeService(contexts,sessions,store,encoder,transaction,audit,events,limiter,
                new StaticListableBeanFactory(Map.of("clock",clock)).getBeanProvider(Clock.class));
    }

    private void assertFailure(PasswordChangeRequest request,PasswordChangeFailureReason reason) {
        assertThatThrownBy(()->service.change(request,trace)).isInstanceOfSatisfying(PasswordChangeException.class,error->assertThat(error.reason()).isEqualTo(reason));
        if(reason!=PasswordChangeFailureReason.RATE_LIMITED)
            verify(events,atLeastOnce()).passwordChangeFailed(eq(context),eq(reason),eq(trace),any());
    }
    private static PasswordChangeRequest request(String old,String next){return new PasswordChangeRequest(old.toCharArray(),next.toCharArray());}
    private static final class MutableClock extends Clock {
        Instant now=Instant.parse("2026-11-02T00:00:00Z");public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}public Instant instant(){return now;}
    }
    private static final class Transaction implements IdentitySecurityTransaction {
        boolean active,failCommit;int calls;Runnable before=()->{};
        public <T>T execute(CurrentTenantContext context,Supplier<T> work){calls++;before.run();T result;active=true;try{result=work.get();}finally{active=false;}if(failCommit)throw new IllegalStateException("commit transport unknown");return result;}
        public void requireActive(CurrentTenantContext context){if(!active)throw new IllegalStateException();}
    }
}
