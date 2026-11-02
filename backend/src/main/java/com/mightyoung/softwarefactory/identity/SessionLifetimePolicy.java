package com.mightyoung.softwarefactory.identity;

import jakarta.servlet.http.HttpSession;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/** Absolute authentication lifetime, independent of the session's sliding idle timeout. */
public final class SessionLifetimePolicy {

    public static final String AUTHENTICATED_AT_ATTRIBUTE =
            SessionLifetimePolicy.class.getName() + ".authenticatedAt";

    private final Clock clock;
    private final Duration absoluteTimeout;

    public SessionLifetimePolicy(Clock clock, Duration absoluteTimeout) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.absoluteTimeout = Objects.requireNonNull(absoluteTimeout, "absoluteTimeout");
        try {
            if (absoluteTimeout.toMillis() < 1) {
                throw new IllegalArgumentException("Absolute session timeout must be at least one millisecond");
            }
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("Absolute session timeout is too large", overflow);
        }
    }

    /** Called only after successful authentication, including re-authentication of an old session. */
    public void markAuthenticated(HttpSession session) {
        session.setAttribute(AUTHENTICATED_AT_ATTRIBUTE, clock.instant());
    }

    /** Missing legacy metadata and clock rollback fail closed; reads never extend the lifetime. */
    public boolean isExpired(HttpSession session) {
        if (session == null) return true;
        Object value;
        try {
            value = session.getAttribute(AUTHENTICATED_AT_ATTRIBUTE);
        } catch (IllegalStateException invalidated) {
            return true;
        }
        if (!(value instanceof Instant authenticatedAt)) return true;
        Instant now = clock.instant();
        if (authenticatedAt.isAfter(now)) return true;
        try {
            return !now.isBefore(authenticatedAt.plus(absoluteTimeout));
        } catch (DateTimeException | ArithmeticException overflow) {
            return true;
        }
    }
}
