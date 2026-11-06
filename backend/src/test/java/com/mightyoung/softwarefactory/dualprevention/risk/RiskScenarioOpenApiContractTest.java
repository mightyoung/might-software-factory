package com.mightyoung.softwarefactory.dualprevention.risk;

import com.mightyoung.softwarefactory.applicationregistry.definition.EntityDefinition.Use;
import com.mightyoung.softwarefactory.dualprevention.hazard.HazardSourceContracts.ExistenceState;
import com.networknt.schema.InputFormat;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.dialect.Dialects;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;
import static com.mightyoung.softwarefactory.dualprevention.risk.RiskScenarioContracts.*;
import static org.junit.jupiter.api.Assertions.*;

/** Source contract proof only; HTTP authorization, pagination and PostgreSQL acceptance are separate gates. */
class RiskScenarioOpenApiContractTest {
    private static final String URI = "https://contract.test/risk-scenario.yaml";
    private static final UUID ID = UUID.fromString("00112233-4455-6677-8899-aabbccddeeff");
    private static final Instant CUT = Instant.parse("2026-11-07T00:00:00.123456Z");

    @Test void freezesNineLiteralRoutesAndTransportBudgets() throws Exception {
        var doc = contract();
        assertEquals("3.1.0", doc.get("openapi"));
        assertEquals("contract-only", doc.get("x-implementation-status"));
        var routes = new ArrayList<String>();
        for (var route : map(doc.get("paths")).entrySet()) {
            for (var method : map(route.getValue()).entrySet()) {
                routes.add(method.getKey().toUpperCase() + " " + route.getKey());
                var op = map(method.getValue());
                assertEquals(List.of(Map.of("sessionCookieAuth", List.of())), op.get("security"));
                assertEquals(16384, op.get("x-response-headers-max-bytes"));
                assertTrue(((Number) op.get("x-response-json-max-bytes")).intValue() <= 1048576);
                assertTrue(map(op.get("responses")).containsKey("503"));
                if (method.getKey().equals("post")) {
                    assertTrue(list(op.get("parameters")).contains(Map.of("$ref", "#/components/parameters/CsrfToken")));
                    assertEquals(true, op.get("x-reject-unknown-query-parameters"));
                }
                if (op.containsKey("x-mutation-result")) {
                    assertEquals(131072, op.get("x-body-max-bytes"));
                    assertTrue(list(op.get("parameters")).contains(Map.of("$ref", "#/components/parameters/IdempotencyKey")));
                }
            }
        }
        assertEquals(java.util.Set.of(
                "POST /api/v1/risk-scenarios", "POST /api/v1/risk-scenarios/{id}/update",
                "GET /api/v1/entities/risk_scenario/{id}", "POST /api/v1/entities/risk_scenario/query",
                "GET /api/v1/risk-scenarios/{id}/history",
                "GET /api/v1/risk-scenarios/{id}/history/{version}/hazards/{hazardId}",
                "POST /api/v1/risk-scenarios/eligible-hazards", "GET /api/v1/entities/risk_scenario/schema",
                "GET /api/v1/entities/risk_scenario/definition"), java.util.Set.copyOf(routes));
        assertEquals(9, routes.size());
    }

    @Test void commandMembershipNeverBecomesQueryOrProjectionAuthority() throws Exception {
        String create = json(new Create("R", ID, "d", "t", "e", "c", List.of(ID)));
        accepts("Create", create, true);
        var request = new org.springframework.mock.web.MockHttpServletRequest();
        request.setContentType("application/json");
        request.setContent(create.getBytes(StandardCharsets.UTF_8));
        assertEquals(new Create("R", ID, "d", "t", "e", "c", List.of(ID)), new RiskScenarioParser(tools.jackson.databind.json.JsonMapper.builder().build()).create(request));
        accepts("Create", create.replace("\"hazardIds\":[\"" + ID + "\"]", "\"hazardIds\":[]"), false);
        accepts("Create", create.substring(0, create.length() - 1) + ",\"tenantId\":\"" + ID + "\"}", false);
        accepts("QueryRequest", "{\"page\":{\"number\":1},\"size\":20,\"fields\":[\"id\"]}", true);
        for (String field : List.of("hazardIds", "hazards", "currentAssessment", "score")) {
            accepts("QueryRequest", "{\"page\":{\"number\":1},\"size\":20,\"fields\":[\"" + field + "\"]}", false);
        }
        accepts("EntitySchema", json(RiskScenarioEntitySchema.schema()), true);
        var provider = new RiskScenarioDefinitionProvider();
        accepts("ReadDefinition", json(provider.definition(Use.READ)), true);
        accepts("CreateDefinition", json(provider.definition(Use.CREATE)), true);
        assertEquals(java.util.Set.copyOf(RiskScenarioEntitySchema.FIELD_KEYS),
                map(map(map(map(schemas().get("EntitySchema")).get("properties")).get("fields")).get("properties")).keySet());
        assertTrue(map(map(schemas().get("CommandInputs")).get("properties")).containsKey("hazardIds"));
    }

