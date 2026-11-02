package com.mightyoung.softwarefactory.identity;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SessionLifetimePolicyTest {

    private final MutableClock clock = new MutableClock();
    private final SessionLifetimePolicy policy = new SessionLifetimePolicy(clock, Duration.ofHours(12));

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void successfulAuthenticationStartsTheLifetimeIndependentlyOfSessionCreation() {
        MockHttpSession session = new MockHttpSession();
        clock.advance(Duration.ofHours(15));

        policy.markAuthenticated(session);

        assertThat(session.getAttribute(SessionLifetimePolicy.AUTHENTICATED_AT_ATTRIBUTE)).isEqualTo(clock.instant());
        assertThat(policy.isExpired(session)).isFalse();
    }

    @Test
    void repeatedReadsDoNotSlideTheDeadlineAndTheExactBoundaryIsExpired() {
        MockHttpSession session = new MockHttpSession();
        Instant authenticatedAt = clock.instant();
        policy.markAuthenticated(session);

        clock.advance(Duration.ofHours(11));
        assertThat(policy.isExpired(session)).isFalse();
        clock.advance(Duration.ofHours(1).minusNanos(1));
        assertThat(policy.isExpired(session)).isFalse();
        assertThat(session.getAttribute(SessionLifetimePolicy.AUTHENTICATED_AT_ATTRIBUTE)).isEqualTo(authenticatedAt);
        clock.advance(Duration.ofNanos(1));
        assertThat(policy.isExpired(session)).isTrue();
    }

    @Test
    void reauthenticationCreatesANewDeadline() {
        MockHttpSession session = new MockHttpSession();
        policy.markAuthenticated(session);
        clock.advance(Duration.ofHours(12));
        assertThat(policy.isExpired(session)).isTrue();

        policy.markAuthenticated(session);

        assertThat(policy.isExpired(session)).isFalse();
        clock.advance(Duration.ofHours(12));
        assertThat(policy.isExpired(session)).isTrue();
    }

    @Test
    void missingWrongTypeFutureAndInvalidatedMetadataFailClosed() {
        assertThat(policy.isExpired(null)).isTrue();
        MockHttpSession session = new MockHttpSession();
        assertThat(policy.isExpired(session)).isTrue();
        session.setAttribute(SessionLifetimePolicy.AUTHENTICATED_AT_ATTRIBUTE, clock.instant().toString());
        assertThat(policy.isExpired(session)).isTrue();
        session.setAttribute(SessionLifetimePolicy.AUTHENTICATED_AT_ATTRIBUTE, clock.instant().plusNanos(1));
        assertThat(policy.isExpired(session)).isTrue();
        session.invalidate();
        assertThat(policy.isExpired(session)).isTrue();
    }

    @Test
    void invalidTimeoutConfigurationCannotDisableTheGate() {
        for (Duration invalid : List.of(Duration.ZERO, Duration.ofMillis(-1), Duration.ofNanos(1),
                Duration.ofSeconds(Long.MAX_VALUE))) {
            assertThatThrownBy(() -> new SessionLifetimePolicy(clock, invalid))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void expiredAuthenticationAndSessionAreRemovedBeforeTheNextFilterRuns() throws Exception {
        var request = new MockHttpServletRequest();
        MockHttpSession session = new MockHttpSession();
        request.setSession(session);
        policy.markAuthenticated(session);
        authenticate();
        clock.advance(Duration.ofHours(12));
        AtomicBoolean continued = new AtomicBoolean();

        new AbsoluteSessionLifetimeFilter(policy).doFilter(request, new MockHttpServletResponse(), (next, response) -> {
            assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
            assertThat(request.getSession(false)).isNull();
            continued.set(true);
        });

        assertThat(continued).isTrue();
        assertThat(session.isInvalid()).isTrue();
    }

    @Test
    void aValidRequestRetainsItsAuthenticationAndOriginalTimestamp() throws Exception {
        var request = new MockHttpServletRequest();
        MockHttpSession session = new MockHttpSession();
        request.setSession(session);
        policy.markAuthenticated(session);
        Instant authenticatedAt = clock.instant();
        authenticate();
        clock.advance(Duration.ofHours(11));

        new AbsoluteSessionLifetimeFilter(policy).doFilter(request, new MockHttpServletResponse(), (next, response) -> {
            assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
            assertThat(request.getSession(false)).isSameAs(session);
        });

        assertThat(session.getAttribute(SessionLifetimePolicy.AUTHENTICATED_AT_ATTRIBUTE)).isEqualTo(authenticatedAt);
    }

    private static void authenticate() {
        UUID id = UUID.randomUUID();
        var principal = VerifiedSessionPrincipalTestFixture.create(id, id, id);
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of()));
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-11-02T00:00:00Z");
        void advance(Duration duration) { now = now.plus(duration); }
        @Override public Instant instant() { return now; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(now, zone); }
    }
}
