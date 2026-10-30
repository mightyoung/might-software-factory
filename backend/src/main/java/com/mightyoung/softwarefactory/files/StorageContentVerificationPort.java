package com.mightyoung.softwarefactory.files;

import java.util.Optional;

/**
 * Read-only access to a trusted content-verification receipt produced before the workflow transaction.
 * Implementations must not scan or read file bytes during a business command.
 */
public interface StorageContentVerificationPort {

    Optional<ContentVerificationReceipt> findVerifiedContent(EvidenceFileUse use, FileObjectMetadata file);
}
