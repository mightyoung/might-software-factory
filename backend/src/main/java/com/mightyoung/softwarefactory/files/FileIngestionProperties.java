package com.mightyoung.softwarefactory.files;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;

/** Explicit candidate configuration. Presence of configuration never establishes resource acceptance. */
public record FileIngestionProperties(boolean enabled, Storage storage, Scanner scanner, Limits limits,
        Set<String> allowedMediaTypes, String policyVersion, String verifierId) {
    public FileIngestionProperties {
        allowedMediaTypes = allowedMediaTypes == null ? Set.of() : Set.copyOf(allowedMediaTypes);
        if (enabled) {
            Objects.requireNonNull(storage); Objects.requireNonNull(scanner); Objects.requireNonNull(limits);
            FileIngestionContracts.ident(policyVersion); FileIngestionContracts.ident(verifierId);
            if (allowedMediaTypes.isEmpty() || !Set.of("image/png", "image/jpeg").containsAll(allowedMediaTypes)) {
                throw new IllegalArgumentException("An explicit supported image policy is required");
            }
        }
    }
    static FileIngestionProperties from(Environment environment) {
        return Binder.get(environment).bind("software-factory.files.ingestion", Bindable.of(FileIngestionProperties.class))
                .orElse(new FileIngestionProperties(false, null, null, null, Set.of(), null, null));
    }
    @Override public String toString() { return "FileIngestionProperties[enabled=" + enabled + "]"; }

    public record Storage(Path quarantineRoot, Path immutableRoot, Path receiptRoot, String rootGeneration) {
        public Storage {
            absolute(quarantineRoot); absolute(immutableRoot); absolute(receiptRoot);
            if (quarantineRoot.equals(immutableRoot) || quarantineRoot.equals(receiptRoot)
                    || immutableRoot.equals(receiptRoot)) throw invalid();
            FileIngestionContracts.hash(rootGeneration);
        }
        @Override public String toString() { return "Storage[rootGeneration=" + rootGeneration + "]"; }
    }
    public record Scanner(Path launcher, String launcherSha256, Path containmentProfile, String profileSha256,
            Path executable, String executableSha256, Path definitions, String engineVersion,
            Map<String,String> inventorySha256, Map<String,String> definitionSha256,
            Map<String,Integer> definitionVersions, int functionalityLevel) {
        public Scanner {
            absolute(launcher); absolute(containmentProfile); absolute(executable); absolute(definitions);
            FileIngestionContracts.hash(launcherSha256); FileIngestionContracts.hash(profileSha256);
            FileIngestionContracts.hash(executableSha256);
            if (!"1.4.6".equals(engineVersion) || functionalityLevel < 1) throw invalid();
            inventorySha256 = Map.copyOf(Objects.requireNonNull(inventorySha256));
            definitionSha256 = Map.copyOf(Objects.requireNonNull(definitionSha256));
            definitionVersions = Map.copyOf(Objects.requireNonNull(definitionVersions));
            // This first policy accepts signed CVDs only; CLD promotion needs separate provenance review.
            Set<String> names = Set.of("main.cvd", "daily.cvd", "bytecode.cvd");
            if (!definitionSha256.keySet().equals(names) || !definitionVersions.keySet().equals(names)
                    || inventorySha256.isEmpty() || inventorySha256.size() > 64) throw invalid();
            definitionSha256.values().forEach(FileIngestionContracts::hash);
            if (definitionVersions.values().stream().anyMatch(v -> v == null || v < 1)) throw invalid();
            inventorySha256.forEach((path, hash) -> { absolute(Path.of(path)); FileIngestionContracts.hash(hash); });
        }
        @Override public String toString() { return "Scanner[engineVersion=" + engineVersion + "]"; }
    }
    public record Limits(long maxBytes, int maxItemsPerSet, int ownerPendingCount, long ownerPendingBytes,
            int tenantPendingCount, long tenantPendingBytes, Duration intentTtl, Duration streamTimeout,
            int concurrency, long scratchBytes, Duration scanTimeout, Duration proofFreshness, long maxImagePixels) {
        public Limits {
            // Reuse the reservation capability's exact validated bounds.
            new UploadProducerReadiness.Capability("bounds", "bounds", "bounds", Set.of("image/png"),
                    maxBytes, maxItemsPerSet, ownerPendingCount, ownerPendingBytes, tenantPendingCount,
                    tenantPendingBytes, intentTtl, streamTimeout, concurrency);
            if (scratchBytes < maxBytes || scratchBytes > 134_217_728L || concurrency > 2
                    || scanTimeout == null || scanTimeout.compareTo(Duration.ofSeconds(1)) < 0
                    || scanTimeout.compareTo(Duration.ofSeconds(90)) > 0
                    || proofFreshness == null || proofFreshness.compareTo(Duration.ofSeconds(1)) < 0
                    || proofFreshness.compareTo(Duration.ofMinutes(15)) > 0
                    || maxImagePixels < 1 || maxImagePixels > 40_000_000L) throw invalid();
        }
    }
    static void absolute(Path path) {
        if (path == null || !path.isAbsolute() || !path.equals(path.normalize())
                || path.toString().indexOf('\0') >= 0 || path.toString().contains("\n")) throw invalid();
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid private ingestion configuration"); }
}
