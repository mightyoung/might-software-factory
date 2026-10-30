package com.mightyoung.softwarefactory.files;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.nio.file.attribute.PosixFilePermission;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalPrivateFileStorageAdapterTest {

    private static final UUID TENANT_ID = UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final UUID ACTOR_ID = UUID.fromString("10000000-0000-0000-0000-000000000004");
    private static final UUID MEMBER_ID = UUID.fromString("20000000-0000-0000-0000-000000000004");
    private static final UUID FINDING_ID = UUID.fromString("40000000-0000-0000-0000-000000000002");
    private static final UUID SET_ID = UUID.fromString("50000000-0000-0000-0000-000000000002");
    private static final UUID ITEM_ID = UUID.fromString("51000000-0000-0000-0000-000000000002");
    private static final UUID FILE_ID = UUID.fromString("52000000-0000-0000-0000-000000000002");
    private static final UUID LINK_ID = UUID.fromString("53000000-0000-0000-0000-000000000002");
    private static final byte[] TRUST_KEY = "synthetic-only-hmac-key-32-bytes!".getBytes(StandardCharsets.UTF_8);
    private static final byte[] VERSION_ONE = "synthetic evidence version one".getBytes(StandardCharsets.UTF_8);
    private static final Set<PosixFilePermission> PRIVATE_DIRECTORY_PERMISSIONS = Set.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE);
    private static final Set<PosixFilePermission> SHARED_DIRECTORY_PERMISSIONS = Set.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE,
            PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_EXECUTE,
            PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_EXECUTE);
    private static final Set<PosixFilePermission> READ_ONLY_FILE_PERMISSIONS = Set.of(PosixFilePermission.OWNER_READ);

    @TempDir Path root;

    private LocalPrivateFileStorageAdapter adapter;

    @BeforeEach
    void setUp() throws Exception {
        Files.setPosixFilePermissions(root, PRIVATE_DIRECTORY_PERMISSIONS);
        Files.createDirectory(root.resolve(".receipt-registry"),
                java.nio.file.attribute.PosixFilePermissions.asFileAttribute(PRIVATE_DIRECTORY_PERMISSIONS));
        adapter = new LocalPrivateFileStorageAdapter(
                root, TRUST_KEY, "local-test-verifier", "test-key-1", "content-v1", 1_048_576L);
    }

    @AfterEach
    void closeAdapter() throws Exception {
        if (adapter != null) adapter.close();
    }

    @Test
    void issuesAndResolvesAReceiptOnlyAfterHashingTheServerStoredBytes() throws Exception {
        writeVersion("disk-v1", VERSION_ONE);
        FileObjectMetadata metadata = metadata("disk-v1", VERSION_ONE);

        assertThat(adapter.findVerifiedContent(use("disk-v1", VERSION_ONE), metadata)).isEmpty();
        ContentVerificationReceipt receipt = adapter.verifyAndRegister(metadata);
        Optional<ContentVerificationReceipt> result = adapter.findVerifiedContent(use("disk-v1", VERSION_ONE), metadata);

        assertThat(result).isPresent();
        assertThat(result.orElseThrow()).isEqualTo(receipt);
        assertThat(receipt.tenantId()).isEqualTo(TENANT_ID);
        assertThat(receipt.fileId()).isEqualTo(FILE_ID);
        assertThat(receipt.sha256()).isEqualTo(sha256(VERSION_ONE));
        assertThat(receipt.storageVersion()).isEqualTo("disk-v1");
        assertThat(receipt.verificationLevel()).isEqualTo(FileEvidenceVerificationLevel.CONTENT);
        assertThat(adapter.findByReceiptId(TENANT_ID, receipt.receiptId())).contains(receipt);
        assertThat(adapter.findByReceiptId(UUID.randomUUID(), receipt.receiptId())).isEmpty();
    }

    @Test
    void refusesToRegisterAReceiptWhenStoredBytesDoNotMatchTheDatabaseDigestClaim() throws Exception {
        writeVersion("disk-v1", VERSION_ONE);
        byte[] differentBytes = "different synthetic bytes".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> adapter.verifyAndRegister(metadata("disk-v1", differentBytes)))
                .isInstanceOf(FileEvidenceTrustUnavailableException.class);
    }

    @Test
    void missingAndUnsupportedStorageVersionsFailClosed() throws Exception {
        FileObjectMetadata missing = metadata("disk-v1", VERSION_ONE);
        assertThat(adapter.findVerifiedContent(use("disk-v1", VERSION_ONE), missing)).isEmpty();
        assertThatThrownBy(() -> adapter.verifyAndRegister(missing))
                .isInstanceOf(FileEvidenceTrustUnavailableException.class);

        writeVersion("disk-v1", VERSION_ONE);
        FileObjectMetadata unsupported = metadata("../latest", VERSION_ONE);
        assertThat(adapter.findVerifiedContent(use("../latest", VERSION_ONE), unsupported)).isEmpty();
        assertThatThrownBy(() -> adapter.verifyAndRegister(unsupported))
                .isInstanceOf(FileEvidenceTrustUnavailableException.class);
    }

    @Test
    void refusesStorageRootsOrReceiptRegistriesReadableByOtherUsers() throws Exception {
        Path sharedRoot = Files.createDirectory(root.resolve("shared-root"),
                java.nio.file.attribute.PosixFilePermissions.asFileAttribute(SHARED_DIRECTORY_PERMISSIONS));
        Files.createDirectory(sharedRoot.resolve(".receipt-registry"),
                java.nio.file.attribute.PosixFilePermissions.asFileAttribute(PRIVATE_DIRECTORY_PERMISSIONS));
        assertThatThrownBy(() -> new LocalPrivateFileStorageAdapter(
                sharedRoot, TRUST_KEY, "local-test-verifier", "test-key-1", "content-v1", 1_048_576L))
                .isInstanceOf(FileEvidenceTrustUnavailableException.class);

        Path privateRoot = Files.createDirectory(root.resolve("private-root"),
                java.nio.file.attribute.PosixFilePermissions.asFileAttribute(PRIVATE_DIRECTORY_PERMISSIONS));
        Files.createDirectory(privateRoot.resolve(".receipt-registry"),
                java.nio.file.attribute.PosixFilePermissions.asFileAttribute(SHARED_DIRECTORY_PERMISSIONS));
        assertThatThrownBy(() -> new LocalPrivateFileStorageAdapter(
                privateRoot, TRUST_KEY, "local-test-verifier", "test-key-1", "content-v1", 1_048_576L))
                .isInstanceOf(FileEvidenceTrustUnavailableException.class);
    }

    @Test
    void byteVerificationCannotRunInsideABusinessTransaction() throws Exception {
        writeVersion("disk-v1", VERSION_ONE);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThatThrownBy(() -> adapter.verifyAndRegister(metadata("disk-v1", VERSION_ONE)))
                    .isInstanceOf(FileEvidenceTrustUnavailableException.class);
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    @Test
    void refusesAStorageVersionThatIsAPathSymlink() throws Exception {
        Path file = root.resolve(TENANT_ID.toString()).resolve(FILE_ID.toString()).resolve("disk-v1.blob");
        Files.createDirectories(file.getParent());
        Path otherSyntheticFile = root.resolve("outside-synthetic-content");
        Files.write(otherSyntheticFile, VERSION_ONE);
        Files.createSymbolicLink(file, otherSyntheticFile);

        assertThatThrownBy(() -> adapter.verifyAndRegister(metadata("disk-v1", VERSION_ONE)))
                .isInstanceOf(FileEvidenceTrustUnavailableException.class);
    }

    @Test
    void refusesASymlinkedTenantDirectory() throws Exception {
        Path outsideDirectory = root.resolve("outside-synthetic-directory");
        Files.createDirectory(outsideDirectory);
        Files.createSymbolicLink(root.resolve(TENANT_ID.toString()), outsideDirectory);

        assertThatThrownBy(() -> adapter.verifyAndRegister(metadata("disk-v1", VERSION_ONE)))
                .isInstanceOf(FileEvidenceTrustUnavailableException.class)
                .hasMessage("Trusted file content verification is unavailable");
    }

    @Test
    void refusesAStorageVersionThatIsStillWritable() throws Exception {
        writeVersion("disk-v1", VERSION_ONE);
        Path storedVersion = root.resolve(TENANT_ID.toString()).resolve(FILE_ID.toString()).resolve("disk-v1.blob");
        Files.setPosixFilePermissions(storedVersion,
                Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));

        assertThatThrownBy(() -> adapter.verifyAndRegister(metadata("disk-v1", VERSION_ONE)))
                .isInstanceOf(FileEvidenceTrustUnavailableException.class)
                .hasMessage("Trusted file content verification is unavailable");
    }

    @Test
    void rejectsReceiptTamperingAndSnapshotReceiptMismatch() throws Exception {
        writeVersion("disk-v1", VERSION_ONE);
        ContentVerificationReceipt receipt = adapter.verifyAndRegister(metadata("disk-v1", VERSION_ONE));
        Path receiptFile = root.resolve(".receipt-registry").resolve("id-" + receipt.receiptId() + ".receipt");
        Files.writeString(receiptFile, "tampered synthetic receipt");

        assertThat(adapter.findByReceiptId(TENANT_ID, receipt.receiptId())).isEmpty();
        assertThatThrownBy(() -> adapter.openVerifiedVersion(snapshot(receipt)))
                .isInstanceOf(FileEvidenceTrustUnavailableException.class);

        byte[] secondVersion = "synthetic second evidence version".getBytes(StandardCharsets.UTF_8);
        writeVersion("disk-v2", secondVersion);
        ContentVerificationReceipt validReceipt = adapter.verifyAndRegister(metadata("disk-v2", secondVersion));
        VerifiedFileEvidenceSnapshot wrongDigest = new VerifiedFileEvidenceSnapshot(
                TENANT_ID, ITEM_ID, FILE_ID, LINK_ID, MEMBER_ID, ACTOR_ID, "b".repeat(64),
                validReceipt.storageVersion(), validReceipt.receiptId(), validReceipt.verifierId(),
                validReceipt.verifierKeyId(), validReceipt.verificationPolicyVersion(),
                validReceipt.verificationLevel(), validReceipt.verifiedAt());
        assertThatThrownBy(() -> adapter.openVerifiedVersion(wrongDigest))
                .isInstanceOf(FileEvidenceTrustUnavailableException.class);
    }

    @Test
    void opensOnlyTheReceiptPinnedVersionAndNeverFallsBackToAnotherVersion() throws Exception {
        byte[] newerBytes = "synthetic evidence version two".getBytes(StandardCharsets.UTF_8);
        writeVersion("disk-v1", VERSION_ONE);
        ContentVerificationReceipt receipt = adapter.verifyAndRegister(metadata("disk-v1", VERSION_ONE));
        writeVersion("disk-v2", newerBytes);

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (VerifiedFileContent content = adapter.openVerifiedVersion(snapshot(receipt))) {
            content.transferTo(output);
        }

        assertThat(output.toByteArray()).containsExactly(VERSION_ONE);
    }

    @Test
    void responseUsesTheBytesVerifiedBeforeTheStoragePathCanBeReplaced() throws Exception {
        writeVersion("disk-v1", VERSION_ONE);
        ContentVerificationReceipt receipt = adapter.verifyAndRegister(metadata("disk-v1", VERSION_ONE));
        VerifiedFileContent content = adapter.openVerifiedVersion(snapshot(receipt));

        writeVersion("disk-v1", "changed after verified handle was opened".getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (content) {
            content.transferTo(output);
        }

        assertThat(output.toByteArray()).containsExactly(VERSION_ONE);
    }

    @Test
    void concurrentVerifiedResponsesCannotExceedTheAggregateMemoryBudget() throws Exception {
        writeVersion("disk-v1", VERSION_ONE);
        ContentVerificationReceipt receipt = adapter.verifyAndRegister(metadata("disk-v1", VERSION_ONE));
        List<VerifiedFileContent> openResponses = new ArrayList<>();

        try {
            for (int index = 0; index < 64; index++) {
                openResponses.add(adapter.openVerifiedVersion(snapshot(receipt)));
            }
            assertThatThrownBy(() -> adapter.openVerifiedVersion(snapshot(receipt)))
                    .isInstanceOf(FileEvidenceTrustUnavailableException.class);

            openResponses.remove(0).close();
            openResponses.add(adapter.openVerifiedVersion(snapshot(receipt)));
        } finally {
            for (VerifiedFileContent response : openResponses) response.close();
        }
    }

    @Test
    void detectsTamperingBeforeReturningAnyContentBytes() throws Exception {
        writeVersion("disk-v1", VERSION_ONE);
        ContentVerificationReceipt receipt = adapter.verifyAndRegister(metadata("disk-v1", VERSION_ONE));
        writeVersion("disk-v1", "tampered synthetic bytes".getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        assertThatThrownBy(() -> adapter.openVerifiedVersion(snapshot(receipt)))
                .isInstanceOf(FileEvidenceTrustUnavailableException.class)
                .hasMessage("Trusted file content verification is unavailable");
        assertThat(output.toByteArray()).isEmpty();
    }

    private FileObjectMetadata metadata(String version, byte[] digestContent) throws Exception {
        return new FileObjectMetadata(TENANT_ID, FILE_ID, MEMBER_ID, ACTOR_ID, FileObjectState.READY,
                digestContent.length, "application/octet-stream", sha256(digestContent), version,
                OffsetDateTime.parse("2026-10-30T10:00:00Z"));
    }

    private EvidenceFileUse use(String version, byte[] content) throws Exception {
        return new EvidenceFileUse(TENANT_ID, ACTOR_ID, MEMBER_ID, FINDING_ID, 2, SET_ID, MEMBER_ID, ITEM_ID,
                FILE_ID, "CONTENT", sha256(content));
    }

    private VerifiedFileEvidenceSnapshot snapshot(ContentVerificationReceipt receipt) {
        return new VerifiedFileEvidenceSnapshot(TENANT_ID, ITEM_ID, FILE_ID, LINK_ID, MEMBER_ID, ACTOR_ID,
                receipt.sha256(), receipt.storageVersion(), receipt.receiptId(), receipt.verifierId(),
                receipt.verifierKeyId(), receipt.verificationPolicyVersion(), receipt.verificationLevel(),
                receipt.verifiedAt());
    }

    private void writeVersion(String version, byte[] bytes) throws Exception {
        Path file = root.resolve(TENANT_ID.toString()).resolve(FILE_ID.toString()).resolve(version + ".blob");
        Files.createDirectories(file.getParent());
        if (Files.exists(file)) {
            Files.setPosixFilePermissions(file,
                    Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        }
        Files.write(file, bytes);
        Files.setPosixFilePermissions(file, READ_ONLY_FILE_PERMISSIONS);
    }

    private static String sha256(byte[] content) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    }
}
