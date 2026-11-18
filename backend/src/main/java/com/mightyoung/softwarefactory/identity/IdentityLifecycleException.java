package com.mightyoung.softwarefactory.identity;

/** Closed public classifications; SQL, capability and provider details never become messages. */
public final class IdentityLifecycleException extends RuntimeException {
    public enum Reason { INVALID_REQUEST, CAPABILITY_REJECTED, CONFLICT, RATE_LIMITED, UNAVAILABLE, COMMIT_OUTCOME_UNKNOWN }
    private final Reason reason;
    public IdentityLifecycleException(Reason reason) {
        super("Identity lifecycle operation could not be completed", null, false, false);
        this.reason = java.util.Objects.requireNonNull(reason);
    }
    public Reason reason() { return reason; }
    static IdentityLifecycleException rejected() { return new IdentityLifecycleException(Reason.CAPABILITY_REJECTED); }
    static IdentityLifecycleException unavailable() { return new IdentityLifecycleException(Reason.UNAVAILABLE); }
}
