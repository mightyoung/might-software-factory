package com.mightyoung.softwarefactory.applicationregistry.definition;

import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.dialect.Dialects;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Source-contract tests only: no claim that an asset HTTP handler or UI consumer exists. */
class AssetFoundationOpenApiContractTest {
    private static final String URI = "https://contract.test/asset-foundation.yaml";
    private static final String UUID = "00112233-4455-6677-8899-aabbccddeeff";

    @Test
    void freezesAllLiteralActionsSelectorsAndTransportGuards() throws Exception {
        var contract = contract();
        assertThat(contract).containsEntry("openapi", "3.1.0")
                .containsEntry("x-implementation-status", "contract-only");
        var routes = new ArrayList<String>();
        var selectors = new ArrayList<String>();
        int mutations = 0;
        for (var path : map(contract.get("paths")).entrySet()) {
            assertThat(path.getKey()).doesNotContain("{key}", "{action}", "{resource}");
            for (var operation : map(path.getValue()).entrySet()) {
                var op = map(operation.getValue());
                routes.add(operation.getKey().toUpperCase() + " " + path.getKey() + " " + op.get("x-action"));
                assertThat(op.get("security")).isEqualTo(List.of(Map.of("sessionCookieAuth", List.of())));
                var parameters = list(op.get("parameters"));
                if (operation.getKey().equals("post")) {
                    assertThat(parameters).contains(Map.of("$ref", "#/components/parameters/CsrfToken"));
                    assertThat(op).containsEntry("x-reject-unknown-query-parameters", true);
                }
                if (op.containsKey("x-mutation-result")) {
                    mutations++;
                    var result = map(op.get("x-mutation-result"));
                    assertThat(result.get("action")).isEqualTo(op.get("x-action"));
                    assertThat(op).containsEntry("x-body-max-bytes", 32768).containsEntry("x-max-json-depth", 4);
                    assertThat(parameters).contains(Map.of("$ref", "#/components/parameters/IdempotencyKey"));
                    String action = result.get("action").toString();
                    assertThat(result.get("resourceType")).isEqualTo(action.substring(0, action.indexOf('.')));
                    String status = action.endsWith(".create") || action.endsWith(".bind") ? "201" : "200";
                    assertThat(map(op.get("responses"))).containsKey(status);
                    assertThat(map(op.get("responses"))).containsKeys("400", "401", "403", "404", "409", "413", "415", "503");
                }
                if (op.containsKey("x-selector")) {
                    var selector = map(op.get("x-selector"));
                    selectors.add(path.getKey() + " " + selector.get("ownerAction") + " "
                            + (selector.get("referencedReadAction") == null ? "-" : selector.get("referencedReadAction"))
                            + " " + selector.get("request"));
                    assertThat(op).containsEntry("x-body-max-bytes", 2048);
                    assertThat(selector).containsEntry("currentAuthorizationBeforePaging", true)
                            .containsEntry("clientActionOrPermissionInput", false);
                    assertThat((List<?>) selector.get("conditions")).isNotEmpty();
                }
                if (path.getKey().endsWith("/definition")) {
                    String entity = path.getKey().split("/")[4];
                    assertThat(map(op.get("x-definition-permissions")))
                            .containsExactlyInAnyOrderEntriesOf(Map.of("READ", entity + ".read", "CREATE", entity + ".create"));
                    var unchanged = map(map(op.get("responses")).get("304"));
                    assertThat(unchanged).doesNotContainKey("content");
                    assertThat(map(unchanged.get("headers"))).containsOnlyKeys("ETag", "Cache-Control", "Vary");
                }
            }
        }
        assertThat(routes).containsExactlyInAnyOrder(EXPECTED_ROUTES.lines().toArray(String[]::new));
        assertThat(selectors).containsExactlyInAnyOrder(EXPECTED_SELECTORS.lines().toArray(String[]::new));
        assertThat(mutations).isEqualTo(23);
        assertThat((List<?>) contract.get("x-action-catalog")).hasSize(29);
        assertThat(Files.readString(path())).doesNotContain("finding.create", "finding.read", "entity-definition.yaml#");
    }

