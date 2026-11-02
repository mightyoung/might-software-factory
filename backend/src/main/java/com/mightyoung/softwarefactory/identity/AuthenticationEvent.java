package com.mightyoung.softwarefactory.identity;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Compact server observation. No submitted identifiers, request material or browser-delivery assertion. */
public record AuthenticationEvent(UUID attemptId, Instant occurredAt, Outcome outcome, Subject subject) {
    public AuthenticationEvent {
        Objects.requireNonNull(attemptId, "attemptId");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(outcome, "outcome");
        boolean verified = outcome == Outcome.SESSION_PERSISTED || outcome == Outcome.SESSION_OUTCOME_UNKNOWN;
        if (verified != (subject != null)) throw new IllegalArgumentException("Outcome and verified subject disagree");
    }

    public enum Outcome {
        AUTHENTICATION_DENIED, AUTHENTICATION_UNAVAILABLE, SESSION_PERSISTED, SESSION_OUTCOME_UNKNOWN
    }

    /** Only a server-verified principal can supply an attributed subject. */
    public static final class Subject {
        private final UUID accountId, actorId, tenantId, memberId;

        private Subject(VerifiedSessionPrincipal principal) {
            accountId = Objects.requireNonNull(principal.accountId());
            actorId = Objects.requireNonNull(principal.actorId());
            tenantId = Objects.requireNonNull(principal.tenantId());
            memberId = Objects.requireNonNull(principal.memberId());
        }

        public static Subject from(VerifiedSessionPrincipal principal) {
            return new Subject(Objects.requireNonNull(principal));
        }

        public UUID accountId() { return accountId; }
        public UUID actorId() { return actorId; }
        public UUID tenantId() { return tenantId; }
        public UUID memberId() { return memberId; }
        @Override public String toString() { return "VerifiedAuthenticationSubject"; }
    }

    @Override public String toString() { return "AuthenticationEvent[" + outcome + "]"; }
}
