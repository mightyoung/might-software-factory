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

    private static final Table<?> FINDING = table(name("public", "finding")).as("finding");
    private static final Table<?> ORGANIZATION = table(name("public", "org_unit")).as("reportable_organization");
    private static final Field<UUID> ID = field(name("finding", "id"), UUID.class);
    private static final Field<UUID> TENANT_ID = field(name("finding", "tenant_id"), UUID.class);
    private static final Field<UUID> ORGANIZATION_ID = field(name("finding", "organization_id"), UUID.class);
    private static final Field<UUID> ORGANIZATION_TENANT_ID =
            field(name("reportable_organization", "tenant_id"), UUID.class);
    private static final Field<UUID> ORGANIZATION_KEY = field(name("reportable_organization", "id"), UUID.class);
    private static final Field<String> ORGANIZATION_NAME =
            field(name("reportable_organization", "name"), String.class).as("organization_name");
    private static final Field<String> CODE = field(name("finding", "code"), String.class);
    private static final Field<String> DESCRIPTION = field(name("finding", "description"), String.class);
    private static final Field<OffsetDateTime> DISCOVERED_AT =
            field(name("finding", "discovered_at"), OffsetDateTime.class);
    private static final Field<UUID> SOURCE_EVENT_ID = field(name("finding", "source_event_id"), UUID.class);
    private static final Field<UUID> RISK_ID = field(name("finding", "risk_id"), UUID.class);
    private static final Field<UUID> RELATED_FINDING_ID = field(name("finding", "related_finding_id"), UUID.class);
    private static final Field<String> SOURCE_CATEGORY = field(name("finding", "source_category"), String.class);
    private static final Field<String> URGENCY = field(name("finding", "urgency"), String.class);
    private static final Field<String> LOCATION = field(name("finding", "location"), String.class);
    private static final Field<String> INTERIM_MEASURE = field(name("finding", "interim_measure"), String.class);
    private static final Field<Boolean> BACKFILL = field(name("finding", "backfill"), Boolean.class);
    private static final Field<String> GRADE = field(name("finding", "grade"), String.class);
    private static final Field<String> STATE = field(name("finding", "state"), String.class);
    private static final Field<Integer> ROUND_NO = field(name("finding", "round_no"), Integer.class);
    private static final Field<Integer> REVIEW_CYCLE = field(name("finding", "review_cycle"), Integer.class);
    private static final Field<String> REVIEW_PROTOCOL = field(name("finding", "review_protocol"), String.class);
    private static final Field<Long> VERSION = field(name("finding", "version"), Long.class);
    private static final Field<OffsetDateTime> CREATED_AT = field(name("finding", "created_at"), OffsetDateTime.class);
    private static final Field<OffsetDateTime> UPDATED_AT = field(name("finding", "updated_at"), OffsetDateTime.class);
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
                    .leftJoin(ORGANIZATION).on(organizationJoinCondition())
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
                    .leftJoin(ORGANIZATION).on(organizationJoinCondition())
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

    private static Condition organizationJoinCondition() {
        return ORGANIZATION_TENANT_ID.eq(TENANT_ID).and(ORGANIZATION_KEY.eq(ORGANIZATION_ID));
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
                case NUMBER -> (key.equals("roundNo") || key.equals("reviewCycle"))
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
            case "id", "organizationId", "sourceEventId", "riskId", "relatedFindingId" -> FieldType.UUID;
            case "code", "description", "organizationName" -> FieldType.TEXT;
            case "grade", "state", "sourceCategory", "urgency", "reviewProtocol" -> FieldType.ENUM;
            case "location", "interimMeasure" -> FieldType.TEXT;
            case "backfill" -> FieldType.BOOLEAN;
            case "discoveredAt", "createdAt", "updatedAt" -> FieldType.DATETIME;
            case "roundNo", "version", "reviewCycle" -> FieldType.NUMBER;
            default -> throw new IllegalArgumentException("Finding field is not registered");
        };
    }

    private static FindingRecord toRecord(Record row) {
        return new FindingRecord(
                selectedValue(row, ID), selectedValue(row, CODE), selectedValue(row, DESCRIPTION),
                selectedValue(row, DISCOVERED_AT), selectedValue(row, SOURCE_EVENT_ID), selectedValue(row, RISK_ID),
                selectedValue(row, GRADE), selectedValue(row, STATE), selectedValue(row, ROUND_NO),
                selectedValue(row, VERSION), selectedValue(row, CREATED_AT), selectedValue(row, UPDATED_AT),
                selectedValue(row, ORGANIZATION_ID), selectedValue(row, ORGANIZATION_NAME),
                selectedValue(row, SOURCE_CATEGORY), selectedValue(row, URGENCY), selectedValue(row, LOCATION),
                selectedValue(row, INTERIM_MEASURE), selectedValue(row, BACKFILL),
                selectedValue(row, RELATED_FINDING_ID), selectedValue(row, REVIEW_CYCLE), selectedValue(row, REVIEW_PROTOCOL));
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
        fields.put("organizationId", ORGANIZATION_ID);
        fields.put("organizationName", ORGANIZATION_NAME);
        fields.put("description", DESCRIPTION);
        fields.put("discoveredAt", DISCOVERED_AT);
        fields.put("sourceEventId", SOURCE_EVENT_ID);
        fields.put("riskId", RISK_ID);
        fields.put("relatedFindingId", RELATED_FINDING_ID);
        fields.put("sourceCategory", SOURCE_CATEGORY);
        fields.put("urgency", URGENCY);
        fields.put("location", LOCATION);
        fields.put("interimMeasure", INTERIM_MEASURE);
        fields.put("backfill", BACKFILL);
        fields.put("grade", GRADE);
        fields.put("state", STATE);
        fields.put("roundNo", ROUND_NO);
        fields.put("version", VERSION);
        fields.put("reviewCycle", REVIEW_CYCLE);
        fields.put("reviewProtocol", REVIEW_PROTOCOL);
        fields.put("createdAt", CREATED_AT);
        fields.put("updatedAt", UPDATED_AT);
        return Map.copyOf(fields);
    }
}
