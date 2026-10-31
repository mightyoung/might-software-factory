package com.mightyoung.softwarefactory.dualprevention;

import com.networknt.schema.Error;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.dialect.Dialects;
import com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract;
import com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryValidator;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class FindingLedgerContractTest {

    private static final String LEDGER_URI = "https://contract.test/finding-ledger.yaml";
    private static final String QUERY_URI = "https://contract.test/entity-query.yaml";

    @Test
    @SuppressWarnings("unchecked")
    void publishesExplicitReadOnlyPathsAndFailClosedAuthenticationContract() throws IOException {
        Map<String, Object> contract = readContract();

        assertThat(contract).containsEntry("openapi", "3.1.0");
        assertThat(contract).containsEntry("x-implementation-status", "local-handlers-fail-closed");
        Map<String, Object> paths = (Map<String, Object>) contract.get("paths");
        assertThat(paths).containsKeys(
                "/api/v1/entities/finding/schema",
                "/api/v1/entities/finding/query",
                "/api/v1/entities/finding/{id}");
        assertThat(paths).hasSize(3);

        Map<String, Object> queryPath = (Map<String, Object>) paths.get("/api/v1/entities/finding/query");
        assertThat(queryPath).containsKey("post");
        assertThat(queryPath).doesNotContainKeys("get", "put", "patch", "delete");
        Map<String, Object> query = (Map<String, Object>) queryPath.get("post");
        assertThat((List<Map<String, Object>>) query.get("security"))
                .contains(Map.of("sessionCookieAuth", List.of()));
        List<Map<String, Object>> parameters = (List<Map<String, Object>>) query.get("parameters");
        assertThat(parameters).anySatisfy(parameter -> {
            assertThat(parameter).containsEntry("name", "X-XSRF-TOKEN").containsEntry("required", true);
        });

        Map<String, Object> detailPath = (Map<String, Object>) paths.get("/api/v1/entities/finding/{id}");
        assertThat(detailPath).containsKey("get");
        assertThat(detailPath).doesNotContainKeys("post", "put", "patch", "delete");
        Map<String, Object> detail = (Map<String, Object>) detailPath.get("get");
        assertThat((List<Map<String, Object>>) detail.get("security"))
                .contains(Map.of("sessionCookieAuth", List.of()));
        Map<String, Object> errorResponses = (Map<String, Object>) detail.get("responses");
        assertThat(errorResponses.get("401").toString()).contains("AUTH_REQUIRED");
        assertThat(errorResponses.get("403").toString()).contains("ACTION_DENIED");
        assertThat(errorResponses.get("404").toString())
                .contains("RESOURCE_NOT_VISIBLE", "nonexistent", "outside the caller's data scope");
    }

    @Test
    @SuppressWarnings("unchecked")
    void registersFindingV4WithOnlyLogicalSchemaFieldsAndExactPageSemantics() throws IOException {
        Map<String, Object> contract = readContract();
        Map<String, Object> schemas = schemas(contract);
        Map<String, Object> findingSchema = (Map<String, Object>) schemas.get("FindingEntitySchema");
        Map<String, Object> schemaProperties = (Map<String, Object>) findingSchema.get("properties");

        assertThat(((Map<String, Object>) schemaProperties.get("key")).get("const")).isEqualTo("finding");
        assertThat(((Map<String, Object>) schemaProperties.get("schemaVersion")).get("const"))
                .isEqualTo(FindingEntitySchema.SCHEMA_VERSION);
        assertThat(FindingEntitySchema.SCHEMA_VERSION).isEqualTo("4");
        assertThat(new FindingEntitySchema().schema().schemaVersion()).isEqualTo(FindingEntitySchema.SCHEMA_VERSION);
        assertThat(((Map<String, Object>) schemaProperties.get("schemaVersion")).get("description").toString())
                .contains("Cache schema metadata by", "discard the older cached schema", "default ordering changes");
        assertThat(((Map<String, Object>) schemaProperties.get("idField")).get("const")).isEqualTo("id");
        assertThat((List<String>) ((Map<String, Object>) schemaProperties.get("defaultFields")).get("const"))
                .containsExactly("id", "code", "organizationId", "organizationName", "description",
                        "discoveredAt", "sourceCategory", "urgency", "location", "interimMeasure", "backfill",
                        "sourceEventId", "riskId", "relatedFindingId", "grade", "state", "roundNo", "createdAt");
        Map<String, Object> fieldsObject = (Map<String, Object>) schemaProperties.get("fields");
        Map<String, Object> fields = (Map<String, Object>) fieldsObject.get("properties");
        assertThat(fields.keySet()).containsExactlyInAnyOrder(
                "id", "code", "organizationId", "organizationName", "description", "discoveredAt",
                "sourceCategory", "urgency", "location", "interimMeasure", "backfill",
                "sourceEventId", "riskId", "relatedFindingId", "grade", "state", "roundNo", "version",
                "createdAt", "updatedAt");
        Map<String, Object> componentSchemas = schemas(contract);
        Map<String, Object> sourceCategory = (Map<String, Object>) componentSchemas.get("FindingSourceCategoryField");
        Map<String, Object> sourceValues = (Map<String, Object>) ((Map<String, Object>) sourceCategory.get("properties"))
                .get("enumValues");
        assertThat((List<String>) sourceValues.get("const"))
                .containsExactly("FIELD_OBSERVATION", "INSPECTION", "PERSON_REPORT");
        Map<String, Object> urgency = (Map<String, Object>) componentSchemas.get("FindingUrgencyField");
        Map<String, Object> urgencyValues = (Map<String, Object>) ((Map<String, Object>) urgency.get("properties"))
                .get("enumValues");
        assertThat((List<String>) urgencyValues.get("const")).containsExactly("NORMAL", "URGENT");
        assertThat(contract.get("x-pagination-semantics").toString())
                .contains("one-based offset", "exact", "after tenant and data-scope predicates",
                        "pageCount = ceil(total / size)", "pageCount is 0 when total is 0");
        assertThat(contract.get("x-tenant-context").toString())
                .contains("verified server session", "active tenant", "request body", "header");
        assertThat((List<Map<String, Object>>) contract.get("x-finding-default-sort"))
                .containsExactly(Map.of("field", "createdAt", "direction", "DESC"),
                        Map.of("field", "id", "direction", "ASC"));

        Map<String, Object> state = (Map<String, Object>) componentSchemas.get("FindingStateField");
        Map<String, Object> stateProperties = (Map<String, Object>) state.get("properties");
        assertThat((List<String>) ((Map<String, Object>) stateProperties.get("enumValues")).get("const"))
                .containsExactly("DISCOVERED", "PERSON_CONFIRM_PENDING", "SAFETY_CONFIRM_PENDING",
                        "SAFETY_DISPATCH_PENDING", "RECTIFYING", "ACCEPTANCE_PENDING", "NOT_ESTABLISHED", "CLOSED");
        Map<String, Object> description = (Map<String, Object>) componentSchemas.get("DescriptionField");
        Map<String, Object> descriptionProperties = (Map<String, Object>) description.get("properties");
        assertThat(((Map<String, Object>) descriptionProperties.get("operators")).get("const"))
                .isEqualTo(List.of());
        assertThat(((Map<String, Object>) descriptionProperties.get("sortable")).get("const"))
                .isEqualTo(false);
    }

    @Test
    void validatesFindingRequestsAndResponsesAgainstResolvedOpenApiSchemas() throws IOException {
        SchemaRegistry registry = schemaRegistry();
        Schema query = registry.getSchema(SchemaLocation.of(
                LEDGER_URI + "#/components/schemas/FindingQueryRequest"));
        Schema listResponse = registry.getSchema(SchemaLocation.of(
                LEDGER_URI + "#/components/schemas/FindingListResponse"));
        Schema detailResponse = registry.getSchema(SchemaLocation.of(
                LEDGER_URI + "#/components/schemas/FindingDetailResponse"));
        Schema findingSchema = registry.getSchema(SchemaLocation.of(
                LEDGER_URI + "#/components/schemas/FindingEntitySchema"));

        assertValid(query, """
                {"filterAST":{"operator":"AND","conditions":[{"field":"grade","operator":"EQ","value":"MAJOR"}]},
                 "sort":[{"field":"discoveredAt","direction":"DESC"}],
                 "page":{"number":1},"size":50,"fields":["id","code","discoveredAt","grade","state","roundNo"]}
                """);
        assertInvalid(query, """
                {"page":{"cursor":"opaque"},"size":50}
                """);
        assertInvalid(query, """
                {"page":{"number":1},"size":50,"tenantId":"00112233-4455-6677-8899-aabbccddeeff"}
                """);

        String fieldRegistryFixture = findingSchemaFixture();
        assertValid(findingSchema, fieldRegistryFixture);
        assertInvalid(findingSchema, fieldRegistryFixture.replace("\"key\":\"finding\"",
                "\"key\":\"finding\",\"tenant_id\":\"00112233-4455-6677-8899-aabbccddeeff\""));

        var javaSchema = javaEntitySchema(readContract());
        var validated = new EntityQueryValidator().validate(
                EntityQueryContract.readRequest("""
                        {"filterAST":{"operator":"AND","conditions":[{"field":"grade","operator":"EQ","value":"MAJOR"}]},
                         "sort":[{"field":"discoveredAt","direction":"DESC"}],
                         "page":{"number":1},"size":50,"fields":["id","code","discoveredAt","grade","state","roundNo"]}
                        """), javaSchema);
        assertThat(validated.fields()).containsExactly("id", "code", "discoveredAt", "grade", "state", "roundNo");
        assertThat(validated.sort()).extracting("field").containsExactly("discoveredAt", "id");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new EntityQueryValidator().validate(
                EntityQueryContract.readRequest("""
                        {"filterAST":{"operator":"AND","conditions":[{"field":"tenant_id","operator":"EQ","value":"00112233-4455-6677-8899-aabbccddeeff"}]},
                         "page":{"number":1},"size":50}
                        """), javaSchema)).isInstanceOf(IllegalArgumentException.class);

        assertValid(listResponse, """
                {"data":{"items":[{"id":"00112233-4455-6677-8899-aabbccddeeff","code":"FND-1001",
                  "discoveredAt":"2026-10-30T10:00:00Z","sourceCategory":"FIELD_OBSERVATION","urgency":"URGENT",
                  "location":"North loading dock","interimMeasure":"Temporary barrier installed","backfill":true,
                  "grade":"GENERAL","state":"PERSON_CONFIRM_PENDING","relatedFindingId":null,"roundNo":1}],
                  "page":{"number":1,"size":50,"total":101,"pageCount":3}},
                 "meta":{"requestId":"req-1","traceId":"trace-1","schemaVersion":"4"}}
                """);
        assertInvalid(listResponse, """
                {"data":{"items":[],"page":{"number":1,"size":50,"pageCount":0}},
                 "meta":{"requestId":"req-1","traceId":"trace-1","schemaVersion":"4"}}
                """);
        assertValid(detailResponse, """
                {"data":{"id":"00112233-4455-6677-8899-aabbccddeeff","code":"FND-1001",
                  "organizationId":"11112233-4455-6677-8899-aabbccddeeff","organizationName":"安全一部",
                  "description":"Test-only synthetic finding","discoveredAt":"2026-10-30T10:00:00Z",
                  "sourceCategory":"INSPECTION","urgency":"NORMAL","location":"Workshop floor",
                  "interimMeasure":null,"backfill":false,
                  "sourceEventId":null,"riskId":null,"grade":"UNKNOWN","state":"PERSON_CONFIRM_PENDING",
                  "roundNo":1,"version":1,"relatedFindingId":null,"createdAt":"2026-10-30T10:00:00Z","updatedAt":"2026-10-30T10:00:00Z"},
                 "meta":{"requestId":"req-2","traceId":"trace-2","schemaVersion":"4"}}
                """);
        assertValid(detailResponse, """
                {"data":{"id":"00112233-4455-6677-8899-aabbccddeeff","code":"FND-1002",
                  "organizationId":"11112233-4455-6677-8899-aabbccddeeff","organizationName":"安全一部",
                  "description":"Legacy synthetic finding","discoveredAt":"2026-10-30T10:00:00Z",
                  "sourceCategory":null,"urgency":null,"location":null,"interimMeasure":null,"backfill":null,
                  "sourceEventId":null,"riskId":null,"grade":"UNKNOWN","state":"DISCOVERED",
                  "roundNo":1,"version":1,"relatedFindingId":null,"createdAt":"2026-10-30T10:00:00Z","updatedAt":"2026-10-30T10:00:00Z"},
                 "meta":{"requestId":"req-3","traceId":"trace-3","schemaVersion":"4"}}
                """);
    }

    @Test
    void documentsTheRemainingProductionAuthenticationAndDataScopeBlockers() throws IOException {
        String yaml = Files.readString(contractPath());
        assertThat(yaml).contains("no production login", "denies by default",
                "V3 identity and authorization migration has not been applied",
                "runtime SELECT access to the V3 identity and authorization tables has not been granted");
    }

    private static void assertValid(Schema schema, String json) {
        List<Error> errors = schema.validate(json, InputFormat.JSON);
        assertThat(errors).as("OpenAPI validation errors: %s", errors).isEmpty();
    }

    private static void assertInvalid(Schema schema, String json) {
        List<Error> errors = schema.validate(json, InputFormat.JSON);
        assertThat(errors).as("Expected OpenAPI validation to reject fixture").isNotEmpty();
    }

    private static SchemaRegistry schemaRegistry() throws IOException {
        String ledgerDocument = Files.readString(contractPath());
        String queryDocument = Files.readString(Path.of("..", "contracts", "openapi", "entity-query.yaml"));
        return SchemaRegistry.withDefaultDialect(Dialects.getOpenApi31(), builder -> builder.schemas(Map.of(
                LEDGER_URI, ledgerDocument,
                QUERY_URI, queryDocument)));
    }

    @SuppressWarnings("unchecked")
    private static EntityQueryContract.EntitySchema javaEntitySchema(Map<String, Object> contract) {
        Map<String, Object> definition = (Map<String, Object>) schemas(contract).get("FindingEntitySchema");
        Map<String, Object> definitionProperties = (Map<String, Object>) definition.get("properties");
        Map<String, Object> fieldsSchema = (Map<String, Object>) definitionProperties.get("fields");
        Map<String, Object> fieldDefinitions = (Map<String, Object>) fieldsSchema.get("properties");
        Map<String, EntityQueryContract.FieldSchema> fields = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : fieldDefinitions.entrySet()) {
            Map<String, Object> fieldRef = (Map<String, Object>) entry.getValue();
            String reference = (String) fieldRef.get("$ref");
            String componentName = reference.substring(reference.lastIndexOf('/') + 1);
            Map<String, Object> fieldComponent = (Map<String, Object>) schemas(contract).get(componentName);
            Map<String, Object> properties = (Map<String, Object>) fieldComponent.get("properties");
            EntityQueryContract.FieldType type = EntityQueryContract.FieldType.valueOf(
                    (String) ((Map<String, Object>) properties.get("type")).get("const"));
            boolean nullable = Boolean.TRUE.equals(((Map<String, Object>) properties.get("nullable")).get("const"));
            boolean selectable = Boolean.TRUE.equals(((Map<String, Object>) properties.get("selectable")).get("const"));
            boolean sortable = Boolean.TRUE.equals(((Map<String, Object>) properties.get("sortable")).get("const"));
            Map<String, Object> operatorsProperty = (Map<String, Object>) properties.get("operators");
            Object declaredOperators = operatorsProperty.get("const");
            List<String> allowedOperators = declaredOperators instanceof List<?> values
                    ? values.stream().map(String.class::cast).toList()
                    : (List<String>) ((Map<String, Object>) operatorsProperty.get("items")).get("enum");
            Set<EntityQueryContract.FilterOperator> operators = allowedOperators.stream()
                    .map(EntityQueryContract.FilterOperator::valueOf).collect(java.util.stream.Collectors.toSet());
            int maxLength = (int) ((Map<String, Object>) properties.get("maxLength")).get("const");
            Object enumValuesConst = ((Map<String, Object>) properties.get("enumValues")).get("const");
            Set<String> enumValues = enumValuesConst instanceof List<?> values
                    ? values.stream().map(String.class::cast).collect(java.util.stream.Collectors.toSet())
                    : Set.of();
            fields.put(entry.getKey(), new EntityQueryContract.FieldSchema(
                    type, nullable, selectable, sortable, operators, maxLength, enumValues));
        }
        Map<String, Object> defaultFieldsProperty = (Map<String, Object>) definitionProperties.get("defaultFields");
        List<String> defaultFields = (List<String>) defaultFieldsProperty.get("const");
        return new EntityQueryContract.EntitySchema(
                "finding", FindingEntitySchema.SCHEMA_VERSION, "id", defaultFields, fields);
    }

    private static String findingSchemaFixture() {
        return """
                {"key":"finding","schemaVersion":"4","idField":"id",
                 "defaultFields":["id","code","organizationId","organizationName","description","discoveredAt","sourceCategory","urgency","location","interimMeasure","backfill","sourceEventId","riskId","relatedFindingId","grade","state","roundNo","createdAt"],
                 "fields":{
                   "id":{"type":"UUID","nullable":false,"selectable":true,"sortable":true,"operators":["EQ","IN"],"maxLength":0,"enumValues":[]},
                   "code":{"type":"TEXT","nullable":false,"selectable":true,"sortable":true,"operators":["EQ","CONTAINS"],"maxLength":64,"enumValues":[]},
                   "organizationId":{"type":"UUID","nullable":false,"selectable":true,"sortable":false,"operators":[],"maxLength":0,"enumValues":[]},
                   "organizationName":{"type":"TEXT","nullable":true,"selectable":true,"sortable":false,"operators":[],"maxLength":0,"enumValues":[]},
                   "description":{"type":"TEXT","nullable":false,"selectable":true,"sortable":false,"operators":[],"maxLength":0,"enumValues":[]},
                   "discoveredAt":{"type":"DATETIME","nullable":false,"selectable":true,"sortable":true,"operators":["EQ","GT","GTE","LT","LTE","BETWEEN"],"maxLength":0,"enumValues":[]},
                   "sourceCategory":{"type":"ENUM","nullable":true,"selectable":true,"sortable":false,"operators":[],"maxLength":0,"enumValues":["FIELD_OBSERVATION","INSPECTION","PERSON_REPORT"]},
                   "urgency":{"type":"ENUM","nullable":true,"selectable":true,"sortable":false,"operators":[],"maxLength":0,"enumValues":["NORMAL","URGENT"]},
                   "location":{"type":"TEXT","nullable":true,"selectable":true,"sortable":false,"operators":[],"maxLength":0,"enumValues":[]},
                   "interimMeasure":{"type":"TEXT","nullable":true,"selectable":true,"sortable":false,"operators":[],"maxLength":0,"enumValues":[]},
                   "backfill":{"type":"BOOLEAN","nullable":true,"selectable":true,"sortable":false,"operators":[],"maxLength":0,"enumValues":[]},
                   "sourceEventId":{"type":"UUID","nullable":true,"selectable":true,"sortable":false,"operators":["EQ","IN","IS_NULL","IS_NOT_NULL"],"maxLength":0,"enumValues":[]},
                   "riskId":{"type":"UUID","nullable":true,"selectable":true,"sortable":false,"operators":["EQ","IN","IS_NULL","IS_NOT_NULL"],"maxLength":0,"enumValues":[]},
                   "relatedFindingId":{"type":"UUID","nullable":true,"selectable":true,"sortable":false,"operators":["EQ","IN","IS_NULL","IS_NOT_NULL"],"maxLength":0,"enumValues":[]},
                   "grade":{"type":"ENUM","nullable":false,"selectable":true,"sortable":true,"operators":["EQ","IN"],"maxLength":0,"enumValues":["UNKNOWN","GENERAL","MAJOR"]},
                   "state":{"type":"ENUM","nullable":false,"selectable":true,"sortable":true,"operators":["EQ","IN"],"maxLength":0,"enumValues":["DISCOVERED","PERSON_CONFIRM_PENDING","SAFETY_CONFIRM_PENDING","SAFETY_DISPATCH_PENDING","RECTIFYING","ACCEPTANCE_PENDING","NOT_ESTABLISHED","CLOSED"]},
                   "roundNo":{"type":"NUMBER","nullable":false,"selectable":true,"sortable":true,"operators":["EQ","GT","GTE","LT","LTE","BETWEEN"],"maxLength":0,"enumValues":[]},
                   "version":{"type":"NUMBER","nullable":false,"selectable":true,"sortable":true,"operators":["EQ","GT","GTE","LT","LTE","BETWEEN"],"maxLength":0,"enumValues":[]},
                   "createdAt":{"type":"DATETIME","nullable":false,"selectable":true,"sortable":true,"operators":["EQ","GT","GTE","LT","LTE","BETWEEN"],"maxLength":0,"enumValues":[]},
                   "updatedAt":{"type":"DATETIME","nullable":false,"selectable":true,"sortable":true,"operators":["EQ","GT","GTE","LT","LTE","BETWEEN"],"maxLength":0,"enumValues":[]}
                 }}
                """;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> schemas(Map<String, Object> contract) {
        return (Map<String, Object>) ((Map<String, Object>) contract.get("components")).get("schemas");
    }

    private static Path contractPath() {
        return Path.of("..", "contracts", "openapi", "finding-ledger.yaml");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readContract() throws IOException {
        try (var reader = Files.newBufferedReader(contractPath())) {
            return new Yaml().load(reader);
        }
    }
}
