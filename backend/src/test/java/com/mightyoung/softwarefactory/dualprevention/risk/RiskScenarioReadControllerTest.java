package com.mightyoung.softwarefactory.dualprevention.risk;

import com.mightyoung.softwarefactory.dualprevention.RiskScenarioHttpTransport;
import com.mightyoung.softwarefactory.identity.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.OutputStream;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;
import static com.mightyoung.softwarefactory.dualprevention.risk.RiskScenarioContracts.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Adapter-only standalone MVC with immediately observable fake transport; not actual session/CSRF/late-header proof. */
class RiskScenarioReadControllerTest {
    private static final UUID ID=UUID.fromString("00112233-4455-6677-8899-aabbccddeeff");
    private static final Instant CUT=Instant.parse("2026-11-07T00:00:00Z");private static final JsonMapper JSON=JsonMapper.builder().build();
    @Test void fiveLiteralRoutesPassIndependentIdsAndStageExactlyServiceBytesAfterClosingCapture()throws Exception {
        for(int route=0;route<5;route++){
            var f=new Fixture();var result=switch(route){
                case 0->f.mvc.perform(get("/api/v1/entities/risk_scenario/"+ID));
                case 1->f.mvc.perform(post("/api/v1/entities/risk_scenario/query").contentType("application/json").content("{\"page\":{\"number\":1},\"size\":20}"));
                case 2->f.mvc.perform(get("/api/v1/risk-scenarios/"+ID+"/history"));
                case 3->f.mvc.perform(get("/api/v1/risk-scenarios/"+ID+"/history/1/hazards/"+ID));
                default->f.mvc.perform(post("/api/v1/risk-scenarios/eligible-hazards").contentType("application/json").content("{\"use\":\"CREATE\",\"organizationId\":\""+ID+"\",\"query\":\"\"}"));
            };
            var exchange=result.andExpect(status().isOk()).andExpect(header().string(HttpHeaders.CACHE_CONTROL,"no-store")).andReturn();byte[] bytes=exchange.getResponse().getContentAsByteArray();assertArrayEquals(f.expected.get(),bytes);assertEquals(bytes.length,f.transport.length);
            var ids=IdentityRequestIds.get(exchange.getRequest());assertNotEquals(ids.requestId(),ids.traceId());var meta=JSON.readTree(bytes).get("meta");assertEquals(ids.requestId().toString(),meta.get("requestId").stringValue());assertEquals(ids.traceId().toString(),meta.get("traceId").stringValue());
            assertEquals(List.of("capture","close","stage","write"),f.events);verify(f.capture).close();verify(f.requests).capture(exchange.getRequest());for(var encoded:f.outputs){verify(encoded,never()).body();verify(encoded).writeTo(any(OutputStream.class));}
            assertEquals(Set.of(HttpHeaders.CONTENT_TYPE,HttpHeaders.CACHE_CONTROL),f.transport.headers.keySet());
        }
    }
    @Test void controllerContainsOnlyFiveExplicitResourceMappings() {
        var routes=new HashSet<String>();for(var method:RiskScenarioReadController.class.getDeclaredMethods()){
            var get=method.getAnnotation(org.springframework.web.bind.annotation.GetMapping.class);if(get!=null)for(String path:get.value())routes.add("GET "+path);
            var post=method.getAnnotation(org.springframework.web.bind.annotation.PostMapping.class);if(post!=null)for(String path:post.value())routes.add("POST "+path);
        }
        assertEquals(Set.of("GET /api/v1/entities/risk_scenario/{id}","POST /api/v1/entities/risk_scenario/query","GET /api/v1/risk-scenarios/{id}/history","GET /api/v1/risk-scenarios/{id}/history/{version}/hazards/{hazardId}","POST /api/v1/risk-scenarios/eligible-hazards"),routes);
    }
    @Test void malformedReadPathsBodiesAndParametersRejectBeforeCapture()throws Exception {
        var f=new Fixture();for(String path:List.of("/api/v1/entities/risk_scenario/"+ID.toString().toUpperCase(),"/api/v1/entities/risk_scenario/"+ID+"?extra=x","/api/v1/risk-scenarios/"+ID+"/history?afterOccurredAt=2026-11-07T00:00:00Z","/api/v1/risk-scenarios/"+ID+"/history/1.0/hazards/"+ID))f.mvc.perform(get(path)).andExpect(status().isBadRequest());
        f.mvc.perform(get("/api/v1/entities/risk_scenario/"+ID).content("x")).andExpect(status().isBadRequest());verify(f.requests,never()).capture(any());verifyNoInteractions(f.reads);
    }
    @Test void strictPostParserRetainsUnsupportedMediaUnknownFieldsAndRawBudgetRejections()throws Exception {
        var f=new Fixture();f.mvc.perform(post("/api/v1/entities/risk_scenario/query").contentType("text/plain").content("{}")).andExpect(status().isUnsupportedMediaType());
        f.mvc.perform(post("/api/v1/entities/risk_scenario/query").contentType("application/json").content("{\"page\":{\"number\":1},\"size\":20,\"hazardIds\":[]}")).andExpect(status().isBadRequest());
        var over=f.mvc.perform(post("/api/v1/entities/risk_scenario/query").contentType("application/json").content("x".repeat(QUERY_BODY_BYTES+1))).andExpect(status().isPayloadTooLarge()).andReturn();assertTrue(over.getResponse().getContentAsByteArray().length<=ResponseBudget.ERROR.bytes());verify(f.requests,never()).capture(any());verifyNoInteractions(f.reads);
    }
    @Test void typedHiddenFailureClosesCaptureAndPreservesStatusCodeAndRequestTrace()throws Exception {
        var f=new Fixture();doThrow(new Failure(404)).when(f.reads).detail(eq(f.capture),eq(ID),any(),any());var result=f.mvc.perform(get("/api/v1/entities/risk_scenario/"+ID)).andExpect(status().isNotFound()).andReturn();var error=JSON.readTree(result.getResponse().getContentAsByteArray());assertEquals("NOT_FOUND",error.get("code").stringValue());assertEquals(IdentityRequestIds.get(result.getRequest()).traceId().toString(),error.get("traceId").stringValue());verify(f.capture).close();assertEquals(List.of("capture","close","stage"),f.events);
    }
    @Test void budgetExceededCodeSurvivesAdapterWithoutBecomingGenericUnavailable()throws Exception {
        var f=new Fixture();doThrow(Failure.responseBudgetExceeded()).when(f.reads).detail(eq(f.capture),eq(ID),any(),any());var result=f.mvc.perform(get("/api/v1/entities/risk_scenario/"+ID)).andExpect(status().isServiceUnavailable()).andReturn();var error=JSON.readTree(result.getResponse().getContentAsByteArray());assertEquals("RESPONSE_BUDGET_EXCEEDED",error.get("code").stringValue());assertFalse(error.get("retryable").booleanValue());verify(f.capture).close();
    }
    @Test void sessionAuthorizationAndDependencyErrorsStayBoundedRedactedAndUseTransport()throws Exception {
        for(RuntimeException failure:List.of(new org.springframework.security.access.AccessDeniedException("PRIVATE SQL HAZARD"),new SessionSensitiveException(SessionSensitiveException.Reason.UNAVAILABLE),new org.springframework.transaction.TransactionSystemException("PRIVATE COMMIT"))){
            var f=new Fixture();doThrow(failure).when(f.reads).detail(eq(f.capture),eq(ID),any(),any());var result=f.mvc.perform(get("/api/v1/entities/risk_scenario/"+ID)).andReturn();assertEquals(failure instanceof org.springframework.security.access.AccessDeniedException?403:503,result.getResponse().getStatus());String body=result.getResponse().getContentAsString();assertFalse(body.contains("PRIVATE"));assertTrue(result.getResponse().getContentAsByteArray().length<=8192);verify(f.capture).close();assertEquals(1,f.transport.calls);
        }
    }
    @Test void captureFailureHasNoCloseAndUntrustedHeadersCannotSupplyServerTrace()throws Exception {
        var f=new Fixture();doThrow(new UnauthenticatedSessionException()).when(f.requests).capture(any());var result=f.mvc.perform(get("/api/v1/entities/risk_scenario/"+ID).header("X-Trace-Id",ID.toString())).andExpect(status().isUnauthorized()).andReturn();var error=JSON.readTree(result.getResponse().getContentAsByteArray());assertEquals(IdentityRequestIds.get(result.getRequest()).traceId().toString(),error.get("traceId").stringValue());assertNotEquals(ID.toString(),error.get("traceId").stringValue());verify(f.capture,never()).close();verifyNoInteractions(f.reads);assertFalse(f.transport.headers.containsKey("X-Trace-Id"));
    }
    @Test void transportWriteFailureIsPropagatedWithoutRestagingOrRepeatingRead()throws Exception {
        var f=new Fixture();f.transport.failAfterWrite=true;var request=new org.springframework.mock.web.MockHttpServletRequest("GET","/api/v1/entities/risk_scenario/"+ID);var response=new org.springframework.mock.web.MockHttpServletResponse();assertThrows(IOException.class,()->f.controller.detail(ID.toString(),request,response));
        verify(f.reads).detail(eq(f.capture),eq(ID),any(),any());verify(f.capture).close();assertEquals(1,f.transport.calls);assertEquals(List.of("capture","close","stage","write"),f.events);
    }
    private static final class Fixture {
        final RiskScenarioReadService reads=mock(RiskScenarioReadService.class);final SessionSensitiveRequests requests=mock(SessionSensitiveRequests.class);final CapturedHumanRequest capture=mock(CapturedHumanRequest.class);final List<String> events=new ArrayList<>();final FakeTransport transport=new FakeTransport(events);final AtomicReference<byte[]> expected=new AtomicReference<>();final List<RiskScenarioReadService.EncodedResponse> outputs=new ArrayList<>();final RiskScenarioReadController controller;final MockMvc mvc;
        Fixture()throws Exception{
            when(requests.capture(any())).thenAnswer(call->{events.add("capture");return capture;});doAnswer(call->{events.add("close");return null;}).when(capture).close();
            when(reads.detail(eq(capture),eq(ID),any(),any())).thenAnswer(call->encoded(new Detail(ID,ID,1,"R","d","t","e","c",CUT,CUT,AssessmentStatus.UNASSESSED,null,null,null,new RestrictedHazards()),call.getArgument(2),call.getArgument(3)));
            when(reads.query(eq(capture),any(),any(),any())).thenAnswer(call->encoded(new QueryPage(List.of(Map.of("id",ID)),new QueryPageInfo(20,1,false,null,1L)),call.getArgument(2),call.getArgument(3)));
            when(reads.history(eq(capture),eq(ID),any(),any(),any())).thenAnswer(call->encoded(new HistoryPage(ID,List.of(),false,null),call.getArgument(3),call.getArgument(4)));
            when(reads.historicalSnapshot(eq(capture),eq(ID),eq(1L),eq(ID),any(),any())).thenAnswer(call->encoded(Map.of("hazardSnapshot",new HistoricalHazardSnapshot(ID,ID,1,"H","frozen",com.mightyoung.softwarefactory.dualprevention.hazard.HazardSourceContracts.ExistenceState.UNKNOWN,2,3)),call.getArgument(4),call.getArgument(5)));
            when(reads.eligibleHazards(eq(capture),any(),any(),any())).thenAnswer(call->encoded(new SelectorPage(List.of(),false,null),call.getArgument(2),call.getArgument(3)));
            controller=new RiskScenarioReadController(reads,new RiskScenarioParser(JSON),requests,transport);mvc=MockMvcBuilders.standaloneSetup(controller).build();
        }
        private RiskScenarioReadService.EncodedResponse encoded(Object data,UUID requestId,UUID trace)throws IOException{
            byte[] bytes=encodeResponse(new Response<>(data,new com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.Metadata(requestId.toString(),trace.toString(),"1")),ResponseBudget.DETAIL);expected.set(bytes);var result=mock(RiskScenarioReadService.EncodedResponse.class);outputs.add(result);when(result.size()).thenReturn(bytes.length);doAnswer(call->{events.add("write");((OutputStream)call.getArgument(0)).write(bytes);return null;}).when(result).writeTo(any(OutputStream.class));return result;
        }
    }
    private static final class FakeTransport implements RiskScenarioHttpTransport {
        final List<String> events;boolean failAfterWrite;int calls,length;Map<String,List<String>> headers;FakeTransport(List<String> events){this.events=events;}
        @Override public void stage(HttpServletRequest request,HttpServletResponse response,int status,Map<String,List<String>> headers,int bodyLength,BodyWriter body)throws IOException{events.add("stage");calls++;this.headers=headers;length=bodyLength;response.setStatus(status);headers.forEach((name,values)->values.forEach(value->response.addHeader(name,value)));if(status!=304)response.setContentLength(bodyLength);body.writeTo(response.getOutputStream());if(failAfterWrite)throw new IOException("synthetic transport delivery failure");}
    }
}
