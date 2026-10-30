package com.mightyoung.softwarefactory.files;

import org.springframework.stereotype.Component;

import java.util.Optional;

/** Fail-closed placeholder: upload scanning and trusted content verification are not implemented here. */
@Component
public final class UnconfiguredStorageContentVerificationAdapter implements StorageContentVerificationPort {

    @Override
    public Optional<ContentVerificationReceipt> findVerifiedContent(EvidenceFileUse use, FileObjectMetadata file) {
        return Optional.empty();
    }
}
