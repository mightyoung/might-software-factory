package com.mightyoung.softwarefactory.identity;

/** Redacted public failure; diagnostic reasons use a fixed vocabulary only. */
public final class PasswordChangeException extends RuntimeException {
    private final PasswordChangeFailureReason reason;
    private final int status;
    private final long retryAfterSeconds;
    PasswordChangeException(PasswordChangeFailureReason reason, int status) { this(reason,status,0); }
    PasswordChangeException(PasswordChangeFailureReason reason, int status, long retryAfterSeconds) {
        super("Password change could not be completed");
        this.reason=reason; this.status=status; this.retryAfterSeconds=Math.min(300,Math.max(0,retryAfterSeconds));
    }
    public PasswordChangeFailureReason reason() { return reason; }
    public int status() { return status; }
    public long retryAfterSeconds() { return retryAfterSeconds; }
}
