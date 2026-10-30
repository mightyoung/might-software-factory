package com.mightyoung.softwarefactory.files;

import java.util.Optional;
import java.util.UUID;

/** Resolves a current, visible file only through its sealed remediation-submission snapshot. */
public interface FindingEvidenceContentReadRepository {

    Optional<VerifiedFileEvidenceSnapshot> findReadableSealedSnapshot(
            FindingEvidenceReadScope scope, UUID findingId, int roundNo, UUID evidenceItemId);
}
