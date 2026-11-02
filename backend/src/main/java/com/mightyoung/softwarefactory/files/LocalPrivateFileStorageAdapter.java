package com.mightyoung.softwarefactory.files;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.UserPrincipal;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.regex.Pattern;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Opt-in adapter for the private local version layout: root/tenant UUID/file UUID/version.blob.
 * It never accepts or reads a database object_key or a caller-supplied path. CONTENT receipts are
 * HMAC-authenticated and stored in a server-controlled registry below the configured root.
 */
public final class LocalPrivateFileStorageAdapter
        implements StorageContentVerificationPort, VerifiedStorageVersionReadPort,
        TrustedContentReceiptIssuer, AutoCloseable {

    private static final Pattern SHA_256 = Pattern.compile("^[0-9a-f]{64}$");
    private static final Pattern STORAGE_VERSION = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_-]{0,126}$");
    private static final String RECEIPT_DIRECTORY = ".receipt-registry";
    private static final String RECEIPT_SUFFIX = ".receipt";
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final int MAX_RECEIPT_BYTES = 4096;
    private static final int BUFFER_BYTES = 32 * 1024;
    private static final long MAX_VERIFIED_CONTENT_BYTES = 67_108_864L;
    private static final long CONTENT_BUDGET_UNIT_BYTES = 1_048_576L;
    private static final int CONTENT_BUDGET_UNITS = 64;

    private final Path root;
    private final UserPrincipal storageOwner;
    private final SecureDirectoryChain rootDirectories;
    private final SecureDirectoryStream<Path> rootDirectory;
    private final SecureDirectoryChain receiptDirectoryChain;
    private final SecureDirectoryStream<Path> receiptDirectory;
    private final byte[] receiptHmacKey;
    private final String verifierId;
    private final String verifierKeyId;
    private final String policyVersion;
    private final long maxBytes;
    private final Clock clock;
    private final Runnable afterSecureFileClose;
    private final Semaphore verifiedContentBudget = new Semaphore(CONTENT_BUDGET_UNITS, true);

    public LocalPrivateFileStorageAdapter(
            Path root,
            byte[] receiptHmacKey,
            String verifierId,
            String verifierKeyId,
            String policyVersion,
            long maxBytes) {
        this(root, receiptHmacKey, verifierId, verifierKeyId, policyVersion, maxBytes, Clock.systemUTC(), () -> { });
    }

    LocalPrivateFileStorageAdapter(
            Path root,
            byte[] receiptHmacKey,
            String verifierId,
            String verifierKeyId,
            String policyVersion,
            long maxBytes,
            Clock clock) {
        this(root, receiptHmacKey, verifierId, verifierKeyId, policyVersion, maxBytes, clock, () -> { });
    }

    LocalPrivateFileStorageAdapter(
            Path root,
            byte[] receiptHmacKey,
            String verifierId,
            String verifierKeyId,
            String policyVersion,
            long maxBytes,
            Clock clock,
            Runnable afterSecureFileClose) {
        this.root = resolveTrustedRoot(root);
        this.storageOwner = currentProcessOwner(this.root);
        if (receiptHmacKey == null || receiptHmacKey.length < 32) {
            throw new IllegalArgumentException("A 256-bit receipt trust key is required");
        }
        this.receiptHmacKey = receiptHmacKey.clone();
        this.verifierId = requireToken(verifierId);
        this.verifierKeyId = requireToken(verifierKeyId);
        this.policyVersion = requireToken(policyVersion);
        if (maxBytes < 1 || maxBytes > MAX_VERIFIED_CONTENT_BYTES) {
            throw new IllegalArgumentException("The local verified content limit must be between 1 and 64 MiB");
        }
        this.maxBytes = maxBytes;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.afterSecureFileClose = Objects.requireNonNull(afterSecureFileClose, "afterSecureFileClose");
        SecureDirectoryChain roots = null;
        SecureDirectoryChain receiptDirectories = new SecureDirectoryChain();
        SecureDirectoryStream<Path> pinnedRoot;
        SecureDirectoryStream<Path> pinnedReceipts;
        try {
            roots = SecureDirectoryChain.openPath(this.root);
            pinnedRoot = roots.leaf();
            requirePrivateDirectory(pinnedRoot, storageOwner);
            pinnedReceipts = receiptDirectories.openChild(pinnedRoot, RECEIPT_DIRECTORY);
            requirePrivateDirectory(pinnedReceipts, storageOwner);
        } catch (IOException | RuntimeException exception) {
            receiptDirectories.closeQuietly();
            if (roots != null) roots.closeQuietly();
            throw new FileEvidenceTrustUnavailableException();
        }
        this.rootDirectories = roots;
        this.rootDirectory = pinnedRoot;
        this.receiptDirectoryChain = receiptDirectories;
        this.receiptDirectory = pinnedReceipts;
    }

    @Override
    public Optional<ContentVerificationReceipt> findVerifiedContent(EvidenceFileUse use, FileObjectMetadata file) {
        if (!validEvidenceUse(use, file)) return Optional.empty();
        try {
            return readReceipt(lookupName(file), null)
                    .filter(receipt -> matchesFileAndUse(file, use, receipt));
        } catch (IOException | GeneralSecurityException | RuntimeException exception) {
            return Optional.empty();
        }
    }

    @Override
    public ContentVerificationReceipt verifyAndRegister(FileObjectMetadata file) {
        if (TransactionSynchronizationManager.isActualTransactionActive() || !validFileMetadata(file)) {
            throw new FileEvidenceTrustUnavailableException();
        }
        try (OpenedSecureFile opened = openVersion(file.tenantId(), file.fileId(), file.storageVersion())) {
            if (opened.initialAttributes.size() != file.sizeBytes()) {
                throw new FileEvidenceTrustUnavailableException();
            }
            String digest = digestAndReset(opened);
            if (!digest.equals(file.sha256())) throw new FileEvidenceTrustUnavailableException();
            Optional<ContentVerificationReceipt> registered = readReceipt(lookupName(file), null)
                    .filter(receipt -> matchesFile(file, receipt));
            if (registered.isPresent()) {
                ContentVerificationReceipt existing = registered.orElseThrow();
                if (findByReceiptId(file.tenantId(), existing.receiptId()).filter(existing::equals).isPresent()) {
                    return existing;
                }
                throw new FileEvidenceTrustUnavailableException();
            }

            // Canonicalize before signing so PostgreSQL timestamptz preserves the exact receipt time.
            ContentVerificationReceipt receipt = new ContentVerificationReceipt(
                    UUID.randomUUID(), file.tenantId(), file.fileId(), digest, file.storageVersion(),
                    FileEvidenceVerificationLevel.CONTENT, verifierId, verifierKeyId, policyVersion,
                    OffsetDateTime.ofInstant(clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS),
                            ZoneOffset.UTC));
            if (writeReceipt(receipt)) return receipt;
            ContentVerificationReceipt existing = readReceipt(lookupName(file), null)
                    .filter(value -> matchesFile(file, value))
                    .orElseThrow(FileEvidenceTrustUnavailableException::new);
            if (findByReceiptId(file.tenantId(), existing.receiptId()).filter(existing::equals).isEmpty()) {
                throw new FileEvidenceTrustUnavailableException();
            }
            return existing;
        } catch (IOException | GeneralSecurityException | RuntimeException exception) {
            if (exception instanceof FileEvidenceTrustUnavailableException trustException) throw trustException;
            throw new FileEvidenceTrustUnavailableException();
        }
    }

    @Override
    public Optional<ContentVerificationReceipt> findByReceiptId(UUID tenantId, UUID receiptId) {
        if (tenantId == null || receiptId == null) return Optional.empty();
        try {
            return readReceipt(receiptName(receiptId), receiptId)
                    .filter(receipt -> tenantId.equals(receipt.tenantId()));
        } catch (IOException | GeneralSecurityException | RuntimeException exception) {
            return Optional.empty();
        }
    }

    @Override
    public VerifiedFileContent openVerifiedVersion(VerifiedFileEvidenceSnapshot snapshot) {
        if (snapshot == null || !receiptMatchesSnapshot(snapshot)) {
            throw new FileEvidenceTrustUnavailableException();
        }
        int reservedUnits = 0;
        boolean reservationHeld = false;
        byte[] verifiedBytes;
        try {
            ContentVerificationReceipt receipt = readReceipt(
                            receiptName(snapshot.verificationReceiptId()), snapshot.verificationReceiptId())
                    .orElseThrow(FileEvidenceTrustUnavailableException::new);
            if (!matches(snapshot, receipt)) throw new FileEvidenceTrustUnavailableException();
            try (OpenedSecureFile opened = openVersion(
                    snapshot.tenantId(), snapshot.fileId(), snapshot.storageVersion())) {
                reservedUnits = contentBudgetUnits(opened.initialAttributes.size());
                if (!verifiedContentBudget.tryAcquire(reservedUnits)) {
                    throw new FileEvidenceTrustUnavailableException();
                }
                reservationHeld = true;
                verifiedBytes = readAndVerify(opened, snapshot.sha256());
            }
        } catch (IOException | GeneralSecurityException | RuntimeException | Error exception) {
            if (reservationHeld) verifiedContentBudget.release(reservedUnits);
            if (exception instanceof FileEvidenceTrustUnavailableException trustException) throw trustException;
            if (exception instanceof Error error) throw error;
            throw new FileEvidenceTrustUnavailableException();
        }
        try {
            // Transfer the memory reservation only after the outer secure file handles closed successfully.
            return new InMemoryVerifiedFileContent(verifiedBytes, verifiedContentBudget, reservedUnits);
        } catch (RuntimeException | Error exception) {
            verifiedContentBudget.release(reservedUnits);
            throw exception;
        }
    }

    @Override
    public void close() throws IOException {
        IOException failure = null;
        try {
            receiptDirectoryChain.close();
        } catch (IOException exception) {
            failure = exception;
        }
        try {
            rootDirectories.close();
        } catch (IOException exception) {
            if (failure == null) failure = exception;
            else failure.addSuppressed(exception);
        }
        if (failure != null) throw failure;
    }

    private boolean validEvidenceUse(EvidenceFileUse use, FileObjectMetadata file) {
        return validFileMetadata(file) && use != null && use.tenantId() != null && use.fileId() != null
                && use.tenantId().equals(file.tenantId()) && use.fileId().equals(file.fileId())
                && "CONTENT".equals(use.recordedVerificationLevel())
                && Objects.equals(file.sha256(), normalizeDigest(use.recordedSha256()));
    }

    private boolean validFileMetadata(FileObjectMetadata file) {
        return file != null && file.tenantId() != null && file.fileId() != null
                && file.state() == FileObjectState.READY && file.verifiedAt() != null
                && file.sizeBytes() > 0 && file.sizeBytes() <= maxBytes
                && validDigest(file.sha256()) && file.storageVersion() != null
                && STORAGE_VERSION.matcher(file.storageVersion()).matches();
    }

    private boolean matchesFile(FileObjectMetadata file, ContentVerificationReceipt receipt) {
        return receipt != null && file.tenantId().equals(receipt.tenantId())
                && file.fileId().equals(receipt.fileId())
                && file.sha256().equals(receipt.sha256())
                && file.storageVersion().equals(receipt.storageVersion())
                && receipt.verificationLevel() == FileEvidenceVerificationLevel.CONTENT
                && verifierId.equals(receipt.verifierId()) && verifierKeyId.equals(receipt.verifierKeyId())
                && policyVersion.equals(receipt.verificationPolicyVersion());
    }

    private boolean matchesFileAndUse(
            FileObjectMetadata file, EvidenceFileUse use, ContentVerificationReceipt receipt) {
        return matchesFile(file, receipt)
                && use.tenantId().equals(receipt.tenantId()) && use.fileId().equals(receipt.fileId())
                && Objects.equals(normalizeDigest(use.recordedSha256()), receipt.sha256());
    }

    private boolean receiptMatchesSnapshot(VerifiedFileEvidenceSnapshot snapshot) {
        return snapshot.tenantId() != null && snapshot.fileId() != null && snapshot.sha256() != null
                && snapshot.storageVersion() != null && STORAGE_VERSION.matcher(snapshot.storageVersion()).matches()
                && snapshot.verificationReceiptId() != null;
    }

    private boolean matches(VerifiedFileEvidenceSnapshot snapshot, ContentVerificationReceipt receipt) {
        return receipt != null && snapshot.tenantId().equals(receipt.tenantId())
                && snapshot.fileId().equals(receipt.fileId())
                && snapshot.verificationReceiptId().equals(receipt.receiptId())
                && snapshot.sha256().equals(receipt.sha256())
                && snapshot.storageVersion().equals(receipt.storageVersion())
                && snapshot.verificationLevel() == receipt.verificationLevel()
                && snapshot.verifierId().equals(receipt.verifierId())
                && snapshot.verifierKeyId().equals(receipt.verifierKeyId())
                && snapshot.verificationPolicyVersion().equals(receipt.verificationPolicyVersion())
                && snapshot.verifiedAt().isEqual(receipt.verifiedAt());
    }

    private String digestAndReset(OpenedSecureFile opened) throws IOException, GeneralSecurityException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        ByteBuffer buffer = ByteBuffer.allocate(BUFFER_BYTES);
        opened.channel.position(0);
        long byteCount = 0;
        int read;
        while ((read = opened.channel.read(buffer)) >= 0) {
            if (Thread.currentThread().isInterrupted()) throw new IOException("Interrupted storage read");
            if (read == 0) continue;
            byteCount += read;
            if (byteCount > maxBytes) throw new IOException("Storage object exceeds configured size limit");
            buffer.flip();
            digest.update(buffer);
            buffer.clear();
        }
        opened.verifyUnchanged();
        if (byteCount != opened.initialAttributes.size()) throw new IOException("Storage size changed");
        opened.channel.position(0);
        return HexFormat.of().formatHex(digest.digest());
    }

    private byte[] readAndVerify(OpenedSecureFile opened, String expectedDigest)
            throws IOException, GeneralSecurityException {
        long size = opened.initialAttributes.size();
        if (size < 1 || size > maxBytes || size > Integer.MAX_VALUE) {
            throw new IOException("Storage object exceeds configured size limit");
        }
        byte[] verifiedBytes = new byte[(int) size];
        ByteBuffer buffer = ByteBuffer.wrap(verifiedBytes);
        opened.channel.position(0);
        while (buffer.hasRemaining()) {
            if (Thread.currentThread().isInterrupted()) throw new IOException("Interrupted storage read");
            int read = opened.channel.read(buffer);
            if (read < 0) throw new IOException("Storage size changed");
            if (read == 0) continue;
        }
        ByteBuffer extraByte = ByteBuffer.allocate(1);
        if (opened.channel.read(extraByte) != -1) throw new IOException("Storage size changed");
        opened.verifyUnchanged();
        String actualDigest = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(verifiedBytes));
        if (!expectedDigest.equals(actualDigest)) throw new FileEvidenceTrustUnavailableException();
        return verifiedBytes;
    }

    private int contentBudgetUnits(long sizeBytes) throws IOException {
        if (sizeBytes < 1 || sizeBytes > maxBytes || sizeBytes > MAX_VERIFIED_CONTENT_BYTES) {
            throw new IOException("Storage object exceeds configured size limit");
        }
        return Math.toIntExact((sizeBytes + CONTENT_BUDGET_UNIT_BYTES - 1) / CONTENT_BUDGET_UNIT_BYTES);
    }

    private OpenedSecureFile openVersion(UUID tenantId, UUID fileId, String version) throws IOException {
        if (tenantId == null || fileId == null || version == null || !STORAGE_VERSION.matcher(version).matches()) {
            throw new IOException("Unsupported storage version");
        }
        SecureDirectoryChain chain = new SecureDirectoryChain();
        FileChannel openedChannel = null;
        try {
            SecureDirectoryStream<Path> tenantDirectory = chain.openChild(rootDirectory, tenantId.toString());
            SecureDirectoryStream<Path> fileDirectory = chain.openChild(tenantDirectory, fileId.toString());
            Path leaf = root.getFileSystem().getPath(version + ".blob");
            BasicFileAttributes attributes = readFileAttributes(fileDirectory, leaf);
            if (!attributes.isRegularFile() || attributes.isSymbolicLink() || attributes.fileKey() == null
                    || attributes.size() < 1 || attributes.size() > maxBytes) {
                throw new IOException("Invalid stored object");
            }
            requireReadOnlyObject(fileDirectory, leaf, storageOwner);
            Path absolutePath = root.resolve(tenantId.toString()).resolve(fileId.toString()).resolve(leaf);
            requireSingleLink(absolutePath, attributes.fileKey());
            Set<OpenOption> options = Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
            SeekableByteChannel channel = fileDirectory.newByteChannel(leaf, options);
            if (!(channel instanceof FileChannel fileChannel)) {
                channel.close();
                throw new IOException("Secure file channels are unavailable");
            }
            openedChannel = fileChannel;
            OpenedSecureFile opened = new OpenedSecureFile(chain, fileDirectory, leaf, fileChannel, attributes,
                    storageOwner, absolutePath, afterSecureFileClose);
            opened.verifyUnchanged();
            return opened;
        } catch (IOException | RuntimeException exception) {
            if (openedChannel != null) {
                try {
                    openedChannel.close();
                } catch (IOException closeFailure) {
                    exception.addSuppressed(closeFailure);
                }
            }
            chain.closeQuietly();
            throw exception;
        }
    }

    private boolean writeReceipt(ContentVerificationReceipt receipt) throws IOException, GeneralSecurityException {
        byte[] payload = encodePayload(receipt);
        byte[] mac = hmac(payload);
        byte[] complete = (new String(payload, java.nio.charset.StandardCharsets.UTF_8) + "\n"
                + HexFormat.of().formatHex(mac)).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (complete.length > MAX_RECEIPT_BYTES) return false;
        boolean idWritten = writeReceiptFile(receiptName(receipt.receiptId()), complete);
        if (!idWritten) return false;
        return writeReceiptFile(lookupName(receipt.tenantId(), receipt.fileId(),
                receipt.storageVersion(), receipt.sha256()), complete);
    }

    private boolean writeReceiptFile(String registryName, byte[] complete) throws IOException {
        Path leaf = root.getFileSystem().getPath(registryName);
        Set<OpenOption> options = Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS);
        try (SeekableByteChannel channel = receiptDirectory.newByteChannel(leaf, options)) {
            ByteBuffer buffer = ByteBuffer.wrap(complete);
            while (buffer.hasRemaining()) channel.write(buffer);
            if (channel instanceof FileChannel fileChannel) fileChannel.force(true);
            return true;
        } catch (java.nio.file.FileAlreadyExistsException exception) {
            return false;
        }
    }

    private Optional<ContentVerificationReceipt> readReceipt(String registryName, UUID requestedReceiptId)
            throws IOException, GeneralSecurityException {
        if (registryName == null || !registryName.matches("^(id-[0-9a-f-]{36}|lookup-[0-9a-f]{64})\\.receipt$")) {
                return Optional.empty();
        }
        Path leaf = root.getFileSystem().getPath(registryName);
        BasicFileAttributes attributes;
        try {
            attributes = readFileAttributes(receiptDirectory, leaf);
        } catch (IOException exception) {
            return Optional.empty();
        }
        if (!attributes.isRegularFile() || attributes.isSymbolicLink() || attributes.size() < 1
                || attributes.size() > MAX_RECEIPT_BYTES) return Optional.empty();
        Set<OpenOption> options = Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
        try (SeekableByteChannel channel = receiptDirectory.newByteChannel(leaf, options)) {
            byte[] data = new byte[(int) attributes.size()];
            ByteBuffer buffer = ByteBuffer.wrap(data);
            while (buffer.hasRemaining()) {
                int read = channel.read(buffer);
                if (read < 0) return Optional.empty();
                if (read == 0) continue;
            }
            ByteBuffer extraByte = ByteBuffer.allocate(1);
            if (channel.read(extraByte) != -1 || channel.size() != attributes.size()) return Optional.empty();
            return decodeAndVerify(data, requestedReceiptId);
        }
    }

    private Optional<ContentVerificationReceipt> decodeAndVerify(byte[] encoded, UUID requestedReceiptId)
            throws GeneralSecurityException {
        String content = new String(encoded, java.nio.charset.StandardCharsets.UTF_8);
        int signatureSeparator = content.lastIndexOf('\n');
        if (signatureSeparator < 0) return Optional.empty();
        byte[] payload = content.substring(0, signatureSeparator)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] suppliedMac;
        try {
            suppliedMac = HexFormat.of().parseHex(content.substring(signatureSeparator + 1));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
        if (!MessageDigest.isEqual(hmac(payload), suppliedMac)) return Optional.empty();
        String[] fields = content.substring(0, signatureSeparator).split("\n", -1);
        if (fields.length != 10) return Optional.empty();
        try {
            ContentVerificationReceipt receipt = new ContentVerificationReceipt(
                    UUID.fromString(fields[0]), UUID.fromString(fields[1]), UUID.fromString(fields[2]),
                    fields[3], fields[4], FileEvidenceVerificationLevel.valueOf(fields[5]), fields[6],
                    fields[7], fields[8], OffsetDateTime.parse(fields[9]));
            if ((requestedReceiptId != null && !requestedReceiptId.equals(receipt.receiptId()))
                    || !validDigest(receipt.sha256())
                    || receipt.verificationLevel() != FileEvidenceVerificationLevel.CONTENT
                    || !receipt.verifierId().equals(verifierId) || !receipt.verifierKeyId().equals(verifierKeyId)
                    || !receipt.verificationPolicyVersion().equals(policyVersion)) {
                return Optional.empty();
            }
            return Optional.of(receipt);
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    private byte[] encodePayload(ContentVerificationReceipt receipt) {
        return String.join("\n", receipt.receiptId().toString(), receipt.tenantId().toString(),
                receipt.fileId().toString(), receipt.sha256(), receipt.storageVersion(),
                receipt.verificationLevel().name(), receipt.verifierId(), receipt.verifierKeyId(),
                receipt.verificationPolicyVersion(), receipt.verifiedAt().toString())
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private byte[] hmac(byte[] payload) throws GeneralSecurityException {
        Mac mac = Mac.getInstance(HMAC_ALGORITHM);
        mac.init(new SecretKeySpec(receiptHmacKey, HMAC_ALGORITHM));
        return mac.doFinal(payload);
    }

    private static Path resolveTrustedRoot(Path configuredRoot) {
        try {
            if (configuredRoot == null || !configuredRoot.isAbsolute()
                    || Files.isSymbolicLink(configuredRoot)
                    || !Files.isDirectory(configuredRoot, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Invalid private storage root");
            }
            Path resolved = configuredRoot.toRealPath();
            if (Files.isSymbolicLink(resolved) || !Files.isDirectory(resolved, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Invalid private storage root");
            }
            return resolved;
        } catch (IOException | SecurityException exception) {
            throw new FileEvidenceTrustUnavailableException();
        }
    }

    private static String requireToken(String value) {
        if (value == null || !value.matches("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")) {
            throw new IllegalArgumentException("Verifier provenance is invalid");
        }
        return value;
    }

    private static String receiptName(UUID receiptId) {
        return "id-" + receiptId + RECEIPT_SUFFIX;
    }

    private static String lookupName(UUID tenantId, UUID fileId, String version, String sha256)
            throws GeneralSecurityException {
        String key = tenantId + "\n" + fileId + "\n" + version + "\n" + sha256;
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(key.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return "lookup-" + HexFormat.of().formatHex(digest) + RECEIPT_SUFFIX;
    }

    private static String lookupName(FileObjectMetadata file) throws GeneralSecurityException {
        return lookupName(file.tenantId(), file.fileId(), file.storageVersion(), file.sha256());
    }

    private static boolean validDigest(String digest) {
        return digest != null && SHA_256.matcher(digest).matches();
    }

    private static String normalizeDigest(String digest) {
        return digest == null ? null : digest.strip().toLowerCase(Locale.ROOT);
    }

    private static BasicFileAttributes readFileAttributes(SecureDirectoryStream<Path> directory, Path leaf)
            throws IOException {
        BasicFileAttributeView view = directory.getFileAttributeView(leaf, BasicFileAttributeView.class,
                LinkOption.NOFOLLOW_LINKS);
        if (view == null) throw new IOException("File attributes are unavailable");
        return view.readAttributes();
    }

    private static void requirePrivateDirectory(
            SecureDirectoryStream<Path> directory, UserPrincipal expectedOwner) throws IOException {
        PosixFileAttributeView view = directory.getFileAttributeView(PosixFileAttributeView.class);
        if (view == null) throw new IOException("Private storage permissions are unavailable");
        var attributes = view.readAttributes();
        Set<PosixFilePermission> permissions = attributes.permissions();
        Set<PosixFilePermission> privateOwnerPermissions = Set.of(PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE);
        Set<PosixFilePermission> sharedPermissions = Set.of(PosixFilePermission.GROUP_READ,
                PosixFilePermission.GROUP_WRITE, PosixFilePermission.GROUP_EXECUTE,
                PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_WRITE,
                PosixFilePermission.OTHERS_EXECUTE);
        if (!attributes.owner().equals(expectedOwner)
                || !permissions.containsAll(privateOwnerPermissions)
                || sharedPermissions.stream().anyMatch(permissions::contains)) {
            throw new IOException("Private storage directory permissions are invalid");
        }
    }

    private static void requireReadOnlyObject(
            SecureDirectoryStream<Path> directory, Path leaf, UserPrincipal expectedOwner) throws IOException {
        PosixFileAttributeView view = directory.getFileAttributeView(
                leaf, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (view == null) throw new IOException("Immutable storage permissions are unavailable");
        var attributes = view.readAttributes();
        Set<PosixFilePermission> permissions = attributes.permissions();
        Set<PosixFilePermission> writePermissions = Set.of(PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.GROUP_WRITE, PosixFilePermission.OTHERS_WRITE);
        Set<PosixFilePermission> sharedReadPermissions = Set.of(
                PosixFilePermission.GROUP_READ, PosixFilePermission.OTHERS_READ);
        if (!attributes.owner().equals(expectedOwner)
                || !permissions.contains(PosixFilePermission.OWNER_READ)
                || writePermissions.stream().anyMatch(permissions::contains)
                || sharedReadPermissions.stream().anyMatch(permissions::contains)) {
            throw new IOException("Storage version is not immutable");
        }
    }

    private static void requireSingleLink(Path absolutePath, Object expectedFileKey) throws IOException {
        BasicFileAttributes pathAttributes = Files.readAttributes(
                absolutePath, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        Object linkCount = Files.getAttribute(absolutePath, "unix:nlink", LinkOption.NOFOLLOW_LINKS);
        if (!pathAttributes.isRegularFile() || pathAttributes.isSymbolicLink()
                || !Objects.equals(expectedFileKey, pathAttributes.fileKey())
                || !(linkCount instanceof Number number) || number.longValue() != 1) {
            throw new IOException("Storage version must be a single-link file");
        }
    }

    private static UserPrincipal currentProcessOwner(Path path) {
        try {
            String userName = System.getProperty("user.name");
            if (userName == null || userName.isBlank()) throw new IOException("Service account is unavailable");
            return path.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByName(userName);
        } catch (IOException | SecurityException exception) {
            throw new FileEvidenceTrustUnavailableException();
        }
    }

    private static final class SecureDirectoryChain implements AutoCloseable {
        private final List<DirectoryStream<Path>> streams = new ArrayList<>();

        static SecureDirectoryChain openPath(Path absolutePath) throws IOException {
            SecureDirectoryChain chain = new SecureDirectoryChain();
            try {
                Path filesystemRoot = absolutePath.getRoot();
                if (filesystemRoot == null) throw new IOException("Storage path must be absolute");
                DirectoryStream<Path> stream = Files.newDirectoryStream(filesystemRoot);
                chain.streams.add(stream);
                if (!(stream instanceof SecureDirectoryStream<?> secure)) {
                    throw new IOException("Secure directory access is unavailable");
                }
                @SuppressWarnings("unchecked")
                SecureDirectoryStream<Path> current = (SecureDirectoryStream<Path>) secure;
                for (Path component : filesystemRoot.relativize(absolutePath)) {
                    current = chain.openChild(current, component.toString());
                }
                return chain;
            } catch (IOException | RuntimeException exception) {
                chain.closeQuietly();
                throw exception;
            }
        }

        SecureDirectoryStream<Path> leaf() throws IOException {
            if (streams.isEmpty() || !(streams.get(streams.size() - 1) instanceof SecureDirectoryStream<?> secure)) {
                throw new IOException("Secure directory access is unavailable");
            }
            @SuppressWarnings("unchecked")
            SecureDirectoryStream<Path> typed = (SecureDirectoryStream<Path>) secure;
            return typed;
        }

        SecureDirectoryStream<Path> openChild(SecureDirectoryStream<Path> parent, String name)
                throws IOException {
            Path childName = Path.of(name);
            DirectoryStream<Path> stream = parent.newDirectoryStream(childName, LinkOption.NOFOLLOW_LINKS);
            streams.add(stream);
            if (!(stream instanceof SecureDirectoryStream<?> secure)) {
                throw new IOException("Secure directory access is unavailable");
            }
            @SuppressWarnings("unchecked")
            SecureDirectoryStream<Path> typed = (SecureDirectoryStream<Path>) secure;
            return typed;
        }

        void closeQuietly() {
            try {
                close();
            } catch (IOException ignored) {
                // Preserve the original storage failure.
            }
        }

        @Override
        public void close() throws IOException {
            IOException failure = null;
            for (int index = streams.size() - 1; index >= 0; index--) {
                try {
                    streams.get(index).close();
                } catch (IOException exception) {
                    if (failure == null) failure = exception;
                    else failure.addSuppressed(exception);
                }
            }
            streams.clear();
            if (failure != null) throw failure;
        }
    }

    private static final class OpenedSecureFile implements AutoCloseable {
        private final SecureDirectoryChain chain;
        private final SecureDirectoryStream<Path> parent;
        private final Path leaf;
        private final FileChannel channel;
        private final BasicFileAttributes initialAttributes;
        private final UserPrincipal storageOwner;
        private final Path absolutePath;
        private final Runnable afterClose;

        private OpenedSecureFile(
                SecureDirectoryChain chain, SecureDirectoryStream<Path> parent, Path leaf, FileChannel channel,
                BasicFileAttributes initialAttributes, UserPrincipal storageOwner, Path absolutePath,
                Runnable afterClose) {
            this.chain = chain;
            this.parent = parent;
            this.leaf = leaf;
            this.channel = channel;
            this.initialAttributes = initialAttributes;
            this.storageOwner = storageOwner;
            this.absolutePath = absolutePath;
            this.afterClose = afterClose;
        }

        private void verifyUnchanged() throws IOException {
            BasicFileAttributes current = readFileAttributes(parent, leaf);
            requireReadOnlyObject(parent, leaf, storageOwner);
            requireSingleLink(absolutePath, initialAttributes.fileKey());
            if (!current.isRegularFile() || current.isSymbolicLink()
                    || !Objects.equals(initialAttributes.fileKey(), current.fileKey())
                    || initialAttributes.size() != current.size()
                    || !initialAttributes.lastModifiedTime().equals(current.lastModifiedTime())
                    || channel.size() != initialAttributes.size()) {
                throw new IOException("Storage object changed during verification");
            }
        }

        @Override
        public void close() throws IOException {
            IOException failure = null;
            try {
                channel.close();
            } catch (IOException exception) {
                failure = exception;
            }
            try {
                chain.close();
            } catch (IOException exception) {
                if (failure == null) failure = exception;
                else failure.addSuppressed(exception);
            }
            try {
                afterClose.run();
            } catch (RuntimeException exception) {
                if (failure != null) exception.addSuppressed(failure);
                throw exception;
            }
            if (failure != null) throw failure;
        }
    }

    private static final class InMemoryVerifiedFileContent implements VerifiedFileContent {
        private final Semaphore memoryBudget;
        private final int reservedUnits;
        private byte[] verifiedBytes;
        private boolean closed;

        private InMemoryVerifiedFileContent(byte[] verifiedBytes, Semaphore memoryBudget, int reservedUnits) {
            this.verifiedBytes = verifiedBytes;
            this.memoryBudget = memoryBudget;
            this.reservedUnits = reservedUnits;
        }

        @Override
        public synchronized long sizeBytes() {
            byte[] current = verifiedBytes;
            return current == null ? 0 : current.length;
        }

        @Override
        public synchronized void transferTo(OutputStream output) throws IOException {
            Objects.requireNonNull(output, "output");
            byte[] bytes = verifiedBytes;
            if (bytes == null) throw new IOException("Verified file content is closed");
            int offset = 0;
            while (offset < bytes.length) {
                if (Thread.currentThread().isInterrupted()) throw new IOException("Storage response was cancelled");
                int count = Math.min(BUFFER_BYTES, bytes.length - offset);
                output.write(bytes, offset, count);
                offset += count;
            }
        }

        @Override
        public synchronized void close() throws IOException {
            if (!closed) {
                closed = true;
                verifiedBytes = null;
                memoryBudget.release(reservedUnits);
            }
        }
    }
}
