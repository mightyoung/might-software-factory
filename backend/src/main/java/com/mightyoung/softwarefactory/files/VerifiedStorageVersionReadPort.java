package com.mightyoung.softwarefactory.files;

/** Opens only the immutable version and digest captured in a sealed evidence snapshot. */
public interface VerifiedStorageVersionReadPort {

    VerifiedFileContent openVerifiedVersion(VerifiedFileEvidenceSnapshot snapshot);
}