    @Test
    void rejectsEveryForeignWorkUnitSourceEvenWhenNull() throws Exception {
        var schema = schema("WorkUnitCreate");
        var sourceFields = List.of("workshopId", "lineId", "stageId", "equipmentId");
        for (String kind : List.of("STANDALONE", "WORKSHOP", "LINE", "STAGE", "EQUIPMENT")) {
            String selected = switch (kind) {
                case "WORKSHOP" -> "workshopId";
                case "LINE" -> "lineId";
                case "STAGE" -> "stageId";
                case "EQUIPMENT" -> "equipmentId";
                default -> null;
            };
            String fields = "\"code\":\"WU_1\",\"name\":\"作业单元😀\",\"organizationId\":\"" + UUID
                    + "\",\"kind\":\"" + kind + "\"";
            if (selected != null) fields += ",\"" + selected + "\":\"" + UUID + "\"";
            accepts(schema, "{" + fields + "}", true);
            for (String foreign : sourceFields) {
                if (!foreign.equals(selected)) accepts(schema, "{" + fields + ",\"" + foreign + "\":null}", false);
            }
            accepts(schema, "{" + fields + ",\"tenantId\":\"" + UUID + "\"}", false);
        }
        accepts(schema, "{\"code\":\"WU\",\"name\":\"Unit\",\"organizationId\":\"" + UUID + "\",\"kind\":\"LINE\"}", false);
        var projection = schema("WorkUnitListItem");
        accepts(projection, "{\"id\":\"" + UUID + "\"}", true);
        accepts(projection, "{\"kind\":\"LINE\",\"lineId\":\"" + UUID + "\"}", true);
        accepts(projection, "{\"kind\":\"LINE\",\"workshopId\":\"" + UUID + "\"}", false);
        accepts(projection, "{\"lineId\":\"" + UUID + "\",\"workshopId\":\"" + UUID + "\"}", false);
    }

    @Test
    void enforcesUnicodeCodePointBoundsCanonicalIdsSafeVersionsAndNoAuthorityInputs() throws Exception {
        var create = schema("WorkshopCreate");
        String prefix = "{\"code\":\"Plant.A_1-2\",\"organizationId\":\"" + UUID + "\",\"name\":\"";
        accepts(create, prefix + "😀".repeat(200) + "\"}", true);
        accepts(create, prefix + "😀".repeat(201) + "\"}", false);
        accepts(create, prefix + " Plant\"}", false);
        accepts(create, prefix + "Plant　\"}", false);
        accepts(create, prefix + "Plant\",\"actorId\":\"" + UUID + "\"}", false);
        accepts(create, prefix + "Plant\",\"boundaryDescription\":\"\"}", false);
        accepts(create, prefix + "Plant\",\"boundaryDescription\":null}", true);
        accepts(create, (prefix + "Plant\"}").replace("Plant.A_1-2", "Plant\\n"), false);
        accepts(create, (prefix + "Plant\"}").replace(UUID, UUID.toUpperCase()), false);
        var archive = schema("ArchiveRequest");
        accepts(archive, "{\"expectedVersion\":1}", true);
        accepts(archive, "{\"expectedVersion\":9007199254740991}", true);
        for (String invalid : List.of("0", "-1", "9007199254740992", "\"1\"", "1.5")) {
            accepts(archive, "{\"expectedVersion\":" + invalid + "}", false);
        }
        // JSON Schema treats 1.0/1e0 as mathematical integers. Strict token rejection is an HTTP parser gate.
        assertThat(map(map(map(contract().get("components")).get("schemas")).get("Version")))
                .containsEntry("x-json-integer-token-only", true);
        accepts(schema("WorkshopUpdate"), "{\"expectedVersion\":1,\"name\":\"New\"}", true);
        accepts(schema("WorkshopUpdate"), "{\"expectedVersion\":1}", false);
        accepts(schema("WorkshopUpdate"), "{\"expectedVersion\":1,\"name\":\"New\",\"organizationId\":\"" + UUID + "\"}", false);
    }

