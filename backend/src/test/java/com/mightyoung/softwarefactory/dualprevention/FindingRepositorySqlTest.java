package com.mightyoung.softwarefactory.dualprevention;

import com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract;
import com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryValidator;
import com.mightyoung.softwarefactory.dualprevention.finding.FindingReadScope;
import com.mightyoung.softwarefactory.dualprevention.finding.JooqFindingRepository;
import com.mightyoung.softwarefactory.files.FileEvidenceTrustUnavailableException;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.tools.jdbc.MockConnection;
import org.jooq.tools.jdbc.MockDataProvider;
import org.jooq.tools.jdbc.MockExecuteContext;
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

class FindingRepositorySqlTest {

    private static final UUID TENANT = UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final UUID ORG = UUID.fromString("31000000-0000-0000-0000-000000000001");

    @Test
    void bindsTenantAndOrganizationBeforeCountingAndApplyingPageFilters() {
        List<String> statements = new ArrayList<>();
        List<List<Object>> bindings = new ArrayList<>();
        DSLContext values = DSL.using(SQLDialect.POSTGRES);
        MockDataProvider provider = context -> {
            statements.add(context.sql());
            bindings.add(Arrays.asList(context.bindings()));
            String sql = context.sql().toLowerCase(java.util.Locale.ROOT);
            if (sql.contains("set_config")) {
                return new MockResult[] {singleValue(values, "set_config", String.class,
                        (String) context.bindings()[1])};
            }
            if (sql.contains("count(*)")) {
                return new MockResult[] {singleValue(values, "count", Long.class, 1L)};
            }
            return new MockResult[] {findingRow(values)};
        };
        DSLContext dsl = DSL.using(new MockConnection(provider), SQLDialect.POSTGRES);
        JooqFindingRepository repository = new JooqFindingRepository(dsl, new NoOpTransactionManager());
        var query = new EntityQueryValidator().validate(EntityQueryContract.readRequest("""
                {"filterAST":{"operator":"AND","conditions":[
                  {"field":"grade","operator":"EQ","value":"MAJOR"}]},
                 "sort":[{"field":"createdAt","direction":"DESC"}],
                 "page":{"number":2},"size":20,"fields":["id","code","organizationId","organizationName",
                   "sourceCategory","urgency","location","interimMeasure","backfill"]}
                """), new FindingEntitySchema().schema());

        var result = repository.findPage(new FindingReadScope(
                UUID.randomUUID(), TENANT, UUID.randomUUID(), java.util.Set.of(ORG)), query);

        assertThat(result.total()).isEqualTo(1L);
        assertThat(result.items().getFirst().organizationId()).isEqualTo(ORG);
        assertThat(result.items().getFirst().organizationName()).isEqualTo("安全一部");
        assertThat(result.items().getFirst().toApiMap())
                .containsEntry("sourceCategory", "FIELD_OBSERVATION")
                .containsEntry("urgency", "URGENT")
                .containsEntry("location", "North loading dock")
                .containsEntry("interimMeasure", "Temporary barrier installed")
                .containsEntry("backfill", true);
        assertThat(statements).hasSize(3);
        assertThat(statements.get(0).toLowerCase(java.util.Locale.ROOT)).contains("set_config");
        List<String> queries = statements.stream()
                .filter(sql -> sql.toLowerCase(java.util.Locale.ROOT).contains("from \"public\".\"finding\""))
                .toList();
        assertThat(queries).hasSize(2);
        assertThat(queries).allSatisfy(sql -> {
            assertThat(sql.toLowerCase(java.util.Locale.ROOT))
                    .contains("\"tenant_id\" = cast(? as uuid)", "\"organization_id\" in (cast(? as uuid))");
        });
        assertThat(queries.get(0).toLowerCase(java.util.Locale.ROOT)).contains("count(*)");
        assertThat(queries.get(1).toLowerCase(java.util.Locale.ROOT))
                .contains("left outer join \"public\".\"org_unit\"",
                        "\"reportable_organization\".\"tenant_id\" = \"finding\".\"tenant_id\"",
                        "\"reportable_organization\".\"id\" = \"finding\".\"organization_id\"",
                        "order by \"finding\".\"created_at\" desc, \"finding\".\"id\" asc",
                        "offset ? rows fetch next ? rows only");
        assertThat(bindings.stream().flatMap(List::stream))
                .contains(TENANT, ORG, "MAJOR", "app.tenant_id", TENANT.toString(), 20L);
    }

