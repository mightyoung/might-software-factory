package com.mightyoung.softwarefactory.files;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FindingEvidenceFileAccessPolicyTest {

    private static final UUID TENANT_ID = UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final UUID OTHER_TENANT_ID = UUID.fromString("30000000-0000-0000-0000-000000000002");
    private static final UUID ACTOR_ID = UUID.fromString("10000000-0000-0000-0000-000000000004");
    private static final UUID OTHER_ACTOR_ID = UUID.fromString("10000000-0000-0000-0000-000000000005");
    private static final UUID MEMBER_ID = UUID.fromString("20000000-0000-0000-0000-000000000004");
    private static final UUID OTHER_MEMBER_ID = UUID.fromString("20000000-0000-0000-0000-000000000005");
    private static final UUID FINDING_ID = UUID.fromString("40000000-0000-0000-0000-000000000002");
    private static final UUID EVIDENCE_SET_ID = UUID.fromString("50000000-0000-0000-0000-000000000002");
    private static final UUID EVIDENCE_ITEM_ID = UUID.fromString("51000000-0000-0000-0000-000000000002");
    private static final UUID FILE_ID = UUID.fromString("52000000-0000-0000-0000-000000000002");
    private static final byte[] SYNTHETIC_CONTENT = "synthetic inspected evidence".getBytes(StandardCharsets.UTF_8);

    private final SyntheticFileAdapter adapter = new SyntheticFileAdapter();
    private final FindingEvidenceFileAccessPolicy policy = new FindingEvidenceFileAccessPolicy(
            adapter, adapter, adapter);

    @BeforeEach
    void setUp() throws Exception {
        String checksum = sha256(SYNTHETIC_CONTENT);
        adapter.metadata = metadata(TENANT_ID, MEMBER_ID, ACTOR_ID, FileObjectState.READY,
                checksum, "disk-v1");
        adapter.content = SYNTHETIC_CONTENT.clone();
        adapter.receipt = adapter.createReceipt(adapter.metadata);
        adapter.linked = true;
    }

    @Test
    void allowsOnlyAnOwnedReadyFileLinkedToTheExactEvidenceItemWithTrustedContentReceipt() {
        policy.requireVerifiedContentEvidence(use(TENANT_ID, ACTOR_ID, MEMBER_ID, "CONTENT"));

        assertThat(adapter.lastReference).isEqualTo(use(TENANT_ID, ACTOR_ID, MEMBER_ID, "CONTENT"));
        assertThat(adapter.lastVerifiedFileId).isEqualTo(FILE_ID);
    }

    @Test
    void rejectsUnknownTenantOrFileOwnerAndMissingEvidenceReference() {
        adapter.metadata = metadata(OTHER_TENANT_ID, MEMBER_ID, ACTOR_ID, FileObjectState.READY,
                sha256Unchecked(SYNTHETIC_CONTENT), "disk-v1");
        assertDenied(use(TENANT_ID, ACTOR_ID, MEMBER_ID, "CONTENT"));

        adapter.metadata = metadata(TENANT_ID, OTHER_MEMBER_ID, ACTOR_ID, FileObjectState.READY,
                sha256Unchecked(SYNTHETIC_CONTENT), "disk-v1");
        assertDenied(use(TENANT_ID, ACTOR_ID, MEMBER_ID, "CONTENT"));

        adapter.metadata = metadata(TENANT_ID, MEMBER_ID, ACTOR_ID, FileObjectState.READY,
                sha256Unchecked(SYNTHETIC_CONTENT), "disk-v1");
        assertDenied(new EvidenceFileUse(TENANT_ID, ACTOR_ID, MEMBER_ID, FINDING_ID, 3,
                EVIDENCE_SET_ID, MEMBER_ID, EVIDENCE_ITEM_ID, FILE_ID, "CONTENT"));
        assertDenied(new EvidenceFileUse(TENANT_ID, ACTOR_ID, MEMBER_ID, UUID.randomUUID(), 2,
                EVIDENCE_SET_ID, MEMBER_ID, EVIDENCE_ITEM_ID, FILE_ID, "CONTENT"));
        assertDenied(new EvidenceFileUse(TENANT_ID, ACTOR_ID, MEMBER_ID, FINDING_ID, 2,
                UUID.randomUUID(), MEMBER_ID, EVIDENCE_ITEM_ID, FILE_ID, "CONTENT"));
        adapter.linked = false;
        assertDenied(use(TENANT_ID, ACTOR_ID, MEMBER_ID, "CONTENT"));
    }

    @Test
    void rejectsInactiveObjectsWrongActorAndClientOrDatabaseOnlyContentClaims() {
        adapter.metadata = metadata(TENANT_ID, MEMBER_ID, ACTOR_ID, FileObjectState.QUARANTINED,
                sha256Unchecked(SYNTHETIC_CONTENT), "disk-v1");
        assertDenied(use(TENANT_ID, ACTOR_ID, MEMBER_ID, "CONTENT"));

        adapter.metadata = metadata(TENANT_ID, MEMBER_ID, ACTOR_ID, FileObjectState.READY,
                sha256Unchecked(SYNTHETIC_CONTENT), "disk-v1");
        assertDenied(use(TENANT_ID, OTHER_ACTOR_ID, MEMBER_ID, "CONTENT"));
        assertDenied(use(TENANT_ID, ACTOR_ID, MEMBER_ID, "UNVERIFIED"));

        adapter.receipt = new ContentVerificationReceipt(TENANT_ID, FILE_ID, "b".repeat(64), "disk-v1",
                FileEvidenceVerificationLevel.CONTENT, "synthetic-storage-verifier",
                OffsetDateTime.parse("2026-10-30T10:00:00Z"));
        assertThatThrownBy(() -> policy.requireVerifiedContentEvidence(use(TENANT_ID, ACTOR_ID, MEMBER_ID, "CONTENT")))
                .isInstanceOf(FileEvidenceTrustUnavailableException.class);
    }

    @Test
    void unavailableTrustedStorageVerifierFailsClosed() {
        adapter.verifierAvailable = false;

        assertThatThrownBy(() -> policy.requireVerifiedContentEvidence(use(TENANT_ID, ACTOR_ID, MEMBER_ID, "CONTENT")))
                .isInstanceOf(FileEvidenceTrustUnavailableException.class);
    }

    private void assertDenied(EvidenceFileUse request) {
        assertThatThrownBy(() -> policy.requireVerifiedContentEvidence(request))
                .isInstanceOf(FileEvidenceAccessException.class);
    }

    private static EvidenceFileUse use(UUID tenantId, UUID actorId, UUID memberId, String recordedLevel) {
        return new EvidenceFileUse(tenantId, actorId, memberId, FINDING_ID, 2,
                EVIDENCE_SET_ID, memberId, EVIDENCE_ITEM_ID, FILE_ID, recordedLevel);
    }

    private static FileObjectMetadata metadata(
            UUID tenantId, UUID ownerMemberId, UUID ownerActorId, FileObjectState state,
            String checksum, String storageVersion) {
        return new FileObjectMetadata(tenantId, FILE_ID, ownerMemberId, ownerActorId,
                state, SYNTHETIC_CONTENT.length, "image/jpeg", checksum, storageVersion,
                state == FileObjectState.READY ? OffsetDateTime.parse("2026-10-30T09:00:00Z") : null);
    }

    private static String sha256Unchecked(byte[] value) {
        try {
            return sha256(value);
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    private static String sha256(byte[] value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    }

    /** In-memory fake for metadata, reference authorization, and stored bytes; it never accesses a real file. */
    private static final class SyntheticFileAdapter implements FileEvidenceMetadataReadRepository,
            EvidenceFileReferenceReadRepository, StorageContentVerificationPort {
        private FileObjectMetadata metadata;
        private byte[] content;
        private boolean linked;
        private boolean verifierAvailable = true;
        private ContentVerificationReceipt receipt;
        private EvidenceFileUse lastReference;
        private UUID lastVerifiedFileId;

        @Override
        public Optional<FileObjectMetadata> findFile(UUID tenantId, UUID fileId) {
            if (metadata == null || !metadata.tenantId().equals(tenantId) || !metadata.fileId().equals(fileId)) {
                return Optional.empty();
            }
            return Optional.of(metadata);
        }

        @Override
        public boolean hasAuthorizedEvidenceReference(EvidenceFileUse use) {
            lastReference = use;
            return linked && TENANT_ID.equals(use.tenantId()) && FINDING_ID.equals(use.findingId())
                    && EVIDENCE_SET_ID.equals(use.evidenceSetId()) && EVIDENCE_ITEM_ID.equals(use.evidenceItemId())
                    && use.roundNo() == 2 && MEMBER_ID.equals(use.evidenceSetOwnerMemberId());
        }

        @Override
        public Optional<ContentVerificationReceipt> findVerifiedContent(
                EvidenceFileUse use, FileObjectMetadata file) {
            lastVerifiedFileId = file.fileId();
            return verifierAvailable ? Optional.ofNullable(receipt) : Optional.empty();
        }

        private ContentVerificationReceipt createReceipt(FileObjectMetadata file) {
            String contentDigest = sha256Unchecked(content);
            return new ContentVerificationReceipt(file.tenantId(), file.fileId(), contentDigest,
                    file.storageVersion(), FileEvidenceVerificationLevel.CONTENT, "synthetic-storage-verifier",
                    OffsetDateTime.parse("2026-10-30T10:00:00Z"));
        }
    }
}
