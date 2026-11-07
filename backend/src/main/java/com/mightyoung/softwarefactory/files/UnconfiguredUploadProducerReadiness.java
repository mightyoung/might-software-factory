package com.mightyoung.softwarefactory.files;

/** Denies uploads independently of configured read or verification adapters. */
public final class UnconfiguredUploadProducerReadiness implements UploadProducerReadiness {
    @Override public Capability requireReady() { throw new FileEvidenceTrustUnavailableException(); }
    @Override public boolean isCurrent(Capability capability) { return false; }
}
