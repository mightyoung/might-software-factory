package com.mightyoung.softwarefactory.dualprevention;

import com.mightyoung.softwarefactory.authorization.AssignableFindingMember;
import com.mightyoung.softwarefactory.dualprevention.finding.workflow.FindingDispatchCandidateService;
import com.mightyoung.softwarefactory.dualprevention.finding.workflow.FindingWorkflowState;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Binding-only HTTP tests; authenticated production permission proof is in PostgreSQL acceptance. */
class FindingDispatchCandidateControllerTest {
    @Test void boundedLiteralQueryAndCanonicalCursorProduceOnlyTheCandidateHintContract()throws Exception {
        var service=mock(FindingDispatchCandidateService.class);UUID id=UUID.randomUUID(),after=UUID.randomUUID(),member=UUID.randomUUID();
        when(service.candidates(id,"100%_",after,2)).thenReturn(new FindingDispatchCandidateService.Result(
                List.of(new AssignableFindingMember(member,"Display")),3,1,FindingWorkflowState.SAFETY_DISPATCH_PENDING,false,null));
        MockMvcBuilders.standaloneSetup(new FindingDispatchCandidateController(service)).setControllerAdvice(new FindingApiExceptionHandler()).build()
                .perform(get("/api/v1/findings/"+id+"/dispatch-candidates").param("q","100%_")
                        .param("limit","2").param("afterMemberId",after.toString().toUpperCase(java.util.Locale.ROOT)))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
                .andExpect(jsonPath("$.data[0].memberId").value(member.toString())).andExpect(jsonPath("$.data[0].displayName").value("Display"))
                .andExpect(jsonPath("$.findingVersion").value(3)).andExpect(jsonPath("$.nextAfterMemberId").isEmpty());
        verify(service).candidates(id,"100%_",after,2);
    }

    @Test void malformedAndAmbiguousInputsCannotReachTheService()throws Exception {
        var service=mock(FindingDispatchCandidateService.class);
        var mvc=MockMvcBuilders.standaloneSetup(new FindingDispatchCandidateController(service)).setControllerAdvice(new FindingApiExceptionHandler()).build();
        String path="/api/v1/findings/"+UUID.randomUUID()+"/dispatch-candidates";
        for(var request:List.of(get(path).param("limit","0"),get(path).param("limit","01"),
                get(path).param("limit","1","2"),get(path).param("q","A","B"),get(path).param("actorId",UUID.randomUUID().toString()),
                get(path).param("afterMemberId","1-1-1-1-1"),get(path).param("afterMemberId",""),
                get("/api/v1/findings/1-1-1-1-1/dispatch-candidates"))){
            mvc.perform(request).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_QUERY"));
        }
        verifyNoInteractions(service);
    }

    @Test void snapshotDeadlineUsesTheGenericRetryableDependencyContract()throws Exception {
        var service=mock(FindingDispatchCandidateService.class);UUID id=UUID.randomUUID();
        when(service.candidates(id,null,null,20)).thenThrow(new org.springframework.transaction.TransactionTimedOutException("private deadline detail"));
        MockMvcBuilders.standaloneSetup(new FindingDispatchCandidateController(service)).setControllerAdvice(new FindingApiExceptionHandler()).build()
                .perform(get("/api/v1/findings/"+id+"/dispatch-candidates"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("DEPENDENCY_UNAVAILABLE"))
                .andExpect(jsonPath("$.retryable").value(true))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private deadline detail"))));
    }
}
