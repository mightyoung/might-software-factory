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
                 "sort":[{"field":"discoveredAt","direction":"DESC"}],
                 "page":{"number":2},"size":20,"fields":["id","code"]}
                """), new FindingEntitySchema().schema());

        var result = repository.findPage(new FindingReadScope(
                UUID.randomUUID(), TENANT, UUID.randomUUID(), java.util.Set.of(ORG)), query);

        assertThat(result.total()).isEqualTo(1L);
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
                .contains("order by \"discovered_at\" desc, \"id\" asc", "offset ? rows fetch next ? rows only");
        assertThat(bindings.stream().flatMap(List::stream))
                .contains(TENANT, ORG, "MAJOR", "app.tenant_id", TENANT.toString(), 20L);
    }

    private static MockResult findingRow(DSLContext dsl) {
        List<Field<?>> fields = List.of(
                DSL.field("id", UUID.class), DSL.field("code", String.class),
                DSL.field("description", String.class), DSL.field("discovered_at", OffsetDateTime.class),
                DSL.field("source_event_id", UUID.class), DSL.field("risk_id", UUID.class),
                DSL.field("grade", String.class), DSL.field("state", String.class),
                DSL.field("round_no", Integer.class), DSL.field("version", Long.class),
                DSL.field("created_at", OffsetDateTime.class), DSL.field("updated_at", OffsetDateTime.class));
        var result = dsl.newResult(fields);
        Record row = dsl.newRecord(fields);
        OffsetDateTime now = OffsetDateTime.parse("2026-10-30T10:00:00Z");
        List<Object> values = Arrays.asList(
                UUID.fromString("40000000-0000-0000-0000-000000000001"), "FND-TEST-01",
                "Synthetic test finding", now, null, null, "MAJOR", "PERSON_CONFIRM_PENDING", 1,
                1L, now, now);
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
