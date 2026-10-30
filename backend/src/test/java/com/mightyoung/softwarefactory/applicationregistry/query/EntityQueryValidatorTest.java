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
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.FilterOperator.*;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EntityQueryValidatorTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final EntityQueryValidator validator = new EntityQueryValidator();
    private final EntitySchema schema = schema();

    @Test
    void validatesRegisteredFieldTypesAndAddsStableIdSort() {
        Request request = new Request(
                new FilterGroup(LogicalOperator.AND, List.of(
                        new FilterCondition("status", IN, json(List.of("OPEN", "CLOSED"))),
                        new FilterCondition("amount", BETWEEN, json(List.of(12.50, 24.75))))),
                List.of(new SortField("name", Direction.DESC)),
                new PageRequest(1, null),
                20,
                List.of("name", "status"));

        var validated = validator.validate(request, schema);

        assertThat(validated.entityKey()).isEqualTo("finding");
        assertThat(validated.schemaVersion()).isEqualTo("7");
        assertThat(validated.filters()).hasSize(2);
        assertThat(validated.sort()).containsExactly(
                new SortField("name", Direction.DESC),
                new SortField("id", Direction.ASC));
        assertThat(validated.fields()).containsExactly("name", "status");
        assertThat(validated.size()).isEqualTo(20);
    }

    @Test
    void rejectsUnregisteredFieldsAndOperatorsOutsideTheFieldSchema() {
        assertThatThrownBy(() -> validator.validate(request(
                new FilterCondition("secret", EQ, json("x"))), schema))
                .isInstanceOf(ValidationException.class);

        assertThatThrownBy(() -> validator.validate(request(
                new FilterCondition("status", CONTAINS, json("OPEN"))), schema))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void validatesValuesByFieldTypeAndRegisteredEnumValues() {
        assertThatThrownBy(() -> validator.validate(request(
                new FilterCondition("amount", GTE, json("12.50"))), schema))
                .isInstanceOf(ValidationException.class);

        assertThatThrownBy(() -> validator.validate(request(
                new FilterCondition("status", EQ, json("NOT_REGISTERED"))), schema))
                .isInstanceOf(ValidationException.class);

        assertThatThrownBy(() -> validator.validate(request(
                new FilterCondition("createdAt", GTE, json("2026-10-30T10:00:00"))), schema))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void distinguishesEmptyTextFromNullAndRequiresNoValueForUnaryOperators() {
        var emptyText = validator.validate(request(new FilterCondition("name", EQ, json(""))), schema);
        var nullText = validator.validate(request(new FilterCondition("name", IS_NULL, null)), schema);

        assertThat(emptyText.filters()).hasSize(1);
        assertThat(nullText.filters()).hasSize(1);
        assertThatThrownBy(() -> validator.validate(request(
                new FilterCondition("name", IS_NULL, json(""))), schema))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void enforcesPageSizePageModeSortAndFieldProjectionWhitelists() {
        assertInvalid(new Request(null, List.of(), new PageRequest(0, null), 20, null));
        assertInvalid(new Request(null, List.of(), new PageRequest(1, "cursor"), 25, null));
        assertInvalid(new Request(null, List.of(), new PageRequest(1, "cursor"), 20, null));
        assertInvalid(new Request(null, List.of(), new PageRequest(1, null), 20, List.of("name", "name")));
        assertInvalid(new Request(null, List.of(
                new SortField("name", Direction.ASC), new SortField("name", Direction.DESC)),
                new PageRequest(1, null), 20, null));
        assertInvalid(new Request(null, List.of(new SortField("secret", Direction.ASC)),
                new PageRequest(1, null), 20, null));
        assertInvalid(new Request(null, List.of(new SortField("notes", Direction.ASC)),
                new PageRequest(1, null), 20, null));
        assertInvalid(new Request(null, List.of(), new PageRequest(1, null), 20, List.of("secret")));
        assertInvalid(new Request(null, List.of(), new PageRequest(1, null), 20, List.of("internal")));
    }

    @Test
    void enforcesOffsetAndCursorBoundaries() {
        assertThatCode(() -> validator.validate(new Request(null, List.of(),
                new PageRequest(501, null), 20, null), schema)).doesNotThrowAnyException();
        assertInvalid(new Request(null, List.of(), new PageRequest(502, null), 20, null));

        assertThatCode(() -> validator.validate(new Request(null, List.of(),
                new PageRequest(201, null), 50, null), schema)).doesNotThrowAnyException();
        assertInvalid(new Request(null, List.of(), new PageRequest(202, null), 50, null));

        assertThatCode(() -> validator.validate(new Request(null, List.of(),
                new PageRequest(101, null), 100, null), schema)).doesNotThrowAnyException();
        assertInvalid(new Request(null, List.of(), new PageRequest(102, null), 100, null));

        assertThatCode(() -> validator.validate(new Request(null, List.of(),
                new PageRequest(null, "c".repeat(2048)), 20, null), schema)).doesNotThrowAnyException();
        assertInvalid(new Request(null, List.of(), new PageRequest(null, "c".repeat(2049)), 20, null));
        assertInvalid(new Request(null, List.of(), new PageRequest(null, "  \t  "), 20, null));
    }

    @Test
    void enforcesInOperatorCountBoundaries() {
        assertInvalid(request(new FilterCondition("status", IN, json(List.of()))));

        List<String> maxValues = java.util.stream.IntStream.range(0, 100)
                .mapToObj(index -> index % 2 == 0 ? "OPEN" : "CLOSED")
                .toList();
        Request maxInRequest = request(new FilterCondition("status", IN, json(maxValues)));
        assertThatCode(() -> validator.validate(maxInRequest, schema)).doesNotThrowAnyException();

        List<String> tooManyValues = java.util.stream.IntStream.range(0, 101)
                .mapToObj(index -> index % 2 == 0 ? "OPEN" : "CLOSED")
                .toList();
        assertInvalid(request(new FilterCondition("status", IN, json(tooManyValues))));
    }

    @Test
    void rejectsUnknownJsonPropertiesBeforeValidation() {
        assertThatThrownBy(() -> EntityQueryContract.readRequest("""
                {"page":{"number":1},"size":20,"unsafeSql":"select * from account"}
                """))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatCode(() -> EntityQueryContract.readRequest("""
                {"filterAST":{"operator":"AND","conditions":[{"field":"status","operator":"EQ","value":"OPEN"}]},
                 "sort":[],"page":{"number":1},"size":20,"fields":["status"]}
                """)).doesNotThrowAnyException();
    }

    @Test
    void omitsUnavailableTotalInsteadOfSerializingAPlaceholder() throws Exception {
        String json = JSON.writeValueAsString(new EntityQueryContract.PageMetadata(20, 1, null, null));
        String offsetPage = JSON.writeValueAsString(new PageRequest(1, null));
        String omittedControls = JSON.writeValueAsString(new Request(
                null, null, new PageRequest(1, null), 20, null));
        String error = JSON.writeValueAsString(new EntityQueryContract.ErrorResponse(
                "INVALID_QUERY", "Invalid query", "trace-1", false, List.of(), null));

        assertThat(json).contains("\"size\":20", "\"number\":1").doesNotContain("total", "nextCursor");
        assertThat(offsetPage).contains("\"number\":1").doesNotContain("cursor");
        assertThat(omittedControls).contains("\"page\":{\"number\":1}")
                .doesNotContain("filterAST", "sort", "fields", "cursor");
        assertThat(error).contains("\"fieldErrors\":[]").doesNotContain("currentVersion");
    }

    @Test
    void boundsFilterCountAndRejectsReversedRanges() {
        List<FilterCondition> tooMany = java.util.stream.IntStream.range(0, 51)
                .mapToObj(index -> new FilterCondition("name", EQ, json("field-" + index)))
                .toList();
        assertInvalid(new Request(new FilterGroup(LogicalOperator.AND, tooMany), List.of(),
                new PageRequest(1, null), 20, null));

        assertThatThrownBy(() -> validator.validate(request(
                new FilterCondition("amount", BETWEEN, json(List.of(25, 12)))), schema))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void comparesDatetimeRangesByInstantAcrossOffsets() {
        String plusEight = "2026-10-30T10:00:00+08:00";
        String utc = "2026-10-30T02:00:00Z";

        assertThatCode(() -> validator.validate(request(
                new FilterCondition("createdAt", BETWEEN, json(List.of(plusEight, utc)))), schema))
                .doesNotThrowAnyException();
        assertThatCode(() -> validator.validate(request(
                new FilterCondition("createdAt", BETWEEN, json(List.of(utc, plusEight)))), schema))
                .doesNotThrowAnyException();

        assertThatThrownBy(() -> validator.validate(request(new FilterCondition(
                        "createdAt", BETWEEN, json(List.of(plusEight, "2026-10-30T01:00:00Z")))), schema))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void validatesCanonicalUuidsAndBooleanDateAndOrganizationValues() {
        String uppercaseUuid = "00112233-4455-6677-8899-AABBCCDDEEFF";
        Request canonicalId = request(new FilterCondition("id", EQ, json(uppercaseUuid)));
        assertThatCode(() -> validator.validate(canonicalId, schema)).doesNotThrowAnyException();
        assertThatThrownBy(() -> validator.validate(request(
                new FilterCondition("id", EQ, json("1-1-1-1-1"))), schema))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> validator.validate(request(
                new FilterCondition("id", EQ, json("0011223-34455-6677-8899-aabbccddeeff"))), schema))
                .isInstanceOf(ValidationException.class);

        Request booleanFilter = request(new FilterCondition("active", EQ, json(true)));
        assertThatCode(() -> validator.validate(booleanFilter, schema)).doesNotThrowAnyException();
        assertThatCode(() -> validator.validate(request(new FilterCondition(
                "dueDate", BETWEEN, json(List.of("2026-10-29", "2026-10-30")))), schema))
                .doesNotThrowAnyException();
        Request organizationFilter = request(new FilterCondition("organization", IN_SUBTREE, json(uppercaseUuid)));
        assertThatCode(() -> validator.validate(organizationFilter, schema)).doesNotThrowAnyException();
    }

    @Test
    void invalidRegistryOperatorDeclarationsFailClosed() {
        EntitySchema badSchema = new EntitySchema("finding", "7", "id", List.of("id"), Map.of(
                "id", field(FieldType.UUID, false, true, Set.of(EQ), 0, Set.of()),
                "amount", field(FieldType.MONEY, false, true, Set.of(CONTAINS), 0, Set.of())));

        assertThatThrownBy(() -> validator.validate(new Request(
                null, List.of(), new PageRequest(1, null), 20, null), badSchema))
                .isInstanceOf(ValidationException.class);
    }

    private void assertInvalid(Request request) {
        assertThatThrownBy(() -> validator.validate(request, schema))
                .isInstanceOf(ValidationException.class);
    }

    private Request request(FilterCondition filter) {
        return new Request(new FilterGroup(LogicalOperator.AND, List.of(filter)),
                List.of(), new PageRequest(1, null), 20, null);
    }

    private static JsonNode json(Object value) {
        return JSON.valueToTree(value);
    }

    private static FieldSchema field(
            FieldType type, boolean nullable, boolean sortable, Set<FilterOperator> operators,
            int maxLength, Set<String> enumValues) {
        return field(type, nullable, true, sortable, operators, maxLength, enumValues);
    }

    private static FieldSchema field(
            FieldType type, boolean nullable, boolean selectable, boolean sortable,
            Set<FilterOperator> operators, int maxLength, Set<String> enumValues) {
        return new FieldSchema(type, nullable, selectable, sortable, operators, maxLength, enumValues);
    }

    private static EntitySchema schema() {
        return new EntitySchema("finding", "7", "id", List.of("id", "name", "status"), Map.of(
                "id", field(FieldType.UUID, false, true, Set.of(EQ), 0, Set.of()),
                "name", field(FieldType.TEXT, true, true,
                        Set.of(EQ, CONTAINS, IS_EMPTY, IS_NOT_EMPTY, IS_NULL, IS_NOT_NULL), 80, Set.of()),
                "status", field(FieldType.ENUM, false, true,
                        Set.of(EQ, IN), 0, Set.of("OPEN", "CLOSED")),
                "amount", field(FieldType.MONEY, true, true,
                        Set.of(EQ, GT, GTE, LT, LTE, BETWEEN, IS_NULL, IS_NOT_NULL), 0, Set.of()),
                "createdAt", field(FieldType.DATETIME, true, true,
                        Set.of(GT, GTE, LT, LTE, BETWEEN, IS_NULL, IS_NOT_NULL), 0, Set.of()),
                "notes", field(FieldType.TEXT, false, false,
                        Set.of(), 120, Set.of()),
                "internal", field(FieldType.TEXT, false, false, false, Set.of(), 0, Set.of()),
                "active", field(FieldType.BOOLEAN, false, true, Set.of(EQ), 0, Set.of()),
                "dueDate", field(FieldType.DATE, false, true,
                        Set.of(EQ, GT, GTE, LT, LTE, BETWEEN), 0, Set.of()),
                "organization", field(FieldType.ORGANIZATION, false, true,
                        Set.of(EQ, IN, IN_SUBTREE), 0, Set.of())));
    }
}
