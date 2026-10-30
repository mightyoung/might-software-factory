package com.mightyoung.softwarefactory.files;

import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/** Read-only PostgreSQL metadata adapter; it never reads file bytes or verifies content. */
@Repository
public final class JooqFileEvidenceMetadataReadRepository implements FileEvidenceMetadataReadRepository {

    private final DSLContext dsl;

    public JooqFileEvidenceMetadataReadRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public Optional<FileObjectMetadata> findFile(UUID tenantId, UUID fileId) {
        Record row = dsl.fetchOne("""
                select tenant_id, id, owner_member_id, owner_actor_id, state,
                       size_bytes, mime, checksum, storage_version, verified_at
                  from public.file_object
                 where tenant_id = cast(? as uuid)
                   and id = cast(? as uuid)
                 for share
                """, tenantId, fileId);
        if (row == null) return Optional.empty();
        return Optional.of(new FileObjectMetadata(
                row.get("tenant_id", UUID.class),
                row.get("id", UUID.class),
                row.get("owner_member_id", UUID.class),
                row.get("owner_actor_id", UUID.class),
                FileObjectState.valueOf(row.get("state", String.class)),
                row.get("size_bytes", Long.class),
                row.get("mime", String.class),
                row.get("checksum", String.class),
                row.get("storage_version", String.class),
                row.get("verified_at", java.time.OffsetDateTime.class)));
    }
}
