package com.mightyoung.softwarefactory.files;

/** Public file-module boundary used before a finding command can consume file-backed evidence. */
public interface FindingEvidenceFileAccess {

    VerifiedFileEvidenceSnapshot requireVerifiedContentEvidence(EvidenceFileUse use);

    /** Validates a previously submitted receipt by its stable ID, never by resolving the current file version. */
    void requirePreviouslySubmittedEvidence(VerifiedFileEvidenceSnapshot snapshot);
}
