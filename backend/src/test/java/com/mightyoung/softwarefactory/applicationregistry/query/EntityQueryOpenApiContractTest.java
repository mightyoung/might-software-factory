package com.mightyoung.softwarefactory.applicationregistry.query;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EntityQueryOpenApiContractTest {

    @Test
    @SuppressWarnings("unchecked")
    void publishesVersionedComponentSchemasWithoutDeclaringADeployedEndpoint() throws IOException {
        Path contractPath = Path.of("..", "contracts", "openapi", "entity-query.yaml");
        Map<String, Object> contract;
        try (var reader = Files.newBufferedReader(contractPath)) {
            contract = new Yaml().load(reader);
        }

        assertThat(contract).containsEntry("openapi", "3.1.0");
        assertThat(((Map<String, Object>) contract.get("info")).get("version")).isEqualTo("1.0.0");
        assertThat(contract.get("x-target-endpoint")).isEqualTo("/api/v1/entities/{key}/query");
        assertThat((Map<String, Object>) contract.get("paths")).isEmpty();
        assertThat((Map<String, Object>) ((Map<String, Object>) contract.get("components")).get("schemas"))
                .containsKeys("EntityQueryRequest", "EntityQueryResponse", "EntityFieldSchema");
    }
}