    @Test
    void boundsOffsetsSelectorsHistoryAndHazardObservationsWithoutInventingFactors() throws Exception {
        var instant = schema("MicrosecondInstant");
        accepts(instant, "\"2026-11-04T12:13:14.123456+08:00\"", true);
        for (String invalid : List.of("2026-11-04T12:13:14.1234567Z", "2026-11-04T12:13:14", "0000-01-01T00:00:00Z", "2026-11-04T00:00:00Z\\n")) {
            accepts(instant, "\"" + invalid + "\"", false);
        }
        var picker = schema("AnchorReferenceRequest");
        String anchor = "{\"organizationId\":\"" + UUID + "\",\"query\":\"";
        accepts(picker, anchor + "😀".repeat(100) + "\",\"limit\":50}", true);
        accepts(picker, anchor + "😀".repeat(101) + "\"}", false);
        accepts(picker, anchor + "\",\"limit\":51}", false);
        accepts(picker, "{\"query\":\"\"}", false);
        accepts(picker, anchor + "\",\"permission\":\"workshop.read\"}", false);
        accepts(schema("BindingHistoryRequest"), "{\"limit\":50}", true);
        accepts(schema("BindingHistoryRequest"), "{\"limit\":51}", false);
        String hazard = "{\"code\":\"H1\",\"organizationId\":\"" + UUID + "\",\"workUnitId\":\"" + UUID
                + "\",\"description\":\"Observed source\",\"identifiedAt\":\"2026-11-04T00:00:00Z\",\"existenceState\":\"UNKNOWN\"";
        accepts(schema("HazardSourceCreate"), hazard + "}", true);
        accepts(schema("HazardSourceCreate"), hazard + ",\"factorCodeRef\":null}", false);
        accepts(schema("HazardSourceCreate"), hazard + ",\"riskGrade\":\"LOW\"}", false);
        accepts(schema("ReferencePage"), "{\"items\":[],\"total\":0}", false);
        accepts(schema("BindingHistoryRequest"), "{}", true);
        var endpoint = schema("EndpointAtBindSnapshot");
        String snapshot = "{\"id\":\"" + UUID + "\",\"version\":1,\"code\":\"LINE1\",\"name\":\"Name at BIND\"";
        accepts(endpoint, snapshot + "}", true);
        accepts(endpoint, snapshot + ",\"organizationId\":\"" + UUID + "\"}", false);
        accepts(endpoint, snapshot.replace(",\"version\":1", "") + "}", false);
        var schemas = map(map(contract().get("components")).get("schemas"));
        assertThat(map(schemas.get("PageLimit"))).containsEntry("default", 20)
                .containsEntry("minimum", 1).containsEntry("maximum", 50);
        assertThat(map(schemas.get("LineWorkshopHistoryItem")).get("required"))
                .asList().contains("lineAtBind", "workshopAtBind");
        assertThat(map(schemas.get("StageEquipmentHistoryItem")).get("required"))
                .asList().contains("stageAtBind", "equipmentAtBind");
        assertThat(map(schemas.get("LineWorkshopHistoryItem")).get("description").toString())
                .contains("without requiring current endpoint READ", "independent READ and scope");
        accepts(schema("LineWorkshopBind"), "{\"expectedLineVersion\":1,\"workshopId\":\"" + UUID
                + "\",\"validFrom\":\"2026-11-04T00:00:00Z\",\"lineVersionAtBind\":1}", false);
    }

    private static void accepts(Schema schema, String json, boolean expected) {
        var errors = schema.validate(json, InputFormat.JSON);
        assertThat(errors.isEmpty()).as("schema acceptance=%s for %s: %s", expected, json, errors).isEqualTo(expected);
    }

    private static Schema schema(String name) throws Exception {
        var schemas = Map.of(
                URI, Files.readString(path()),
                "https://contract.test/entity-query.yaml", Files.readString(path().resolveSibling("entity-query.yaml")));
        var registry = SchemaRegistry.withDefaultDialect(Dialects.getOpenApi31(), builder -> builder.schemas(schemas));
        return registry.getSchema(SchemaLocation.of(URI + "#/components/schemas/" + name));
    }

