package com.mightyoung.softwarefactory.dualprevention.asset;

import com.mightyoung.softwarefactory.applicationregistry.definition.EntityDefinition;
import com.mightyoung.softwarefactory.dualprevention.asset.AssetFoundationEntitySchemas.Root;
import com.mightyoung.softwarefactory.identity.CapturedHumanRequest;
import com.mightyoung.softwarefactory.identity.SessionSensitiveException;
import com.mightyoung.softwarefactory.identity.SessionSensitiveRequests;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AssetFoundationDefinitionHttpTest {
    private final AssetFoundationDefinitionService service = mock(AssetFoundationDefinitionService.class);
    private final SessionSensitiveRequests requests = mock(SessionSensitiveRequests.class);
    private final CapturedHumanRequest capture = mock(CapturedHumanRequest.class);
    private final AssetFoundationDefinitionProvider provider = new AssetFoundationDefinitionProvider();
    @ParameterizedTest @ValueSource(strings = {"*", "W/\"current\"", "\"current\"", "\"other\", W/\"current\""})
    void matchingValidatorsOnlyProduceBodyless304AfterCurrentAuthorization(String validator) throws Exception {
        var data = AssetFoundationDefinitionService.project(provider.publication(Root.WORKSHOP), EntityDefinition.Use.CREATE, Root.WORKSHOP);
        when(service.definition(capture, Root.WORKSHOP, EntityDefinition.Use.CREATE))
                .thenReturn(new AssetFoundationDefinitionService.DefinitionResult(data, "W/\"current\""));
        var response = mvc().perform(get("/api/v1/entities/workshop/definition").param("use", "CREATE")
                .header(HttpHeaders.IF_NONE_MATCH, validator)).andExpect(status().isNotModified())
                .andExpect(content().string("")).andExpect(header().string(HttpHeaders.ETAG, "W/\"current\""))
                .andExpect(header().string(HttpHeaders.VARY, "Cookie")).andReturn();
        assertThat(response.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).contains("private", "no-cache");
        verify(service).definition(capture, Root.WORKSHOP, EntityDefinition.Use.CREATE);
        verify(capture).close();
    }
    @Test void revokedOrUnavailableIdentityNeverBecomes304() throws Exception {
        when(service.definition(capture, Root.WORKSHOP, EntityDefinition.Use.READ))
                .thenThrow(new org.springframework.security.access.AccessDeniedException("PRIVATE"));
        var first = mvc().perform(get("/api/v1/entities/workshop/definition").param("use", "READ")
                .header(HttpHeaders.IF_NONE_MATCH, "*")).andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(HttpHeaders.ETAG)).andReturn();
        AssetFoundationHttpWireContractTest.validate("ErrorResponse", first.getResponse().getContentAsString());
        doThrow(new SessionSensitiveException(SessionSensitiveException.Reason.UNAVAILABLE)).when(service).definition(capture, Root.WORKSHOP, EntityDefinition.Use.READ);
        mvc().perform(get("/api/v1/entities/workshop/definition").param("use", "READ").header(HttpHeaders.IF_NONE_MATCH, "*"))
                .andExpect(status().isServiceUnavailable()).andExpect(header().doesNotExist(HttpHeaders.ETAG));
    }
    @Test void literalSchemaAndDefinitionResponsesMatchAllSixClosedWireContracts() throws Exception {
        for (Root root : Root.values()) {
            when(service.schema(capture, root)).thenReturn(AssetFoundationEntitySchemas.schema(root));
            String name = switch (root) {
                case WORKSHOP -> "Workshop"; case PRODUCTION_LINE -> "ProductionLine"; case PROCESS_STAGE -> "ProcessStage";
                case EQUIPMENT -> "Equipment"; case WORK_UNIT -> "WorkUnit"; case HAZARD_SOURCE -> "HazardSource";
            };
            var schema = mvc().perform(get("/api/v1/entities/" + root.key() + "/schema"))
                    .andExpect(status().isOk()).andReturn();
            AssetFoundationHttpWireContractTest.validate(name + "SchemaResponse", schema.getResponse().getContentAsString());
            for (EntityDefinition.Use use : EntityDefinition.Use.values()) {
                var data = AssetFoundationDefinitionService.project(provider.publication(root), use, root);
                when(service.definition(capture, root, use)).thenReturn(new AssetFoundationDefinitionService.DefinitionResult(data, "W/\"current\""));
                var result = mvc().perform(get("/api/v1/entities/" + root.key() + "/definition").param("use", use.name()))
                        .andExpect(status().isOk()).andReturn();
                AssetFoundationHttpWireContractTest.validate(name + "DefinitionResponse", result.getResponse().getContentAsString());
            }
        }
    }
    @Test void rejectsMissingDuplicateUnknownAndCaseFoldedUseBeforeCapture() throws Exception {
        for (String path : List.of("/api/v1/entities/workshop/definition", "/api/v1/entities/workshop/definition?use=read",
                "/api/v1/entities/workshop/definition?use=READ&use=CREATE", "/api/v1/entities/workshop/definition?use=READ&extra=x",
                "/api/v1/entities/workshop/schema?extra=x")) {
            mvc().perform(get(path)).andExpect(status().isBadRequest());
        }
        verifyNoInteractions(service); verify(requests, never()).capture(any());
    }
    private org.springframework.test.web.servlet.MockMvc mvc() {
        when(requests.capture(any())).thenReturn(capture);
        return MockMvcBuilders.standaloneSetup(new AssetFoundationDefinitionController(service, requests)).build();
    }
}
