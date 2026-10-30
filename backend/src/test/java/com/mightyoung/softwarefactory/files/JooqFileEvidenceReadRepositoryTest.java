package com.mightyoung.softwarefactory.files;

import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.SQLDialect;
import org.jooq.tools.jdbc.MockConnection;
import org.jooq.tools.jdbc.MockDataProvider;
import org.jooq.tools.jdbc.MockResult;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.using;

class JooqFileEvidenceReadRepositoryTest {

    private static final UUID TENANT_ID = UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final UUID ACTOR_ID = UUID.fromString("10000000-0000-0000-0000-000000000004");
    private static final UUID MEMBER_ID = UUID.fromString("20000000-0000-0000-0000-000000000004");
    private static final UUID FINDING_ID = UUID.fromString("40000000-0000-0000-0000-000000000002");
    private static final UUID SET_ID = UUID.fromString("50000000-0000-0000-0000-000000000002");
    private static final UUID ITEM_ID = UUID.fromString("51000000-0000-0000-0000-000000000002");
    private static final UUID FILE_ID = UUID.fromString("52000000-0000-0000-0000-000000000002");

    @Test
    void fileMetadataLookupIsTenantAndFileScopedAndCarriesTheOwnerAndStorageVersion() {
        AtomicReference<String> sql = new AtomicReference<>();
        AtomicReference<List<Object>> bindings = new AtomicReference<>();
        DSLContext values = using(SQLDialect.POSTGRES);
        MockDataProvider provider = context -> {
            sql.set(context.sql());
            bindings.set(Arrays.asList(context.bindings()));
            return metadataResult(values);
        };
        var repository = new JooqFileEvidenceMetadataReadRepository(
                using(new MockConnection(provider), SQLDialect.POSTGRES));

        var file = repository.findFile(TENANT_ID, FILE_ID).orElseThrow();

        assertThat(file.tenantId()).isEqualTo(TENANT_ID);
        assertThat(file.fileId()).isEqualTo(FILE_ID);
        assertThat(file.ownerMemberId()).isEqualTo(MEMBER_ID);
        assertThat(file.ownerActorId()).isEqualTo(ACTOR_ID);
        assertThat(file.state()).isEqualTo(FileObjectState.READY);
        assertThat(file.storageVersion()).isEqualTo("storage-revision-7");
        assertThat(file.sha256()).isEqualTo("a".repeat(64));
        assertThat(sql.get().toLowerCase(java.util.Locale.ROOT))
                .contains("from public.file_object", "where tenant_id", "and id", "for share");
        assertThat(bindings.get()).containsExactly(TENANT_ID, FILE_ID);
    }

    @Test
    void referenceLookupRequiresExactTenantFileEvidenceItemFindingRoundAndOwnerLink() {
        AtomicReference<String> sql = new AtomicReference<>();
        AtomicReference<List<Object>> bindings = new AtomicReference<>();
        DSLContext values = using(SQLDialect.POSTGRES);
        MockDataProvider provider = context -> {
            sql.set(context.sql());
            bindings.set(Arrays.asList(context.bindings()));
            Field<UUID> id = field("id", UUID.class);
            var result = values.newResult(id);
            var row = values.newRecord(id);
            row.set(id, UUID.randomUUID());
            result.add(row);
            return new MockResult[] {new MockResult(1, result)};
        };
        var repository = new JooqEvidenceFileReferenceReadRepository(
                using(new MockConnection(provider), SQLDialect.POSTGRES));
        EvidenceFileUse use = new EvidenceFileUse(TENANT_ID, ACTOR_ID, MEMBER_ID,
                FINDING_ID, 2, SET_ID, MEMBER_ID, ITEM_ID, FILE_ID, "CONTENT");

        assertThat(repository.hasAuthorizedEvidenceReference(use)).isTrue();
        String normalized = sql.get().toLowerCase(java.util.Locale.ROOT);
        assertThat(normalized).contains(
                "from public.file_link fl", "join public.evidence_item ei", "join public.evidence_set es",
                "fl.resource_type = 'finding_evidence_item'", "fl.purpose = 'remediation_evidence'",
                "fl.revoked_at is null", "es.finding_id", "es.round_no", "es.owner_member_id",
                "for share of fl");
        assertThat(bindings.get()).containsExactly(
                TENANT_ID, FILE_ID, ITEM_ID, TENANT_ID, SET_ID, ITEM_ID, FINDING_ID, 2, MEMBER_ID);
    }

    private static MockResult[] metadataResult(DSLContext dsl) {
        Field<UUID> tenant = nameField("tenant_id", UUID.class);
        Field<UUID> id = nameField("id", UUID.class);
        Field<UUID> ownerMember = nameField("owner_member_id", UUID.class);
        Field<UUID> ownerActor = nameField("owner_actor_id", UUID.class);
        Field<String> state = nameField("state", String.class);
        Field<Long> size = nameField("size_bytes", Long.class);
        Field<String> mime = nameField("mime", String.class);
        Field<String> checksum = nameField("checksum", String.class);
        Field<String> storageVersion = nameField("storage_version", String.class);
        Field<OffsetDateTime> verifiedAt = nameField("verified_at", OffsetDateTime.class);
        var result = dsl.newResult(tenant, id, ownerMember, ownerActor, state, size, mime, checksum,
                storageVersion, verifiedAt);
        var row = dsl.newRecord(tenant, id, ownerMember, ownerActor, state, size, mime, checksum,
                storageVersion, verifiedAt);
        row.set(tenant, TENANT_ID);
        row.set(id, FILE_ID);
        row.set(ownerMember, MEMBER_ID);
        row.set(ownerActor, ACTOR_ID);
        row.set(state, "READY");
        row.set(size, 64L);
        row.set(mime, "image/jpeg");
        row.set(checksum, "a".repeat(64));
        row.set(storageVersion, "storage-revision-7");
        row.set(verifiedAt, OffsetDateTime.parse("2026-10-30T09:00:00Z"));
        result.add(row);
        return new MockResult[] {new MockResult(1, result)};
    }

    private static <T> Field<T> nameField(String column, Class<T> type) {
        return field(name(column), type);
    }
}
