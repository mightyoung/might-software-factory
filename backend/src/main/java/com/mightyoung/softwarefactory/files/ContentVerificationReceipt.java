package com.mightyoung.softwarefactory.files;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Output from a trusted storage/content verifier, bound to an immutable object version and digest. */
public record ContentVerificationReceipt(
        UUID tenantId,
        UUID fileId,
        String sha256,
        String storageVersion,
        FileEvidenceVerificationLevel verificationLevel,
        String verifierId,
        OffsetDateTime verifiedAt) {
}
