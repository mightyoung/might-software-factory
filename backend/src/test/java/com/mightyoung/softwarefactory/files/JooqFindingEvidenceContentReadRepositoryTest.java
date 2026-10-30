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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.using;

class JooqFindingEvidenceContentReadRepositoryTest {

    private static final UUID TENANT_ID = UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final UUID ORG_ID = UUID.fromString("31000000-0000-0000-0000-000000000001");
    private static final UUID FINDING_ID = UUID.fromString("40000000-0000-0000-0000-000000000002");
    private static final UUID ITEM_ID = UUID.fromString("51000000-0000-0000-0000-000000000002");
    private static final UUID FILE_ID = UUID.fromString("52000000-0000-0000-0000-000000000002");
    private static final UUID LINK_ID = UUID.fromString("53000000-0000-0000-0000-000000000002");
    private static final UUID RECEIPT_ID = UUID.fromString("54000000-0000-0000-0000-000000000002");
    private static final UUID MEMBER_ID = UUID.fromString("20000000-0000-0000-0000-000000000004");
    private static final UUID ACTOR_ID = UUID.fromString("10000000-0000-0000-0000-000000000004");

    @Test
    void readResolvesOnlyASealedSnapshotWithCurrentLinkStateAndAuthorizedTenantScope() {
        AtomicReference<String> snapshotSql = new AtomicReference<>();
        AtomicReference<List<Object>> snapshotBindings = new AtomicReference<>();
        DSLContext values = using(SQLDialect.POSTGRES);
        MockDataProvider provider = context -> {
            if (context.sql().contains("set_config")) {
                Field<String> setting = field(name("setting"), String.class);
                var result = values.newResult(setting);
                var row = values.newRecord(setting);
                row.set(setting, TENANT_ID.toString());
                result.add(row);
                return new MockResult[] {new MockResult(1, result)};
            }
            snapshotSql.set(context.sql());
            snapshotBindings.set(Arrays.asList(context.bindings()));
            return snapshotResult(values);
        };
        var repository = new JooqFindingEvidenceContentReadRepository(
                using(new MockConnection(provider), SQLDialect.POSTGRES));

        VerifiedFileEvidenceSnapshot snapshot = repository.findReadableSealedSnapshot(
                new FindingEvidenceReadScope(TENANT_ID, Set.of(ORG_ID)), FINDING_ID, 2, ITEM_ID).orElseThrow();

        assertThat(snapshot.tenantId()).isEqualTo(TENANT_ID);
        assertThat(snapshot.fileId()).isEqualTo(FILE_ID);
        assertThat(snapshot.fileLinkId()).isEqualTo(LINK_ID);
        assertThat(snapshot.storageVersion()).isEqualTo("disk-v1");
        assertThat(snapshot.verificationReceiptId()).isEqualTo(RECEIPT_ID);
        String sql = snapshotSql.get().toLowerCase(java.util.Locale.ROOT);
        assertThat(sql).contains(
                "from public.remediation_submission_evidence se",
                "join public.remediation_submission rs",
                "rs.evidence_snapshots_sealed_at is not null",
                "rs.evidence_snapshot_count > 0",
                "rs.evidence_snapshot_manifest",
                "es.submission_sealed_at is not null",
                "fl.revoked_at is null",
                "fo.state = 'ready'",
                "fo.checksum = se.sha256",
                "fo.storage_version = se.storage_version",
                "f.organization_id = any",
                "for share of fl, fo");
        assertThat(sql).doesNotContain("object_key", "latest");
        assertThat(snapshotBindings.get().subList(0, 4)).containsExactly(TENANT_ID, FINDING_ID, 2, ITEM_ID);
        assertThat(snapshotBindings.get().get(4).toString()).contains(ORG_ID.toString());
    }

    @Test
    void emptyOrCrossTenantAuthorizationScopeDoesNotQueryStorageMetadata() {
        AtomicReference<Integer> calls = new AtomicReference<>(0);
        DSLContext values = using(SQLDialect.POSTGRES);
        MockDataProvider provider = context -> {
            calls.set(calls.get() + 1);
            return new MockResult[] {new MockResult(0, values.newResult(field(name("value"), String.class)))};
        };
        var repository = new JooqFindingEvidenceContentReadRepository(
                using(new MockConnection(provider), SQLDialect.POSTGRES));

        assertThat(repository.findReadableSealedSnapshot(
                new FindingEvidenceReadScope(TENANT_ID, Set.of()), FINDING_ID, 2, ITEM_ID)).isEmpty();
        assertThat(calls.get()).isZero();
    }

    private static MockResult[] snapshotResult(DSLContext dsl) {
        Field<UUID> tenant = nameField("tenant_id", UUID.class);
        Field<UUID> item = nameField("evidence_item_id", UUID.class);
        Field<UUID> file = nameField("file_id", UUID.class);
        Field<UUID> link = nameField("file_link_id", UUID.class);
        Field<UUID> ownerMember = nameField("owner_member_id", UUID.class);
        Field<UUID> ownerActor = nameField("owner_actor_id", UUID.class);
        Field<String> sha = nameField("sha256", String.class);
        Field<String> version = nameField("storage_version", String.class);
        Field<UUID> receipt = nameField("verification_receipt_id", UUID.class);
        Field<String> verifier = nameField("verifier_id", String.class);
        Field<String> key = nameField("verifier_key_id", String.class);
        Field<String> policy = nameField("verification_policy_version", String.class);
        Field<String> level = nameField("verification_level", String.class);
        Field<OffsetDateTime> verifiedAt = nameField("verified_at", OffsetDateTime.class);
        var result = dsl.newResult(tenant, item, file, link, ownerMember, ownerActor,
                sha, version, receipt, verifier, key, policy, level, verifiedAt);
        var row = dsl.newRecord(tenant, item, file, link, ownerMember, ownerActor,
                sha, version, receipt, verifier, key, policy, level, verifiedAt);
        row.set(tenant, TENANT_ID);
        row.set(item, ITEM_ID);
        row.set(file, FILE_ID);
        row.set(link, LINK_ID);
        row.set(ownerMember, MEMBER_ID);
        row.set(ownerActor, ACTOR_ID);
        row.set(sha, "a".repeat(64));
        row.set(version, "disk-v1");
        row.set(receipt, RECEIPT_ID);
        row.set(verifier, "server-verifier");
        row.set(key, "key-1");
        row.set(policy, "content-v1");
        row.set(level, "CONTENT");
        row.set(verifiedAt, OffsetDateTime.parse("2026-10-30T10:00:00Z"));
        result.add(row);
        return new MockResult[] {new MockResult(1, result)};
    }

    private static <T> Field<T> nameField(String column, Class<T> type) {
        return field(name(column), type);
    }
}