    @Test void restrictedUnionCannotDiscloseMembershipOrInventAssessment() throws Exception {
        accepts("Hazards", json(new RestrictedHazards()), true);
        for (String leaked : List.of("items", "count", "ids", "hasMore"))
            accepts("Hazards", "{\"visibility\":\"RESTRICTED\",\"" + leaked + "\":null}", false);
        accepts("Hazards", "{\"visibility\":\"COMPLETE\",\"items\":[]}", false);
        var hazard = new HistoricalHazardSnapshot(ID, ID, 1, "H", "observed", ExistenceState.UNKNOWN, 2, 3);
        accepts("Hazards", json(new CompleteHazards(List.of(hazard.summary()))), true);
        String detail = json(new Detail(ID, ID, 1, "R", "d", "t", "e", "c", CUT, CUT,
                AssessmentStatus.UNASSESSED, null, null, null, new RestrictedHazards()));
        accepts("Detail", detail, true);
        accepts("Detail", detail.replace("UNASSESSED", "LOW"), false);
        accepts("Detail", detail.replace("\"currentAssessment\":null", "\"currentAssessment\":{}"), false);
        accepts("Detail", detail.substring(0, detail.length() - 1) + ",\"grade\":\"LOW\"}", false);
    }

    @Test void historicalPointRetainsFullDescriptionAndBothVersionAnchors() throws Exception {
        var snapshot = new HistoricalHazardSnapshot(ID, ID, 1, "H", "𠀀".repeat(201), ExistenceState.PRESENT, 2, 3);
        accepts("HistoricalHazardSnapshot", json(snapshot), true);
        accepts("HazardSummary", json(snapshot.summary()), true);
        assertTrue(snapshot.summary().descriptionTruncated());
        String wire = json(snapshot);
        for (String anchor : List.of("hazardVersionAtLink", "hazardVersionAtScenarioRevision"))
            accepts("HistoricalHazardSnapshot", wire.replace(",\"" + anchor + "\":" + (anchor.endsWith("Link") ? 2 : 3), ""), false);
        assertTrue(map(map(schemas().get("HistoricalHazardSnapshot")).get("properties")).containsKey("fullDescription"));
        assertFalse(map(map(schemas().get("HazardSummary")).get("properties")).containsKey("fullDescription"));
    }

    @Test void versionsSelectorsAndUnknownInputsStayStrict() throws Exception {
        for (String valid : List.of("1", "9007199254740991")) accepts("Version", valid, true);
        for (String invalid : List.of("0", "-1", "9007199254740992", "1.5", "\"1\"")) accepts("Version", invalid, false);
        // JSON Schema accepts mathematical integer 1.0; the parser independently rejects noninteger tokens.
        assertEquals(true, map(schemas().get("Version")).get("x-json-integer-token-only"));
        accepts("SelectorRequest", "{\"use\":\"CREATE\",\"organizationId\":\"" + ID + "\",\"query\":\"\"}", true);
        accepts("SelectorRequest", "{\"use\":\"UPDATE\",\"scenarioId\":\"" + ID + "\",\"query\":\"\"}", true);
        accepts("SelectorRequest", "{\"use\":\"UPDATE\",\"organizationId\":\"" + ID + "\",\"query\":\"\"}", false);
        accepts("SelectorRequest", "{\"use\":\"CREATE\",\"organizationId\":\"" + ID + "\",\"query\":\"\",\"permission\":\"hazard_source.read\"}", false);
    }

    private static String json(Object value) {
        return new String(encodeResponse(value, ResponseBudget.METADATA), StandardCharsets.UTF_8);
    }
    private static void accepts(String name, String json, boolean expected) throws Exception {
        var contractText = Files.readString(path());
        var schema = SchemaRegistry.withDefaultDialect(Dialects.getOpenApi31(),
                builder -> builder.schemas(Map.of(URI, contractText)))
                .getSchema(SchemaLocation.of(URI + "#/components/schemas/" + name));
        var errors = schema.validate(json, InputFormat.JSON);
        assertEquals(expected, errors.isEmpty(), () -> name + ": " + errors);
    }
    private static Path path() { return Path.of("..", "contracts", "openapi", "risk-scenario.yaml"); }
    private static Map<String, Object> schemas() throws Exception { return map(map(contract().get("components")).get("schemas")); }
    @SuppressWarnings("unchecked") private static Map<String, Object> contract() throws Exception {
        try (var reader = Files.newBufferedReader(path())) { return (Map<String, Object>) new Yaml().load(reader); }
    }
    @SuppressWarnings("unchecked") private static Map<String, Object> map(Object value) { return (Map<String, Object>) value; }
    @SuppressWarnings("unchecked") private static List<Object> list(Object value) { return (List<Object>) value; }
}
