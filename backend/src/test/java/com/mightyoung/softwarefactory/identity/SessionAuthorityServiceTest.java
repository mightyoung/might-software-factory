package com.mightyoung.softwarefactory.identity;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.security.core.context.SecurityContextHolder;
import javax.sql.DataSource;
import java.sql.Connection;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static com.mightyoung.softwarefactory.identity.SessionSensitiveContracts.*;

class SessionAuthorityServiceTest {
    final SessionSensitiveSecurityTransaction boundaries=mock(SessionSensitiveSecurityTransaction.class);
    final SessionSensitiveSecurityTransaction.GuardedBoundary proof=mock(SessionSensitiveSecurityTransaction.GuardedBoundary.class);
    final SessionSensitiveSecurityTransaction.LogoutBoundary logoutProof=mock(SessionSensitiveSecurityTransaction.LogoutBoundary.class);
    final SessionAuthorityStore store=mock(SessionAuthorityStore.class);
    final SessionSensitiveAudit audit=mock(SessionSensitiveAudit.class);
    final DataSource source=mock(DataSource.class);final Connection connection=mock(Connection.class);
    final List<String> order=new ArrayList<>();
    final Instant now=Instant.parse("2026-11-04T00:01:00Z");
    final SessionHandle handle=new SessionHandle(UUID.randomUUID());
    final UUID event=UUID.randomUUID(),trace=UUID.randomUUID();
    final SessionAuthorityService service=new SessionAuthorityService(new SessionSensitiveRequests(new SessionContextResolver(),Duration.ofHours(12)),boundaries,store,audit,new KnownLogoutRequests(serializer->serializer.setUseBase64Encoding(false)));
    @BeforeEach void setup(){
        when(proof.observedAt()).thenReturn(now);when(logoutProof.observedAt()).thenReturn(now);
        doAnswer(call->{order.add("writer");Object result=call.<Function<SessionSensitiveSecurityTransaction.GuardedBoundary,Object>>getArgument(1).apply(proof);
            order.add("commit");return result;}).when(boundaries).execute(any(),any());
        doAnswer(call->{order.add("writer");Object result=call.<Function<SessionSensitiveSecurityTransaction.LogoutBoundary,Object>>getArgument(1).apply(logoutProof);
            order.add("commit");return result;}).when(boundaries).executeLogout(any(),any());
        when(store.logout(any(),eq(logoutProof),any())).thenAnswer(call->{order.add("fence");return owned(call.getArgument(0),true);});
        doAnswer(call->{order.add("audit");return null;}).when(audit).success(any(),eq(proof),any());
        doAnswer(call->{order.add("audit");return null;}).when(audit).logoutSuccess(any(),eq(logoutProof),any(),any());
        doAnswer(call->{order.add("failure-audit");return null;}).when(audit).failure(any());
    }
    private SessionLogoutResult owned(SessionSensitiveSecurityTransaction.LogoutScope scope,boolean created){
        UUID account=scope.request().claimedAccount();if(account==null)account=UUID.randomUUID();
        var owner=new LogoutOwnership(scope,logoutProof,source,connection,account,scope.request().claimedActor(),handle);
        return new SessionLogoutResult(scope,logoutProof,source,connection,owner,new FenceResult(handle,handle,1,created,event,now));
    }
    private void absence(){
        doAnswer(call->{order.add("absence");return new SessionLogoutResult(call.getArgument(0),logoutProof,source,connection,null,null);})
                .when(store).logout(any(),eq(logoutProof),any());
    }
    @AfterEach void clear(){SecurityContextHolder.clearContext();}
    @Test void logoutCommitsFenceAndAuditBeforeLocalCleanup(){
        var result=service.logout(CapturedHumanRequestTest.authenticatedRequest(),trace,()->order.add("cleanup"));
        assertThat(order).containsExactly("writer","fence","audit","commit","cleanup");
        assertThat(result.revocationState()).isEqualTo(RevocationState.CONFIRMED);assertThat(result.localCleanupComplete()).isTrue();
        verify(boundaries,never()).execute(any(),any());
    }
    @Test void commitFailureIsUnknownAndNeverRetriesWriter(){
        doAnswer(call->{order.add("writer");call.<Function<SessionSensitiveSecurityTransaction.LogoutBoundary,Object>>getArgument(1).apply(logoutProof);
            order.add("commit-failed");throw new IllegalStateException("not safe to expose");}).when(boundaries).executeLogout(any(),any());
        var result=service.logout(CapturedHumanRequestTest.authenticatedRequest(),trace,()->order.add("cleanup"));
        assertThat(result.revocationState()).isEqualTo(RevocationState.UNKNOWN);assertThat(order).containsExactly("writer","fence","audit","commit-failed","failure-audit");
        assertThat(result.localCleanupComplete()).isFalse();verifyFailure(Operation.LOGOUT,"UNKNOWN","UNAVAILABLE");
        verify(boundaries).executeLogout(any(),any());
    }
    @Test void onlySuccessfullyCommittedAuthoritativeAbsenceIsLocalOnly(){
        absence();var result=service.logout(new org.springframework.mock.web.MockHttpServletRequest(),trace,()->order.add("cleanup"));
        assertThat(result.revocationState()).isEqualTo(RevocationState.LOCAL_ONLY);assertThat(result.localCleanupComplete()).isTrue();
        assertThat(order).containsExactly("writer","absence","commit","failure-audit","cleanup");
        verifyFailure(Operation.LOGOUT,"REJECTED","NO_TRUSTED_LINEAGE");
    }
    @Test void exceptionEnumDoesNotSubstituteForCompletedAbsenceLookup(){
        doThrow(new SessionSensitiveException(SessionSensitiveException.Reason.NO_TRUSTED_LINEAGE)).when(store).logout(any(),eq(logoutProof),any());
        var result=service.logout(CapturedHumanRequestTest.authenticatedRequest(),trace,()->order.add("cleanup"));
        assertThat(result.revocationState()).isEqualTo(RevocationState.UNKNOWN);assertThat(result.localCleanupComplete()).isFalse();
        assertThat(order).containsExactly("writer","failure-audit");
    }
    @Test void cleanupFailureDoesNotEraseKnownCommittedFence(){
        var result=service.logout(CapturedHumanRequestTest.authenticatedRequest(),trace,()->{throw new IllegalStateException();});
        assertThat(result.revocationState()).isEqualTo(RevocationState.CONFIRMED);assertThat(result.localCleanupComplete()).isFalse();
    }
    @Test void retainedFenceReplayDoesNotAppendLogoutAudit(){
        doAnswer(call->owned(call.getArgument(0),false)).when(store).logout(any(),eq(logoutProof),any());
        var result=service.logout(CapturedHumanRequestTest.authenticatedRequest(),trace,()->{});
        assertThat(result.revocationState()).isEqualTo(RevocationState.CONFIRMED);verifyNoInteractions(audit);
    }
    @Test void auditFailurePreventsConfirmedOutcome(){
        doThrow(SessionSensitiveException.unavailable()).when(audit).logoutSuccess(any(),any(),any(),any());
        var result=service.logout(CapturedHumanRequestTest.authenticatedRequest(),trace,()->order.add("cleanup"));
        assertThat(result.revocationState()).isEqualTo(RevocationState.UNKNOWN);assertThat(order).containsExactly("writer","fence","failure-audit");
        assertThat(result.localCleanupComplete()).isFalse();verifyFailure(Operation.LOGOUT,"UNKNOWN","UNAVAILABLE");
    }
    @Test void absenceCommitUncertaintyStillPreservesFrameworkProof(){
        absence();doAnswer(call->{call.<Function<SessionSensitiveSecurityTransaction.LogoutBoundary,Object>>getArgument(1).apply(logoutProof);
            throw new IllegalStateException();}).when(boundaries).executeLogout(any(),any());
        var result=service.logout(new org.springframework.mock.web.MockHttpServletRequest(),trace,()->order.add("cleanup"));
        assertThat(result.revocationState()).isEqualTo(RevocationState.UNKNOWN);assertThat(result.localCleanupComplete()).isFalse();
        verifyFailure(Operation.LOGOUT,"UNKNOWN","UNAVAILABLE");
    }
    @Test void independentAbsenceAuditFailureIsUnknownAndIsAttemptedOnlyOnce(){
        absence();doThrow(new IllegalStateException()).when(audit).failure(any());
        var result=service.logout(new org.springframework.mock.web.MockHttpServletRequest(),trace,()->order.add("cleanup"));
        assertThat(result.revocationState()).isEqualTo(RevocationState.UNKNOWN);assertThat(result.localCleanupComplete()).isFalse();
        assertThat(order).containsExactly("writer","absence","commit");verify(audit,times(1)).failure(any());
    }
    @Test void missingLifetimeAndRejectedFreshContextDoNotPreventKnownOwnershipFence(){
        var request=CapturedHumanRequestTest.authenticatedRequest();request.getSession(false).removeAttribute(SessionLifetimePolicy.AUTHENTICATED_AT_ATTRIBUTE);
        SessionContextResolver contexts=mock(SessionContextResolver.class);when(contexts.currentTenant()).thenThrow(SessionSensitiveException.rejected());
        var subject=new SessionAuthorityService(new SessionSensitiveRequests(contexts,Duration.ofHours(12)),boundaries,store,audit,new KnownLogoutRequests(serializer->serializer.setUseBase64Encoding(false)));
        var result=subject.logout(request,trace,()->order.add("cleanup"));
        assertThat(result.revocationState()).isEqualTo(RevocationState.CONFIRMED);verifyNoInteractions(contexts);
        assertThat(order).containsExactly("writer","fence","audit","commit","cleanup");
    }
    @Test void staleOrUnavailableFreshResolverIsNeverUsedToDecideKnownLogoutOwnership(){
        var request=CapturedHumanRequestTest.authenticatedRequest();request.getSession(false).setAttribute(SessionLifetimePolicy.AUTHENTICATED_AT_ATTRIBUTE,Instant.EPOCH);
        SessionContextResolver contexts=mock(SessionContextResolver.class);when(contexts.currentTenant()).thenThrow(SessionSensitiveException.unavailable());
        var subject=new SessionAuthorityService(new SessionSensitiveRequests(contexts,Duration.ofHours(12)),boundaries,store,audit,new KnownLogoutRequests(serializer->serializer.setUseBase64Encoding(false)));
        var result=subject.logout(request,trace,()->order.add("cleanup"));
        assertThat(result.revocationState()).isEqualTo(RevocationState.CONFIRMED);verifyNoInteractions(contexts);
        assertThat(order).containsExactly("writer","fence","audit","commit","cleanup");
    }
    @Test void captureMutationOrFailureIsUnknownNotAbsence(){
        var request=CapturedHumanRequestTest.authenticatedRequest();request.setAsyncStarted(true);
        var result=service.logout(request,trace,()->order.add("cleanup"));
        assertThat(result.revocationState()).isEqualTo(RevocationState.UNKNOWN);assertThat(result.localCleanupComplete()).isFalse();
        verifyNoInteractions(boundaries,store);verifyFailure(Operation.LOGOUT,"UNKNOWN","UNAVAILABLE");
    }
    @Test void rejectedRegistrationIsAuditedAfterWriterUnwindsWithoutInventingFreshIdentity(){
        when(store.register(any(),eq(proof),any())).thenThrow(SessionSensitiveException.rejected());
        doAnswer(call->{
            order.add("writer");
            try{return call.<Function<SessionSensitiveSecurityTransaction.GuardedBoundary,Object>>getArgument(1).apply(proof);}
            finally{order.add("transaction-ended");}
        }).when(boundaries).execute(any(),any());
        assertThatThrownBy(()->service.register(CapturedHumanRequestTest.authenticatedRequest(),trace))
                .isInstanceOfSatisfying(SessionSensitiveException.class,e->assertThat(e.reason()).isEqualTo(SessionSensitiveException.Reason.REJECTED));
        assertThat(order).containsExactly("writer","transaction-ended","failure-audit");
        verifyFailure(Operation.REGISTER_SESSION,"REJECTED","REJECTED");verify(store).register(any(),eq(proof),any());
    }
    @Test void revokeConflictEmitsOneIndependentFailureAndNeverRetriesEffect(){
        try(var secret=IdentityLifecycleContracts.OneUseSecret.generate(new java.security.SecureRandom())){
            var command=new RevokeCommand(UUID.randomUUID(),new SessionHandle(UUID.randomUUID()),new ExpectedAuthority(0),UUID.randomUUID(),secret);
            when(store.revoke(any(),eq(proof),eq(command),any())).thenThrow(new SessionSensitiveException(SessionSensitiveException.Reason.CONFLICT));
            assertThatThrownBy(()->service.revoke(CapturedHumanRequestTest.authenticatedRequest(),command,trace))
                    .isInstanceOfSatisfying(SessionSensitiveException.class,e->assertThat(e.reason()).isEqualTo(SessionSensitiveException.Reason.CONFLICT));
            verify(store).revoke(any(),eq(proof),eq(command),any());verifyFailure(Operation.REVOKE_SESSION,"REJECTED","CONFLICT");
        }
    }
    @Test void registrationCommitFailureIsUnknownEvenWhenCallbackReturnedSuccessfully(){
        when(store.register(any(),eq(proof),any())).thenReturn(new Registration(handle,true));
        doAnswer(call->{
            call.<Function<SessionSensitiveSecurityTransaction.GuardedBoundary,Object>>getArgument(1).apply(proof);
            throw new SessionSensitiveException(SessionSensitiveException.Reason.CONFLICT);
        }).when(boundaries).execute(any(),any());
        assertThatThrownBy(()->service.register(CapturedHumanRequestTest.authenticatedRequest(),trace))
                .isInstanceOfSatisfying(SessionSensitiveException.class,e->assertThat(e.reason()).isEqualTo(SessionSensitiveException.Reason.UNAVAILABLE));
        verifyFailure(Operation.REGISTER_SESSION,"UNKNOWN","UNAVAILABLE");verify(store).register(any(),eq(proof),any());
    }
    @Test void registrationFailureAuditUnavailableDoesNotExposeOrRetryOriginalFailure(){
        when(store.register(any(),eq(proof),any())).thenThrow(SessionSensitiveException.rejected());
        doThrow(new IllegalStateException("private detail")).when(audit).failure(any());
        assertThatThrownBy(()->service.register(CapturedHumanRequestTest.authenticatedRequest(),trace))
                .isInstanceOfSatisfying(SessionSensitiveException.class,e->assertThat(e.reason()).isEqualTo(SessionSensitiveException.Reason.UNAVAILABLE));
        verify(audit).failure(any());verify(store).register(any(),eq(proof),any());
    }
    private void verifyFailure(Operation operation,String result,String reason){
        var event=org.mockito.ArgumentCaptor.forClass(SensitiveEvent.class);verify(audit).failure(event.capture());
        SensitiveEvent value=event.getValue();assertThat(value.operation()).isEqualTo(operation);assertThat(value.result()).isEqualTo(result);
        assertThat(value.reason()).isEqualTo(reason);assertThat(value.traceId()).isEqualTo(trace);assertThat(value.provenance()).isEqualTo(Provenance.ANONYMOUS);
        assertThat(value.accountId()).isNull();assertThat(value.actorId()).isNull();assertThat(value.source()).isNull();assertThat(value.target()).isNull();
        assertThat(value.proofId()).isNull();assertThat(value.challengeId()).isNull();
    }

}
