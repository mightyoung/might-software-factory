package com.mightyoung.softwarefactory.applicationregistry.query;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EntityQueryOpenApiContractTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    @SuppressWarnings("unchecked")
    void publishesVersionedComponentSchemasWithoutDeclaringADeployedEndpoint() throws IOException {
        Map<String, Object> contract = readContract();

        assertThat(contract).containsEntry("openapi", "3.1.0");
        assertThat(((Map<String, Object>) contract.get("info")).get("version")).isEqualTo("1.0.0");
        assertThat(contract.get("x-target-endpoint")).isEqualTo("/api/v1/entities/{key}/query");
        assertThat((Map<String, Object>) contract.get("paths")).isEmpty();
        assertThat((Map<String, Object>) ((Map<String, Object>) contract.get("components")).get("schemas"))
                .containsKeys("EntityQueryRequest", "EntityQueryResponse", "EntityFieldSchema");
    }

    @Test
    @SuppressWarnings("unchecked")
    void oneJsonFixtureHasMatchingExplicitNullSemanticsInOpenApiAndJava() throws Exception {
        String fixture = """
                {"filterAST":null,"sort":null,"page":{"number":1,"cursor":null},"size":20,"fields":null}
                """;
        JsonNode json = JSON.readTree(fixture);
        assertThat(json.has("sort") && json.get("sort").isNull()).isTrue();
        assertThat(json.has("fields") && json.get("fields").isNull()).isTrue();
        assertThat(json.path("page").has("cursor") && json.path("page").get("cursor").isNull()).isTrue();

        Map<String, Object> contract = readContract();
        Map<String, Object> schemas = (Map<String, Object>) ((Map<String, Object>) contract.get("components")).get("schemas");
        Map<String, Object> requestSchema = (Map<String, Object>) schemas.get("EntityQueryRequest");
        Map<String, Object> requestProperties = (Map<String, Object>) requestSchema.get("properties");
        assertThat(schemaAllowsNull(requestProperties.get("filterAST"))).isTrue();
        assertThat(schemaAllowsNull(requestProperties.get("sort"))).isTrue();
        assertThat(schemaAllowsNull(requestProperties.get("fields"))).isTrue();

        List<Map<String, Object>> pageAlternatives =
                (List<Map<String, Object>>) ((Map<String, Object>) schemas.get("PageRequest")).get("oneOf");
        Map<String, Object> numberProperties =
                (Map<String, Object>) pageAlternatives.get(0).get("properties");
        Map<String, Object> cursorProperties =
                (Map<String, Object>) pageAlternatives.get(1).get("properties");
        assertThat(schemaAllowsNull(numberProperties.get("cursor"))).isTrue();
        assertThat(schemaAllowsNull(cursorProperties.get("number"))).isTrue();

        EntityQueryContract.Request decoded = EntityQueryContract.readRequest(fixture);
        assertThat(decoded.filterAST()).isNull();
        assertThat(decoded.sort()).isNull();
        assertThat(decoded.fields()).isNull();
        assertThat(decoded.page()).isEqualTo(new EntityQueryContract.PageRequest(1, null));
        assertThat(decoded.size()).isEqualTo(20);
    }

    private static boolean schemaAllowsNull(Object schema) {
        if (!(schema instanceof Map<?, ?> schemaMap)) {
            return false;
        }
        if ("null".equals(schemaMap.get("type"))) {
            return true;
        }
        Object oneOf = schemaMap.get("oneOf");
        return oneOf instanceof List<?> alternatives && alternatives.stream().anyMatch(
                EntityQueryOpenApiContractTest::schemaAllowsNull);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readContract() throws IOException {
        Path contractPath = Path.of("..", "contracts", "openapi", "entity-query.yaml");
        try (var reader = Files.newBufferedReader(contractPath)) {
            return new Yaml().load(reader);
        }
    }
}
