package com.mightyoung.softwarefactory;

import com.mightyoung.softwarefactory.identity.IdentityRequestIds;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import static org.assertj.core.api.Assertions.*;

/** Mechanical shared-boundary proof; no real audit authorization, container or activated filter graph. */
class TenantAuditHttpBoundaryTest {
    private static final String AUDIT="/api/v1/audit-events/query";
    private static final String RISK="/api/v1/entities/risk_scenario/id";
    private static final Map<String,List<String>> JSON=Map.of("Content-Type",List.of("application/json;charset=UTF-8"),"Cache-Control",List.of("no-store"));
    @Test void oldAndExplicitFalseConstructorsLeaveAuditRequestsUntouched() throws Exception {
        for(var boundary:List.of(new RiskScenarioHttpBoundary(0),new RiskScenarioHttpBoundary(()->0),new RiskScenarioHttpBoundary(0,false),new RiskScenarioHttpBoundary(()->0,false))){
            var request=request("POST",AUDIT);var target=new MockHttpServletResponse();
            boundary.doFilter(request,target,(req,res)->{assertThat(req).isSameAs(request);assertThat(res).isSameAs(target);});
            assertThat(IdentityRequestIds.existing(request)).isEmpty();
        }
    }
    @Test void onlyExactPostPathIsCapturedAndContextPathIsRespected() throws Exception {
        for(var pair:List.of(new String[]{"GET",AUDIT},new String[]{"HEAD",AUDIT},new String[]{"PUT",AUDIT},new String[]{"POST",AUDIT+"/"},new String[]{"POST",AUDIT+"/extra"},new String[]{"POST","/api/v1/audit-events/Query"})){
            var boundary=new RiskScenarioHttpBoundary(0,true);var request=request(pair[0],pair[1]);var target=new MockHttpServletResponse();
            boundary.doFilter(request,target,(req,res)->{assertThat(req).isSameAs(request);assertThat(res).isSameAs(target);});assertThat(IdentityRequestIds.existing(request)).isEmpty();
        }
        var boundary=new RiskScenarioHttpBoundary(0,true);var request=request("POST","/app"+AUDIT);request.setContextPath("/app");var target=new MockHttpServletResponse();
        boundary.doFilter(request,target,(req,res)->boundary.stageAudit((HttpServletRequest)req,(HttpServletResponse)res,200,JSON,1,out->out.write(7)));
        assertThat(target.getContentAsByteArray()).containsExactly((byte)7);
    }
    @Test void waitsForLateSecurityAndDuplicateCookiesBeforeWritingTheOwnedBodyOnce() throws Exception {
        var boundary=new RiskScenarioHttpBoundary(()->0,true);var target=new MockHttpServletResponse();var writes=new AtomicInteger();
        boundary.doFilter(request("POST",AUDIT),target,(req,res)->{
            boundary.stageAudit(new HttpServletRequestWrapper((HttpServletRequest)req),new HttpServletResponseWrapper((HttpServletResponse)res),200,JSON,2,out->{writes.incrementAndGet();out.write(new byte[]{1,2});});
            assertThat(writes.get()).isZero();assertThat(target.getContentAsByteArray()).isEmpty();
            ((HttpServletResponse)res).addHeader("Set-Cookie","SESSION=one; Secure");((HttpServletResponse)res).addHeader("Set-Cookie","SESSION=one; Secure");((HttpServletResponse)res).setHeader("X-Content-Type-Options","nosniff");
        });
        assertThat(writes.get()).isEqualTo(1);assertThat(target.getContentAsByteArray()).containsExactly((byte)1,(byte)2);assertThat(target.getHeader("Content-Length")).isEqualTo("2");
        assertThat(target.getHeaders("Set-Cookie")).containsExactly("SESSION=one; Secure","SESSION=one; Secure");assertThat(target.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
    }
    @Test void wrongPortsPermanentlyPoisonBothSurfacesEvenWhenCaught() {
        for(boolean audit:new boolean[]{false,true}){
            var boundary=new RiskScenarioHttpBoundary(0,true);var target=new MockHttpServletResponse();var writes=new AtomicInteger();
            assertThatThrownBy(()->boundary.doFilter(request(audit?"POST":"GET",audit?AUDIT:RISK),target,(req,res)->{
                if(audit)assertThatThrownBy(()->boundary.stage((HttpServletRequest)req,(HttpServletResponse)res,200,JSON,0,out->{})).isInstanceOf(IOException.class);
                else assertThatThrownBy(()->boundary.stageAudit((HttpServletRequest)req,(HttpServletResponse)res,200,JSON,0,out->{})).isInstanceOf(IOException.class);
                assertThatThrownBy(()->{if(audit)boundary.stageAudit((HttpServletRequest)req,(HttpServletResponse)res,200,JSON,0,out->writes.incrementAndGet());else boundary.stage((HttpServletRequest)req,(HttpServletResponse)res,200,JSON,0,out->writes.incrementAndGet());}).isInstanceOf(IOException.class);
            })).isInstanceOf(ServletException.class);assertThat(writes.get()).isZero();assertThat(target.getContentAsByteArray()).isEmpty();
        }
    }
    @Test void mutableUriCannotSwitchTheSurfaceChosenAtEntry() throws Exception {
        for(boolean audit:new boolean[]{false,true}){
            var boundary=new RiskScenarioHttpBoundary(0,true);var original=request(audit?"POST":"GET",audit?AUDIT:RISK);var target=new MockHttpServletResponse();
            boundary.doFilter(original,target,(req,res)->{
                original.setRequestURI(audit?RISK:AUDIT);original.setMethod(audit?"HEAD":"POST");
                if(audit)boundary.stageAudit((HttpServletRequest)req,(HttpServletResponse)res,200,JSON,1,out->out.write(5));
                else boundary.stage((HttpServletRequest)req,(HttpServletResponse)res,200,JSON,1,out->out.write(5));
            });assertThat(target.getContentAsByteArray()).containsExactly((byte)5);
        }
    }
    @Test void mutableUriDoesNotPermitUsingTheOtherPort() {
        var boundary=new RiskScenarioHttpBoundary(0,true);var original=request("POST",AUDIT);var target=new MockHttpServletResponse();
        assertThatThrownBy(()->boundary.doFilter(original,target,(req,res)->{
            original.setRequestURI(RISK);original.setMethod("GET");
            assertThatThrownBy(()->boundary.stage((HttpServletRequest)req,(HttpServletResponse)res,200,JSON,0,out->{})).isInstanceOf(IOException.class);
        })).isInstanceOf(ServletException.class);assertThat(target.getContentAsByteArray()).isEmpty();
    }
    @ParameterizedTest @ValueSource(ints={200,400,401,403,409,413,415,503})
    void exactDeclaredBodyLimitsAndClosedStatusesAreAccepted(int status) throws Exception {
        int maximum=status==200?128*1024:8192;var bytes=new byte[maximum];Arrays.fill(bytes,(byte)3);
        var boundary=new RiskScenarioHttpBoundary(0,true);var target=new MockHttpServletResponse();
        boundary.doFilter(request("POST",AUDIT),target,(req,res)->boundary.stageAudit((HttpServletRequest)req,(HttpServletResponse)res,status,JSON,maximum,out->out.write(bytes)));
        assertThat(target.getStatus()).isEqualTo(status);assertThat(target.getContentAsByteArray()).containsExactly(bytes);
        var rejected=new RiskScenarioHttpBoundary(0,true);var refused=new MockHttpServletResponse();
        assertThatThrownBy(()->rejected.doFilter(request("POST",AUDIT),refused,(req,res)->{
            assertThatThrownBy(()->rejected.stageAudit((HttpServletRequest)req,(HttpServletResponse)res,status,JSON,maximum+1,out->{})).isInstanceOf(IOException.class);
        })).isInstanceOf(ServletException.class);assertThat(refused.getContentAsByteArray()).isEmpty();
    }
    @ParameterizedTest @ValueSource(ints={201,204,302,304,404,422,500,502})
    void otherStagedStatusesAreRejectedBeforeAnyBody(int status) {
        var boundary=new RiskScenarioHttpBoundary(0,true);var target=new MockHttpServletResponse();var writes=new AtomicInteger();
        assertThatThrownBy(()->boundary.doFilter(request("POST",AUDIT),target,(req,res)->{
            assertThatThrownBy(()->boundary.stageAudit((HttpServletRequest)req,(HttpServletResponse)res,status,JSON,0,out->writes.incrementAndGet())).isInstanceOf(IOException.class);
        })).isInstanceOf(ServletException.class);assertThat(writes.get()).isZero();
    }
    @Test void fixedHeadersMustBeExactlyTheTwoSingletonValues() {
        var cases=List.of(Map.of("Content-Type",List.of("application/json"),"Cache-Control",List.of("no-store")),
                Map.of("Content-Type",List.of("application/json;charset=UTF-8")),
                Map.of("Content-Type",List.of("application/json;charset=UTF-8"),"Cache-Control",List.of("public")),
                Map.of("Content-Type",List.of("application/json;charset=UTF-8"),"Cache-Control",List.of("no-store","no-store")),
                Map.of("Content-Type",List.of("application/json;charset=UTF-8"),"Cache-Control",List.of("no-store"),"ETag",List.of("fixed")),
                Map.of("Content-Type",List.of("application/json;charset=UTF-8"),"Cache-Control",List.of("no-store"),"Vary",List.of("Cookie")));
        for(var headers:cases){var boundary=new RiskScenarioHttpBoundary(0,true);var target=new MockHttpServletResponse();
            assertThatThrownBy(()->boundary.doFilter(request("POST",AUDIT),target,(req,res)->{
                assertThatThrownBy(()->boundary.stageAudit((HttpServletRequest)req,(HttpServletResponse)res,200,headers,0,out->{})).isInstanceOf(IOException.class);
            })).isInstanceOf(ServletException.class);assertThat(target.getContentAsByteArray()).isEmpty();}
    }
    @Test void lateFixedHeaderChangesAndMetadataHeadersRefuseRelease() {
        for(String mutation:List.of("type","cache","duplicate","etag","vary")){
            var boundary=new RiskScenarioHttpBoundary(0,true);var target=new MockHttpServletResponse();var writes=new AtomicInteger();
            assertThatThrownBy(()->boundary.doFilter(request("POST",AUDIT),target,(req,res)->{
                boundary.stageAudit((HttpServletRequest)req,(HttpServletResponse)res,200,JSON,0,out->writes.incrementAndGet());var response=(HttpServletResponse)res;
                switch(mutation){case "type"->response.setHeader("Content-Type","text/plain");case "cache"->response.setHeader("Cache-Control","public");case "duplicate"->response.addHeader("Cache-Control","no-store");case "etag"->response.setHeader("ETag","fixed");default->response.setHeader("Vary","Cookie");}
            })).isInstanceOf(ServletException.class);assertThat(writes.get()).isZero();assertThat(target.getContentAsByteArray()).isEmpty();
        }
    }
    @Test void totalLateHeaderBudgetStillRejectsWithoutDroppingSecurityHeadersOrWriting() {
        var boundary=new RiskScenarioHttpBoundary(8192,true);var target=new MockHttpServletResponse();var writes=new AtomicInteger();
        assertThatThrownBy(()->boundary.doFilter(request("POST",AUDIT),target,(req,res)->{
            boundary.stageAudit((HttpServletRequest)req,(HttpServletResponse)res,200,JSON,0,out->writes.incrementAndGet());((HttpServletResponse)res).setHeader("X-Security","a".repeat(8192));
        })).isInstanceOf(IOException.class);assertThat(writes.get()).isZero();assertThat(target.getContentAsByteArray()).isEmpty();
    }
    @Test void earlyUnstagedSecurityResponsesAllowOnly401403503WithoutInventingContentType() throws Exception {
        for(int status:new int[]{401,403,503}){var boundary=new RiskScenarioHttpBoundary(0,true);var target=new MockHttpServletResponse();
            boundary.doFilter(request("POST",AUDIT),target,(req,res)->{var response=(HttpServletResponse)res;response.setStatus(status);response.getOutputStream().write(7);});
            assertThat(target.getStatus()).isEqualTo(status);assertThat(target.getContentType()).isNull();assertThat(target.getContentAsByteArray()).containsExactly((byte)7);}
        for(int status:new int[]{400,404,409,413,415,500}){var boundary=new RiskScenarioHttpBoundary(0,true);var target=new MockHttpServletResponse();
            assertThatThrownBy(()->boundary.doFilter(request("POST",AUDIT),target,(req,res)->((HttpServletResponse)res).setStatus(status))).isInstanceOf(ServletException.class);}
    }
    @Test void earlySecurityErrorBodyRemainsBoundedAtEightKiB() throws Exception {
        var boundary=new RiskScenarioHttpBoundary(0,true);var target=new MockHttpServletResponse();
        boundary.doFilter(request("POST",AUDIT),target,(req,res)->{var response=(HttpServletResponse)res;response.setStatus(401);response.getOutputStream().write(new byte[8192]);});
        assertThat(target.getContentAsByteArray()).hasSize(8192);
        var rejected=new RiskScenarioHttpBoundary(0,true);var refused=new MockHttpServletResponse();
        assertThatThrownBy(()->rejected.doFilter(request("POST",AUDIT),refused,(req,res)->{var response=(HttpServletResponse)res;response.setStatus(401);assertThatThrownBy(()->response.getOutputStream().write(new byte[8193])).isInstanceOf(IllegalStateException.class);})).isInstanceOf(ServletException.class);
        assertThat(refused.getContentAsByteArray()).isEmpty();
    }
    @Test void shortAndLongWritersFailWithoutRetry() {
        for(int length:new int[]{0,2}){var boundary=new RiskScenarioHttpBoundary(0,true);var target=new MockHttpServletResponse();var writes=new AtomicInteger();
            assertThatThrownBy(()->boundary.doFilter(request("POST",AUDIT),target,(req,res)->boundary.stageAudit((HttpServletRequest)req,(HttpServletResponse)res,200,JSON,1,out->{writes.incrementAndGet();out.write(new byte[length]);}))).isInstanceOf(IOException.class);
            assertThat(writes.get()).isEqualTo(1);}
    }
    @Test void caughtSinkIoRuntimeOrErrorCannotBeRetried() {
        for(int mode:new int[]{0,1,2}){var attempts=new AtomicInteger();var boundary=new RiskScenarioHttpBoundary(0,true);
            var target=new MockHttpServletResponse(){@Override public ServletOutputStream getOutputStream(){return new ServletOutputStream(){
                @Override public void write(int value)throws IOException{attempts.incrementAndGet();if(mode==0)throw new IOException("synthetic");if(mode==1)throw new IllegalStateException("synthetic");throw new AssertionError("synthetic");}
                @Override public boolean isReady(){return true;}@Override public void setWriteListener(WriteListener listener){throw new UnsupportedOperationException();}
            };}};
            assertThatThrownBy(()->boundary.doFilter(request("POST",AUDIT),target,(req,res)->boundary.stageAudit((HttpServletRequest)req,(HttpServletResponse)res,200,JSON,1,out->{
                try{out.write(1);}catch(IOException|RuntimeException|Error ignored){}
                assertThatThrownBy(()->out.write(1)).isInstanceOf(IOException.class);
            }))).isInstanceOf(IOException.class);assertThat(attempts.get()).isEqualTo(1);
        }
    }
    private static MockHttpServletRequest request(String method,String path){return new MockHttpServletRequest(method,path);}
}