    @Test
    void enrichesOnlyTheCurrentRoundAndBindsEveryDetailQueryToTenantAndOrganization() {
        List<String> statements = new ArrayList<>();
        List<List<Object>> bindings = new ArrayList<>();
        DSLContext values = DSL.using(SQLDialect.POSTGRES);
        UUID finding = UUID.randomUUID(), reporter = UUID.randomUUID(), responsible = UUID.randomUUID();
        JooqFindingRepository repository = detailRepository(context -> {
            statements.add(context.sql());
            bindings.add(Arrays.asList(context.bindings()));
            if (context.sql().contains("set_config")) return new MockResult[] {
                    singleValue(values, "set_config", String.class, TENANT.toString())};
            if (context.sql().contains("created_by_member_id")) return new MockResult[] {
                    detailMembers(values, reporter, "Reporter", responsible, "Assignee")};
            if (context.sql().contains("from public.remediation_submission submission")) return new MockResult[] {emptySubmissions(values)};
            return new MockResult[] {historyRows(values, 2, true)};
        });

        var detail = repository.findDetailContext(scope(TENANT), finding).orElseThrow();

        assertThat(detail.reporter().memberId()).isEqualTo(reporter);
        assertThat(detail.responsible().memberId()).isEqualTo(responsible);
        assertThat(detail.responsible().displayName()).isEqualTo("Assignee");
        assertThat(detail.history()).extracting("toVersion").containsExactly(1L, 2L);
        assertThat(detail.history().getFirst().actorName()).isEqualTo("Actor");
        assertThat(detail.historyTruncated()).isFalse();
        assertThat(statements).hasSize(4);
        assertThat(statements.get(1)).contains("notice.round_no = f.round_no",
                "reporter.tenant_id = f.tenant_id", "responsible.tenant_id = notice.tenant_id");
        assertThat(statements.get(2)).contains("actor.tenant_id = transition.tenant_id",
                "actor.account_id = principal.account_id", "order by transition.to_version desc, transition.id desc");
        for (int i = 1; i < 3; i++) {
            assertThat(statements.get(i)).contains("f.tenant_id = cast(? as uuid)",
                    "f.id = cast(? as uuid)", "f.organization_id = any(cast(? as uuid[]))");
            assertThat(bindings.get(i).get(0)).isEqualTo(TENANT);
            assertThat(bindings.get(i).get(1)).isEqualTo(finding);
            assertThat(bindings.get(i).get(2).toString()).contains(ORG.toString());
        }
        assertThat(bindings.get(2).getLast()).isEqualTo(201);
    }

    @Test
    void retainsPersistedIdentifiersWhenDirectoryLabelsAreMissingAndDoesNotInventAnAssignee() {
        DSLContext values = DSL.using(SQLDialect.POSTGRES);
        UUID reporter = UUID.randomUUID();
        JooqFindingRepository repository = detailRepository(context -> {
            if (context.sql().contains("set_config")) return new MockResult[] {
                    singleValue(values, "set_config", String.class, TENANT.toString())};
            if (context.sql().contains("created_by_member_id")) return new MockResult[] {
                    detailMembers(values, reporter, null, null, null)};
            if (context.sql().contains("from public.remediation_submission submission")) return new MockResult[] {emptySubmissions(values)};
            return new MockResult[] {historyRows(values, 1, false)};
        });

        var detail = repository.findDetailContext(scope(TENANT), UUID.randomUUID()).orElseThrow();

        assertThat(detail.reporter().memberId()).isEqualTo(reporter);
        assertThat(detail.reporter().displayName()).isNull();
        assertThat(detail.responsible()).isNull();
        assertThat(detail.history().getFirst().actorName()).isNull();
    }

    @Test
    void anInvisibleFindingReturnsNoDirectoryOrHistoryDataAndSkipsTheHistoryQuery() {
        DSLContext values = DSL.using(SQLDialect.POSTGRES);
        List<String> statements = new ArrayList<>();
        UUID foreignTenant = UUID.randomUUID();
        JooqFindingRepository repository = detailRepository(context -> {
            statements.add(context.sql());
            if (context.sql().contains("set_config")) return new MockResult[] {
                    singleValue(values, "set_config", String.class, foreignTenant.toString())};
            assertThat(context.bindings()[0]).isEqualTo(foreignTenant);
            return new MockResult[] {new MockResult(0, values.newResult(DSL.field("reporter_id", UUID.class)))};
        });

        assertThat(repository.findDetailContext(scope(foreignTenant), UUID.randomUUID())).isEmpty();
        assertThat(statements).hasSize(2).noneMatch(sql -> sql.contains("finding_transition"));
    }

