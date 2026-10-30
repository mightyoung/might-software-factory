package com.mightyoung.softwarefactory.applicationregistry.query;

import com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.Direction;
import com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.EntitySchema;
import com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.FieldSchema;
import com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.FieldType;
import com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.FilterCondition;
import com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.FilterGroup;
import com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.FilterOperator;
import com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.LogicalOperator;
import com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.PageRequest;
import com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.Request;
import com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.SortField;
import com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.ValidationException;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import static com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.FilterOperator.BETWEEN;
import static com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.FilterOperator.CONTAINS;
import static com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.FilterOperator.EQ;
import static com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.FilterOperator.GT;
import static com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.FilterOperator.GTE;
import static com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.FilterOperator.IN;
import static com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.FilterOperator.IN_SUBTREE;
import static com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.FilterOperator.IS_EMPTY;
import static com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.FilterOperator.IS_NOT_EMPTY;
import static com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.FilterOperator.IS_NOT_NULL;
import static com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.FilterOperator.IS_NULL;
import static com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.FilterOperator.LT;
import static com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.FilterOperator.LTE;

/**
 * Pure allowlist and value validation for a generic query request.
 *
 * <p>The supplied schema must already be restricted to the caller's tenant and permissions.
 * This class is not an authorization check, SQL builder, or data access layer.</p>
 */
