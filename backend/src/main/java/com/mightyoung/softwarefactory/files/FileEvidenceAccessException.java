package com.mightyoung.softwarefactory.files;

/** The file is unknown, incorrectly linked, owned by another member, or otherwise not authorized. */
public final class FileEvidenceAccessException extends RuntimeException {

    public FileEvidenceAccessException() {
        super("The file is not authorized for this evidence item");
    }
}