    @Test
    void aLongHistoryReturnsOnlyTheLatestTwoHundredTransitionsInChronologicalVersionOrder() {
        DSLContext values = DSL.using(SQLDialect.POSTGRES);
        JooqFindingRepository repository = detailRepository(context -> {
            if (context.sql().contains("set_config")) return new MockResult[] {
                    singleValue(values, "set_config", String.class, TENANT.toString())};
            if (context.sql().contains("created_by_member_id")) return new MockResult[] {
                    detailMembers(values, UUID.randomUUID(), "Reporter", null, null)};
            if (context.sql().contains("from public.remediation_submission submission")) return new MockResult[] {emptySubmissions(values)};
            return new MockResult[] {historyRows(values, 201, true)};
        });

        var detail = repository.findDetailContext(scope(TENANT), UUID.randomUUID()).orElseThrow();

        assertThat(detail.historyTruncated()).isTrue();
        assertThat(detail.history()).hasSize(200);
        assertThat(detail.history().getFirst().toVersion()).isEqualTo(2);
        assertThat(detail.history().getLast().toVersion()).isEqualTo(201);
    }

    private static JooqFindingRepository detailRepository(MockDataProvider provider) {
        return new JooqFindingRepository(DSL.using(new MockConnection(provider), SQLDialect.POSTGRES),
                new NoOpTransactionManager());
    }

    @Test
    void readsOnlyPinnedSubmissionEvidenceWithSameTenantScopeAndNoPrivateStorageFields() {
        DSLContext values = DSL.using(SQLDialect.POSTGRES);
        List<String> statements = new ArrayList<>();
        UUID submission = UUID.randomUUID(), item = UUID.randomUUID(), file = UUID.randomUUID();
        JooqFindingRepository repository = submittedRepository(values, statements,
                submissionRows(values, submission, 2, new UUID[] {item}, 1),
                snapshotRows(values, submission, item, file));

        var detail = repository.findDetailContext(scope(TENANT), UUID.randomUUID()).orElseThrow();

        assertThat(detail.submittedRemediationsTruncated()).isFalse();
        var submitted = detail.submittedRemediations().getFirst();
        assertThat(submitted.id()).isEqualTo(submission);
        assertThat(submitted.roundNo()).isEqualTo(2);
        assertThat(submitted.statement()).isEqualTo("Persisted remediation statement");
        assertThat(submitted.submitterName()).isNull();
        assertThat(submitted.evidence().getFirst().itemId()).isEqualTo(item);
        assertThat(submitted.evidence().getFirst().fileId()).isEqualTo(file);
        assertThat(submitted.evidence().getFirst().verificationLevel()).isEqualTo("SCANNED_CONTENT_V2");
        assertThat(statements).hasSize(5);
        assertThat(statements.get(3)).contains("submitter.tenant_id = submission.tenant_id",
                "order by submission.round_no desc, submission.id desc");
        assertThat(statements.get(4)).contains("from public.remediation_submission_evidence pinned",
                "pinned.tenant_id = submission.tenant_id", "pinned.finding_id = submission.finding_id",
                "pinned.round_no = submission.round_no", "pinned.submitted_by = submission.submitted_by",
                "pinned.evidence_item_id = any(submission.evidence_snapshot_manifest)", "limit 101", "limit 2001")
                .doesNotContain("from public.evidence_item", "object_key", "storage_version", "receipt");
        assertThat(statements.subList(3, 5)).allSatisfy(query -> assertThat(query).contains(
                "f.tenant_id = cast(? as uuid)", "f.id = cast(? as uuid)", "f.organization_id = any("));
    }

    @Test
    void missingPinnedItemsFailClosedInsteadOfShowingPartialSubmissionEvidence() {
        DSLContext values = DSL.using(SQLDialect.POSTGRES);
        UUID submission = UUID.randomUUID();
        JooqFindingRepository repository = submittedRepository(values, new ArrayList<>(),
                submissionRows(values, submission, 1, new UUID[] {UUID.randomUUID()}, 1),
                emptySubmissions(values));
        assertThatThrownBy(() -> repository.findDetailContext(scope(TENANT), UUID.randomUUID()))
                .isInstanceOf(FileEvidenceTrustUnavailableException.class);
    }

