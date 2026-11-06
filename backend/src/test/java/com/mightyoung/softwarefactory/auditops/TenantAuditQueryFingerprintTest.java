package com.mightyoung.softwarefactory.auditops;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static com.mightyoung.softwarefactory.auditops.TenantAuditQueryContracts.*;
import static org.assertj.core.api.Assertions.*;

class TenantAuditQueryFingerprintTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final Instant FROM = Instant.parse("2026-11-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-11-02T00:00:00Z");
    private static final Window WINDOW = new Window(FROM, TO);

    @Test void versionedTypedFramingHasAStableGoldenVectorAndResolvedDefaultEquivalence() {
        var implicit = new Query(null, null, null, null, null, null, null, 20, null);
        var explicit = new Query(FROM, TO, null, null, null, null, null, 20, null);
        // Independently calculated UTF-8/null-marker/big-endian-length vector, not mapper JSON output.
        assertThat(TenantAuditQueryFingerprint.of(TENANT, WINDOW, implicit))
                .isEqualTo("e0d78bff1dbdb56ca090ae9c0ecff68b9a119d1e103f29f9f02601da8d251f16");
        assertThat(TenantAuditQueryFingerprint.of(TENANT, WINDOW, explicit)).isEqualTo(TenantAuditQueryFingerprint.of(TENANT, WINDOW, implicit));
    }

    @Test void pageSizeAndCursorAreExcludedFromContinuationConsistency() {
        var first = new Query(FROM, TO, null, "finding", null, "finding.create", Result.SUCCESS, 1, null);
        var after = new Query(FROM, TO, null, "finding", null, "finding.create", Result.SUCCESS, 50, new Cursor(FROM, OTHER, "f".repeat(64)));
        assertThat(TenantAuditQueryFingerprint.of(TENANT, WINDOW, first)).isEqualTo(TenantAuditQueryFingerprint.of(TENANT, WINDOW, after));
    }

    @Test void everyProtectedFilterTenantAndEffectiveWindowAffectsTheDigest() {
        var base = new Query(FROM, TO, TENANT, "finding", TENANT, "finding.create", Result.SUCCESS, 20, null);
        String expected = TenantAuditQueryFingerprint.of(TENANT, WINDOW, base);
        for (var changed : List.of(
                new Query(FROM, TO, OTHER, "finding", TENANT, "finding.create", Result.SUCCESS, 20, null),
                new Query(FROM, TO, TENANT, "equipment", TENANT, "finding.create", Result.SUCCESS, 20, null),
                new Query(FROM, TO, TENANT, "finding", OTHER, "finding.create", Result.SUCCESS, 20, null),
                new Query(FROM, TO, TENANT, "finding", TENANT, "finding.update", Result.SUCCESS, 20, null),
                new Query(FROM, TO, TENANT, "finding", TENANT, "finding.create", Result.DENIED, 20, null))) {
            assertThat(TenantAuditQueryFingerprint.of(TENANT, WINDOW, changed)).isNotEqualTo(expected);
        }
        assertThat(TenantAuditQueryFingerprint.of(OTHER, WINDOW, base)).isNotEqualTo(expected);
        var defaulted = new Query(null, null, null, null, null, null, null, 20, null);
        assertThat(TenantAuditQueryFingerprint.of(TENANT, new Window(FROM.plusSeconds(1), TO), defaulted))
                .isNotEqualTo(TenantAuditQueryFingerprint.of(TENANT, WINDOW, defaulted));
        var changedTo = new Query(FROM, TO.plusSeconds(1), null, null, null, null, null, 20, null);
        assertThat(TenantAuditQueryFingerprint.of(TENANT, new Window(FROM, TO.plusSeconds(1)), changedTo))
                .isNotEqualTo(TenantAuditQueryFingerprint.of(TENANT, WINDOW, defaulted));
    }

    @Test void nullMarkersAndLengthFramingPreventConcatenationAmbiguity() {
        var left = new Query(FROM, TO, null, "ab", null, "c", null, 20, null);
        var right = new Query(FROM, TO, null, "a", null, "bc", null, 20, null);
        assertThat(TenantAuditQueryFingerprint.of(TENANT, WINDOW, left)).isNotEqualTo(TenantAuditQueryFingerprint.of(TENANT, WINDOW, right));
        var absent = new Query(FROM, TO, null, null, null, null, null, 20, null);
        var literalNull = new Query(FROM, TO, null, "null", null, null, null, 20, null);
        assertThat(TenantAuditQueryFingerprint.of(TENANT, WINDOW, absent)).isNotEqualTo(TenantAuditQueryFingerprint.of(TENANT, WINDOW, literalNull));
    }

    @Test void mismatchedExplicitWindowAndMissingTypedInputsFailWithoutEchoingValues() {
        var query = new Query(FROM, TO, null, null, null, null, null, 20, null);
        assertThatThrownBy(() -> TenantAuditQueryFingerprint.of(TENANT, new Window(FROM.plusSeconds(1), TO), query))
                .isExactlyInstanceOf(IllegalStateException.class).hasMessage("Audit query fingerprint is unavailable");
        assertThatThrownBy(() -> TenantAuditQueryFingerprint.of(null, WINDOW, query)).isExactlyInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> TenantAuditQueryFingerprint.of(TENANT, null, query)).isExactlyInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> TenantAuditQueryFingerprint.of(TENANT, WINDOW, null)).isExactlyInstanceOf(IllegalStateException.class);
    }
}
