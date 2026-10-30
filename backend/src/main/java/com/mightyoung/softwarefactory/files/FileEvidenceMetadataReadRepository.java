package com.mightyoung.softwarefactory.files;

import java.util.Optional;
import java.util.UUID;

/** Read-only tenant-scoped lookup for public file-object metadata. */
public interface FileEvidenceMetadataReadRepository {

    Optional<FileObjectMetadata> findFile(UUID tenantId, UUID fileId);
}
