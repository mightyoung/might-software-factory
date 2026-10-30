package com.mightyoung.softwarefactory.dualprevention.finding.workflow;

import com.mightyoung.softwarefactory.dualprevention.finding.workflow.FindingWorkflowContracts.Submission;
import com.mightyoung.softwarefactory.files.FileEvidenceVerificationLevel;
import com.mightyoung.softwarefactory.files.VerifiedFileEvidenceSnapshot;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.SQLDialect;
import org.jooq.tools.jdbc.MockConnection;
import org.jooq.tools.jdbc.MockDataProvider;
import org.jooq.tools.jdbc.MockResult;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.using;

class JooqFindingWorkflowEvidenceSnapshotPersistenceTest {

    private static final UUID TENANT = UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final UUID SUBMISSION = UUID.fromString("61000000-0000-0000-0000-000000000001");
    private static final UUID FINDING = UUID.fromString("40000000-0000-0000-0000-000000000001");
    private static final UUID NOTICE = UUID.fromString("62000000-0000-0000-0000-000000000001");
    private static final UUID MEMBER = UUID.fromString("20000000-0000-0000-0000-000000000004");
    private static final UUID FILE = UUID.fromString("52000000-0000-0000-0000-000000000001");
    private static final UUID SET = UUID.fromString("50000000-0000-0000-0000-000000000001");
    private static final UUID COMMAND = UUID.fromString("60000000-0000-0000-0000-000000000001");
    private static final String SHA256 = "a".repeat(64);
    private static final OffsetDateTime VERIFIED_AT = OffsetDateTime.parse("2026-10-30T10:00:00Z");

    @Test
    void persistsAndReloadsExactReceiptAndStorageVersionsWithoutDeduplicatingRepeatedFileIds() {
        UUID firstItem = UUID.fromString("51000000-0000-0000-0000-000000000001");
        UUID secondItem = UUID.fromString("51000000-0000-0000-0000-000000000002");
        List<VerifiedFileEvidenceSnapshot> snapshots = List.of(
                snapshot(firstItem, "54000000-0000-0000-0000-000000000001", "disk-v1"),
                snapshot(secondItem, "54000000-0000-0000-0000-000000000002", "disk-v2"));
        Submission submission = new Submission(TENANT, SUBMISSION, FINDING, NOTICE, 1, MEMBER, VERIFIED_AT,
                SET, snapshots, "Repaired guard", 3, COMMAND);
        List<String> statements = new ArrayList<>();
        List<List<Object>> bindings = new ArrayList<>();
        DSLContext values = using(SQLDialect.POSTGRES);
        MockDataProvider provider = context -> {
            statements.add(context.sql());
            bindings.add(Arrays.asList(context.bindings()));
            String sql = context.sql().toLowerCase(java.util.Locale.ROOT);
            if (sql.contains("set_config")) {
                return new MockResult[] {singleValue(values, "set_config", String.class,
                        (String) context.bindings()[1])};
            }
            if (sql.startsWith("insert into public.remediation_submission")) {
                return new MockResult[] {new MockResult(1)};
            }
            if (sql.startsWith("update public.remediation_submission")) {
                return new MockResult[] {new MockResult(1)};
            }
            if (sql.contains("from public.remediation_submission_evidence")) {
                return new MockResult[] {snapshotRows(values, snapshots)};
            }
            if (sql.contains("from public.remediation_submission")) {
                return new MockResult[] {submissionRow(values, snapshots.size(), itemIds(snapshots), VERIFIED_AT)};
            }
            throw new AssertionError("Unexpected SQL: " + context.sql());
        };
        JooqFindingWorkflowRepository repository = new JooqFindingWorkflowRepository(
                using(new MockConnection(provider), SQLDialect.POSTGRES), new NoOpTransactionManager());

        Submission reloaded = repository.inTenantTransaction(TENANT, tx -> {
            tx.insertSubmission(submission);
            return tx.findSubmission(FINDING, 1).orElseThrow();
        });

        assertThat(reloaded.evidenceSnapshots()).containsExactlyElementsOf(snapshots);
        assertThat(reloaded.evidenceSnapshots()).extracting(VerifiedFileEvidenceSnapshot::fileId)
                .containsExactly(FILE, FILE);
        List<Integer> evidenceInsertIndexes = new ArrayList<>();
        for (int index = 0; index < statements.size(); index++) {
            if (statements.get(index).toLowerCase(java.util.Locale.ROOT)
                    .startsWith("insert into public.remediation_submission_evidence")) {
                evidenceInsertIndexes.add(index);
            }
        }
        assertThat(evidenceInsertIndexes).hasSize(2);
        assertThat(evidenceInsertIndexes).allSatisfy(index -> {
            assertThat(statements.get(index)).contains(
                    "verification_receipt_id", "storage_version", "verifier_key_id", "verification_policy_version");
            assertThat(bindings.get(index)).contains(FILE, SHA256, "synthetic-verifier", "synthetic-key-7",
                    "content-policy-v3", "CONTENT");
        });
        int sealIndex = -1;
        for (int index = 0; index < statements.size(); index++) {
            if (statements.get(index).toLowerCase(java.util.Locale.ROOT)
                    .startsWith("update public.remediation_submission")) {
                sealIndex = index;
                break;
            }
        }
        assertThat(sealIndex).isGreaterThan(evidenceInsertIndexes.getLast());
        assertThat(statements.get(sealIndex)).contains("evidence_snapshots_sealed_at");
        assertThat(statements.stream().filter(sql -> sql.toLowerCase(java.util.Locale.ROOT)
                .contains("from public.remediation_submission_evidence")).findFirst().orElseThrow())
                .contains("verification_receipt_id", "storage_version", "order by evidence_item_id");
    }