    @Test
    void oversizedSubmissionManifestFailsBeforeReadingEvidence() {
        DSLContext values = DSL.using(SQLDialect.POSTGRES);
        UUID[] items = java.util.stream.IntStream.range(0, 101).mapToObj(i -> UUID.randomUUID()).toArray(UUID[]::new);
        List<String> statements = new ArrayList<>();
        JooqFindingRepository repository = submittedRepository(values, statements,
                submissionRows(values, UUID.randomUUID(), 1, items, 101), emptySubmissions(values));
        assertThatThrownBy(() -> repository.findDetailContext(scope(TENANT), UUID.randomUUID()))
                .isInstanceOf(FileEvidenceTrustUnavailableException.class);
        assertThat(statements).hasSize(4);
    }

    @Test
    void returnsOnlyTheLatestTwentySubmissionsChronologicallyAndFlagsTheOlderWindow() {
        DSLContext values = DSL.using(SQLDialect.POSTGRES);
        UUID submission = UUID.randomUUID(), item = UUID.randomUUID();
        MockResult submissions = submissionRows(values, submission, 21, new UUID[] {item}, 1);
        MockResult evidence = snapshotRows(values, submission, item, UUID.randomUUID());
        for (int round = 20; round >= 1; round--) {
            submission = UUID.randomUUID(); item = UUID.randomUUID();
            appendRows(submissions, submissionRows(values, submission, round, new UUID[] {item}, 1));
            if (round >= 2) appendRows(evidence, snapshotRows(values, submission, item, UUID.randomUUID()));
        }
        JooqFindingRepository repository = submittedRepository(values, new ArrayList<>(),
                new MockResult(21, submissions.data), new MockResult(20, evidence.data));

        var detail = repository.findDetailContext(scope(TENANT), UUID.randomUUID()).orElseThrow();

        assertThat(detail.submittedRemediationsTruncated()).isTrue();
        assertThat(detail.submittedRemediations()).hasSize(20);
        assertThat(detail.submittedRemediations().getFirst().roundNo()).isEqualTo(2);
        assertThat(detail.submittedRemediations().getLast().roundNo()).isEqualTo(21);
    }

    private static JooqFindingRepository submittedRepository(DSLContext values, List<String> statements,
                                                            MockResult submissions, MockResult evidence) {
        return detailRepository(context -> {
            statements.add(context.sql());
            if (context.sql().contains("set_config")) return new MockResult[] {
                    singleValue(values, "set_config", String.class, TENANT.toString())};
            if (context.sql().contains("created_by_member_id")) return new MockResult[] {
                    detailMembers(values, UUID.randomUUID(), "Reporter", null, null)};
            if (context.sql().contains("finding_transition")) return new MockResult[] {historyRows(values, 1, true)};
            if (context.sql().contains("join lateral")) return new MockResult[] {evidence};
            return new MockResult[] {submissions};
        });
    }

