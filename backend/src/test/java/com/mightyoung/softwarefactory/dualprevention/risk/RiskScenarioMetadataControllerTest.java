package com.mightyoung.softwarefactory.dualprevention.risk;

import com.mightyoung.softwarefactory.applicationregistry.definition.EntityDefinition.Use;
import com.mightyoung.softwarefactory.dualprevention.RiskScenarioHttpTransport;
import com.mightyoung.softwarefactory.identity.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.OutputStream;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;
import static com.mightyoung.softwarefactory.dualprevention.risk.RiskScenarioContracts.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Standalone adapter/fake-transport evidence only, not actual session, CSRF, filter or late-header acceptance. */
class RiskScenarioMetadataControllerTest {
    private static final String ETAG="W/\""+"a".repeat(64)+"\"";private static final JsonMapper JSON=JsonMapper.builder().build();
    @Test void bothRoutesAndBothDefinitionUsesStageFinalDistinctIdBytesWithoutReserialization()throws Exception {
        for(int route=0;route<3;route++){
            var f=new Fixture();var result=f.mvc.perform(request(route).header(HttpHeaders.IF_NONE_MATCH,"\"different\"")).andExpect(status().isOk()).andExpect(header().string(HttpHeaders.ETAG,ETAG)).andExpect(header().string(HttpHeaders.VARY,"Cookie")).andReturn();
            assertArrayEquals(f.expected.get(),result.getResponse().getContentAsByteArray());var ids=IdentityRequestIds.get(result.getRequest());assertNotEquals(ids.requestId(),ids.traceId());var meta=JSON.readTree(result.getResponse().getContentAsByteArray()).get("meta");assertEquals(ids.requestId().toString(),meta.get("requestId").stringValue());assertEquals(ids.traceId().toString(),meta.get("traceId").stringValue());
            assertEquals(List.of("capture","close","stage","write"),f.events);verify(f.capture).close();verify(f.outputs.getFirst(),never()).body();verify(f.outputs.getFirst()).writeTo(any(OutputStream.class));assertEquals(Set.of(HttpHeaders.CONTENT_TYPE,HttpHeaders.ETAG,HttpHeaders.CACHE_CONTROL,HttpHeaders.VARY),f.transport.headers.keySet());
            assertTrue(result.getResponse().getHeader(HttpHeaders.CACHE_CONTROL).contains("private"));assertTrue(result.getResponse().getHeader(HttpHeaders.CACHE_CONTROL).contains("no-cache"));
        }
    }
    @Test void weakStrongListsRepeatedHeadersAndSoleWildcardMatchOnlyAfterCurrentServiceCall()throws Exception {
        var validators=List.of(new String[]{"*"},new String[]{ETAG},new String[]{ETAG.substring(2)},new String[]{"\"other\", "+ETAG},new String[]{"\"tag,with,commas\", "+ETAG},new String[]{"\"other\"",ETAG});
        for(int route=0;route<2;route++)for(var values:validators){
            var f=new Fixture();var result=f.mvc.perform(request(route).header(HttpHeaders.IF_NONE_MATCH,(Object[])values)).andExpect(status().isNotModified()).andExpect(content().string("")).andExpect(header().string(HttpHeaders.ETAG,ETAG)).andExpect(header().string(HttpHeaders.VARY,"Cookie")).andExpect(header().doesNotExist(HttpHeaders.CONTENT_TYPE)).andExpect(header().doesNotExist(HttpHeaders.CONTENT_LENGTH)).andReturn();
            assertEquals(0,f.transport.length);assertEquals(Set.of(HttpHeaders.ETAG,HttpHeaders.CACHE_CONTROL,HttpHeaders.VARY),f.transport.headers.keySet());assertEquals(List.of("capture","close","stage"),f.events);verify(f.capture).close();verify(f.outputs.getFirst(),never()).writeTo(any(OutputStream.class));verify(f.outputs.getFirst(),never()).body();verify(f.requests).capture(result.getRequest());assertEquals(1,f.outputs.size());
        }
    }
    @Test void revokedOrUnavailableCurrentAuthorityNeverBecomes304EvenForWildcard()throws Exception {
        for(int route=0;route<2;route++)for(boolean unavailable:List.of(false,true)){
            var f=new Fixture();RuntimeException failure=unavailable?new SessionSensitiveException(SessionSensitiveException.Reason.UNAVAILABLE):new org.springframework.security.access.AccessDeniedException("PRIVATE ROLE");
            if(route==0)doThrow(failure).when(f.service).schema(eq(f.capture),any(),any());else doThrow(failure).when(f.service).definition(eq(f.capture),eq(Use.READ),any(),any());
            var result=f.mvc.perform(request(route).header(HttpHeaders.IF_NONE_MATCH,"*")).andReturn();assertEquals(unavailable?503:403,result.getResponse().getStatus());assertNull(result.getResponse().getHeader(HttpHeaders.ETAG));assertEquals("no-store",result.getResponse().getHeader(HttpHeaders.CACHE_CONTROL));assertFalse(result.getResponse().getContentAsString().contains("PRIVATE"));verify(f.capture).close();assertEquals(0,f.outputs.size());
        }
    }
    @Test void authorizationFailurePrecedesEvenMalformedConditionalHeaderValidation()throws Exception {
        var f=new Fixture();doThrow(new org.springframework.security.access.AccessDeniedException("revoked")).when(f.service).schema(eq(f.capture),any(),any());f.mvc.perform(request(0).header(HttpHeaders.IF_NONE_MATCH,"*, invalid")).andExpect(status().isForbidden()).andExpect(header().doesNotExist(HttpHeaders.ETAG));verify(f.capture).close();
    }
    @Test void malformedOrOversizedValidatorsRejectAfterAuthorizationWithoutPublishingEtag()throws Exception {
        var bad=List.of("", "*, "+ETAG,ETAG+", *","\"unterminated","w/\"tag\"",String.valueOf((char)1),"\"𠀀\"","\""+"a".repeat(8192)+"\"");
        for(String validator:bad){var f=new Fixture();var result=f.mvc.perform(request(0).header(HttpHeaders.IF_NONE_MATCH,validator)).andExpect(status().isBadRequest()).andExpect(header().doesNotExist(HttpHeaders.ETAG)).andReturn();assertEquals(1,f.outputs.size());verify(f.service).schema(eq(f.capture),any(),any());verify(f.capture).close();assertEquals("BAD_REQUEST",JSON.readTree(result.getResponse().getContentAsByteArray()).get("code").stringValue());}
        var f=new Fixture();f.mvc.perform(request(0).header(HttpHeaders.IF_NONE_MATCH,"*","*")).andExpect(status().isBadRequest());verify(f.service).schema(eq(f.capture),any(),any());
    }
    @Test void parserRejectsUnknownDuplicateMissingUseAndGetBodiesBeforeCapture()throws Exception {
        var f=new Fixture();for(String path:List.of("/api/v1/entities/risk_scenario/schema?extra=x","/api/v1/entities/risk_scenario/definition","/api/v1/entities/risk_scenario/definition?use=read","/api/v1/entities/risk_scenario/definition?use=READ&use=CREATE","/api/v1/entities/risk_scenario/definition?use=READ&extra=x"))f.mvc.perform(get(path)).andExpect(status().isBadRequest());f.mvc.perform(request(0).content("x")).andExpect(status().isBadRequest());verify(f.requests,never()).capture(any());verifyNoInteractions(f.service);
    }
    @Test void otherConditionalHeadersCannotBeMistakenForSuccessfulIfNoneMatch()throws Exception {
        var f=new Fixture();f.mvc.perform(request(0).header(HttpHeaders.IF_NONE_MATCH,"\"different\"").header(HttpHeaders.IF_MATCH,"\"wrong\"").header(HttpHeaders.IF_MODIFIED_SINCE,"Wed, 01 Jan 2099 00:00:00 GMT")).andExpect(status().isOk());assertEquals(1,f.outputs.size());
    }
    @Test void twoMetadataMappingsAreLiteralAndNoCommandRoutesAreIntroduced(){
        var routes=new HashSet<String>();for(var method:RiskScenarioMetadataController.class.getDeclaredMethods()){var mapping=method.getAnnotation(org.springframework.web.bind.annotation.GetMapping.class);if(mapping!=null)routes.addAll(List.of(mapping.value()));assertNull(method.getAnnotation(org.springframework.web.bind.annotation.PostMapping.class));}assertEquals(Set.of("/api/v1/entities/risk_scenario/schema","/api/v1/entities/risk_scenario/definition"),routes);
    }
    private static MockHttpServletRequestBuilder request(int route){return route==0?get("/api/v1/entities/risk_scenario/schema"):get("/api/v1/entities/risk_scenario/definition").param("use",route==1?"READ":"CREATE");}
    private static final class Fixture {
        final RiskScenarioMetadataService service=mock(RiskScenarioMetadataService.class);final SessionSensitiveRequests requests=mock(SessionSensitiveRequests.class);final CapturedHumanRequest capture=mock(CapturedHumanRequest.class);final List<String> events=new ArrayList<>();final FakeTransport transport=new FakeTransport(events);final AtomicReference<byte[]> expected=new AtomicReference<>();final List<RiskScenarioMetadataService.MetadataOutcome> outputs=new ArrayList<>();final RiskScenarioDefinitionProvider provider=new RiskScenarioDefinitionProvider();final MockMvc mvc;
        Fixture()throws Exception{
            when(requests.capture(any())).thenAnswer(call->{events.add("capture");return capture;});doAnswer(call->{events.add("close");return null;}).when(capture).close();
            when(service.schema(eq(capture),any(),any())).thenAnswer(call->outcome(RiskScenarioEntitySchema.schema(),call.getArgument(1),call.getArgument(2)));
            when(service.definition(eq(capture),any(),any(),any())).thenAnswer(call->outcome(provider.definition(call.getArgument(1)),call.getArgument(2),call.getArgument(3)));
            mvc=MockMvcBuilders.standaloneSetup(new RiskScenarioMetadataController(service,new RiskScenarioParser(JSON),requests,transport)).build();
        }
        private RiskScenarioMetadataService.MetadataOutcome outcome(Object data,UUID requestId,UUID trace)throws IOException{
            byte[] bytes=encodeResponse(new Response<>(data,new com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.Metadata(requestId.toString(),trace.toString(),"1")),ResponseBudget.METADATA);expected.set(bytes);var result=mock(RiskScenarioMetadataService.MetadataOutcome.class);outputs.add(result);when(result.etag()).thenReturn(ETAG);when(result.size()).thenReturn(bytes.length);doAnswer(call->{events.add("write");((OutputStream)call.getArgument(0)).write(bytes);return null;}).when(result).writeTo(any(OutputStream.class));return result;
        }
    }
    private static final class FakeTransport implements RiskScenarioHttpTransport {
        final List<String> events;int length;Map<String,List<String>> headers;FakeTransport(List<String> events){this.events=events;}
        @Override public void stage(HttpServletRequest request,HttpServletResponse response,int status,Map<String,List<String>> headers,int bodyLength,BodyWriter body)throws IOException{events.add("stage");this.headers=headers;length=bodyLength;response.setStatus(status);headers.forEach((name,values)->values.forEach(value->response.addHeader(name,value)));if(status!=304)response.setContentLength(bodyLength);body.writeTo(response.getOutputStream());}
    }
}