    @Test
    void rejectsSubmissionRowsWithAnIncompleteCountOrDifferentStoredManifest() {
        List<VerifiedFileEvidenceSnapshot> snapshots = List.of(
                snapshot(UUID.fromString("51000000-0000-0000-0000-000000000001"),
                        "54000000-0000-0000-0000-000000000001", "disk-v1"),
                snapshot(UUID.fromString("51000000-0000-0000-0000-000000000002"),
                        "54000000-0000-0000-0000-000000000002", "disk-v2"));

        assertManifestRejected(snapshots, 1, itemIds(snapshots));
        assertManifestRejected(snapshots, 2, new UUID[] {
                UUID.fromString("51000000-0000-0000-0000-000000000099"),
                UUID.fromString("51000000-0000-0000-0000-000000000002")});
    }

    @Test
    void aFailedSealAbortsTheSubmissionTransaction() {
        List<VerifiedFileEvidenceSnapshot> snapshots = List.of(
                snapshot(UUID.fromString("51000000-0000-0000-0000-000000000001"),
                        "54000000-0000-0000-0000-000000000001", "disk-v1"));
        Submission submission = new Submission(TENANT, SUBMISSION, FINDING, NOTICE, 1, MEMBER, VERIFIED_AT,
                SET, snapshots, "Repaired guard", 3, COMMAND);
        DSLContext values = using(SQLDialect.POSTGRES);
        MockDataProvider provider = context -> {
            String sql = context.sql().toLowerCase(java.util.Locale.ROOT);
            if (sql.contains("set_config")) {
                return new MockResult[] {singleValue(values, "set_config", String.class,
                        (String) context.bindings()[1])};
            }
            if (sql.startsWith("insert into public.remediation_submission")) {
                return new MockResult[] {new MockResult(1)};
            }
            if (sql.startsWith("update public.remediation_submission")) {
                return new MockResult[] {new MockResult(0)};
            }
            throw new AssertionError("Unexpected SQL: " + context.sql());
        };
        NoOpTransactionManager transactionManager = new NoOpTransactionManager();
        JooqFindingWorkflowRepository repository = new JooqFindingWorkflowRepository(
                using(new MockConnection(provider), SQLDialect.POSTGRES), transactionManager);

        assertThatThrownBy(() -> repository.inTenantTransaction(TENANT, tx -> {
            tx.insertSubmission(submission);
            return null;
        })).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("snapshot set did not seal");
        assertThat(transactionManager.rollbackCount).isEqualTo(1);
    }

