package com.mightyoung.softwarefactory.files;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Output from a trusted storage/content verifier, bound to an immutable object version and digest. */
public record ContentVerificationReceipt(
        UUID receiptId,
        UUID tenantId,
        UUID fileId,
        String sha256,
        String storageVersion,
        FileEvidenceVerificationLevel verificationLevel,
        String verifierId,
        String verifierKeyId,
        String verificationPolicyVersion,
        OffsetDateTime verifiedAt) {
}
