package com.mightyoung.softwarefactory.files;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class UnconfiguredStorageContentVerificationAdapterTest {

    @Test
    void missingStorageVerifierProducesNoTrustedReceipt() {
        var adapter = new UnconfiguredStorageContentVerificationAdapter();
        UUID tenantId = UUID.randomUUID();
        UUID fileId = UUID.randomUUID();
        UUID memberId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        UUID findingId = UUID.randomUUID();
        UUID setId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        var use = new EvidenceFileUse(tenantId, actorId, memberId, findingId, 1, setId,
                memberId, itemId, fileId, "CONTENT", "a".repeat(64));
        var file = new FileObjectMetadata(tenantId, fileId, memberId, actorId, FileObjectState.READY,
                10L, "application/octet-stream", "a".repeat(64), "synthetic-version",
                OffsetDateTime.parse("2026-10-30T09:00:00Z"));

        assertThat(adapter.findVerifiedContent(use, file)).isEmpty();
        assertThat(adapter.findByReceiptId(tenantId, UUID.randomUUID())).isEmpty();
    }
}