    private static void assertManifestRejected(
            List<VerifiedFileEvidenceSnapshot> snapshots, int expectedCount, UUID[] manifest) {
        DSLContext values = using(SQLDialect.POSTGRES);
        MockDataProvider provider = context -> {
            String sql = context.sql().toLowerCase(java.util.Locale.ROOT);
            if (sql.contains("set_config")) {
                return new MockResult[] {singleValue(values, "set_config", String.class,
                        (String) context.bindings()[1])};
            }
            if (sql.contains("from public.remediation_submission_evidence")) {
                return new MockResult[] {snapshotRows(values, snapshots)};
            }
            if (sql.contains("from public.remediation_submission")) {
                return new MockResult[] {submissionRow(values, expectedCount, manifest, VERIFIED_AT)};
            }
            throw new AssertionError("Unexpected SQL: " + context.sql());
        };
        JooqFindingWorkflowRepository repository = new JooqFindingWorkflowRepository(
                using(new MockConnection(provider), SQLDialect.POSTGRES), new NoOpTransactionManager());

        assertThatThrownBy(() -> repository.inTenantTransaction(
                TENANT, tx -> tx.findSubmission(FINDING, 1).orElseThrow()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("snapshot manifest is inconsistent");
    }

    private static UUID[] itemIds(List<VerifiedFileEvidenceSnapshot> snapshots) {
        return snapshots.stream().map(VerifiedFileEvidenceSnapshot::evidenceItemId).toArray(UUID[]::new);
    }

    private static VerifiedFileEvidenceSnapshot snapshot(UUID itemId, String receipt, String version) {
        return new VerifiedFileEvidenceSnapshot(TENANT, itemId, FILE,
                UUID.fromString(itemId.toString().replace("51000000", "53000000")), MEMBER,
                UUID.fromString("10000000-0000-0000-0000-000000000004"), SHA256, version,
                UUID.fromString(receipt), "synthetic-verifier", "synthetic-key-7", "content-policy-v3",
                FileEvidenceVerificationLevel.CONTENT, VERIFIED_AT);
    }

    private static MockResult submissionRow(
            DSLContext dsl, int snapshotCount, UUID[] snapshotManifest, OffsetDateTime sealedAt) {
        Field<?>[] fields = new Field<?>[] {
                nameField("id", UUID.class), nameField("finding_id", UUID.class),
                nameField("notice_id", UUID.class), nameField("round_no", Integer.class),
                nameField("submitted_by", UUID.class), nameField("submitted_at", OffsetDateTime.class),
                nameField("evidence_set_id", UUID.class), nameField("statement", String.class),
                nameField("expected_version", Long.class), nameField("command_id", UUID.class),
                nameField("evidence_snapshot_count", Integer.class),
                nameField("evidence_snapshot_manifest", UUID[].class),
                nameField("evidence_snapshots_sealed_at", OffsetDateTime.class)};
        Record row = dsl.newRecord(fields);
        set(row, fields[0], SUBMISSION);
        set(row, fields[1], FINDING);
        set(row, fields[2], NOTICE);
        set(row, fields[3], 1);
        set(row, fields[4], MEMBER);
        set(row, fields[5], VERIFIED_AT);
        set(row, fields[6], SET);
        set(row, fields[7], "Repaired guard");
        set(row, fields[8], 3L);
        set(row, fields[9], COMMAND);
        set(row, fields[10], snapshotCount);
        set(row, fields[11], snapshotManifest);
        set(row, fields[12], sealedAt);
        var result = dsl.newResult(fields);
        result.add(row);
        return new MockResult(1, result);
    }

    private static MockResult snapshotRows(DSLContext dsl, List<VerifiedFileEvidenceSnapshot> snapshots) {
        Field<?>[] fields = new Field<?>[] {
                nameField("tenant_id", UUID.class), nameField("evidence_item_id", UUID.class),
                nameField("file_id", UUID.class), nameField("file_link_id", UUID.class),
                nameField("owner_member_id", UUID.class), nameField("owner_actor_id", UUID.class),
                nameField("sha256", String.class), nameField("storage_version", String.class),
                nameField("verification_receipt_id", UUID.class), nameField("verifier_id", String.class),
                nameField("verifier_key_id", String.class), nameField("verification_policy_version", String.class),
                nameField("verification_level", String.class), nameField("verified_at", OffsetDateTime.class)};
        var result = dsl.newResult(fields);
        for (VerifiedFileEvidenceSnapshot snapshot : snapshots) {
            Record row = dsl.newRecord(fields);
            set(row, fields[0], snapshot.tenantId());
            set(row, fields[1], snapshot.evidenceItemId());
            set(row, fields[2], snapshot.fileId());
            set(row, fields[3], snapshot.fileLinkId());
            set(row, fields[4], snapshot.ownerMemberId());
            set(row, fields[5], snapshot.ownerActorId());
            set(row, fields[6], snapshot.sha256());
            set(row, fields[7], snapshot.storageVersion());
            set(row, fields[8], snapshot.verificationReceiptId());
            set(row, fields[9], snapshot.verifierId());
            set(row, fields[10], snapshot.verifierKeyId());
            set(row, fields[11], snapshot.verificationPolicyVersion());
            set(row, fields[12], snapshot.verificationLevel().name());
            set(row, fields[13], snapshot.verifiedAt());
            result.add(row);
        }
        return new MockResult(result.size(), result);
    }

    private static <T> MockResult singleValue(DSLContext dsl, String key, Class<T> type, T value) {
        Field<T> resultField = field(key, type);
        var result = dsl.newResult(resultField);
        var row = dsl.newRecord(resultField);
        row.set(resultField, value);
        result.add(row);
        return new MockResult(1, result);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void set(Record row, Field field, Object value) {
        row.set(field, value);
    }

    private static <T> Field<T> nameField(String key, Class<T> type) {
        return field(name(key), type);
    }

    private static final class NoOpTransactionManager extends AbstractPlatformTransactionManager {
        private int rollbackCount;

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            rollbackCount++;
        }
    }
}
