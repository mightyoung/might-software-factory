package com.mightyoung.softwarefactory.files;

import java.util.Optional;
import java.util.UUID;

/**
 * Read-only access to trusted, immutable content-verification receipts. Implementations must not scan or read
 * file bytes during a business command. A receipt lookup is keyed by its stable ID. The corresponding storage
 * adapter must preserve immutable object versions and any later content read must request that exact version;
 * it must never silently fall back to the latest object. If the backing store cannot provide that guarantee,
 * the adapter must not issue a CONTENT receipt.
 */
public interface StorageContentVerificationPort {

    Optional<ContentVerificationReceipt> findVerifiedContent(EvidenceFileUse use, FileObjectMetadata file);

    Optional<ContentVerificationReceipt> findByReceiptId(UUID tenantId, UUID receiptId);
}
