package com.mightyoung.softwarefactory.files;

/** Public file-module boundary used before a finding command can consume file-backed evidence. */
public interface FindingEvidenceFileAccess {

    void requireVerifiedContentEvidence(EvidenceFileUse use);
}
