package com.mightyoung.softwarefactory.files;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Tenant-scoped metadata supplied by the public file module, never by an API request body. */
public record FileObjectMetadata(
        UUID tenantId,
        UUID fileId,
        UUID ownerMemberId,
        UUID ownerActorId,
        FileObjectState state,
        long sizeBytes,
        String mimeType,
        String sha256,
        String storageVersion,
        OffsetDateTime verifiedAt) {
}
