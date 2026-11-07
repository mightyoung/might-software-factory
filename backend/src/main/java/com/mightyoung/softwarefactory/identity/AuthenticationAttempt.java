package com.mightyoung.softwarefactory.identity;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.UUID;

/** Request-private state, enrolled only after source, parse and pair admission. Never stored in Session. */
final class AuthenticationAttempt {
    private static final String ATTRIBUTE = AuthenticationAttempt.class.getName();
    private final UUID id = UUID.randomUUID();
    private AuthenticationEvent.Subject subject;
    private boolean denied, successHandlerCompleted, sessionFailure;

    static AuthenticationAttempt enroll(HttpServletRequest request) {
        if (current(request) != null) throw new IllegalStateException("Authentication attempt already enrolled");
        var attempt = new AuthenticationAttempt();
        request.setAttribute(ATTRIBUTE, attempt);
        return attempt;
    }

    static AuthenticationAttempt current(HttpServletRequest request) {
        return request.getAttribute(ATTRIBUTE) instanceof AuthenticationAttempt value ? value : null;
    }

    void verified(VerifiedSessionPrincipal principal) { subject = AuthenticationEvent.Subject.from(principal); }
    void denied() { denied = true; }
    void successHandlerCompleted() { successHandlerCompleted = true; }
    void sessionFailure() { sessionFailure = true; }

    AuthenticationEvent finished(Instant at, boolean returnedNormally, int status) {
        AuthenticationEvent.Outcome outcome;
        if (subject != null) {
            outcome = returnedNormally && !sessionFailure && successHandlerCompleted && status == 204
                    ? AuthenticationEvent.Outcome.SESSION_PERSISTED : AuthenticationEvent.Outcome.SESSION_OUTCOME_UNKNOWN;
        } else {
            outcome = returnedNormally && !sessionFailure && denied
                    ? AuthenticationEvent.Outcome.AUTHENTICATION_DENIED : AuthenticationEvent.Outcome.AUTHENTICATION_UNAVAILABLE;
        }
        return new AuthenticationEvent(id, at, outcome, subject);
    }

    @Override public String toString() { return "AuthenticationAttempt"; }
}
