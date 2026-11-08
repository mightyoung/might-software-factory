package com.mightyoung.softwarefactory.dualprevention.asset;

import com.mightyoung.softwarefactory.identity.CapturedHumanRequest;
import com.mightyoung.softwarefactory.identity.SessionSensitiveRequests;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;
import static com.mightyoung.softwarefactory.dualprevention.asset.AssetFoundationEntitySchemas.Root.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Transport/projection fixtures only: no authentication-filter or persisted HUMAN acceptance claim. */
class AssetFoundationReadControllersTest {
    private final JsonMapper json=JsonMapper.builder().build();
    @Test void sixLiteralQueriesPublishClosedMetadataAndNeverHiddenSortFields()throws Exception {
        var service=mock(AssetFoundationQueryService.class);var requests=mock(SessionSensitiveRequests.class);var capture=mock(CapturedHumanRequest.class);
        when(requests.capture(any())).thenReturn(capture);
        when(service.query(eq(capture),any(),any())).thenReturn(new AssetFoundationQueryContracts.Page(List.of(Map.of("code","A")),new com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.PageMetadata(20,1,null,null)));
        MockMvc mvc=MockMvcBuilders.standaloneSetup(new AssetFoundationQueryController(service,json,requests)).build();
        for(var root:AssetFoundationEntitySchemas.Root.values()) {
            mvc.perform(post("/api/v1/entities/"+root.key()+"/query").contentType("application/json").content("{\"page\":{\"number\":1},\"size\":20}"))
                    .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
                    .andExpect(jsonPath("$.meta.requestId").isString()).andExpect(jsonPath("$.meta.traceId").isString()).andExpect(jsonPath("$.meta.schemaVersion").value("1"))
                    .andExpect(jsonPath("$.data.items[0].code").value("A")).andExpect(jsonPath("$.data.page.total").doesNotExist());
            verify(service).query(eq(capture),eq(root),any());
        }
        verify(capture,times(6)).close();
        mvc.perform(post("/api/v1/entities/not_registered/query").contentType("application/json").content("{}"))
                .andExpect(status().isNotFound());
    }
    @Test void denialAndStrictInputProduceErrorsNotSuccessEnvelope()throws Exception {
        var service=mock(AssetFoundationQueryService.class);var requests=mock(SessionSensitiveRequests.class);var capture=mock(CapturedHumanRequest.class);
        when(requests.capture(any())).thenReturn(capture);when(service.query(eq(capture),eq(WORKSHOP),any())).thenThrow(new AssetFoundationFailure(403));
        MockMvc mvc=MockMvcBuilders.standaloneSetup(new AssetFoundationQueryController(service,json,requests)).build();
        mvc.perform(post("/api/v1/entities/workshop/query").contentType("application/json").content("{\"page\":{\"number\":1},\"size\":20}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN")).andExpect(jsonPath("$.message").isString())
                .andExpect(jsonPath("$.traceId").isString()).andExpect(jsonPath("$.retryable").value(false)).andExpect(jsonPath("$.fieldErrors").isArray()).andExpect(jsonPath("$.data").doesNotExist());
        mvc.perform(post("/api/v1/entities/workshop/query").contentType("application/json").content("{\"size\":20,\"size\":50}"))
                .andExpect(status().isBadRequest());
        verify(service,times(1)).query(any(),any(),any());verify(capture).close();
    }
    @Test void historyUsesThreeExactParentRoutesAndOmitsFinalCursor()throws Exception {
        var service=mock(AssetFoundationHistoryService.class);var requests=mock(SessionSensitiveRequests.class);var capture=mock(CapturedHumanRequest.class);
        when(requests.capture(any())).thenReturn(capture);when(service.page(eq(capture),any(),any(),any())).thenReturn(new AssetFoundationHistoryContracts.Page(List.of(),null));
        MockMvc mvc=MockMvcBuilders.standaloneSetup(new AssetFoundationHistoryController(service,json,requests)).build();
        UUID id=UUID.randomUUID();
        for(String path:List.of("production-lines/"+id+"/workshop-bindings","process-stages/"+id+"/equipment-bindings","work-units/"+id+"/responsibility-bindings"))
            mvc.perform(post("/api/v1/assets/"+path+"/query").contentType("application/json").content("{}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.items").isArray()).andExpect(jsonPath("$.data.nextAfter").doesNotExist()).andExpect(jsonPath("$.meta.schemaVersion").value("1"));
        verify(capture,times(3)).close();
    }
}
