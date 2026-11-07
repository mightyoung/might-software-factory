package com.mightyoung.softwarefactory.identity;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

class LoginAuditFilterTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-03T00:00:00Z"), ZoneOffset.UTC);
    private static final VerifiedSessionPrincipal PRINCIPAL = VerifiedSessionPrincipalTestFixture.create(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1, 1);

    @AfterEach void clearContext() { org.springframework.security.core.context.SecurityContextHolder.clearContext(); }

    @Test void successfulResponseRemainsEntirelyPrivateUntilTheEventReturns() throws Exception {
        var request = request(); var response = new MockHttpServletResponse();
        List<AuthenticationEvent> recorded = new ArrayList<>();
        var filter = filter(event -> {
            assertThat(response.getStatus()).isEqualTo(200);
            assertThat(response.getHeaderNames()).isEmpty();
            assertThat(response.getContentAsByteArray()).isEmpty();
            assertThat(response.isCommitted()).isFalse();
            recorded.add(event);
        });
        filter.doFilter(request, response, (req, res) -> {
            success((HttpServletRequest)req);
            var out = (HttpServletResponse)res;
            out.setStatus(204); out.addHeader("Set-Cookie", "SESSION=private; Secure; HttpOnly");
            out.setHeader("X-Content-Type-Options", "nosniff"); out.flushBuffer();
            assertThat(out.isCommitted()).isFalse();
        });
        assertThat(response.getStatus()).isEqualTo(204);
        assertThat(response.getHeader("Set-Cookie")).contains("SESSION=private");
        assertThat(recorded).singleElement().satisfies(event -> {
            assertThat(event.outcome()).isEqualTo(AuthenticationEvent.Outcome.SESSION_PERSISTED);
            assertThat(event.subject().accountId()).isEqualTo(PRINCIPAL.accountId());
            assertThat(event.subject().tenantId()).isEqualTo(PRINCIPAL.tenantId());
            assertThat(event.occurredAt()).isEqualTo(CLOCK.instant());
        });
    }

    @Test void denialAndUnavailableEventsNeverBorrowAnExistingOrSubmittedIdentity() throws Exception {
        for (boolean denied : List.of(true, false)) {
            List<AuthenticationEvent> recorded = new ArrayList<>(); var response = new MockHttpServletResponse();
            var request = request(); request.addParameter("accountId", PRINCIPAL.accountId().toString());
            filter(recorded::add).doFilter(request, response, (req,res) -> {
                var admitted = AuthenticationAttempt.enroll((HttpServletRequest)req);
                if (denied) admitted.denied();
                IdentityHttpResponses.writeError((HttpServletResponse)res, denied ? 401 : 503,
                        denied ? "AUTH_REQUIRED" : "DEPENDENCY_UNAVAILABLE", "Generic failure", !denied);
            });
            assertThat(recorded).singleElement().satisfies(event -> {
                assertThat(event.outcome()).isEqualTo(denied ? AuthenticationEvent.Outcome.AUTHENTICATION_DENIED
                        : AuthenticationEvent.Outcome.AUTHENTICATION_UNAVAILABLE);
                assertThat(event.subject()).isNull();
            });
        }
    }

    @Test void sessionFailureMarkerSurvivesTheInnerFailureTranslationAndContextClearing() throws Exception {
        List<AuthenticationEvent> recorded = new ArrayList<>(); var response = new MockHttpServletResponse();
        filter(recorded::add).doFilter(request(), response, (req,res) ->
                new LoginSessionPersistenceFailureFilter().doFilter(req,res,(nested, output) -> {
                    success((HttpServletRequest)nested);
                    ((HttpServletResponse)output).setStatus(204);
                    ((HttpServletResponse)output).addHeader("Set-Cookie", "SESSION=unpublished; Secure");
                    output.flushBuffer();
                    throw new DataAccessResourceFailureException("private-internal-detail");
                }));
        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getHeaders("Set-Cookie")).noneMatch(value -> value.contains("unpublished"));
        assertThat(response.getContentAsString()).doesNotContain("private-internal-detail");
        assertThat(recorded).singleElement().satisfies(event -> {
            assertThat(event.outcome()).isEqualTo(AuthenticationEvent.Outcome.SESSION_OUTCOME_UNKNOWN);
            assertThat(event.subject().actorId()).isEqualTo(PRINCIPAL.actorId());
        });
    }

    @Test void unconfirmedEventNeverPublishesAuthenticationCookiesOrRetriesEitherOperation() throws Exception {
        var appendCalls = new AtomicInteger(); var chainCalls = new AtomicInteger();
        var response = new MockHttpServletResponse();
        filter(event -> { appendCalls.incrementAndGet(); throw new DataAccessResourceFailureException("commit-unknown"); })
                .doFilter(request(), response, (req,res) -> {
                    chainCalls.incrementAndGet(); success((HttpServletRequest)req);
                    var out=(HttpServletResponse)res; out.setStatus(204);out.setContentLength(0);
                    out.addHeader("Set-Cookie", "SESSION=withheld; Secure");
                    out.addHeader("Set-Cookie", "XSRF-TOKEN=withheld; Secure");
                    out.addHeader("Set-Cookie", "theme=dark; Secure");out.flushBuffer();
                });
        assertThat(appendCalls).hasValue(1); assertThat(chainCalls).hasValue(1);
        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getHeader("Content-Length")).isNull();
        assertThat(response.getHeaders("Set-Cookie")).contains("theme=dark; Secure")
                .noneMatch(value -> value.contains("withheld"));
        assertThat(response.getContentAsString()).contains("DEPENDENCY_UNAVAILABLE").doesNotContain("commit-unknown");
    }

    @Test void unexpectedAdmittedFailureAndResponseOverflowFailClosedWithOneObservation() throws Exception {
        for (boolean overflow : List.of(false, true)) {
            List<AuthenticationEvent> recorded = new ArrayList<>(); var response=new MockHttpServletResponse();
            filter(recorded::add).doFilter(request(),response,(req,res)->{
                success((HttpServletRequest)req);((HttpServletResponse)res).setStatus(204);
                if(overflow) res.getOutputStream().write(new byte[16385]);
                else throw new IllegalStateException("private-failure");
            });
            assertThat(response.getStatus()).isEqualTo(503);
            assertThat(recorded).singleElement().extracting(AuthenticationEvent::outcome)
                    .isEqualTo(AuthenticationEvent.Outcome.SESSION_OUTCOME_UNKNOWN);
            assertThat(response.getContentAsString()).doesNotContain("private-failure");
        }
    }

    @Test void excludedRequestsAndRoutesDoNotCreateAnEvent() throws Exception {
        var calls = new AtomicInteger(); var filter=filter(event->calls.incrementAndGet());
        for(var request:List.of(request(),new MockHttpServletRequest("GET","/api/v1/auth/login"),
                new MockHttpServletRequest("POST","/api/v1/findings"))) {
            filter.doFilter(request,new MockHttpServletResponse(),(req,res)->((HttpServletResponse)res).setStatus(403));
        }
        assertThat(calls).hasValue(0);
    }

    @Test void verifiedProviderBeforeFailedSessionStrategyIsStillAttributedButNeverSuccessful() throws Exception {
        List<AuthenticationEvent> recorded=new ArrayList<>();var response=new MockHttpServletResponse();
        filter(recorded::add).doFilter(request(),response,(req,res)->{
            AuthenticationAttempt.enroll((HttpServletRequest)req).verified(PRINCIPAL);
            // A provider can finish before fixation/context/lifetime handling fails or returns unexpectedly.
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
            ((HttpServletResponse)res).setStatus(204);
        });
        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(recorded).singleElement().satisfies(event->{
            assertThat(event.outcome()).isEqualTo(AuthenticationEvent.Outcome.SESSION_OUTCOME_UNKNOWN);
            assertThat(event.subject().memberId()).isEqualTo(PRINCIPAL.memberId());
        });
    }

    @Test void finalEncoderOverflowBeforeAppendIsMappedWithoutReleasingAnyPriorContent() throws Exception {
        List<AuthenticationEvent> recorded=new ArrayList<>();var response=new MockHttpServletResponse();
        filter(recorded::add).doFilter(request(),response,(req,res)->{
            success((HttpServletRequest)req);((HttpServletResponse)res).setStatus(204);
            res.setCharacterEncoding("UTF-8");var writer=res.getWriter();
            writer.write("x".repeat(16384));writer.write('\uD83D');
        });
        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getContentAsString()).contains("DEPENDENCY_UNAVAILABLE").doesNotContain("xxxx");
        assertThat(recorded).singleElement().extracting(AuthenticationEvent::outcome)
                .isEqualTo(AuthenticationEvent.Outcome.SESSION_OUTCOME_UNKNOWN);
    }

    @Test void auditPermitIsHeldAfterProviderWorkUntilAppendAndReleaseFinish() throws Exception {
        var entered = new CountDownLatch(1); var finish = new CountDownLatch(1); var calls = new AtomicInteger();
        var filter=filter(event->{if(calls.incrementAndGet()==1){entered.countDown();try {
            if(!finish.await(5,TimeUnit.SECONDS))throw new IllegalStateException("Test append deadline");
        } catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IllegalStateException(interrupted);}}});
        var worker=Executors.newSingleThreadExecutor();
        try {
            var pending=worker.submit(()->{filter.doFilter(request(),new MockHttpServletResponse(),(req,res)->{
                success((HttpServletRequest)req);((HttpServletResponse)res).setStatus(204);
            });return null;});
            assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue(); var overloaded=new MockHttpServletResponse();
            filter.doFilter(request(),overloaded,(req,res)->fail("Saturated audit gate must not call downstream"));
            assertThat(overloaded.getStatus()).isEqualTo(429); assertThat(overloaded.getHeader("Retry-After")).isEqualTo("1");
            assertThat(calls).hasValue(1);finish.countDown();pending.get(5,TimeUnit.SECONDS);
            var next=new MockHttpServletResponse();filter.doFilter(request(),next,(req,res)->{
                success((HttpServletRequest)req);((HttpServletResponse)res).setStatus(204);
            });assertThat(next.getStatus()).isEqualTo(204);assertThat(calls).hasValue(2);
        } finally {finish.countDown();worker.shutdownNow();assertThat(worker.awaitTermination(5,TimeUnit.SECONDS)).isTrue();}
    }

    @Test void transportFailureAfterConfirmedAppendDoesNotCreateAnotherObservation() {
        var calls=new AtomicInteger();var response=new MockHttpServletResponse(){
            @Override public ServletOutputStream getOutputStream(){return new ServletOutputStream(){
                @Override public void write(int value)throws IOException{throw new IOException("transport-closed");}
                @Override public boolean isReady(){return true;}
                @Override public void setWriteListener(WriteListener listener){throw new UnsupportedOperationException();}
            };}
        };
        assertThatThrownBy(()->filter(event->calls.incrementAndGet()).doFilter(request(),response,(req,res)->{
            AuthenticationAttempt.enroll((HttpServletRequest)req).denied();
            IdentityHttpResponses.writeError((HttpServletResponse)res,401,"AUTH_REQUIRED","Denied",false);
        })).isInstanceOf(IOException.class).hasMessage("transport-closed");assertThat(calls).hasValue(1);
    }

    @Test void internalFailureDiagnosticsRetainTheStageWithoutRequestSecretsOrRawExceptionDetails() throws Exception {
        var logger=(ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(LoginAuditFilter.class);
        var appender=new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();logger.addAppender(appender);
        try {
            filter(event->{throw new DataAccessResourceFailureException("private-sql secret-cookie");})
                    .doFilter(request(),new MockHttpServletResponse(),(req,res)->{
                        success((HttpServletRequest)req);((HttpServletResponse)res).setStatus(204);
                    });
            filter(event->{}).doFilter(request(),new MockHttpServletResponse(),(req,res)->{
                success((HttpServletRequest)req);throw new IllegalStateException("private-sql secret-cookie");
            });
            assertThat(appender.list).hasSize(2).allSatisfy(event->{
                assertThat(event.getFormattedMessage()).contains("failureType=").doesNotContain("private-sql","secret-cookie");
                assertThat(event.getThrowableProxy()).isNull();
            });
            assertThat(appender.list.get(0).getFormattedMessage()).contains("append was not acknowledged");
            assertThat(appender.list.get(1).getFormattedMessage()).contains("stage=DOWNSTREAM");
        } finally {logger.detachAppender(appender);appender.stop();}
    }

    @Test void asyncLoginIsRejectedWithoutPublishingAndRegistrationLeavesTwoPredecessors() throws Exception {
        List<AuthenticationEvent> recorded=new ArrayList<>();var response=new MockHttpServletResponse();
        filter(recorded::add).doFilter(request(),response,(req,res)->{
            success((HttpServletRequest)req);assertThat(req.isAsyncSupported()).isFalse();req.startAsync();
        });assertThat(response.getStatus()).isEqualTo(503);
        assertThat(recorded).singleElement().extracting(AuthenticationEvent::outcome)
                .isEqualTo(AuthenticationEvent.Outcome.SESSION_OUTCOME_UNKNOWN);
        var clocks=new StaticListableBeanFactory();clocks.addBean("clock",CLOCK);
        var configuration=new SecurityConfiguration();
        assertThat(configuration.loginAuditBoundary(new MockEnvironment().withProperty("spring.session.servlet.filter-order","-1000"),
                recorded::add,clocks.getBeanProvider(Clock.class),8).getOrder()).isEqualTo(-1002);
        assertThat(configuration.loginAuditBoundary(new MockEnvironment(),recorded::add,
                clocks.getBeanProvider(Clock.class),8).isAsyncSupported()).isTrue();
        for(int order:List.of(Integer.MIN_VALUE,Integer.MIN_VALUE+1)) {
            assertThatThrownBy(()->configuration.loginAuditBoundary(new MockEnvironment().withProperty(
                    "spring.session.servlet.filter-order",Integer.toString(order)),recorded::add,clocks.getBeanProvider(Clock.class),8))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    private static LoginAuditFilter filter(AuthenticationEvents events){return new LoginAuditFilter(events,CLOCK,1);}
    private static MockHttpServletRequest request(){return new MockHttpServletRequest("POST","/api/v1/auth/login");}
    private static void success(HttpServletRequest request){var attempt=AuthenticationAttempt.enroll(request);
        attempt.verified(PRINCIPAL);attempt.successHandlerCompleted();}
}
