package com.mightyoung.softwarefactory.files;

import java.time.Duration;
import java.util.Objects;
import java.util.Set;

/** Actual producer capability; a fixed-version reader or receipt issuer cannot supply readiness.
 * Resource/scanner probing happens before a database transaction. Generation checks are local,
 * nonblocking configuration reads and must not perform external I/O.
 */
public interface UploadProducerReadiness {
    Capability requireReady();
    boolean isCurrent(Capability capability);

    record Capability(String configurationVersion, String policyVersion, String scannerDefinitionVersion,
            Set<String> allowedMediaTypes, long maxBytes, int maxItemsPerSet,
            int ownerPendingCount, long ownerPendingBytes, int tenantPendingCount,
            long tenantPendingBytes, Duration intentTtl, Duration streamTimeout, int concurrency) {
        public Capability {
            identifier(configurationVersion); identifier(policyVersion); identifier(scannerDefinitionVersion);
            allowedMediaTypes = Set.copyOf(Objects.requireNonNull(allowedMediaTypes));
            if (allowedMediaTypes.isEmpty() || allowedMediaTypes.size() > 128
                    || allowedMediaTypes.stream().anyMatch(type -> !type.matches("[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+"))) {
                throw new IllegalArgumentException("An explicit bounded media/signature policy is required");
            }
            if (maxBytes < 1 || maxBytes > 67_108_864L || maxItemsPerSet < 1 || maxItemsPerSet > 100
                    || ownerPendingCount < 1 || ownerPendingCount > 1000
                    || tenantPendingCount < ownerPendingCount || tenantPendingCount > 10000
                    || ownerPendingBytes < maxBytes || ownerPendingBytes > 6_710_886_400L
                    || tenantPendingBytes < ownerPendingBytes || tenantPendingBytes > 67_108_864_000L
                    || intentTtl == null || intentTtl.compareTo(Duration.ofMinutes(1)) < 0
                    || intentTtl.compareTo(Duration.ofHours(24)) > 0
                    || streamTimeout == null || streamTimeout.compareTo(Duration.ofSeconds(1)) < 0
                    || streamTimeout.compareTo(Duration.ofMinutes(5)) > 0 || concurrency < 1 || concurrency > 32) {
                throw new IllegalArgumentException("Upload producer bounds are invalid");
            }
        }
        private static void identifier(String value) {
            if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,63}")) {
                throw new IllegalArgumentException("A pinned producer policy identifier is required");
            }
        }
    }
}