    private static Map<String, Object> contract() throws Exception {
        try (var reader = Files.newBufferedReader(path())) { return new Yaml().load(reader); }
    }

    private static Path path() { return Path.of("..", "contracts", "openapi", "asset-foundation.yaml"); }
    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) { return (Map<String, Object>) value; }
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Object value) { return (List<Map<String, Object>>) value; }

    // Independent R3 frozen route inventory: changes require an explicit contract review.
    private static final String EXPECTED_ROUTES = """
                POST /api/v1/assets/workshops workshop.create
                POST /api/v1/assets/workshops/{id}/update workshop.update
                POST /api/v1/assets/workshops/{id}/archive workshop.archive
                GET /api/v1/entities/workshop/{id} workshop.read
                POST /api/v1/entities/workshop/query workshop.read
                GET /api/v1/entities/workshop/schema workshop.read
                GET /api/v1/entities/workshop/definition workshop.READ or CREATE
                POST /api/v1/assets/production-lines production_line.create
                POST /api/v1/assets/production-lines/{id}/update production_line.update
                POST /api/v1/assets/production-lines/{id}/archive production_line.archive
                GET /api/v1/entities/production_line/{id} production_line.read
                POST /api/v1/entities/production_line/query production_line.read
                GET /api/v1/entities/production_line/schema production_line.read
                GET /api/v1/entities/production_line/definition production_line.READ or CREATE
                POST /api/v1/assets/process-stages process_stage.create
                POST /api/v1/assets/process-stages/{id}/update process_stage.update
                POST /api/v1/assets/process-stages/{id}/archive process_stage.archive
                GET /api/v1/entities/process_stage/{id} process_stage.read
                POST /api/v1/entities/process_stage/query process_stage.read
                GET /api/v1/entities/process_stage/schema process_stage.read
                GET /api/v1/entities/process_stage/definition process_stage.READ or CREATE
                POST /api/v1/assets/equipment equipment.create
                POST /api/v1/assets/equipment/{id}/update equipment.update
                POST /api/v1/assets/equipment/{id}/archive equipment.archive
                GET /api/v1/entities/equipment/{id} equipment.read
                POST /api/v1/entities/equipment/query equipment.read
                GET /api/v1/entities/equipment/schema equipment.read
                GET /api/v1/entities/equipment/definition equipment.READ or CREATE
                POST /api/v1/assets/work-units work_unit.create
                POST /api/v1/assets/work-units/{id}/update work_unit.update
                POST /api/v1/assets/work-units/{id}/archive work_unit.archive
                GET /api/v1/entities/work_unit/{id} work_unit.read
                POST /api/v1/entities/work_unit/query work_unit.read
                GET /api/v1/entities/work_unit/schema work_unit.read
                GET /api/v1/entities/work_unit/definition work_unit.READ or CREATE
                POST /api/v1/hazard-sources hazard_source.create
                POST /api/v1/hazard-sources/{id}/update hazard_source.update
                GET /api/v1/entities/hazard_source/{id} hazard_source.read
                POST /api/v1/entities/hazard_source/query hazard_source.read
                GET /api/v1/entities/hazard_source/schema hazard_source.read
                GET /api/v1/entities/hazard_source/definition hazard_source.READ or CREATE
                POST /api/v1/assets/production-lines/{lineId}/workshop-bindings line_workshop.bind
                POST /api/v1/assets/production-lines/{lineId}/workshop-bindings/{bindingId}/end line_workshop.end
                POST /api/v1/assets/production-lines/{lineId}/workshop-bindings/query production_line.read
                POST /api/v1/assets/process-stages/{stageId}/equipment-bindings stage_equipment.bind
                POST /api/v1/assets/process-stages/{stageId}/equipment-bindings/{bindingId}/end stage_equipment.end
                POST /api/v1/assets/process-stages/{stageId}/equipment-bindings/query process_stage.read
                POST /api/v1/assets/work-units/{workUnitId}/responsibility-bindings responsibility_binding.bind
                POST /api/v1/assets/work-units/{workUnitId}/responsibility-bindings/{bindingId}/end responsibility_binding.end
                POST /api/v1/assets/work-units/{workUnitId}/responsibility-bindings/query work_unit.read
                POST /api/v1/entities/workshop/references/create-organization/query workshop.create
                POST /api/v1/entities/production_line/references/create-organization/query production_line.create
                POST /api/v1/entities/process_stage/references/create-organization/query process_stage.create
                POST /api/v1/entities/equipment/references/create-organization/query equipment.create
                POST /api/v1/entities/work_unit/references/create-organization/query work_unit.create
                POST /api/v1/entities/hazard_source/references/create-organization/query hazard_source.create
                POST /api/v1/entities/production_line/references/parent-workshop/query production_line.create
                POST /api/v1/entities/process_stage/references/parent-line/query process_stage.create
                POST /api/v1/entities/hazard_source/references/work-unit/query hazard_source.create
                POST /api/v1/entities/work_unit/references/source-workshop/query work_unit.create
                POST /api/v1/entities/work_unit/references/source-line/query work_unit.create
                POST /api/v1/entities/work_unit/references/source-stage/query work_unit.create
                POST /api/v1/entities/work_unit/references/source-equipment/query work_unit.create
                POST /api/v1/assets/production-lines/{lineId}/workshop-bindings/references/workshop/query line_workshop.bind
                POST /api/v1/assets/process-stages/{stageId}/equipment-bindings/references/equipment/query stage_equipment.bind
                POST /api/v1/assets/work-units/{workUnitId}/responsibility-bindings/references/organization/query responsibility_binding.bind
                POST /api/v1/assets/work-units/{workUnitId}/responsibility-bindings/references/position/query responsibility_binding.bind
                """;
    private static final String EXPECTED_SELECTORS = """
                /api/v1/entities/workshop/references/create-organization/query workshop.create - ReferenceRequest
                /api/v1/entities/production_line/references/create-organization/query production_line.create - ReferenceRequest
                /api/v1/entities/process_stage/references/create-organization/query process_stage.create - ReferenceRequest
                /api/v1/entities/equipment/references/create-organization/query equipment.create - ReferenceRequest
                /api/v1/entities/work_unit/references/create-organization/query work_unit.create - ReferenceRequest
                /api/v1/entities/hazard_source/references/create-organization/query hazard_source.create - ReferenceRequest
                /api/v1/entities/production_line/references/parent-workshop/query production_line.create workshop.read AnchorReferenceRequest
                /api/v1/entities/process_stage/references/parent-line/query process_stage.create production_line.read AnchorReferenceRequest
                /api/v1/entities/hazard_source/references/work-unit/query hazard_source.create work_unit.read AnchorReferenceRequest
                /api/v1/entities/work_unit/references/source-workshop/query work_unit.create workshop.read AnchorReferenceRequest
                /api/v1/entities/work_unit/references/source-line/query work_unit.create production_line.read AnchorReferenceRequest
                /api/v1/entities/work_unit/references/source-stage/query work_unit.create process_stage.read AnchorReferenceRequest
                /api/v1/entities/work_unit/references/source-equipment/query work_unit.create equipment.read AnchorReferenceRequest
                /api/v1/assets/production-lines/{lineId}/workshop-bindings/references/workshop/query line_workshop.bind workshop.read ReferenceRequest
                /api/v1/assets/process-stages/{stageId}/equipment-bindings/references/equipment/query stage_equipment.bind equipment.read ReferenceRequest
                /api/v1/assets/work-units/{workUnitId}/responsibility-bindings/references/organization/query responsibility_binding.bind - ReferenceRequest
                /api/v1/assets/work-units/{workUnitId}/responsibility-bindings/references/position/query responsibility_binding.bind - OrganizationPositionReferenceRequest
                """;
}
