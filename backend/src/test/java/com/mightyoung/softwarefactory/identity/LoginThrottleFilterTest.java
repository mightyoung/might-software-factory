package com.mightyoung.softwarefactory.identity;

import jakarta.servlet.ServletInputStream;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LoginThrottleFilterTest {

    private static final String PATH = "/api/v1/auth/login";

    @Test
    void malformedBodiesConsumeSourceBudgetAndRejectionPrecedesParsingAndProviderWork() throws Exception {
        var calls = new AtomicInteger();
        var filter = filter(config(1, 5), provider(calls, new AtomicReference<>(), Outcome.SUCCESS));
        var malformed = request("{}");
        var first = new MockHttpServletResponse();
        filter.doFilter(malformed, first, (request, response) -> { throw new AssertionError("Unexpected chain"); });
        assertThat(first.getStatus()).isEqualTo(400);

        var rejected = new MockHttpServletRequest("POST", PATH) {
            @Override public ServletInputStream getInputStream() {
                throw new AssertionError("A source-limited request must not read its body");
            }
        };
        rejected.setServletPath(PATH);
        rejected.setRemoteAddr("192.0.2.1");
        var response = new MockHttpServletResponse();
        filter.doFilter(rejected, response, (request, next) -> { throw new AssertionError("Unexpected chain"); });

        assertRateLimited(response, "60");
        assertThat(calls).hasValue(0);
    }

    @Test
    void changingTenantUnicodeSpellingAndForwardingHeadersDoesNotBypassThePairCooldown() throws Exception {
        var calls = new AtomicInteger();
        var token = new AtomicReference<LocalPasswordAuthenticationToken>();
        var filter = filter(config(100, 1), provider(calls, token, Outcome.BAD_CREDENTIALS));
        var first = new MockHttpServletResponse();
        filter.doFilter(request(body("operator", "tenant-a")), first,
                (request, response) -> { throw new AssertionError("Unexpected chain"); });
        assertThat(first.getStatus()).isEqualTo(401);
        assertThat(token.get().getCredentials()).isNull();

        var changed = request(body(" ＯＰＥＲＡＴＯＲ ", "tenant-b"));
        changed.addHeader("X-Forwarded-For", "198.51.100.1");
        changed.addHeader("Forwarded", "for=198.51.100.2");
        var response = new MockHttpServletResponse();
        filter.doFilter(changed, response, (request, next) -> { throw new AssertionError("Unexpected chain"); });

        assertRateLimited(response, "30");
        assertThat(calls).hasValue(1);
    }

    @Test
    void serviceAndUnexpectedFailuresReleaseReservationsWithoutBecomingBadCredentials() {
        for (Outcome outcome : List.of(Outcome.SERVICE_FAILURE, Outcome.RUNTIME_FAILURE)) {
            var calls = new AtomicInteger();
            var token = new AtomicReference<LocalPasswordAuthenticationToken>();
            var filter = filter(config(100, 1), provider(calls, token, outcome));
            for (int i = 0; i < 3; i++) {
                assertThatThrownBy(() -> filter.attemptAuthentication(request(body("operator", "tenant-a")),
                        new MockHttpServletResponse())).isInstanceOf(outcome == Outcome.SERVICE_FAILURE
                        ? AuthenticationServiceException.class : IllegalStateException.class);
                assertThat(token.get().getCredentials()).isNull();
            }
            assertThat(calls).hasValue(3);
        }
    }

    @Test
    void successfulProviderCallsAlsoEraseCredentialsAndReleaseConcurrentCapacity() {
        var calls = new AtomicInteger();
        var token = new AtomicReference<LocalPasswordAuthenticationToken>();
        var filter = filter(config(100, 1), provider(calls, token, Outcome.SUCCESS));
        for (int i = 0; i < 3; i++) {
            assertThat(filter.attemptAuthentication(request(body("operator", "tenant-a")),
                    new MockHttpServletResponse()).isAuthenticated()).isTrue();
            assertThat(token.get().getCredentials()).isNull();
        }
        assertThat(calls).hasValue(3);
    }

    private static void assertRateLimited(MockHttpServletResponse response, String retry) throws Exception {
        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("Retry-After")).isEqualTo(retry);
        var error = new ObjectMapper().readTree(response.getContentAsString());
        assertThat(error.path("code").asString()).isEqualTo("RATE_LIMITED");
        assertThat(error.path("message").asString()).isEqualTo("Too many login attempts; try again later");
        assertThat(error.path("retryable").asBoolean()).isTrue();
        assertThat(error.path("traceId").asString()).isNotBlank();
        assertThat(response.getContentAsString()).doesNotContain("operator", "tenant", "192.0.2", "synthetic-pass");
    }

    private static LoginAttemptLimiter.Config config(int sourceAttempts, int failureThreshold) {
        return new LoginAttemptLimiter.Config(100, sourceAttempts, Duration.ofMinutes(1), failureThreshold,
                Duration.ofMinutes(5), Duration.ofSeconds(30), 1, 10, 10);
    }

    private static LocalPasswordAuthenticationFilter filter(LoginAttemptLimiter.Config config,
                                                            AuthenticationProvider provider) {
        return new LocalPasswordAuthenticationFilter(provider, new LoginRequestAuthenticationConverter(new ObjectMapper()),
                new HttpSessionSecurityContextRepository(), CookieCsrfTokenRepository.withHttpOnlyFalse(),
                new SessionLifetimePolicy(Clock.systemUTC(), Duration.ofHours(12)),
                new LoginAttemptLimiter(config, () -> 0));
    }

    private static AuthenticationProvider provider(AtomicInteger calls,
                                                    AtomicReference<LocalPasswordAuthenticationToken> captured,
                                                    Outcome outcome) {
        return new AuthenticationProvider() {
            @Override public Authentication authenticate(Authentication authentication) {
                calls.incrementAndGet();
                captured.set((LocalPasswordAuthenticationToken) authentication);
                return switch (outcome) {
                    case SUCCESS -> UsernamePasswordAuthenticationToken.authenticated(
                            VerifiedSessionPrincipalTestFixture.create(java.util.UUID.randomUUID(), java.util.UUID.randomUUID(), java.util.UUID.randomUUID()), null, List.of());
                    case BAD_CREDENTIALS -> throw new BadCredentialsException("synthetic internal failure");
                    case SERVICE_FAILURE -> throw new AuthenticationServiceException("synthetic dependency failure");
                    case RUNTIME_FAILURE -> throw new IllegalStateException("synthetic unexpected failure");
                };
            }
            @Override public boolean supports(Class<?> authentication) {
                return LocalPasswordAuthenticationToken.class.isAssignableFrom(authentication);
            }
        };
    }

    private static MockHttpServletRequest request(String body) {
        var request = new MockHttpServletRequest("POST", PATH);
        request.setServletPath(PATH);
        request.setRemoteAddr("192.0.2.1");
        request.setContentType("application/json");
        request.setContent(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return request;
    }

    private static String body(String loginKey, String tenantCode) {
        return "{\"loginKey\":\"" + loginKey + "\",\"password\":\"synthetic-pass\",\"tenantCode\":\"" + tenantCode + "\"}";
    }

    private enum Outcome { SUCCESS, BAD_CREDENTIALS, SERVICE_FAILURE, RUNTIME_FAILURE }
}
