package com.mightyoung.softwarefactory.dualprevention.finding.evidenceupload;

public final class FindingEvidenceSetInitializationException extends RuntimeException {
    public enum Reason { IDEMPOTENCY_CONFLICT, VERSION_CONFLICT, QUOTA_EXCEEDED, ASSIGNMENT_UNRESOLVED, UNAVAILABLE }
    private final Reason reason;
    public FindingEvidenceSetInitializationException(Reason reason) {
        super("Evidence set initialization "+reason.name().toLowerCase(java.util.Locale.ROOT));this.reason=reason;
    }
    public Reason reason(){return reason;}
}
