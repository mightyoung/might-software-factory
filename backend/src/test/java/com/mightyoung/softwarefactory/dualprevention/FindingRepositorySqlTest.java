package com.mightyoung.softwarefactory.dualprevention;

import com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract;
import com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryValidator;
import com.mightyoung.softwarefactory.dualprevention.finding.FindingReadScope;
import com.mightyoung.softwarefactory.dualprevention.finding.JooqFindingRepository;
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
            return new MockResult[] {historyRows(values, 2, true)};
        });

        var detail = repository.findDetailContext(scope(TENANT), finding).orElseThrow();

        assertThat(detail.reporter().memberId()).isEqualTo(reporter);
        assertThat(detail.responsible().memberId()).isEqualTo(responsible);
        assertThat(detail.responsible().displayName()).isEqualTo("Assignee");
        assertThat(detail.history()).extracting("toVersion").containsExactly(1L, 2L);
        assertThat(detail.history().getFirst().actorName()).isEqualTo("Actor");
        assertThat(detail.historyTruncated()).isFalse();
        assertThat(statements).hasSize(3);
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
