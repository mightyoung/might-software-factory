package com.mightyoung.softwarefactory.files;

import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/** Immutable provenance captured when a file is accepted into one remediation submission. */
public record VerifiedFileEvidenceSnapshot(
        UUID tenantId,
        UUID evidenceItemId,
        UUID fileId,
        UUID fileLinkId,
        UUID ownerMemberId,
        UUID ownerActorId,
        String sha256,
        String storageVersion,
        UUID verificationReceiptId,
        String verifierId,
        String verifierKeyId,
        String verificationPolicyVersion,
        FileEvidenceVerificationLevel verificationLevel,
        OffsetDateTime verifiedAt) {

    private static final Pattern SHA_256 = Pattern.compile("^[0-9a-f]{64}$");

    public VerifiedFileEvidenceSnapshot {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(evidenceItemId, "evidenceItemId");
        Objects.requireNonNull(fileId, "fileId");
        Objects.requireNonNull(fileLinkId, "fileLinkId");
        Objects.requireNonNull(ownerMemberId, "ownerMemberId");
        Objects.requireNonNull(ownerActorId, "ownerActorId");
        Objects.requireNonNull(verificationReceiptId, "verificationReceiptId");
        Objects.requireNonNull(verificationLevel, "verificationLevel");
        Objects.requireNonNull(verifiedAt, "verifiedAt");
        if (sha256 == null || !SHA_256.matcher(sha256).matches()) {
            throw new IllegalArgumentException("A canonical SHA-256 digest is required");
        }
        if (storageVersion == null || storageVersion.isBlank()
                || verifierId == null || verifierId.isBlank()
                || verifierKeyId == null || verifierKeyId.isBlank()
                || verificationPolicyVersion == null || verificationPolicyVersion.isBlank()) {
            throw new IllegalArgumentException("Immutable storage and verifier provenance is required");
        }
        if (!verificationLevel.isContentVerified()) {
            throw new IllegalArgumentException("Only content-verified evidence can be snapshotted");
        }
    }
}
