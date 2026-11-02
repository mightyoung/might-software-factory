package com.mightyoung.softwarefactory.identity;

/** Fixed diagnostic vocabulary; no secret, arbitrary message, request or session identifiers. */
public enum PasswordChangeFailureReason {
    CURRENT_PASSWORD_REJECTED, STALE_IDENTITY, INVALID_NEW_PASSWORD, PREPARATION_EXPIRED,
    RATE_LIMITED, WRITE_FAILED, AUDIT_UNAVAILABLE, COMMIT_OUTCOME_UNKNOWN;

    public String eventResult() {
        return switch (this) {
            case COMMIT_OUTCOME_UNKNOWN -> "UNKNOWN";
            case WRITE_FAILED, AUDIT_UNAVAILABLE -> "FAILED";
            default -> "DENIED";
        };
    }
}
