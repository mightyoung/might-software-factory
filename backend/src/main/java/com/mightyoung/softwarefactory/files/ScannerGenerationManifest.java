package com.mightyoung.softwarefactory.files;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import static com.mightyoung.softwarefactory.files.FileIngestionContracts.*;

/** Full immutable resource snapshot; inspecting it alone does not accept a scanner generation. */
public final class ScannerGenerationManifest {
    private final FileIngestionProperties properties;
    private final ImmutableContainmentProfile profile;
    private final List<PinnedFile> resources;
    private final List<Definition> definitions;
    private final byte[] resourcePayload;
    private final String resourceSha256;
    private final Instant dailyFreshUntil;

    private ScannerGenerationManifest(FileIngestionProperties properties, ImmutableContainmentProfile profile,
            List<PinnedFile> resources, List<Definition> definitions, byte[] payload, Instant dailyFreshUntil) {
        this.properties = properties; this.profile = profile; this.resources = List.copyOf(resources);
        this.definitions = List.copyOf(definitions); this.resourcePayload = payload.clone();
        this.resourceSha256 = sha256(payload); this.dailyFreshUntil = dailyFreshUntil;
    }

    static ScannerGenerationManifest inspect(FileIngestionProperties properties,
            ImmutableContainmentProfile profile, Deadline deadline, Instant now) throws IOException {
        var scanner = properties.scanner();
        var resources = new ArrayList<PinnedFile>();
        resources.add(PinnedFile.read(scanner.launcher(), 67_108_864L, deadline, true));
        resources.add(PinnedFile.read(scanner.containmentProfile(), 32_768, deadline, true));
        resources.add(PinnedFile.read(scanner.executable(), 134_217_728L, deadline, true));
        if (!resources.get(0).sha256.equals(scanner.launcherSha256())
                || !resources.get(1).sha256.equals(scanner.profileSha256())
                || !resources.get(2).sha256.equals(scanner.executableSha256())) throw unavailable();
        requireImmutablePath(scanner.definitions(), true);
        try (var entries = Files.newDirectoryStream(scanner.definitions())) {
            var actual = new java.util.HashSet<String>();
            for (Path entry : entries) actual.add(entry.getFileName().toString());
            if (!actual.equals(Set.of("main.cvd", "daily.cvd", "bytecode.cvd"))) throw unavailable();
        }
        var definitions = new ArrayList<Definition>();
        Instant freshUntil = null;
        for (String name : List.of("main.cvd", "daily.cvd", "bytecode.cvd")) {
            PinnedFile file = PinnedFile.read(scanner.definitions().resolve(name), 2_147_483_648L, deadline, true);
            Definition definition = parseHeader(name, file.header, file.size, file.sha256, now, scanner.functionalityLevel());
            if (!file.sha256.equals(scanner.definitionSha256().get(name))
                    || definition.version != scanner.definitionVersions().get(name)) throw unavailable();
            if (name.equals("daily.cvd")) freshUntil = definition.builtAt.plus(Duration.ofHours(48));
            definitions.add(definition); resources.add(file);
        }
        if (scanner.inventorySha256().keySet().stream().noneMatch(path -> Path.of(path).getFileName().toString().contains("libclamav"))
                || scanner.inventorySha256().keySet().stream().noneMatch(path -> Path.of(path).getFileName().toString().startsWith("clamav-package"))) {
            throw unavailable();
        }
        for (var entry : scanner.inventorySha256().entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
            PinnedFile file = PinnedFile.read(Path.of(entry.getKey()), 2_147_483_648L, deadline, true);
            if (!file.sha256.equals(entry.getValue())) throw unavailable();
            resources.add(file);
        }
        // Every physical slot has its own staged immutable root; no host/staged DB mismatch.
        for(var slot:profile.slots())for(var entry:profile.inventory().entrySet()){
            Path actual=slot.hostScratch().getParent().resolve(entry.getKey());
            if(resources.stream().anyMatch(existing->existing.path.equals(actual)))continue;
            PinnedFile file=PinnedFile.read(actual,2_147_483_648L,deadline,true);if(!file.sha256.equals(entry.getValue()))throw unavailable();resources.add(file);
        }
        var payload = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(payload)) {
            out.writeUTF("MSF_SCANNER_RESOURCES_V2"); out.writeUTF(scanner.engineVersion());
            out.writeUTF(profile.id()); out.writeUTF(profile.sha256());
            out.writeUTF(properties.storage().rootGeneration()); out.writeUTF(properties.policyVersion());
            out.writeUTF(ClamScanProcessAdapter.MEDIA_POLICY_SHA256);
            for (String media : properties.allowedMediaTypes().stream().sorted().toList()) out.writeUTF(media);
            out.writeUTF("END_MEDIA");
            for (PinnedFile file : resources) { out.writeUTF(file.path.toString()); out.writeLong(file.size); out.writeUTF(file.sha256); out.writeUTF(file.key.toString());out.writeUTF(file.modified.toString());out.writeUTF(file.changed.toString()); }
            out.writeUTF("END_RESOURCES");
            for (Definition definition : definitions) {
                out.writeUTF(definition.name); out.writeInt(definition.version); out.writeLong(definition.signatureCount);
                out.writeInt(definition.functionalityLevel); out.writeLong(definition.builtAt.getEpochSecond());
                out.writeUTF(definition.headerSignature);
            }
            for (String arg : ClamScanProcessAdapter.policyArguments(properties)) out.writeUTF(arg);
            out.writeUTF("LC_ALL=C"); out.writeUTF("TZ=UTC");
            out.writeUTF(ClamScanProcessAdapter.effectivePolicySha256(properties));
        }
        if (payload.size() > MAX_CANONICAL_BYTES) throw unavailable();
        return new ScannerGenerationManifest(properties, profile, resources, definitions, payload.toByteArray(), freshUntil);
    }

    /** Exact header fields from the pinned ClamAV 1.4.6 CVD format. Signature validity is additionally checked by the real engine. */
    static Definition parseHeader(String name, byte[] header, long size, String sha256, Instant now, int engineFunctionality) {
        if (!Set.of("main.cvd", "daily.cvd", "bytecode.cvd").contains(name) || header.length != 512 || size <= 512) throw unavailable();
        for (byte value : header) if (value < 32 || value > 126) throw unavailable();
        String[] fields = new String(header, java.nio.charset.StandardCharsets.US_ASCII).stripTrailing().split(":", -1);
        if (fields.length != 9 || !fields[0].equals("ClamAV-VDB") || fields[1].isEmpty()
                || fields[1].length() > 48 || !fields[5].matches("[0-9a-f]{32}")
                || !fields[6].matches("[A-Za-z0-9+/=]{16,256}") || !fields[7].matches("[A-Za-z0-9._-]{1,64}")) throw unavailable();
        try {
            int version = positiveInteger(fields[2]);
            long signatures = positiveLong(fields[3]);
            int functionality = positiveInteger(fields[4]);
            Instant built = Instant.ofEpochSecond(positiveLong(fields[8]));
            if (functionality > engineFunctionality || built.isAfter(now.plus(Duration.ofMinutes(5)))
                    || (name.equals("daily.cvd") && built.isBefore(now.minus(Duration.ofHours(48))))) throw unavailable();
            FileIngestionContracts.hash(sha256);
            return new Definition(name, size, sha256, version, signatures, functionality, built, fields[6]);
        } catch (RuntimeException malformed) { throw unavailable(); }
    }
    private static int positiveInteger(String value) { return Math.toIntExact(positiveLong(value)); }
    private static long positiveLong(String value) {
        if (!value.matches("[1-9][0-9]{0,18}")) throw unavailable();
        return Long.parseLong(value);
    }

    void verifyUnchanged(Deadline deadline, Instant now) throws IOException {
        if (!now.isBefore(dailyFreshUntil)) throw unavailable();
        for (PinnedFile resource : resources) resource.verifyMetadata();
        // Actual scan boundaries rehash all pinned resources; mutable deployment artifacts cannot reuse controls.
        for (PinnedFile resource : resources) {
            PinnedFile current = PinnedFile.read(resource.path, resource.size, deadline, true);
            if (!resource.same(current)) throw unavailable();
        }
    }
    void verifyMetadata(Instant now) throws IOException {
        if (!now.isBefore(dailyFreshUntil)) throw unavailable();
        for (PinnedFile resource : resources) resource.verifyMetadata();
    }
    byte[] resourcePayload() { return resourcePayload.clone(); }
    String resourceSha256() { return resourceSha256; }
    Instant dailyFreshUntil() { return dailyFreshUntil; }
    ImmutableContainmentProfile profile() { return profile; }
    Set<String> executableDigests() {
        var digests = new java.util.HashSet<>(properties.scanner().inventorySha256().values());
        digests.add(properties.scanner().executableSha256()); digests.add(properties.scanner().launcherSha256());
        return Set.copyOf(digests);
    }
    FileIngestionProperties properties() { return properties; }
    @Override public String toString() { return "ScannerGenerationManifest[resourceSha256=" + resourceSha256 + "]"; }
    record Definition(String name, long size, String sha256, int version, long signatureCount,
                      int functionalityLevel, Instant builtAt, String headerSignature) {}

    static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 is unavailable"); }
    }
    static void requireImmutablePath(Path path, boolean directory) throws IOException {
        FileIngestionProperties.absolute(path);
        Path current = path.getRoot();
        for (Path component : path) {
            current = current.resolve(component);
            if (Files.isSymbolicLink(current) || (!current.equals(path) && Files.isWritable(current))) throw unavailable();
        }
        if (directory ? !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) : !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw unavailable();
        if (Files.isWritable(path)) throw unavailable();
        Set<java.nio.file.attribute.PosixFilePermission> mode = Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS);
        if (mode.contains(java.nio.file.attribute.PosixFilePermission.GROUP_WRITE)
                || mode.contains(java.nio.file.attribute.PosixFilePermission.OTHERS_WRITE)) throw unavailable();
    }
    static FileEvidenceTrustUnavailableException unavailable() { return new FileEvidenceTrustUnavailableException(); }

    static final class PinnedFile {
        final Path path; final long size; final String sha256; final byte[] header;
        final Object key; final java.nio.file.attribute.FileTime modified, changed;
        private PinnedFile(Path path, long size, String sha256, byte[] header, BasicFileAttributes a, java.nio.file.attribute.FileTime changed) {
            this.path = path; this.size = size; this.sha256 = sha256; this.header = header.clone();
            this.key = a.fileKey(); this.modified = a.lastModifiedTime(); this.changed = changed;
        }
        static PinnedFile read(Path path, long maximum, Deadline deadline, boolean immutable) throws IOException {
            if (immutable) requireImmutablePath(path, false);
            BasicFileAttributes before = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!before.isRegularFile() || before.fileKey() == null || before.size() < 1 || before.size() > maximum
                    || ((Number) Files.getAttribute(path, "unix:nlink", LinkOption.NOFOLLOW_LINKS)).longValue() != 1) throw unavailable();
            var change = (java.nio.file.attribute.FileTime) Files.getAttribute(path, "unix:ctime", LinkOption.NOFOLLOW_LINKS);
            var digest = digest(); byte[] header = new byte[(int)Math.min(512, before.size())]; long count = 0;
            try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                ByteBuffer buffer = ByteBuffer.allocate(32_768);
                int n;
                while ((n = channel.read(buffer)) >= 0) {
                    deadline.remainingNanos(); if (n == 0) continue;
                    count = Math.addExact(count, n); if (count > maximum) throw unavailable();
                    buffer.flip();
                    if (count - n < header.length) {
                        int start = Math.toIntExact(count - n), copy = Math.min(n, header.length - start);
                        buffer.duplicate().get(header, start, copy);
                    }
                    digest.update(buffer); buffer.clear();
                }
                if (channel.size() != before.size() || count != before.size()) throw unavailable();
            }
            PinnedFile result = new PinnedFile(path, count, HexFormat.of().formatHex(digest.digest()), header, before, change);
            result.verifyMetadata(); return result;
        }
        void verifyMetadata() throws IOException {
            requireImmutablePath(path, false);
            BasicFileAttributes a = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!Objects.equals(key, a.fileKey()) || size != a.size() || !modified.equals(a.lastModifiedTime())
                    || !changed.equals(Files.getAttribute(path, "unix:ctime", LinkOption.NOFOLLOW_LINKS))
                    || ((Number)Files.getAttribute(path,"unix:nlink",LinkOption.NOFOLLOW_LINKS)).longValue()!=1) throw unavailable();
        }
        boolean same(PinnedFile other) { return path.equals(other.path) && size == other.size && sha256.equals(other.sha256) && key.equals(other.key) && modified.equals(other.modified) && changed.equals(other.changed); }
        private static MessageDigest digest() { try { return MessageDigest.getInstance("SHA-256"); } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); } }
    }
}
