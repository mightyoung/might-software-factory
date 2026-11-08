package com.mightyoung.softwarefactory.auditops;

import com.mightyoung.softwarefactory.identity.*;
import java.io.IOException;
import java.io.OutputStream;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.web.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Adapter and conditional-registration proof; no actual authentication, database or late-header graph. */
class TenantAuditQueryControllerTest {
    @org.junit.jupiter.api.AfterEach void clearSecurityContext(){org.springframework.security.core.context.SecurityContextHolder.clearContext();}
    @Test void disabledByDefaultAndExplicitFalseWithoutDependencies() {
        for(String enabled:List.of("missing","false"))try(var context=context(enabled,false,false)) {
            context.refresh();assertTrue(context.getBeansOfType(TenantAuditQueryController.class).isEmpty());
        }
    }
    @Test void enabledRequiresBothServiceAndTransportAndAcceptsCompleteGraph() {
        for(boolean service:List.of(false,true))try(var context=context("true",service,!service)) {
            assertThrows(org.springframework.beans.BeansException.class,context::refresh);
        }
        try(var context=context("true",true,true)){context.refresh();assertNotNull(context.getBean(TenantAuditQueryController.class));}
    }
    @Test void onlyLiteralPostRouteIsRegistered() throws Exception {
        var f=new Fixture();var mvc=MockMvcBuilders.standaloneSetup(f.controller).build();
        mvc.perform(get("/api/v1/audit-events/query")).andExpect(status().isMethodNotAllowed());
        mvc.perform(post("/api/v1/audit-events/query").contentType("application/json").content("{}"))
                .andExpect(status().isOk()).andExpect(content().bytes(f.bytes));
        assertEquals(List.of("capture","service","close","stage"),f.events);
    }
    @Test void independentServerIdsAndOwnedBytesPassThroughAfterCaptureClose() throws Exception {
        var f=new Fixture();var req=request();var forgedRequest=UUID.randomUUID();var forgedTrace=UUID.randomUUID();req.addHeader("X-Request-Id",forgedRequest.toString());req.addHeader("X-Trace-Id",forgedTrace.toString());
        f.controller.query(req,new MockHttpServletResponse());var ids=IdentityRequestIds.get(req);
        assertNotEquals(ids.requestId(),ids.traceId());assertNotEquals(forgedRequest,ids.requestId());assertNotEquals(forgedTrace,ids.traceId());verify(f.service).query(eq(f.capture),any(),eq(ids.requestId()),eq(ids.traceId()));
        verify(f.result).size();verify(f.result).writeTo(any(OutputStream.class));assertEquals(List.of("capture","service","close","stage"),f.events);
    }
    @Test void realCaptureIsClosedBeforeSuccessAndUnavailableErrorAreStaged() throws Exception {
        for(boolean unavailable:List.of(false,true)){
            var request=request();var session=new MockHttpSession();request.setSession(session);
            VerifiedSessionPrincipalTestFixture.markAuthenticatedSession(session);
            var principal=VerifiedSessionPrincipalTestFixture.create(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID());
            var authentication=org.springframework.security.authentication.UsernamePasswordAuthenticationToken.authenticated(principal,"unused",List.of());
            org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(authentication);
            var captured=new java.util.concurrent.atomic.AtomicReference<CapturedHumanRequest>();var stages=new int[1];
            var service=mock(TenantAuditQueryService.class);var result=mock(TenantAuditQueryService.EncodedResult.class);
            when(service.query(any(),any(),any(),any())).thenAnswer(call->{
                CapturedHumanRequest actual=call.getArgument(0);actual.requireCurrent();captured.set(actual);
                if(unavailable)throw new TenantAuditQueryService.Unavailable();return result;
            });
            var controller=new TenantAuditQueryController(service,new TenantAuditQueryParser(),
                    new SessionSensitiveRequests(new SessionContextResolver(),java.time.Duration.ofHours(8)),
                    (req,res,status,headers,length,body)->{
                        stages[0]++;assertNotNull(captured.get());
                        var closed=assertThrows(SessionSensitiveException.class,captured.get()::requireCurrent);
                        assertEquals(SessionSensitiveException.Reason.REJECTED,closed.reason());assertEquals(unavailable?503:200,status);
                    });
            try{controller.query(request,new MockHttpServletResponse());assertEquals(1,stages[0]);verify(service).query(eq(captured.get()),any(),any(),any());}
            finally{org.springframework.security.core.context.SecurityContextHolder.clearContext();}
        }
    }
    @Test void parserErrorsRejectBeforeCaptureAndRemainSingleStagedErrors() throws Exception {
        for(String[] bad:List.of(new String[]{"text/plain","{}","415"},new String[]{"application/json","{\"unknown\":1}","400"},new String[]{"application/json","x".repeat(8193),"413"})){
            var f=new Fixture();var req=request();req.setContentType(bad[0]);req.setContent(bad[1].getBytes(java.nio.charset.StandardCharsets.UTF_8));var response=new MockHttpServletResponse();
            f.controller.query(req,response);assertEquals(Integer.parseInt(bad[2]),response.getStatus());assertEquals(List.of("stage"),f.events);verifyNoInteractions(f.requests,f.service);
        }
    }
    @Test void serviceFailureClosesBeforeErrorStageAndCaptureFailureDoesNotClose() throws Exception {
        var f=new Fixture();doThrow(new TenantAuditQueryService.Unavailable()).when(f.service).query(any(),any(),any(),any());var response=new MockHttpServletResponse();
        f.controller.query(request(),response);assertEquals(503,response.getStatus());assertEquals(List.of("capture","close","stage"),f.events);
        var g=new Fixture();doThrow(new UnauthenticatedSessionException()).when(g.requests).capture(any());g.controller.query(request(),new MockHttpServletResponse());verify(g.capture,never()).close();verifyNoInteractions(g.service);
    }
    @Test void transportKnownRuntimeAndIoFailuresNeverRestageOrRepeatService() throws Exception {
        for(Exception failure:List.of(new IOException("delivery"),new AccessDeniedException("transport"))){
            var f=new Fixture();f.transportFailure=failure;
            var thrown=assertThrows(Exception.class,()->f.controller.query(request(),new MockHttpServletResponse()));assertSame(failure,thrown);
            assertEquals(1,Collections.frequency(f.events,"stage"));verify(f.service).query(any(),any(),any(),any());verify(f.capture).close();
        }
    }
    private static AnnotationConfigApplicationContext context(String enabled,boolean service,boolean transport){
        var c=new AnnotationConfigApplicationContext();if(!enabled.equals("missing"))c.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test",Map.of("msf.audit-query.http.enabled",enabled)));
        c.register(TenantAuditQueryController.class);c.registerBean(TenantAuditQueryParser.class,TenantAuditQueryParser::new);c.registerBean(SessionSensitiveRequests.class,()->mock(SessionSensitiveRequests.class));
        if(service)c.registerBean(TenantAuditQueryService.class,()->mock(TenantAuditQueryService.class));if(transport)c.registerBean(TenantAuditHttpTransport.class,()->mock(TenantAuditHttpTransport.class));return c;
    }
    private static MockHttpServletRequest request(){var r=new MockHttpServletRequest("POST","/api/v1/audit-events/query");r.setContentType("application/json");r.setContent("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8));return r;}
    private static final class Fixture {
        final TenantAuditQueryService service=mock(TenantAuditQueryService.class);final SessionSensitiveRequests requests=mock(SessionSensitiveRequests.class);
        final CapturedHumanRequest capture=mock(CapturedHumanRequest.class);final TenantAuditQueryService.EncodedResult result=mock(TenantAuditQueryService.EncodedResult.class);
        final List<String> events=new ArrayList<>();final byte[] bytes="{\"owned\":true}".getBytes(java.nio.charset.StandardCharsets.UTF_8);Exception transportFailure;
        final TenantAuditQueryController controller;
        Fixture()throws IOException {
            when(requests.capture(any())).thenAnswer(call->{events.add("capture");return capture;});doAnswer(call->{events.add("close");return null;}).when(capture).close();
            when(service.query(any(),any(),any(),any())).thenAnswer(call->{events.add("service");return result;});when(result.size()).thenReturn(bytes.length);doAnswer(call->{((OutputStream)call.getArgument(0)).write(bytes);return null;}).when(result).writeTo(any(OutputStream.class));
            controller=new TenantAuditQueryController(service,new TenantAuditQueryParser(),requests,(req,res,status,headers,length,body)->{
                events.add("stage");if(transportFailure instanceof IOException io)throw io;if(transportFailure instanceof RuntimeException runtime)throw runtime;
                res.setStatus(status);assertEquals(Set.of("Content-Type","Cache-Control"),headers.keySet());headers.forEach((name,values)->values.forEach(value->res.addHeader(name,value)));assertFalse(headers.containsKey("Content-Length"));
                var output=new java.io.ByteArrayOutputStream();body.writeTo(output);assertEquals(length,output.size());res.getOutputStream().write(output.toByteArray());
            });
        }
    }
}
