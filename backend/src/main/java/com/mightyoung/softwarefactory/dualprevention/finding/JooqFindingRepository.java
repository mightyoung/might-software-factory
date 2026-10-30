package com.mightyoung.softwarefactory.dualprevention.finding;

import com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.Direction;
import com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.FieldType;
import com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.FilterCondition;
import com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.FilterOperator;
import com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryValidator.ValidatedQuery;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.SortField;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.FilterOperator.BETWEEN;
import static com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.FilterOperator.CONTAINS;
import static com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.FilterOperator.EQ;
import static com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.FilterOperator.GT;
import static com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.FilterOperator.GTE;
import static com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.FilterOperator.IN;
import static com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.FilterOperator.IS_EMPTY;
import static com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.FilterOperator.IS_NOT_EMPTY;
import static com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.FilterOperator.IS_NOT_NULL;
import static com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.FilterOperator.IS_NULL;
import static com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.FilterOperator.LT;
import static com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.FilterOperator.LTE;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

/** jOOQ read repository with transaction-local tenant RLS and explicit organization scope predicates. */
@Repository
public final class JooqFindingRepository implements FindingRepository {

    private static final Table<?> FINDING = table(name("public", "finding"));
    private static final Field<UUID> ID = field(name("id"), UUID.class);
    private static final Field<UUID> TENANT_ID = field(name("tenant_id"), UUID.class);
    private static final Field<UUID> ORGANIZATION_ID = field(name("organization_id"), UUID.class);
    private static final Field<String> CODE = field(name("code"), String.class);
    private static final Field<String> DESCRIPTION = field(name("description"), String.class);
    private static final Field<OffsetDateTime> DISCOVERED_AT = field(name("discovered_at"), OffsetDateTime.class);
    private static final Field<UUID> SOURCE_EVENT_ID = field(name("source_event_id"), UUID.class);
    private static final Field<UUID> RISK_ID = field(name("risk_id"), UUID.class);
    private static final Field<String> GRADE = field(name("grade"), String.class);
    private static final Field<String> STATE = field(name("state"), String.class);
    private static final Field<Integer> ROUND_NO = field(name("round_no"), Integer.class);
    private static final Field<Long> VERSION = field(name("version"), Long.class);
    private static final Field<OffsetDateTime> CREATED_AT = field(name("created_at"), OffsetDateTime.class);
    private static final Field<OffsetDateTime> UPDATED_AT = field(name("updated_at"), OffsetDateTime.class);
    private static final Map<String, Field<?>> API_FIELDS = apiFields();
    private static final List<Field<?>> ALL_FIELDS = List.copyOf(API_FIELDS.values());

    private final DSLContext dsl;
    private final TransactionTemplate readTransaction;

