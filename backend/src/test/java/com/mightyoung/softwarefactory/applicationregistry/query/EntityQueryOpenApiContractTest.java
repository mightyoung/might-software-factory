package com.mightyoung.softwarefactory.applicationregistry.query;

import com.networknt.schema.Error;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.dialect.Dialects;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.EntitySchema;
import static com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.FieldSchema;
import static com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.FieldType.UUID;
import static com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.FilterOperator.EQ;
import static org.assertj.core.api.Assertions.assertThat;

class EntityQueryOpenApiContractTest {

    private static final String OPENAPI_DOCUMENT_URI = "https://contract.test/entity-query.yaml";
    private static final String REQUEST_SCHEMA_URI =
            OPENAPI_DOCUMENT_URI + "#/components/schemas/EntityQueryRequest";

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
    void requestFixturesAreValidatedByTheResolvedOpenApiSchemaAndJavaValidator() throws IOException {
        Schema requestSchema = loadRequestSchema();
        EntityQueryValidator validator = new EntityQueryValidator();
        EntitySchema entitySchema = entitySchema();

        for (RequestFixture fixture : requestFixtures()) {
            List<Error> schemaErrors = requestSchema.validate(fixture.json(), InputFormat.JSON);
            assertThat(schemaErrors.isEmpty())
                    .as("OpenAPI validation for fixture %s; errors: %s", fixture.name(), schemaErrors)
                    .isEqualTo(fixture.valid());

            boolean javaValid = javaValidatorAccepts(fixture.json(), validator, entitySchema);
            assertThat(javaValid).as("Java validator for fixture %s", fixture.name()).isEqualTo(fixture.valid());
        }
    }

    private static Schema loadRequestSchema() throws IOException {
        String openApiDocument = Files.readString(contractPath());
        SchemaRegistry registry = SchemaRegistry.withDefaultDialect(
                Dialects.getOpenApi31(),
                builder -> builder.schemas(Map.of(OPENAPI_DOCUMENT_URI, openApiDocument)));
        return registry.getSchema(SchemaLocation.of(REQUEST_SCHEMA_URI));
    }

    private static List<RequestFixture> requestFixtures() {
        return List.of(
                new RequestFixture("number pagination", """
                        {"page":{"number":1},"size":20}
                        """, true),
                new RequestFixture("cursor pagination", """
                        {"page":{"cursor":"opaque-cursor"},"size":20}
                        """, true),
                new RequestFixture("optional explicit nulls", """
                        {"filterAST":null,"sort":null,"page":{"number":1,"cursor":null},"size":20,"fields":null}
                        """, true),
                new RequestFixture("resolved filter schema references", """
                        {"filterAST":{"operator":"AND","conditions":[{"field":"id","operator":"EQ","value":"00112233-4455-6677-8899-aabbccddeeff"}]},"page":{"number":1},"size":20}
                        """, true),
                new RequestFixture("missing required referenced condition operator", """
                        {"filterAST":{"operator":"AND","conditions":[{"field":"id","value":"00112233-4455-6677-8899-aabbccddeeff"}]},"page":{"number":1},"size":20}
                        """, false),
                new RequestFixture("both page members null", """
                        {"page":{"number":null,"cursor":null},"size":20}
                        """, false),
                new RequestFixture("both page members non-null", """
                        {"page":{"number":1,"cursor":"opaque-cursor"},"size":20}
                        """, false),
                new RequestFixture("missing required page", """
                        {"size":20}
                        """, false),
                new RequestFixture("missing required size", """
                        {"page":{"number":1}}
                        """, false));
    }

    private static boolean javaValidatorAccepts(
            String json, EntityQueryValidator validator, EntitySchema entitySchema) {
        try {
            validator.validate(EntityQueryContract.readRequest(json), entitySchema);
            return true;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static EntitySchema entitySchema() {
        return new EntitySchema("example", "1", "id", List.of("id"), Map.of(
                "id", new FieldSchema(UUID, false, true, true, Set.of(EQ), 0, Set.of())));
    }

    private static Path contractPath() {
        return Path.of("..", "contracts", "openapi", "entity-query.yaml");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readContract() throws IOException {
        try (var reader = Files.newBufferedReader(contractPath())) {
            return new Yaml().load(reader);
        }
    }

    private record RequestFixture(String name, String json, boolean valid) {
    }
}
