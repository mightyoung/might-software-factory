package com.mightyoung.softwarefactory.dualprevention.finding.evidenceupload;

/** Safe domain failures; HTTP mapping belongs to the strict HTTP integration slice. */
public final class FindingEvidenceUploadException extends RuntimeException {
    public enum Reason { CONFLICT, DECLARED_SIZE_EXCEEDED, MEDIA_TYPE_NOT_ALLOWED, IDEMPOTENCY_CONFLICT, VERSION_CONFLICT, EVIDENCE_REVISION_CONFLICT, QUOTA_EXCEEDED, ASSIGNMENT_UNRESOLVED, UNAVAILABLE }
    private final Reason reason;
    public FindingEvidenceUploadException(Reason reason) { super("Evidence upload " + reason.name().toLowerCase(java.util.Locale.ROOT)); this.reason = reason; }
    public Reason reason() { return reason; }
}
