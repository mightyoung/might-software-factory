package com.mightyoung.softwarefactory.dualprevention;

import com.mightyoung.softwarefactory.files.VerifiedFileContent;

import java.util.UUID;

/** Public seam for an authorized request to read one submitted evidence item. */
public interface FindingEvidenceContentReader {

    VerifiedFileContent openSubmittedEvidence(UUID findingId, int roundNo, UUID evidenceItemId);
}
