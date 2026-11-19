package com.mightyoung.softwarefactory.identity;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.mightyoung.softwarefactory.identity.ReauthIssuerContracts.*;
import static com.mightyoung.softwarefactory.identity.SessionSensitiveContracts.*;

/** Service ownership/admission fault tests; actual SQL issuer-to-consumer is verified separately. */
class ReauthIssuerServiceTest {
    static final String HASH="{bcrypt}$2b$12$"+"a".repeat(53);
    final SessionSensitiveSecurityTransaction boundaries=mock(SessionSensitiveSecurityTransaction.class);
    final SessionSensitiveSecurityTransaction.GuardedBoundary boundary=()->Instant.parse("2026-11-07T00:00:00Z");
    final ReauthIssuerStore store=mock(ReauthIssuerStore.class);
    final SessionSensitiveAudit audit=mock(SessionSensitiveAudit.class);
    final PasswordEncoder encoder=mock(PasswordEncoder.class);
    final AtomicReference<IdentityLifecycleContracts.OneUseSecret> material=new AtomicReference<>();
    final javax.sql.DataSource dataSource=mock(javax.sql.DataSource.class);
    final java.sql.Connection connection=mock(java.sql.Connection.class);
    final AtomicReference<SessionSensitiveSecurityTransaction.Scope> activeScope=new AtomicReference<>();
    org.mockito.MockedStatic<IdentityLifecycleContracts.OneUseSecret> generator;
    final SessionHandle source=new SessionHandle(UUID.randomUUID());
    ReauthIssuerService service;
    @BeforeEach void prepare(){
        material.set(IdentityLifecycleContracts.OneUseSecret.parse("A".repeat(43)));
        generator=mockStatic(IdentityLifecycleContracts.OneUseSecret.class);
        generator.when(()->IdentityLifecycleContracts.OneUseSecret.generate(any(SecureRandom.class))).thenReturn(material.get());
        when(store.readCredential(any())).thenReturn(Optional.of(new PasswordCredentialState(4,HASH)));
        when(encoder.matches(any(),eq(HASH))).thenReturn(true);
        doAnswer(call->transaction(call.getArgument(0),()->call.<Function<SessionSensitiveSecurityTransaction.GuardedBoundary,Object>>getArgument(1).apply(boundary)))
                .when(boundaries).execute(any(),any());
        doAnswer(call->{
            assertThat(call.getArgument(0,SessionSensitiveSecurityTransaction.Scope.class)).isSameAs(activeScope.get());
            assertThat(call.getArgument(1,Object.class)).isSameAs(boundary);assertThat(call.getArgument(2,Object.class)).isSameAs(dataSource);
            assertThat(call.getArgument(3,Object.class)).isSameAs(connection);
            assertThat(((ConnectionHolder)TransactionSynchronizationManager.getResource(dataSource)).getConnection()).isSameAs(connection);return null;
        }).when(boundaries).requireGuarded(any(),any(),any(),any());
        when(store.issue(any(),any(),any())).thenAnswer(call->{
            var prepared=call.getArgument(2,ReauthIssuerService.VerifiedPreparation.class);prepared.claimFor(service,call.getArgument(0),boundary);
            var result=new ReauthIssuerStore.Stored(new Metadata(prepared.proofId(),prepared.receiptId(),boundary.observedAt().plusSeconds(300)),
                    source,prepared.eventId(),boundary.observedAt(),true);
            prepared.completeFor(service,call.getArgument(0),boundary,result);return result;
        });
        service=service(limiter(),limiter());
    }
    @AfterEach void cleanup(){SecurityContextHolder.clearContext();if(generator!=null)generator.close();if(material.get()!=null)material.get().close();}
    LoginAttemptLimiter limiter(){return new LoginAttemptLimiter(LoginAttemptLimiter.Config.defaults(),System::nanoTime);}
    ReauthIssuerService service(LoginAttemptLimiter account,LoginAttemptLimiter network){
        return new ReauthIssuerService(new SessionSensitiveRequests(new SessionContextResolver(),Duration.ofHours(12)),dataSource,boundaries,store,audit,encoder,
                account,network,Clock.fixed(boundary.observedAt(),ZoneOffset.UTC),new SecureRandom());
    }
    Object transaction(SessionSensitiveSecurityTransaction.Scope scope,java.util.function.Supplier<Object> body){
        var holder=new ConnectionHolder(connection);TransactionSynchronizationManager.bindResource(dataSource,holder);activeScope.set(scope);
        try{return body.get();}finally{activeScope.set(null);TransactionSynchronizationManager.unbindResource(dataSource);}
    }
    Outcome invoke(String password){return service.issueSessionRevoke(CapturedHumanRequestTest.authenticatedRequest(),UUID.randomUUID(),UUID.randomUUID(),
            new Request(new SessionHandle(UUID.randomUUID()),new ExpectedAuthority(0),password.toCharArray()));}
    @Test void shortLegacyPasswordIsVerifiedAndOnlyConfirmedIssueTransfersMemorySecret(){
        try(var result=invoke("x")){
            assertThat(result).isInstanceOf(Issued.class);
            var secret=((Issued)result).secret();assertThat(secret).isSameAs(material.get());
            String token=secret.use(String::new);assertThat(token).hasSize(43);assertThat(secret.digest()).hasSize(64);
            verify(audit).success(any(),eq(boundary),argThat(event->event.operation()==Operation.ISSUE_REAUTH
                    &&event.target()==null&&event.challengeId()==null&&event.source().equals(source)));
            assertThat(result.toString()).doesNotContain(token);
        }
        assertThatThrownBy(()->material.get().use(String::new)).isInstanceOf(IdentityLifecycleException.class);
    }
    @Test void exactReplayClosesUnusedGeneratedSecretAndNeverAppendsSuccessAgain(){
        doAnswer(call->{var prepared=call.getArgument(2,ReauthIssuerService.VerifiedPreparation.class);prepared.claimFor(service,call.getArgument(0),boundary);
            var result=new ReauthIssuerStore.Stored(new Metadata(UUID.randomUUID(),UUID.randomUUID(),boundary.observedAt().minusSeconds(1)),
                source,UUID.randomUUID(),boundary.observedAt().minusSeconds(301),false);prepared.completeFor(service,call.getArgument(0),boundary,result);return result;})
                .when(store).issue(any(),any(),any());
        var replay=(Replay)invoke("short");assertThat(replay.tokenDelivery()).isEqualTo("UNAVAILABLE_ON_REPLAY");
        assertThatThrownBy(()->material.get().use(String::new)).isInstanceOf(IdentityLifecycleException.class);
        verify(audit,never()).success(any(),any(),any());
    }
    @ParameterizedTest @ValueSource(strings={"commit","cleanup","error"})
    void bodySuccessFollowedByTransactionFailureCannotExposeOrKeepTheToken(String fault){
        doAnswer(call->transaction(call.getArgument(0),()->{call.<Function<SessionSensitiveSecurityTransaction.GuardedBoundary,Object>>getArgument(1).apply(boundary);
            if(fault.equals("error"))throw new AssertionError("private driver sentinel");throw new IllegalStateException("private driver sentinel");}))
                .when(boundaries).execute(any(),any());
        assertThatThrownBy(()->invoke("short")).isInstanceOf(SessionSensitiveException.class).hasMessage("UNAVAILABLE").hasNoCause();
        assertThatThrownBy(()->material.get().use(String::new)).isInstanceOf(IdentityLifecycleException.class);
        verify(boundaries,times(1)).execute(any(),any());
        verify(audit).failure(argThat(event->event.result().equals("UNKNOWN")));
    }
    @ParameterizedTest @ValueSource(strings={"{bcrypt}$2b$11$","{bcrypt}$2b$17$","{noop}","malformed"})
    void unsupportedStoredHashNeverRunsTheEncoderOrWriter(String prefix){
        when(store.readCredential(any())).thenReturn(Optional.of(new PasswordCredentialState(4,prefix+"a".repeat(53))));
        assertThatThrownBy(()->invoke("short")).isInstanceOf(SessionSensitiveException.class).hasMessage("REJECTED");
        verifyNoInteractions(encoder);verify(boundaries,never()).execute(any(),any());
    }
    @ParameterizedTest @ValueSource(strings={"ééééééééééééééééééééééééééééééééééééé", "\uD800"})
    void malformedOrOver72Utf8ByteCurrentPasswordsNeverReadCredentials(String password){
        assertThatThrownBy(()->invoke(password)).isInstanceOf(SessionSensitiveException.class).hasMessage("REJECTED");
        verify(store,never()).readCredential(any());verify(store,never()).issue(any(),any(),any());
        verifyNoInteractions(encoder);verify(boundaries,never()).execute(any(),any());
    }
    @Test void wrongPasswordIsRejectedBeforeEnteringTheWriter(){
        when(encoder.matches(any(),any())).thenReturn(false);
        assertThatThrownBy(()->invoke("wrong")).isInstanceOf(SessionSensitiveException.class).hasMessage("REJECTED");
        verify(boundaries,never()).execute(any(),any());
    }
    @Test void accountAndNetworkBudgetsCannotBeTheSameLimiter(){
        var same=limiter();assertThatThrownBy(()->service(same,same)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void committedIssuanceCannotDeliverItsTokenAfterTheCapturedSessionChanges(){
        var request=CapturedHumanRequestTest.authenticatedRequest();
        doAnswer(call->{
            Object committed=transaction(call.getArgument(0),()->call.<Function<SessionSensitiveSecurityTransaction.GuardedBoundary,Object>>getArgument(1).apply(boundary));
            request.changeSessionId(); // The writer returned successfully, but delivery now has a different capture.
            return committed;
        }).when(boundaries).execute(any(),any());
        assertThatThrownBy(()->service.issueSessionRevoke(request,UUID.randomUUID(),UUID.randomUUID(),
                new Request(new SessionHandle(UUID.randomUUID()),new ExpectedAuthority(0),"short".toCharArray())))
                .isInstanceOf(SessionSensitiveException.class).hasMessage("UNAVAILABLE").hasNoCause();
        assertThatThrownBy(()->material.get().use(String::new)).isInstanceOf(IdentityLifecycleException.class);
        verify(audit).success(any(),eq(boundary),argThat(event->event.operation()==Operation.ISSUE_REAUTH));
        verify(audit).failure(argThat(event->event.result().equals("UNKNOWN")));
        verify(boundaries,times(1)).execute(any(),any());
    }
    @Test void changingSocketPeerCannotSplitTheStableAccountAttemptBudget(){
        var config=new LoginAttemptLimiter.Config(100,1,Duration.ofMinutes(1),5,Duration.ofMinutes(5),Duration.ofSeconds(30),8,32,32);
        service=service(new LoginAttemptLimiter(config,System::nanoTime),limiter());
        var request=CapturedHumanRequestTest.authenticatedRequest();request.setRemoteAddr("192.0.2.1");
        try(var first=service.issueSessionRevoke(request,UUID.randomUUID(),UUID.randomUUID(),new Request(new SessionHandle(UUID.randomUUID()),new ExpectedAuthority(0),"x".toCharArray()))){ }
        request.setRemoteAddr("192.0.2.2");
        assertThatThrownBy(()->service.issueSessionRevoke(request,UUID.randomUUID(),UUID.randomUUID(),new Request(new SessionHandle(UUID.randomUUID()),new ExpectedAuthority(0),"x".toCharArray())))
                .isInstanceOf(SessionSensitiveException.class).hasMessage("UNAVAILABLE");
        verify(store,times(1)).readCredential(any());verify(boundaries,times(1)).execute(any(),any());
    }
    @Test void changingAccountsCannotSplitTheNetworkSourceAttemptBudget(){
        var config=new LoginAttemptLimiter.Config(100,1,Duration.ofMinutes(1),5,Duration.ofMinutes(5),Duration.ofSeconds(30),8,32,32);
        service=service(limiter(),new LoginAttemptLimiter(config,System::nanoTime));
        try(var first=invoke("x")){ }
        assertThatThrownBy(()->invoke("x")).isInstanceOf(SessionSensitiveException.class).hasMessage("UNAVAILABLE");
        verify(store,times(1)).readCredential(any());verify(boundaries,times(1)).execute(any(),any());
    }
}