    public JooqFindingRepository(DSLContext dsl, PlatformTransactionManager transactionManager) {
        this.dsl = dsl;
        this.readTransaction = new TransactionTemplate(transactionManager);
        this.readTransaction.setReadOnly(true);
        this.readTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    @Override
    public PageResult findPage(FindingReadScope scope, ValidatedQuery query) {
        requireNonEmptyScope(scope);
        return readTransaction.execute(status -> {
            setLocalTenant(scope.tenantId());
            Condition where = scopeCondition(scope).and(filterCondition(query.filters()));
            long total = dsl.selectCount().from(FINDING).where(where).fetchOne(0, Long.class);
            List<Field<?>> projection = query.fields().stream().map(JooqFindingRepository::apiField).toList();
            List<SortField<?>> ordering = query.sort().stream().map(JooqFindingRepository::sortField).toList();
            int offset = Math.toIntExact((long) (query.page().number() - 1) * query.size());
            List<FindingRecord> items = dsl.select(projection)
                    .from(FINDING)
                    .where(where)
                    .orderBy(ordering)
                    .offset(offset)
                    .limit(query.size())
                    .fetch(JooqFindingRepository::toRecord);
            return new PageResult(items, total);
        });
    }

    @Override
    public Optional<FindingRecord> findVisible(FindingReadScope scope, UUID findingId) {
        requireNonEmptyScope(scope);
        return readTransaction.execute(status -> {
            setLocalTenant(scope.tenantId());
            return dsl.select(ALL_FIELDS)
                    .from(FINDING)
                    .where(scopeCondition(scope).and(ID.eq(findingId)))
                    .limit(1)
                    .fetchOptional(JooqFindingRepository::toRecord);
        });
    }

    private void setLocalTenant(UUID tenantId) {
        String applied = (String) dsl.fetchValue("select set_config(?, ?, true)",
                "app.tenant_id", tenantId.toString());
        if (!tenantId.toString().equals(applied)) {
            throw new IllegalStateException("Unable to apply the finding tenant context");
        }
    }

    private static Condition scopeCondition(FindingReadScope scope) {
        return TENANT_ID.eq(scope.tenantId()).and(ORGANIZATION_ID.in(scope.organizationIds()));
    }

    private static Condition filterCondition(List<FilterCondition> filters) {
        Condition condition = DSL.noCondition();
        for (FilterCondition filter : filters) {
            condition = condition.and(filterCondition(filter));
        }
        return condition;
    }

    private static Condition filterCondition(FilterCondition filter) {
        Field<Object> column = objectField(filter.field());
        FilterOperator operator = filter.operator();
        if (operator == IS_NULL) return column.isNull();
        if (operator == IS_NOT_NULL) return column.isNotNull();
        if (operator == IS_EMPTY) return column.eq("");
        if (operator == IS_NOT_EMPTY) return column.ne("");

        FieldType type = fieldType(filter.field());
        if (operator == IN) {
            List<Object> values = new ArrayList<>();
            filter.value().forEach(value -> values.add(databaseValue(filter.field(), type, value)));
            return column.in(values);
        }
        if (operator == BETWEEN) {
            Object lower = databaseValue(filter.field(), type, filter.value().get(0));
            Object upper = databaseValue(filter.field(), type, filter.value().get(1));
            return column.between(lower).and(upper);
        }
        Object value = databaseValue(filter.field(), type, filter.value());
        return switch (operator) {
            case EQ -> column.eq(value);
            case GT -> column.gt(value);
            case GTE -> column.ge(value);
            case LT -> column.lt(value);
            case LTE -> column.le(value);
            case CONTAINS -> ((Field<String>) API_FIELDS.get(filter.field())).contains((String) value);
            default -> throw new IllegalArgumentException("Unsupported finding filter operator");
        };
    }

    private static Object databaseValue(String key, FieldType type, tools.jackson.databind.JsonNode value) {
        try {
            return switch (type) {
                case TEXT, ENUM -> value.stringValue();
                case UUID, USER, ORGANIZATION, REFERENCE -> UUID.fromString(value.stringValue());
                case DATETIME -> OffsetDateTime.parse(value.stringValue());
                case DATE -> java.time.LocalDate.parse(value.stringValue());
                case BOOLEAN -> value.booleanValue();
                case NUMBER -> key.equals("roundNo")
                        ? value.decimalValue().intValueExact()
                        : value.decimalValue().longValueExact();
                case MONEY -> new BigDecimal(value.numberValue().toString());
                case IMAGE, ATTACHMENT -> throw new IllegalArgumentException("Unsupported finding filter type");
            };
        } catch (IllegalArgumentException | DateTimeParseException | ArithmeticException exception) {
            throw new IllegalArgumentException("Validated finding filter could not be converted", exception);
        }
    }

    private static SortField<?> sortField(
            com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.SortField sort) {
        Field<?> field = apiField(sort.field());
        return sort.direction() == Direction.ASC ? field.asc() : field.desc();
    }

    private static Field<?> apiField(String key) {
        Field<?> selected = API_FIELDS.get(key);
        if (selected == null) {
            throw new IllegalArgumentException("Finding field is not registered");
        }
        return selected;
    }

    @SuppressWarnings("unchecked")
    private static Field<Object> objectField(String key) {
        return (Field<Object>) API_FIELDS.get(key);
    }

    private static FieldType fieldType(String key) {
        return switch (key) {
            case "id", "sourceEventId", "riskId" -> FieldType.UUID;
            case "code", "description" -> FieldType.TEXT;
            case "grade", "state" -> FieldType.ENUM;
            case "discoveredAt", "createdAt", "updatedAt" -> FieldType.DATETIME;
            case "roundNo", "version" -> FieldType.NUMBER;
            default -> throw new IllegalArgumentException("Finding field is not registered");
        };
    }

    private static FindingRecord toRecord(Record row) {
        return new FindingRecord(
                selectedValue(row, ID), selectedValue(row, CODE), selectedValue(row, DESCRIPTION),
                selectedValue(row, DISCOVERED_AT), selectedValue(row, SOURCE_EVENT_ID), selectedValue(row, RISK_ID),
                selectedValue(row, GRADE), selectedValue(row, STATE), selectedValue(row, ROUND_NO),
                selectedValue(row, VERSION), selectedValue(row, CREATED_AT), selectedValue(row, UPDATED_AT));
    }

    private static <T> T selectedValue(Record row, Field<T> field) {
        boolean selected = java.util.Arrays.stream(row.fields())
                .anyMatch(resultField -> resultField.getName().equals(field.getName()));
        return selected ? row.get(field) : null;
    }

    private static void requireNonEmptyScope(FindingReadScope scope) {
        if (scope == null || scope.organizationIds().isEmpty()) {
            throw new org.springframework.security.access.AccessDeniedException(
                    "A non-empty finding data scope is required");
        }
    }

    private static Map<String, Field<?>> apiFields() {
        Map<String, Field<?>> fields = new LinkedHashMap<>();
        fields.put("id", ID);
        fields.put("code", CODE);
        fields.put("description", DESCRIPTION);
        fields.put("discoveredAt", DISCOVERED_AT);
        fields.put("sourceEventId", SOURCE_EVENT_ID);
        fields.put("riskId", RISK_ID);
        fields.put("grade", GRADE);
        fields.put("state", STATE);
        fields.put("roundNo", ROUND_NO);
        fields.put("version", VERSION);
        fields.put("createdAt", CREATED_AT);
        fields.put("updatedAt", UPDATED_AT);
        return Map.copyOf(fields);
    }
}
