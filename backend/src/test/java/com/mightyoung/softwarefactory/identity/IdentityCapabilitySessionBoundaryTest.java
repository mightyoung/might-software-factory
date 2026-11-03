package com.mightyoung.softwarefactory.identity;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class IdentityCapabilitySessionBoundaryTest {
    private static final String RESET="/api/v1/auth/password-resets/consume";
    private static final List<String> CAPABILITIES=List.of(RESET,"/api/v1/auth/password-reset-requests","/api/v1/auth/invitations/consume");
    private final AccountStatusReadRepository accounts=mock(AccountStatusReadRepository.class);
    private final VerifiedSessionPrincipal principal=new VerifiedSessionPrincipal(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),1,1,4);
    @AfterEach void clear(){SecurityContextHolder.clearContext();}

    @Test void exactPolicySeparatesThreePublicCapabilitiesFromAllSixWrites() {
        for(String path:CAPABILITIES) {
            assertThat(IdentityCapabilityRoutePolicy.matches(new MockHttpServletRequest("POST",path))).isTrue();
            for(String method:List.of("GET","PUT","DELETE","HEAD"))assertThat(IdentityCapabilityRoutePolicy.matches(new MockHttpServletRequest(method,path))).isFalse();
            assertThat(IdentityCapabilityRoutePolicy.matches(new MockHttpServletRequest("POST",path+"/extra"))).isFalse();
        }
        for(String path:management()) {
            assertThat(IdentityCapabilityRoutePolicy.matches(new MockHttpServletRequest("POST",path))).isFalse();
            assertThat(IdentityCapabilityRoutePolicy.matchesLifecycleWrite(new MockHttpServletRequest("POST",path))).isTrue();
            assertThat(IdentityCapabilityRoutePolicy.matchesLifecycleWrite(new MockHttpServletRequest("GET",path))).isFalse();
        }
        var contextual=new MockHttpServletRequest("POST","/app"+RESET);contextual.setContextPath("/app");
        assertThat(IdentityCapabilityRoutePolicy.matches(contextual)).isTrue();
    }
    @Test void provedStaleMissingAndDisabledCapabilitiesBecomeAnonymousAndKeepCsrf()throws Exception {
        for(var state:List.of(Optional.<AccountSecurityState>empty(),Optional.of(new AccountSecurityState("DISABLED",4)),Optional.of(new AccountSecurityState("ACTIVE",5)))) {
            for(String path:CAPABILITIES) {
                var request=authenticated(path);var response=new MockHttpServletResponse();var calls=new AtomicInteger();
                when(accounts.findState(principal.accountId())).thenReturn(state);
                new CredentialVersionSessionFilter(accounts).doFilter(request,response,(a,b)->{
                    calls.incrementAndGet();assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();assertThat(request.getSession(false)).isNull();
                });
                assertThat(calls).hasValue(1);assertThat(request.getAttribute(IdentityCapabilityRoutePolicy.ANONYMOUS_CLASSIFICATION)).isEqualTo(IdentityCapabilityRoutePolicy.AnonymousClassification.STALE_CREDENTIAL);
                assertThat(response.getHeaders("Set-Cookie")).hasSize(1).allMatch(cookie->cookie.startsWith("SESSION=")&&cookie.contains("Max-Age=0"));
                assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
            }
        }
    }
    @Test void capabilityStillValidatesAnAttachedCurrentGenerationAndDoesNotInventAnonymousProof()throws Exception {
        when(accounts.findState(principal.accountId())).thenReturn(Optional.of(new AccountSecurityState("ACTIVE",4)));
        var request=authenticated(RESET);var session=request.getSession(false);var response=new MockHttpServletResponse();
        new CredentialVersionSessionFilter(accounts).doFilter(request,response,(a,b)->assertThat(SecurityContextHolder.getContext().getAuthentication().getPrincipal()).isSameAs(principal));
        verify(accounts).findState(principal.accountId());assertThat(request.getSession(false)).isSameAs(session);
        assertThat(request.getAttribute(IdentityCapabilityRoutePolicy.ANONYMOUS_CLASSIFICATION)).isNull();assertThat(response.getHeaders("Set-Cookie")).isEmpty();
    }
    @Test void dependencyOrInvalidationFailureNeverReachesAnAnonymousHandler()throws Exception {
        var lookup=authenticated(RESET);var response=new MockHttpServletResponse();
        when(accounts.findState(principal.accountId())).thenThrow(new DataAccessResourceFailureException("private SQL/token"));
        new CredentialVersionSessionFilter(accounts).doFilter(lookup,response,(a,b)->{throw new AssertionError("handler must not run");});
        assertThat(response.getStatus()).isEqualTo(503);assertThat(response.getContentAsString()).doesNotContain("private","SQL/token");
        reset(accounts);when(accounts.findState(principal.accountId())).thenReturn(Optional.empty());
        var invalidation=authenticated(RESET);var broken=new MockHttpSession(){@Override public void invalidate(){throw new IllegalStateException("private store");}};invalidation.setSession(broken);response=new MockHttpServletResponse();
        new CredentialVersionSessionFilter(accounts).doFilter(invalidation,response,(a,b)->{throw new AssertionError("handler must not run");});
        assertThat(response.getStatus()).isEqualTo(503);assertThat(invalidation.getAttribute(IdentityCapabilityRoutePolicy.ANONYMOUS_CLASSIFICATION)).isNull();
    }
    @Test void wrongMethodOrSiblingRouteKeepsProtectedStaleSessionBehavior()throws Exception {
        for(var request:List.of(new MockHttpServletRequest("GET",RESET),new MockHttpServletRequest("POST",RESET+"/extra"),new MockHttpServletRequest("POST","/api/v1/account-invitations"))) {
            authenticate();request.getSession(true);when(accounts.findState(principal.accountId())).thenReturn(Optional.empty());var response=new MockHttpServletResponse();
            new CredentialVersionSessionFilter(accounts).doFilter(request,response,(a,b)->{throw new AssertionError();});
            assertThat(response.getStatus()).isEqualTo(401);assertThat(response.getHeaders("Set-Cookie")).hasSize(2);
        }
    }
    @Test void absoluteExpiryPrecedesCapabilityGenerationAndCannotCreateAFreshTargetSession()throws Exception {
        var clock=Clock.fixed(Instant.parse("2026-11-03T12:00:00Z"),ZoneOffset.UTC);var lifetime=new SessionLifetimePolicy(clock,Duration.ofHours(1));
        var request=authenticated(RESET);request.getSession().setAttribute(SessionLifetimePolicy.AUTHENTICATED_AT_ATTRIBUTE,clock.instant().minusSeconds(3600));
        var response=new MockHttpServletResponse();var calls=new AtomicInteger();
        new AbsoluteSessionLifetimeFilter(lifetime).doFilter(request,response,(a,b)->new CredentialVersionSessionFilter(accounts).doFilter(a,b,(c,d)->{
            calls.incrementAndGet();assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();assertThat(request.getSession(false)).isNull();
        }));
        assertThat(calls).hasValue(1);verifyNoInteractions(accounts);
        assertThat(request.getAttribute(IdentityCapabilityRoutePolicy.ANONYMOUS_CLASSIFICATION)).isEqualTo(IdentityCapabilityRoutePolicy.AnonymousClassification.ABSOLUTE_LIFETIME_EXPIRED);
        assertThat(response.getHeaders("Set-Cookie")).hasSize(1).allMatch(cookie->cookie.startsWith("SESSION="));
    }
    @Test void expiredSessionReadOrCleanupFailureIs503WithoutHandlerFallback()throws Exception {
        var lifetime=new SessionLifetimePolicy(Clock.systemUTC(),Duration.ofHours(1));
        for(boolean readFailure:new boolean[]{false,true}) {
            authenticate();
            var request=readFailure?new MockHttpServletRequest("POST",RESET){@Override public jakarta.servlet.http.HttpSession getSession(boolean create){throw new DataAccessResourceFailureException("private session query");}}:new MockHttpServletRequest("POST",RESET);
            if(!readFailure)request.setSession(new MockHttpSession(){@Override public void invalidate(){throw new DataAccessResourceFailureException("private cleanup");}});
            var response=new MockHttpServletResponse();
            new AbsoluteSessionLifetimeFilter(lifetime).doFilter(request,response,(a,b)->{throw new AssertionError();});
            assertThat(response.getStatus()).isEqualTo(503);assertThat(response.getContentAsString()).doesNotContain("private");
        }
    }
    @Test void outerPersistenceFailureUsesPhaseNotStatusAndNeverRepeatsTheChain()throws Exception {
        var paths=new ArrayList<>(CAPABILITIES);paths.addAll(management());
        for(String path:paths)for(int priorStatus:new int[]{200,201,204,400}) {
            var request=new MockHttpServletRequest("POST",path);var response=new MockHttpServletResponse();var count=new AtomicInteger();UUID trace=UUID.randomUUID();
            new IdentityLifecycleSessionPersistenceFilter().doFilter(request,response,(a,b)->{
                count.incrementAndGet();request.setAttribute(IdentityLifecycleController.TRACE_ATTRIBUTE,trace);response.setHeader("X-Trace-Id",trace.toString());response.setStatus(priorStatus);
                throw new DataAccessResourceFailureException("private SQL capability secret");
            });
            assertThat(count).hasValue(1);assertThat(response.getStatus()).isEqualTo(503);assertThat(response.getContentAsString()).contains("OUTCOME_UNKNOWN",trace.toString()).doesNotContain("private","capability secret");
        }
        var request=new MockHttpServletRequest("POST",RESET);var response=new MockHttpServletResponse();
        new IdentityLifecycleSessionPersistenceFilter().doFilter(request,response,(a,b)->{throw new DataAccessResourceFailureException("private pre-handler save");});
        assertThat(response.getStatus()).isEqualTo(503);assertThat(response.getContentAsString()).contains("DEPENDENCY_UNAVAILABLE").doesNotContain("OUTCOME_UNKNOWN");
    }
    @Test void committedResponseAndProgrammingFailureAreNotReplacedOrRetried()throws Exception {
        var request=new MockHttpServletRequest("POST",RESET);var response=new MockHttpServletResponse();var count=new AtomicInteger();
        var persistence=new DataAccessResourceFailureException("private");
        assertThatThrownBy(()->new IdentityLifecycleSessionPersistenceFilter().doFilter(request,response,(a,b)->{count.incrementAndGet();response.setStatus(204);response.flushBuffer();throw persistence;})).isSameAs(persistence);
        assertThat(count).hasValue(1);assertThat(response.getStatus()).isEqualTo(204);assertThat(response.getContentAsString()).isEmpty();
        var bug=new IllegalArgumentException("bug");
        assertThatThrownBy(()->new IdentityLifecycleSessionPersistenceFilter().doFilter(new MockHttpServletRequest("POST",RESET),new MockHttpServletResponse(),(a,b)->{throw bug;})).isSameAs(bug);
    }
    @Test void registrationIsOutsideActualConfiguredSessionOrderAndLeavesLoginBoundaryUnchanged() {
        var config=new SecurityConfiguration();var environment=new MockEnvironment().withProperty("spring.session.servlet.filter-order","-1000");
        assertThat(config.lifecycleSessionPersistenceFailureBoundary(environment).getOrder()).isEqualTo(-1001);
        assertThat(config.loginSessionPersistenceFailureBoundary(environment).getOrder()).isEqualTo(-1001);
        assertThatThrownBy(()->config.lifecycleSessionPersistenceFailureBoundary(new MockEnvironment().withProperty("spring.session.servlet.filter-order",Integer.toString(Integer.MIN_VALUE)))).isInstanceOf(IllegalStateException.class);
    }
    @Test void deferredContextLoadFailureInAbsoluteFilterIs503BeforeEveryCapabilityHandler()throws Exception {
        var lifetime=new SessionLifetimePolicy(Clock.systemUTC(),Duration.ofHours(1));
        for(RuntimeException failure:deferredFailures())for(String path:CAPABILITIES) {
            SecurityContextHolder.setDeferredContext(()->{throw failure;});var response=new MockHttpServletResponse();var calls=new AtomicInteger();
            new AbsoluteSessionLifetimeFilter(lifetime).doFilter(new MockHttpServletRequest("POST",path),response,(a,b)->calls.incrementAndGet());
            assertDeferredFailure(response,calls);
        }
    }
    @Test void deferredContextLoadFailureInGenerationFilterIs503BeforeEveryCapabilityHandler()throws Exception {
        for(RuntimeException failure:deferredFailures())for(String path:CAPABILITIES) {
            SecurityContextHolder.setDeferredContext(()->{throw failure;});var response=new MockHttpServletResponse();var calls=new AtomicInteger();
            new CredentialVersionSessionFilter(accounts).doFilter(new MockHttpServletRequest("POST",path),response,(a,b)->calls.incrementAndGet());
            assertDeferredFailure(response,calls);
        }
        verifyNoInteractions(accounts);
    }
    @Test void deferredContextLoadFailureOnLegacyProtectedRequestsStillPropagatesWithoutAnonymousFallback() {
        var lifetime=new SessionLifetimePolicy(Clock.systemUTC(),Duration.ofHours(1));
        for(RuntimeException failure:deferredFailures()) {
            for(boolean absolute:new boolean[]{false,true}) {
                SecurityContextHolder.setDeferredContext(()->{throw failure;});var response=new MockHttpServletResponse();var calls=new AtomicInteger();
                assertThatThrownBy(()->{
                    var request=new MockHttpServletRequest("GET","/api/v1/session/current");
                    if(absolute)new AbsoluteSessionLifetimeFilter(lifetime).doFilter(request,response,(a,b)->calls.incrementAndGet());
                    else new CredentialVersionSessionFilter(accounts).doFilter(request,response,(a,b)->calls.incrementAndGet());
                }).isSameAs(failure);
                assertThat(calls).hasValue(0);assertThat(response.getContentAsByteArray()).isEmpty();SecurityContextHolder.clearContext();
            }
        }
    }
    private static List<RuntimeException> deferredFailures(){return List.of(
            new org.springframework.core.serializer.support.SerializationFailedException("private serialization token"),
            new IllegalStateException("private session state"),new DataAccessResourceFailureException("private SQL credential"));}
    private static void assertDeferredFailure(MockHttpServletResponse response,AtomicInteger calls)throws Exception {
        assertThat(calls).hasValue(0);assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getContentAsString()).contains("DEPENDENCY_UNAVAILABLE").doesNotContain("private","serialization","credential");
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }
    private MockHttpServletRequest authenticated(String path){authenticate();var request=new MockHttpServletRequest("POST",path);request.getSession(true);return request;}
    private void authenticate(){SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal,null,List.of()));}
    private static List<String> management(){return List.of("/api/v1/account-invitations","/api/v1/account-invitations/10000000-0000-0000-0000-000000000001/revoke","/api/v1/account-invitations/10000000-0000-0000-0000-000000000001/reissue");}
}
