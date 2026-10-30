package com.mightyoung.softwarefactory.files;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/** Fail-closed policy for owner-only evidence links and trusted, object-version-bound content receipts. */
@org.springframework.stereotype.Component
public final class FindingEvidenceFileAccessPolicy implements FindingEvidenceFileAccess {

    private static final Pattern SHA_256 = Pattern.compile("^[0-9a-f]{64}$");

    private final FileEvidenceMetadataReadRepository metadataRepository;
    private final EvidenceFileReferenceReadRepository referenceRepository;
    private final StorageContentVerificationPort contentVerifier;

    public FindingEvidenceFileAccessPolicy(
            FileEvidenceMetadataReadRepository metadataRepository,
            EvidenceFileReferenceReadRepository referenceRepository,
            StorageContentVerificationPort contentVerifier) {
        this.metadataRepository = Objects.requireNonNull(metadataRepository, "metadataRepository");
        this.referenceRepository = Objects.requireNonNull(referenceRepository, "referenceRepository");
        this.contentVerifier = Objects.requireNonNull(contentVerifier, "contentVerifier");
    }

    @Override
    public VerifiedFileEvidenceSnapshot requireVerifiedContentEvidence(EvidenceFileUse use) {
        if (!validUse(use) || !"CONTENT".equals(use.recordedVerificationLevel())
                || !use.memberId().equals(use.evidenceSetOwnerMemberId())) {
            throw new FileEvidenceAccessException();
        }
        UUID fileLinkId = referenceRepository.findAuthorizedEvidenceReference(use)
                .orElseThrow(FileEvidenceAccessException::new);

        FileObjectMetadata file = metadataRepository.findFile(use.tenantId(), use.fileId())
                .filter(value -> sameUuid(value.tenantId(), use.tenantId())
                        && sameUuid(value.fileId(), use.fileId()))
                .orElseThrow(FileEvidenceAccessException::new);
        if (file.ownerMemberId() == null || !file.ownerMemberId().equals(use.evidenceSetOwnerMemberId())
                || file.ownerActorId() == null || !file.ownerActorId().equals(use.actorId())
                || file.state() != FileObjectState.READY || file.sizeBytes() <= 0
                || file.verifiedAt() == null
                || file.mimeType() == null || file.mimeType().isBlank()
                || !validDigest(file.sha256()) || file.storageVersion() == null || file.storageVersion().isBlank()) {
            throw new FileEvidenceAccessException();
        }
        if (!file.sha256().equals(normalizeDigest(use.recordedSha256()))) {
            throw new FileEvidenceAccessException();
        }

        ContentVerificationReceipt receipt = contentVerifier.findVerifiedContent(use, file)
                .orElseThrow(FileEvidenceTrustUnavailableException::new);
        if (!receiptMatches(file, use.tenantId(), receipt)) {
            throw new FileEvidenceTrustUnavailableException();
        }
        return snapshot(use, file, fileLinkId, receipt);
    }

    @Override
    public void requirePreviouslySubmittedEvidence(VerifiedFileEvidenceSnapshot snapshot) {
        if (snapshot == null) throw new FileEvidenceAccessException();
        ContentVerificationReceipt receipt = contentVerifier
                .findByReceiptId(snapshot.tenantId(), snapshot.verificationReceiptId())
                .orElseThrow(FileEvidenceTrustUnavailableException::new);
        if (!sameUuid(receipt.tenantId(), snapshot.tenantId())
                || !sameUuid(receipt.fileId(), snapshot.fileId())
                || !sameUuid(receipt.receiptId(), snapshot.verificationReceiptId())
                || !snapshot.sha256().equals(normalizeDigest(receipt.sha256()))
                || !snapshot.storageVersion().equals(receipt.storageVersion())
                || receipt.verificationLevel() != FileEvidenceVerificationLevel.CONTENT
                || !snapshot.verifierId().equals(receipt.verifierId())
                || !snapshot.verifierKeyId().equals(receipt.verifierKeyId())
                || !snapshot.verificationPolicyVersion().equals(receipt.verificationPolicyVersion())
                || receipt.verifiedAt() == null
                || !snapshot.verifiedAt().isEqual(receipt.verifiedAt())) {
            throw new FileEvidenceTrustUnavailableException();
        }
    }

    private static boolean receiptMatches(
            FileObjectMetadata file, UUID tenantId, ContentVerificationReceipt receipt) {
        return receipt.receiptId() != null
                && sameUuid(receipt.tenantId(), tenantId)
                && sameUuid(receipt.fileId(), file.fileId())
                && file.sha256().equals(normalizeDigest(receipt.sha256()))
                && file.storageVersion().equals(receipt.storageVersion())
                && receipt.verificationLevel() == FileEvidenceVerificationLevel.CONTENT
                && nonBlank(receipt.verifierId()) && nonBlank(receipt.verifierKeyId())
                && nonBlank(receipt.verificationPolicyVersion()) && receipt.verifiedAt() != null;
    }

    private static boolean nonBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static VerifiedFileEvidenceSnapshot snapshot(
            EvidenceFileUse use, FileObjectMetadata file, UUID fileLinkId, ContentVerificationReceipt receipt) {
        return new VerifiedFileEvidenceSnapshot(
                use.tenantId(), use.evidenceItemId(), file.fileId(), fileLinkId,
                file.ownerMemberId(), file.ownerActorId(), file.sha256(), file.storageVersion(),
                receipt.receiptId(), receipt.verifierId(), receipt.verifierKeyId(),
                receipt.verificationPolicyVersion(), receipt.verificationLevel(), receipt.verifiedAt());
    }

    private static boolean validUse(EvidenceFileUse use) {
        return use != null && use.tenantId() != null && use.actorId() != null && use.memberId() != null
                && use.findingId() != null && use.roundNo() > 0 && use.evidenceSetId() != null
                && use.evidenceSetOwnerMemberId() != null && use.evidenceItemId() != null && use.fileId() != null
                && use.recordedSha256() != null;
    }

    private static boolean validDigest(String digest) {
        return digest != null && SHA_256.matcher(digest).matches();
    }

    private static String normalizeDigest(String digest) {
        return digest == null ? null : digest.strip().toLowerCase(Locale.ROOT);
    }

    private static boolean sameUuid(UUID actual, UUID expected) {
        return actual != null && actual.equals(expected);
    }
}