public final class EntityQueryValidator {

    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_OFFSET = 10_000;
    private static final int MAX_CURSOR_LENGTH = 2_048;
    private static final int MAX_FILTERS = 50;
    private static final int MAX_SORT_FIELDS = 3;
    private static final int MAX_OUTPUT_FIELDS = 50;
    private static final int MAX_IN_VALUES = 100;
    private static final Pattern FIELD_KEY = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,63}");

    private static final Map<FieldType, Set<FilterOperator>> TYPE_OPERATORS = Map.ofEntries(
            Map.entry(FieldType.TEXT, Set.of(EQ, CONTAINS, IS_EMPTY, IS_NOT_EMPTY, IS_NULL, IS_NOT_NULL)),
            Map.entry(FieldType.NUMBER, Set.of(EQ, GT, GTE, LT, LTE, BETWEEN, IS_NULL, IS_NOT_NULL)),
            Map.entry(FieldType.MONEY, Set.of(EQ, GT, GTE, LT, LTE, BETWEEN, IS_NULL, IS_NOT_NULL)),
            Map.entry(FieldType.DATE, Set.of(EQ, GT, GTE, LT, LTE, BETWEEN, IS_NULL, IS_NOT_NULL)),
            Map.entry(FieldType.DATETIME, Set.of(EQ, GT, GTE, LT, LTE, BETWEEN, IS_NULL, IS_NOT_NULL)),
            Map.entry(FieldType.BOOLEAN, Set.of(EQ, IS_NULL, IS_NOT_NULL)),
            Map.entry(FieldType.ENUM, Set.of(EQ, IN, IS_NULL, IS_NOT_NULL)),
            Map.entry(FieldType.UUID, Set.of(EQ, IN, IS_NULL, IS_NOT_NULL)),
            Map.entry(FieldType.USER, Set.of(EQ, IN, IS_NULL, IS_NOT_NULL)),
            Map.entry(FieldType.ORGANIZATION, Set.of(EQ, IN, IN_SUBTREE, IS_NULL, IS_NOT_NULL)),
            Map.entry(FieldType.REFERENCE, Set.of(EQ, IN, IS_NULL, IS_NOT_NULL)),
            Map.entry(FieldType.IMAGE, Set.of(IS_NULL, IS_NOT_NULL)),
            Map.entry(FieldType.ATTACHMENT, Set.of(IS_NULL, IS_NOT_NULL)));

    public ValidatedQuery validate(Request request, EntitySchema schema) {
        validateSchema(schema);
        if (request == null) {
            throw invalid("request", "required", "A query request is required");
        }

        int size = validateSize(request.size());
        PageRequest page = validatePage(request.page(), size);
        List<FilterCondition> filters = validateFilters(request.filterAST(), schema);
        List<SortField> sort = validateSort(request.sort(), schema);
        List<String> fields = validateProjection(request.fields(), schema);

        return new ValidatedQuery(schema.key(), schema.schemaVersion(), filters, sort, page, size, fields);
    }

    private static void validateSchema(EntitySchema schema) {
        if (schema == null || !validKey(schema.key()) || !hasText(schema.schemaVersion())
                || schema.schemaVersion().length() > 64 || schema.fields() == null || schema.fields().isEmpty()) {
            throw invalid("schema", "invalid_registry", "The registered entity schema is invalid");
        }
        if (!validKey(schema.idField()) || !schema.fields().containsKey(schema.idField())) {
            throw invalid("schema.idField", "invalid_registry", "The schema must register its stable ID field");
        }

        for (Map.Entry<String, FieldSchema> entry : schema.fields().entrySet()) {
            String key = entry.getKey();
            FieldSchema field = entry.getValue();
            if (!validKey(key) || field == null || field.type() == null || field.operators() == null
                    || field.enumValues() == null || field.maxLength() < 0
                    || (field.type() != FieldType.TEXT && field.maxLength() != 0)) {
                throw invalid("schema.fields." + key, "invalid_registry", "A registered field schema is invalid");
            }
            Set<FilterOperator> compatible = compatibleOperators(field.type());
            if (!compatible.containsAll(field.operators())) {
                throw invalid("schema.fields." + key + ".operators", "invalid_registry",
                        "Registered operators must be compatible with the field type");
            }
            if (field.nullable() && !compatible.contains(IS_NULL)) {
                throw invalid("schema.fields." + key, "invalid_registry", "This field type cannot be nullable");
            }
            if (field.type() == FieldType.ENUM) {
                if (field.enumValues().isEmpty() || field.enumValues().stream().anyMatch(value -> !hasText(value))) {
                    throw invalid("schema.fields." + key + ".enumValues", "invalid_registry",
                            "Enum fields must have non-empty registered values");
                }
            } else if (!field.enumValues().isEmpty()) {
                throw invalid("schema.fields." + key + ".enumValues", "invalid_registry",
                        "Only enum fields may declare enum values");
            }
        }

        FieldSchema id = schema.fields().get(schema.idField());
        if (!id.sortable() || !id.selectable()) {
            throw invalid("schema.idField", "invalid_registry", "The stable ID field must be selectable and sortable");
        }
        if (schema.defaultFields() == null || schema.defaultFields().isEmpty()
                || schema.defaultFields().stream().anyMatch(key -> {
                    FieldSchema field = schema.fields().get(key);
                    return field == null || !field.selectable();
                })) {
            throw invalid("schema.defaultFields", "invalid_registry", "Default fields must be selectable fields");
        }
    }

    private static int validateSize(Integer size) {
        if (size == null || !(size == 20 || size == 50 || size == MAX_PAGE_SIZE)) {
            throw invalid("size", "unsupported_page_size", "Page size must be 20, 50, or 100");
        }
        return size;
    }

    private static PageRequest validatePage(PageRequest page, int size) {
        if (page == null) {
            throw invalid("page", "required", "Choose a page number or cursor");
        }
        boolean hasNumber = page.number() != null;
        boolean hasCursor = page.cursor() != null;
        if (hasNumber == hasCursor) {
            throw invalid("page", "invalid_page_mode", "Provide exactly one of page.number or page.cursor");
        }
        if (hasNumber) {
            if (page.number() < 1 || (long) (page.number() - 1) * size > MAX_OFFSET) {
                throw invalid("page.number", "page_out_of_range", "Page number is outside the supported range");
            }
        } else if (!hasText(page.cursor()) || page.cursor().length() > MAX_CURSOR_LENGTH) {
            throw invalid("page.cursor", "invalid_cursor", "Cursor must be non-empty and bounded");
        }
        return page;
    }

    private static List<FilterCondition> validateFilters(FilterGroup group, EntitySchema schema) {
        if (group == null) {
            return List.of();
        }
        if (group.operator() != LogicalOperator.AND || group.conditions() == null
                || group.conditions().size() > MAX_FILTERS) {
            throw invalid("filterAST", "invalid_filter_group", "Only a bounded AND filter group is supported");
        }
        List<FilterCondition> normalized = new ArrayList<>();
        for (int index = 0; index < group.conditions().size(); index++) {
            FilterCondition condition = group.conditions().get(index);
            String path = "filterAST.conditions[" + index + "]";
            if (condition == null || !hasText(condition.field()) || condition.operator() == null) {
                throw invalid(path, "invalid_filter", "Filter field and operator are required");
            }
            FieldSchema field = schema.fields().get(condition.field());
            if (field == null) {
                throw invalid(path + ".field", "unknown_field", "Field is not registered for this entity");
            }
            if (!field.operators().contains(condition.operator())) {
                throw invalid(path + ".operator", "operator_not_allowed", "Operator is not allowed for this field");
            }
            validateValue(condition, field, path);
            normalized.add(condition);
        }
        return List.copyOf(normalized);
    }

    private static void validateValue(FilterCondition condition, FieldSchema field, String path) {
        FilterOperator operator = condition.operator();
        JsonNode value = condition.value();
        if (operator == IS_NULL || operator == IS_NOT_NULL || operator == IS_EMPTY || operator == IS_NOT_EMPTY) {
            if (value != null && !value.isNull()) {
                throw invalid(path + ".value", "unexpected_value", "This operator does not accept a value");
            }
            if ((operator == IS_NULL || operator == IS_NOT_NULL) && !field.nullable()) {
                throw invalid(path + ".operator", "null_check_not_allowed", "Field is not nullable");
            }
            return;
        }
        if (value == null || value.isNull()) {
            throw invalid(path + ".value", "value_required", "A typed filter value is required");
        }
        if (operator == IN || operator == BETWEEN) {
            validateArrayValues(value, field, operator, path);
            return;
        }
        validateScalar(value, field, path + ".value");
    }

    private static void validateArrayValues(JsonNode value, FieldSchema field, FilterOperator operator, String path) {
        if (!value.isArray()) {
            throw invalid(path + ".value", "array_required", "This operator requires an array value");
        }
        int count = value.size();
        int min = operator == BETWEEN ? 2 : 1;
        int max = operator == BETWEEN ? 2 : MAX_IN_VALUES;
        if (count < min || count > max) {
            throw invalid(path + ".value", "invalid_value_count", "Filter value count is outside the supported range");
        }
        for (int i = 0; i < count; i++) {
            validateScalar(value.get(i), field, path + ".value[" + i + "]");
        }
        if (operator == BETWEEN && compareRangeValues(value.get(0), value.get(1), field.type()) > 0) {
            throw invalid(path + ".value", "invalid_range", "Range lower bound must not exceed upper bound");
        }
    }

    private static int compareRangeValues(JsonNode lower, JsonNode upper, FieldType type) {
        return switch (type) {
            case NUMBER, MONEY -> lower.decimalValue().compareTo(upper.decimalValue());
            case DATE -> LocalDate.parse(lower.stringValue()).compareTo(LocalDate.parse(upper.stringValue()));
            case DATETIME -> OffsetDateTime.parse(lower.stringValue()).toInstant()
                    .compareTo(OffsetDateTime.parse(upper.stringValue()).toInstant());
            default -> 0;
        };
    }

    private static void validateScalar(JsonNode value, FieldSchema field, String path) {
        if (value == null || value.isNull()) {
            throw invalid(path, "value_required", "Null is not a typed filter value");
        }
        boolean valid = switch (field.type()) {
            case TEXT -> value.isString() && value.stringValue().length() <= field.maxLength();
            case NUMBER, MONEY -> value.isNumber();
            case DATE -> isDate(value);
            case DATETIME -> isOffsetDateTime(value);
            case BOOLEAN -> value.isBoolean();
            case ENUM -> value.isString() && field.enumValues().contains(value.stringValue());
            case UUID, USER, ORGANIZATION -> isUuid(value);
            case REFERENCE -> value.isString() && hasText(value.stringValue()) && value.stringValue().length() <= 256;
            case IMAGE, ATTACHMENT -> false;
        };
        if (!valid) {
            throw invalid(path, "value_type_mismatch", "Filter value does not match the registered field type");
        }
    }

    private static List<SortField> validateSort(List<SortField> requested, EntitySchema schema) {
        List<SortField> sort = requested == null ? List.of() : requested;
        if (sort.size() > MAX_SORT_FIELDS) {
            throw invalid("sort", "too_many_sort_fields", "At most three sort fields may be requested");
        }
        Set<String> seen = new HashSet<>();
        List<SortField> normalized = new ArrayList<>();
        for (int index = 0; index < sort.size(); index++) {
            SortField item = sort.get(index);
            if (item == null || !hasText(item.field()) || item.direction() == null) {
                throw invalid("sort[" + index + "]", "invalid_sort", "Sort field and direction are required");
            }
            FieldSchema field = schema.fields().get(item.field());
            if (field == null || !field.sortable()) {
                throw invalid("sort[" + index + "].field", "sort_not_allowed", "Field is not sortable");
            }
            if (!seen.add(item.field())) {
                throw invalid("sort[" + index + "].field", "duplicate_sort_field", "Sort fields must be unique");
            }
            normalized.add(item);
        }
        if (!seen.contains(schema.idField())) {
            normalized.add(new SortField(schema.idField(), Direction.ASC));
        }
        return List.copyOf(normalized);
    }

    private static List<String> validateProjection(List<String> requested, EntitySchema schema) {
        List<String> fields = requested == null || requested.isEmpty() ? schema.defaultFields() : requested;
        if (fields.size() > MAX_OUTPUT_FIELDS) {
            throw invalid("fields", "too_many_fields", "At most fifty output fields may be requested");
        }
        Set<String> unique = new LinkedHashSet<>();
        for (String key : fields) {
            FieldSchema field = key == null ? null : schema.fields().get(key);
            if (field == null || !field.selectable()) {
                throw invalid("fields", "field_not_selectable", "Requested output field is not selectable");
            }
            if (!unique.add(key)) {
                throw invalid("fields", "duplicate_field", "Output fields must be unique");
            }
        }
        return List.copyOf(unique);
    }

    private static Set<FilterOperator> compatibleOperators(FieldType type) {
        Set<FilterOperator> operators = TYPE_OPERATORS.get(type);
        return operators == null ? Set.of() : operators;
    }

    private static boolean validKey(String key) {
        return key != null && FIELD_KEY.matcher(key).matches();
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static boolean isNumeric(FieldType type) {
        return type == FieldType.NUMBER || type == FieldType.MONEY;
    }

    private static boolean isDate(JsonNode value) {
        if (!value.isString()) {
            return false;
        }
        try {
            LocalDate.parse(value.stringValue());
            return true;
        } catch (DateTimeParseException exception) {
            return false;
        }
    }

    private static boolean isOffsetDateTime(JsonNode value) {
        if (!value.isString()) {
            return false;
        }
        try {
            OffsetDateTime.parse(value.stringValue());
            return true;
        } catch (DateTimeParseException exception) {
            return false;
        }
    }

    private static boolean isUuid(JsonNode value) {
        if (!value.isString()) {
            return false;
        }
        try {
            UUID uuid = UUID.fromString(value.stringValue());
            return uuid.toString().equalsIgnoreCase(value.stringValue());
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static ValidationException invalid(String field, String code, String message) {
        return new ValidationException(field, code, message);
    }

    public record ValidatedQuery(
            String entityKey,
            String schemaVersion,
            List<FilterCondition> filters,
            List<SortField> sort,
            PageRequest page,
            int size,
            List<String> fields) {
    }
}
