package com.mightyoung.softwarefactory.files;

/** The trusted source required to validate file bytes or provenance is not available. */
public final class FileEvidenceTrustUnavailableException extends RuntimeException {

    public FileEvidenceTrustUnavailableException() {
        super("Trusted file content verification is unavailable");
    }
}
