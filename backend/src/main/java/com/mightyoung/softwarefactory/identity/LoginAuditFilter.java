package com.mightyoung.softwarefactory.identity;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Clock;
import java.util.Objects;
import java.util.concurrent.Semaphore;

/** Outside Session: withhold a login response until a single independent event append is acknowledged. */
final class LoginAuditFilter extends OncePerRequestFilter {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(LoginAuditFilter.class);
    private static final int MAX_RESPONSE_BYTES = 16 * 1024;
    private final PathPatternRequestMatcher login = PathPatternRequestMatcher.withDefaults()
            .matcher(HttpMethod.POST, "/api/v1/auth/login");
    private final AuthenticationEvents events;
    private final Clock clock;
    private final Semaphore permits;

    LoginAuditFilter(AuthenticationEvents events, Clock clock, int maximumConcurrent) {
        this.events = Objects.requireNonNull(events);
        this.clock = Objects.requireNonNull(clock);
        if (maximumConcurrent < 1 || maximumConcurrent > 64) {
            throw new IllegalArgumentException("Login audit concurrency must be between 1 and 64");
        }
        permits = new Semaphore(maximumConcurrent);
    }

    @Override protected boolean shouldNotFilter(HttpServletRequest request) { return !login.matches(request); }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        if (!permits.tryAcquire()) {
            response.setHeader("Retry-After", "1");
            IdentityHttpResponses.writeError(response, 429, "RATE_LIMITED", "Too many login attempts; try again later", true);
            return;
        }
        try {
            if (response.isCommitted()) throw new ServletException("Login response was already committed");
            var buffered = new BufferedLoginResponse(response, MAX_RESPONSE_BYTES);
            Exception failure = null;
            String failedStage = null;
            try {
                chain.doFilter(new SynchronousLoginRequest(request), buffered);
            } catch (IOException | ServletException | RuntimeException unavailable) {
                failure = unavailable;
                failedStage = "DOWNSTREAM";
            }
            try {
                buffered.prepare();
            } catch (RuntimeException unavailable) {
                if (failure == null) { failure = unavailable; failedStage = "RESPONSE_ENCODING"; }
            }
            var attempt = AuthenticationAttempt.current(request);
            if (attempt == null) {
                if (failure != null) rethrow(failure);
                buffered.release();
                return;
            }
            if (failure != null) {
                LOG.error("Admitted login completion failed; stage={} failureType={}", failedStage, failure.getClass().getName());
            }
            var event = attempt.finished(clock.instant(), failure == null, buffered.getStatus());
            try {
                events.record(event);
            } catch (RuntimeException unconfirmedAppend) {
                LOG.error("Login event append was not acknowledged; outcome={} failureType={}",
                        event.outcome(), unconfirmedAppend.getClass().getName());
                unavailable(buffered);
                buffered.release();
                return;
            }
            if (failure != null || event.outcome() == AuthenticationEvent.Outcome.SESSION_OUTCOME_UNKNOWN
                    || (event.outcome() == AuthenticationEvent.Outcome.AUTHENTICATION_DENIED && buffered.getStatus() != 401)
                    || (event.outcome() == AuthenticationEvent.Outcome.AUTHENTICATION_UNAVAILABLE && buffered.getStatus() != 503)) {
                unavailable(buffered);
            }
            // A subsequent transport failure does not change the committed observation or retry its INSERT.
            buffered.release();
        } finally {
            permits.release();
        }
    }

    private static void unavailable(BufferedLoginResponse response) throws IOException {
        SecurityContextHolder.clearContext();
        try {
            LoginSessionPersistenceFailureFilter.unavailable(response);
        } catch (BufferedLoginResponse.ResponseLimitExceeded boundedResponseOverflow) {
            // Optional headers may already occupy the entire budget. Keep the failure response bounded too.
            response.reset();
            IdentityHttpResponses.clearSessionCookies(response);
            IdentityHttpResponses.writeError(response, 503, "DEPENDENCY_UNAVAILABLE", "Authentication could not be completed", true);
        }
    }

    private static void rethrow(Exception failure) throws IOException, ServletException {
        if (failure instanceof IOException value) throw value;
        if (failure instanceof ServletException value) throw value;
        throw (RuntimeException) failure;
    }

    private static final class SynchronousLoginRequest extends HttpServletRequestWrapper {
        SynchronousLoginRequest(HttpServletRequest request) { super(request); }
        @Override public boolean isAsyncSupported() { return false; }
        @Override public AsyncContext startAsync() { throw new IllegalStateException("Login does not support asynchronous dispatch"); }
        @Override public AsyncContext startAsync(ServletRequest request, ServletResponse response) {
            throw new IllegalStateException("Login does not support asynchronous dispatch");
        }
    }
}
