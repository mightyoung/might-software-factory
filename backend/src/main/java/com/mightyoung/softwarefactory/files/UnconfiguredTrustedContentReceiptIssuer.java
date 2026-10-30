package com.mightyoung.softwarefactory.files;

/** Default deny for any upload-verification worker until a trusted storage adapter is configured. */
public final class UnconfiguredTrustedContentReceiptIssuer implements TrustedContentReceiptIssuer {

    @Override
    public ContentVerificationReceipt verifyAndRegister(FileObjectMetadata file) {
        throw new FileEvidenceTrustUnavailableException();
    }
}