    private static MockResult emptySubmissions(DSLContext values) {
        return new MockResult(0, values.newResult(DSL.field("id", UUID.class)));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void appendRows(MockResult target, MockResult source) {
        ((org.jooq.Result) target.data).addAll(source.data);
    }

    private static MockResult submissionRows(DSLContext dsl, UUID submission, int round, UUID[] manifest, int count) {
        List<Field<?>> fields = List.of(DSL.field("id", UUID.class), DSL.field("round_no", Integer.class),
                DSL.field("statement", String.class), DSL.field("submitted_at", OffsetDateTime.class),
                DSL.field("submitted_by", UUID.class), DSL.field("submitter_name", String.class),
                DSL.field("evidence_snapshot_count", Integer.class), DSL.field("evidence_snapshot_manifest", UUID[].class),
                DSL.field("evidence_snapshots_sealed_at", OffsetDateTime.class));
        var result = dsl.newResult(fields); var row = dsl.newRecord(fields);
        var now = OffsetDateTime.parse("2026-10-30T10:00:00Z");
        List<Object> data = Arrays.asList(submission, round, "Persisted remediation statement", now,
                UUID.randomUUID(), null, count, manifest, now);
        for (int i = 0; i < fields.size(); i++) set(row, fields.get(i), data.get(i));
        result.add(row); return new MockResult(1, result);
    }

    private static MockResult snapshotRows(DSLContext dsl, UUID submission, UUID item, UUID file) {
        List<Field<?>> fields = List.of(DSL.field("submission_id", UUID.class), DSL.field("evidence_item_id", UUID.class),
                DSL.field("file_id", UUID.class), DSL.field("sha256", String.class), DSL.field("verification_level", String.class));
        var result = dsl.newResult(fields); var row = dsl.newRecord(fields);
        List<Object> data = List.of(submission, item, file, "a".repeat(64), "SCANNED_CONTENT_V2");
        for (int i = 0; i < fields.size(); i++) set(row, fields.get(i), data.get(i));
        result.add(row); return new MockResult(1, result);
    }

    private static FindingReadScope scope(UUID tenant) {
        return new FindingReadScope(UUID.randomUUID(), tenant, UUID.randomUUID(), java.util.Set.of(ORG));
    }

    private static MockResult detailMembers(DSLContext dsl, UUID reporter, String reporterName,
                                            UUID responsible, String responsibleName) {
        List<Field<?>> fields = List.of(DSL.field("reporter_id", UUID.class),
                DSL.field("reporter_name", String.class), DSL.field("responsible_id", UUID.class),
                DSL.field("responsible_name", String.class));
        var result = dsl.newResult(fields);
        var row = dsl.newRecord(fields);
        List<Object> values = Arrays.asList(reporter, reporterName, responsible, responsibleName);
        for (int i = 0; i < fields.size(); i++) set(row, fields.get(i), values.get(i));
        result.add(row);
        return new MockResult(1, result);
    }

    private static MockResult historyRows(DSLContext dsl, int count, boolean hasActorLabel) {
        List<Field<?>> fields = List.of(DSL.field("id", UUID.class),
                DSL.field("occurred_at", OffsetDateTime.class), DSL.field("from_state", String.class),
                DSL.field("to_state", String.class), DSL.field("from_version", Long.class),
                DSL.field("to_version", Long.class), DSL.field("actor_name", String.class),
                DSL.field("reason", String.class));
        var result = dsl.newResult(fields);
        for (long version = count; version >= 1; version--) {
            var row = dsl.newRecord(fields);
            List<Object> values = Arrays.asList(UUID.randomUUID(),
                    OffsetDateTime.parse("2026-10-30T10:00:00Z"), version == 1 ? null : "DISCOVERED",
                    "PERSON_CONFIRM_PENDING", version - 1, version, hasActorLabel ? "Actor" : null, null);
            for (int i = 0; i < fields.size(); i++) set(row, fields.get(i), values.get(i));
            result.add(row);
        }
        return new MockResult(count, result);
    }

    private static MockResult findingRow(DSLContext dsl) {
        List<Field<?>> fields = List.of(
                DSL.field("id", UUID.class), DSL.field("code", String.class),
                DSL.field("organization_id", UUID.class), DSL.field("organization_name", String.class),
                DSL.field("source_category", String.class), DSL.field("urgency", String.class),
                DSL.field("location", String.class), DSL.field("interim_measure", String.class),
                DSL.field("backfill", Boolean.class));
        var result = dsl.newResult(fields);
        Record row = dsl.newRecord(fields);
        OffsetDateTime now = OffsetDateTime.parse("2026-10-30T10:00:00Z");
        List<Object> values = Arrays.asList(
                UUID.fromString("40000000-0000-0000-0000-000000000001"), "FND-TEST-01",
                ORG, "安全一部",
                "FIELD_OBSERVATION", "URGENT", "North loading dock", "Temporary barrier installed", true);
        for (int i = 0; i < fields.size(); i++) {
            set(row, fields.get(i), values.get(i));
        }
        result.add(row);
        return new MockResult(result.size(), result);
    }

    private static <T> MockResult singleValue(DSLContext dsl, String key, Class<T> type, T value) {
        Field<T> field = DSL.field(key, type);
        var result = dsl.newResult(field);
        var row = dsl.newRecord(field);
        row.set(field, value);
        result.add(row);
        return new MockResult(result.size(), result);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void set(Record row, Field field, Object value) {
        row.set(field, value);
    }

    private static final class NoOpTransactionManager extends AbstractPlatformTransactionManager {
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
        }
    }
}
