package com.mightyoung.softwarefactory.dualprevention.asset;

import com.mightyoung.softwarefactory.identity.SessionContextResolver;
import com.mightyoung.softwarefactory.identity.SessionSensitiveRequests;
import com.mightyoung.softwarefactory.identity.VerifiedSessionPrincipalTestFixture;
import com.networknt.schema.InputFormat;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.dialect.Dialects;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Actual HTTP serialization validated against the unchanged closed OpenAPI schemas; domain commands are fixtures. */
class AssetFoundationHttpWireContractTest {
    private static final UUID ID = UUID.fromString("a1234567-1234-1234-1234-123456789abc");
    private static final UUID ORG = UUID.fromString("b1234567-1234-1234-1234-123456789abc");
    private static final Instant AT = Instant.parse("2026-11-04T08:00:00.123456Z");
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }

    @Test void createUsesTheExactCommandAuditPublicationTraceInCompleteMetadata() throws Exception {
        var commands = mock(AssetFoundationService.class);
        var reads = mock(AssetFoundationReadService.class);
        var trace = new AtomicReference<UUID>();
        when(commands.createWorkshop(any(), any(), any(), any())).thenAnswer(invocation -> {
            trace.set(invocation.getArgument(2));
            return new AssetContracts.CommandResult(UUID.randomUUID(), "workshop", ID, 1, AT);
        });
        var result = mvc(commands, reads).perform(post("/api/v1/assets/workshops").session(session())
                .header("Idempotency-Key", UUID.randomUUID()).contentType("application/json")
                .content("{\"code\":\"W1\",\"name\":\"Workshop\",\"organizationId\":\"" + ORG + "\"}"))
                .andExpect(status().isCreated()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.meta.schemaVersion").value("1")).andReturn();
        String json = result.getResponse().getContentAsString();
        validate("WorkshopCommandResultResponse", json);
        assertThat(JsonMapper.builder().build().readTree(json).at("/meta/traceId").asString()).isEqualTo(trace.get().toString());
    }
    @Test void detailMatchesClosedSchemaWithNullableBusinessFieldAndCompleteMetadata() throws Exception {
        var commands = mock(AssetFoundationService.class); var reads = mock(AssetFoundationReadService.class);
        when(reads.workshop(any(), eq(ID))).thenReturn(new AssetContracts.Workshop(ID, ORG, 1, "W1", "Workshop", null,
                AssetContracts.Lifecycle.ACTIVE, AT, AT));
        var result = mvc(commands, reads).perform(get("/api/v1/entities/workshop/" + ID).session(session()))
                .andExpect(status().isOk()).andReturn();
        validate("WorkshopDetailResponse", result.getResponse().getContentAsString());
    }
    @Test void commandRejectionKeepsItsTraceAndDoesNotExposeInternalCause() throws Exception {
        var commands = mock(AssetFoundationService.class); var reads = mock(AssetFoundationReadService.class);
        var trace = new AtomicReference<UUID>();
        when(commands.createWorkshop(any(), any(), any(), any())).thenAnswer(invocation -> {
            trace.set(invocation.getArgument(2));
            throw new AssetFoundationFailure(503, new java.sql.SQLException("PRIVATE SQL AND PARAMETERS", "08006"));
        });
        var result = mvc(commands, reads).perform(post("/api/v1/assets/workshops").session(session())
                .header("Idempotency-Key", UUID.randomUUID()).contentType("application/json")
                .content("{\"code\":\"W1\",\"name\":\"Workshop\",\"organizationId\":\"" + ORG + "\"}"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.retryable").value(true)).andReturn();
        String body = result.getResponse().getContentAsString(); validate("ErrorResponse", body);
        assertThat(JsonMapper.builder().build().readTree(body).get("traceId").asString()).isEqualTo(trace.get().toString());
        assertThat(body).doesNotContain("PRIVATE", "08006", "java.sql", "data", "meta");
    }
    static void validate(String name, String body) throws Exception {
        Path path = Path.of("..", "contracts", "openapi", "asset-foundation.yaml");
        String base = "https://contract.test/asset-foundation.yaml";
        var sources = Map.of(base, Files.readString(path), "https://contract.test/entity-query.yaml", Files.readString(path.resolveSibling("entity-query.yaml")));
        var registry = SchemaRegistry.withDefaultDialect(Dialects.getOpenApi31(), builder -> builder.schemas(sources));
        assertThat(registry.getSchema(SchemaLocation.of(base + "#/components/schemas/" + name)).validate(body, InputFormat.JSON)).isEmpty();
    }
    private static org.springframework.test.web.servlet.MockMvc mvc(AssetFoundationService commands, AssetFoundationReadService reads) {
        return MockMvcBuilders.standaloneSetup(new AssetFoundationController(commands, reads,
                new AssetFoundationRequestParser(JsonMapper.builder().build()),
                new SessionSensitiveRequests(new SessionContextResolver(), Duration.ofHours(12)))).build();
    }
    private static MockHttpSession session() {
        var principal = VerifiedSessionPrincipalTestFixture.create(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal, "", List.of()));
        var session = new MockHttpSession(); VerifiedSessionPrincipalTestFixture.markAuthenticatedSession(session); return session;
    }
}
