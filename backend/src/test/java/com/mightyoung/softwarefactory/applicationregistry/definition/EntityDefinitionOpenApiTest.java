package com.mightyoung.softwarefactory.applicationregistry.definition;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class EntityDefinitionOpenApiTest {
    @Test
    @SuppressWarnings("unchecked")
    void fixesOnlyBusinessOwnedRoutesAndFiniteUseWithAuthorizedWeakCaching() throws Exception {
        Map<String, Object> document;
        try (var reader = Files.newBufferedReader(Path.of("..", "contracts", "openapi", "entity-definition.yaml"))) {
            document = new Yaml().load(reader);
        }
        var paths = (Map<String, Object>) document.get("paths");
        assertThat(paths).containsOnlyKeys("/api/v1/entities/finding/definition",
                "/api/v1/entities/finding/references/report-organization/query");
        var get = (Map<String, Object>) ((Map<String, Object>) paths.get("/api/v1/entities/finding/definition")).get("get");
        var parameters = (List<Map<String, Object>>) get.get("parameters");
        assertThat(parameters).anySatisfy(parameter -> {
            assertThat(parameter).containsEntry("name", "use").containsEntry("required", true);
            assertThat((Map<String, Object>) parameter.get("schema")).containsEntry("enum", List.of("READ", "CREATE"));
        });
        var responses = (Map<String, Object>) get.get("responses");
        var unchanged = (Map<String, Object>) responses.get("304");
        assertThat(unchanged).doesNotContainKey("content");
        assertThat((Map<String, Object>) unchanged.get("headers")).containsOnlyKeys("ETag", "Cache-Control", "Vary");
        assertThat(document.get("x-cache-semantics").toString()).contains("Authorize before If-None-Match", "not If-Match", "No shared cache");
        var components = (Map<String, Object>) document.get("components");
        var schemas = (Map<String, Object>) components.get("schemas");
        var effective = (Map<String, Object>) schemas.get("EffectiveDefinition");
        assertThat((Map<String, Object>) effective.get("properties")).doesNotContainKeys("querySchema", "context", "accountId", "tenantId");
        var referenceRequest = (Map<String, Object>) schemas.get("ReferenceRequest");
        assertThat(referenceRequest).containsEntry("additionalProperties", false).containsEntry("required", List.of("query"));
        assertThat(document.get("x-reference-body-limit-bytes")).isEqualTo(2048);
    }
}
