package com.mightyoung.softwarefactory.files;

/** Default deny when no trusted immutable-version reader has been configured. */
public final class UnconfiguredVerifiedStorageVersionReadPort implements VerifiedStorageVersionReadPort {

    @Override
    public VerifiedFileContent openVerifiedVersion(VerifiedFileEvidenceSnapshot snapshot) {
        throw new FileEvidenceTrustUnavailableException();
    }
}
