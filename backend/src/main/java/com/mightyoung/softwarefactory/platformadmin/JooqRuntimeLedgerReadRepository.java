package com.mightyoung.softwarefactory.platformadmin;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.*;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.SortField;
import org.jooq.Record;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.ValidationException;
import com.mightyoung.softwarefactory.platformadmin.LedgerConfigurationModels.Definition;
import com.mightyoung.softwarefactory.platformadmin.RuntimeLedgerCommandModels.RecordView;
import static com.mightyoung.softwarefactory.platformadmin.RuntimeLedgerReadModels.*;
import static org.jooq.impl.DSL.*;

@Repository
public final class JooqRuntimeLedgerReadRepository implements RuntimeLedgerReadRepository, com.mightyoung.softwarefactory.applicationregistry.navigation.PublishedLedgerKeys {
    private static final TypeReference<Map<String, Object>> VALUES = new TypeReference<>() {};
    private final DSLContext db;
    private final ObjectMapper json;
    public JooqRuntimeLedgerReadRepository(DSLContext db, ObjectMapper json) { this.db = db; this.json = json; }

    @Override public List<String> publishedKeys(UUID tenant) {
        scope(tenant);
        return db.fetch("select configuration_key from ledger_configuration where tenant_id=? and published_version is not null order by configuration_key", tenant).getValues(0, String.class);
    }
    @Override public List<Published> published(UUID tenant) {
        scope(tenant);
        return db.fetch("select c.configuration_key,c.published_version,s.definition::text from ledger_configuration c"
                + " join ledger_configuration_snapshot s on s.tenant_id=c.tenant_id and s.configuration_key=c.configuration_key"
                + " and s.version=c.published_version where c.tenant_id=? order by c.configuration_key", tenant)
                .map(row -> new Published(row.get(0, String.class), row.get(1, Long.class), json.readValue(row.get(2, String.class), Definition.class)));
    }
    @Override public Published published(UUID tenant, String key) {
        return published(tenant).stream().filter(item -> item.key().equals(key)).findFirst().orElseThrow(JooqRuntimeLedgerReadRepository::missing);
    }
    @Override public List<Organization> organizations(UUID tenant, Set<UUID> scope) {
        scope(tenant);
        return db.select(field("id", UUID.class), field("name", String.class)).from(table("org_unit"))
                .where(field("tenant_id", UUID.class).eq(tenant)).and(field("active", Boolean.class).eq(true))
                .and(field("id", UUID.class).in(scope)).orderBy(field("name"), field("id"))
                .fetch(row -> new Organization(row.value1(), row.value2()));
    }
    @Override public Page query(UUID tenant, Published published, Set<UUID> scope, Query query) {
        scope(tenant);
        if (query == null || query.page() == null || query.page() < 1 || query.page() > 1_000_000
                || query.size() == null || query.size() < 1 || query.size() > 100) invalid("page", "invalid_pagination");
        List<Filter> filters = query.filters() == null ? List.of() : query.filters();
        if (filters.size() > 20) invalid("filters", "too_many");
        Condition condition = records(tenant, published.key(), scope);
        for (Filter filter : filters) condition = condition.and(filter(published.definition(), filter));
        List<SortField<?>> order = new ArrayList<>();
        if (query.sort() != null) {
            Sort sort = query.sort();
            if (sort.direction() == null || !Set.of("asc", "desc").contains(sort.direction())) invalid("sort", "invalid_direction");
            var definition = queryField(published.definition(), sort.field());
            org.jooq.Field<?> expression = expression(definition);
            order.add("asc".equals(sort.direction()) ? expression.asc().nullsLast() : expression.desc().nullsLast());
        }
        order.add(field(name("r", "id")).asc());
        long total = db.selectCount().from(table(name("ordinary_ledger_record")).as("r")).where(condition).fetchOne(0, long.class);
        List<RecordView> rows = db.selectFrom(table(name("ordinary_ledger_record")).as("r")).where(condition)
                .orderBy(order).limit(query.size()).offset((query.page() - 1) * query.size())
                .fetch(row -> record(row, published.definition().listFields()));
        return new Page(rows, query.page(), query.size(), total, (long) query.page() * query.size() < total);
    }
    @Override public Detail detail(UUID tenant, String key, UUID id, Set<UUID> scope, Capabilities capabilities) {
        scope(tenant);
        Record row = db.selectFrom(table(name("ordinary_ledger_record")).as("r"))
                .where(records(tenant, key, scope)).and(field(name("r", "id"), UUID.class).eq(id)).fetchOne();
        if (row == null) throw missing();
        String definition = db.fetchValue("select definition::text from ledger_configuration_snapshot where tenant_id=? and configuration_key=? and version=?",
                tenant, key, row.get("definition_version", Long.class)).toString();
        Definition pinned = json.readValue(definition, Definition.class);
        return new Detail(record(row, pinned.fields().stream().map(LedgerConfigurationModels.Field::key).toList()), PublicDefinition.from(pinned), capabilities);
    }
    private RecordView record(Record row, List<String> allowed) {
        Map<String, Object> raw = json.readValue(row.get("values_json", String.class), VALUES);
        Map<String, Object> values = new LinkedHashMap<>();
        for (String field : allowed) if (raw.containsKey(field)) values.put(field, raw.get(field));
        return new RecordView(row.get("id", UUID.class), row.get("configuration_key", String.class),
                row.get("definition_version", Long.class), row.get("organization_id", UUID.class), values,
                row.get("version", Long.class), row.get("created_at", OffsetDateTime.class).toInstant(),
                row.get("updated_at", OffsetDateTime.class).toInstant());
    }
    private static Condition records(UUID tenant, String key, Set<UUID> scope) {
        return field(name("r", "tenant_id"), UUID.class).eq(tenant)
                .and(field(name("r", "configuration_key"), String.class).eq(key))
                .and(field(name("r", "organization_id"), UUID.class).in(scope))
                .and(field(name("r", "deleted"), Boolean.class).eq(false));
    }
    static Condition filter(Definition definition, Filter filter) {
        if (filter == null || filter.value() == null) invalid("filters", "invalid_filter");
        var config = queryField(definition, filter.field());
        org.jooq.Field expression = expression(config);
        Object value = typed(config.type(), filter.value());
        return switch (filter.operator() == null ? "" : filter.operator()) {
            case "eq" -> expression.eq(value);
            case "gte" -> {
                if (!Set.of("number", "date").contains(config.type())) invalid("filters", "invalid_operator");
                yield expression.ge(value);
            }
            case "lte" -> {
                if (!Set.of("number", "date").contains(config.type())) invalid("filters", "invalid_operator");
                yield expression.le(value);
            }
            case "contains" -> {
                if (!"text".equals(config.type())) invalid("filters", "invalid_operator");
                yield ((org.jooq.Field<String>) expression).contains((String) value);
            }
            default -> throw new ValidationException("filters", "invalid_operator", "Unsupported ledger query");
        };
    }
    private static LedgerConfigurationModels.Field queryField(Definition definition, String key) {
        if (key == null || !definition.queryFields().contains(key)) invalid("field", "query_not_allowed");
        return definition.fields().stream().filter(field -> field.key().equals(key)).findFirst()
                .orElseThrow(() -> new ValidationException("field", "unknown_field", "Unknown ledger field"));
    }
    private static org.jooq.Field<?> expression(LedgerConfigurationModels.Field field) {
        var key = val(field.key());
        var text = field("({0}->>{1})", String.class, DSL.field(name("r", "values_json")), key);
        return switch (field.type()) {
            case "number" -> field("case when jsonb_typeof({0}->{1})='number' then cast({2} as numeric) end",
                    BigDecimal.class, DSL.field(name("r", "values_json")), key, text);
            case "boolean" -> field("case when jsonb_typeof({0}->{1})='boolean' then cast({2} as boolean) end",
                    Boolean.class, DSL.field(name("r", "values_json")), key, text);
            case "date" -> field("case when pg_input_is_valid({0},'date') then cast({0} as date) end", LocalDate.class, text);
            default -> field("case when jsonb_typeof({0}->{1})='string' then {2} end",
                    String.class, DSL.field(name("r", "values_json")), key, text);
        };
    }
    private static Object typed(String type, Object value) {
        try {
            return switch (type) {
                case "number" -> {
                    if (!(value instanceof Number)) invalid("value", "number_required");
                    yield new BigDecimal(value.toString());
                }
                case "boolean" -> {
                    if (!(value instanceof Boolean)) invalid("value", "boolean_required");
                    yield value;
                }
                case "date" -> LocalDate.parse(value.toString());
                default -> {
                    if (!(value instanceof String) || value.toString().length() > 1000) invalid("value", "text_required");
                    yield value;
                }
            };
        } catch (java.time.DateTimeException | IllegalArgumentException failure) {
            if (failure instanceof ValidationException) throw failure;
            throw new ValidationException("value", "invalid_type", "Invalid ledger filter value");
        }
    }
    private void scope(UUID tenant) { db.fetchValue("select set_config('app.tenant_id', ?, true)", tenant.toString()); }
    private static LedgerConfigurationException missing() { return new LedgerConfigurationException(LedgerConfigurationException.Reason.NOT_FOUND); }
    private static void invalid(String field, String code) { throw new ValidationException(field, code, "Invalid ledger query"); }
}
