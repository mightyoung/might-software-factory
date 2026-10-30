package com.mightyoung.softwarefactory.files;

import java.util.Optional;
import java.util.UUID;

/** Fail-closed placeholder: upload scanning and trusted content verification are not implemented here. */
public final class UnconfiguredStorageContentVerificationAdapter
        implements StorageContentVerificationPort {

    @Override
    public Optional<ContentVerificationReceipt> findVerifiedContent(EvidenceFileUse use, FileObjectMetadata file) {
        return Optional.empty();
    }

    @Override
    public Optional<ContentVerificationReceipt> findByReceiptId(UUID tenantId, UUID receiptId) {
        return Optional.empty();
    }
}
