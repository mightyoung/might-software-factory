package com.mightyoung.softwarefactory.dualprevention;

import com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.Metadata;
import com.mightyoung.softwarefactory.dualprevention.finding.workflow.FindingReviewHistoryService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class FindingReviewHistoryControllerHttpTest {
    @Test void getReturnsScopedEnvelopeWithExplicitNullHints() throws Exception {
        var service=mock(FindingReviewHistoryService.class);UUID id=UUID.randomUUID();
        when(service.history(id,20,null)).thenReturn(new FindingReviewHistoryApiModels.Response(
                new FindingReviewHistoryApiModels.Data(List.of(),8,"DISCOVERED",1,"LEGACY",null,null,List.of(),"WORKFLOW_NOT_INITIALIZED",false,null),new Metadata("request","trace","1")));
        MockMvcBuilders.standaloneSetup(new FindingReviewHistoryController(service)).build()
                .perform(get("/api/v1/findings/"+id+"/review-history"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.currentVersion").value(8))
                .andExpect(jsonPath("$.data.entries").isArray()).andExpect(jsonPath("$.data.reviewProtocol").value("LEGACY"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"processDefinitionKey\":null")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"reviewStage\":null")))
                .andExpect(jsonPath("$.meta.schemaVersion").value("1"));
    }
}
