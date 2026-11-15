package com.mightyoung.softwarefactory.files;

/** Verification levels returned by a trusted server-side content verifier. */
public enum FileEvidenceVerificationLevel {
    UNVERIFIED,
    METADATA,
    CONTENT,
    /** A separately signed ingestion V2 receipt, never a legacy CONTENT receipt. */
    SCANNED_CONTENT_V2;

    public boolean isContentVerified() {
        return this == CONTENT || this == SCANNED_CONTENT_V2;
    }

    public static boolean isContentVerified(String recorded) {
        return CONTENT.name().equals(recorded) || SCANNED_CONTENT_V2.name().equals(recorded);
    }
}
