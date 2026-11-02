package com.mightyoung.softwarefactory.identity;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PasswordChangeControllerTest {
    @AfterEach void clear(){SecurityContextHolder.clearContext();}
    @Test void committedSuccessClearsAuthAndCookiesEvenWhenSessionCleanupFails() {
        var parser=mock(PasswordChangeRequestParser.class);var service=mock(PasswordChangeService.class);
        var request=new MockHttpServletRequest();var session=mock(MockHttpSession.class);request.setSession(session);
        doThrow(new IllegalStateException("cleanup storage unavailable")).when(session).invalidate();
        var secrets=new PasswordChangeRequest("current".toCharArray(),"new password 123456".toCharArray());
        when(parser.parse(request)).thenReturn(secrets);
        doAnswer(call->{call.<java.util.function.Supplier<PasswordChangeRequest>>getArgument(0).get().close();return null;})
                .when(service).change(any(java.util.function.Supplier.class),any());
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated("synthetic",null,List.of()));
        var response=new MockHttpServletResponse();new PasswordChangeController(parser,service).change(request,response);
        assertThat(response.getHeader("X-Trace-Id")).isEqualTo(request.getAttribute(PasswordChangeController.TRACE_ATTRIBUTE).toString());
        assertThat(response.getStatus()).isEqualTo(204);assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(response.getHeaders("Set-Cookie")).hasSize(2).allMatch(cookie->cookie.contains("Max-Age=0")&&cookie.contains("Secure"));
        verify(service).change(any(java.util.function.Supplier.class),any());verify(parser).parse(request);secrets.close();
    }
    @Test void rejectedAdmissionDoesNotParseBodyOrReachControllerCleanup() {
        var parser=mock(PasswordChangeRequestParser.class);var service=mock(PasswordChangeService.class);
        doThrow(new PasswordChangeException(PasswordChangeFailureReason.RATE_LIMITED,429,1))
                .when(service).change(any(java.util.function.Supplier.class),any());
        assertThatThrownBy(()->new PasswordChangeController(parser,service).change(new MockHttpServletRequest(),new MockHttpServletResponse()))
                .isInstanceOf(PasswordChangeException.class);
        verifyNoInteractions(parser);
    }
    @Test void errorsExposeOnlyFixedRedactedContractAndSafeRetryHeader()throws Exception {
        var response=new MockHttpServletResponse();new PasswordChangeExceptionHandler().handle(new PasswordChangeException(PasswordChangeFailureReason.RATE_LIMITED,429,999999),new MockHttpServletRequest(),response);
        assertThat(response.getStatus()).isEqualTo(429);assertThat(response.getHeader("Retry-After")).isEqualTo("300");
        assertThat(response.getContentAsString()).contains("RATE_LIMITED").doesNotContain("CURRENT_PASSWORD","encodedPassword","secret_hash");
    }
    @Test void errorTraceMatchesTheIndependentEventAndIgnoresClientSuppliedTrace() throws Exception {
        var contexts=mock(SessionContextResolver.class);
        var trusted=new CurrentTenantContext(java.util.UUID.randomUUID(),java.util.UUID.randomUUID(),java.util.UUID.randomUUID(),java.util.UUID.randomUUID(),1,1,1);
        when(contexts.currentTenant()).thenReturn(trusted);
        var events=mock(IdentitySecurityEvents.class);
        var clock=java.time.Clock.fixed(java.time.Instant.parse("2026-11-02T00:00:00Z"),java.time.ZoneOffset.UTC);
        var service=new PasswordChangeService(contexts,mock(CurrentSessionService.class),mock(PasswordCredentialStore.class),
                mock(org.springframework.security.crypto.password.PasswordEncoder.class),mock(IdentitySecurityTransaction.class),mock(IdentitySecurityAudit.class),events,
                new LoginAttemptLimiter(LoginAttemptLimiter.Config.defaults(),System::nanoTime),
                new org.springframework.beans.factory.support.StaticListableBeanFactory(java.util.Map.of("clock",clock)).getBeanProvider(java.time.Clock.class));
        var request=new MockHttpServletRequest();request.setContentType("application/json");request.setContent("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String clientTrace="00000000-0000-0000-0000-000000000001";request.addHeader("X-Trace-Id",clientTrace);
        var response=new MockHttpServletResponse();
        var controller=new PasswordChangeController(new PasswordChangeRequestParser(new tools.jackson.databind.ObjectMapper()),service);
        var failure=catchThrowable(()->controller.change(request,response));
        assertThat(failure).isInstanceOf(PasswordChangeException.class);
        new PasswordChangeExceptionHandler().handle((PasswordChangeException)failure,request,response);
        var captured=org.mockito.ArgumentCaptor.forClass(java.util.UUID.class);
        verify(events).passwordChangeFailed(eq(trusted),eq(PasswordChangeFailureReason.INVALID_NEW_PASSWORD),captured.capture(),any());
        String errorTrace=new tools.jackson.databind.ObjectMapper().readTree(response.getContentAsString()).path("traceId").asString();
        assertThat(errorTrace).isEqualTo(captured.getValue().toString()).isNotEqualTo(clientTrace);
        assertThat(response.getHeader("X-Trace-Id")).isEqualTo(errorTrace);
    }
    @Test void missingOrInvalidServerTraceAttributeFallsBackToFreshUuidWithoutTrustingHeaders() throws Exception {
        for(boolean wrongType:new boolean[]{false,true}) {
            var request=new MockHttpServletRequest();
            String clientTrace="00000000-0000-0000-0000-000000000001";
            request.addHeader("X-Trace-Id",clientTrace);
            if(wrongType)request.setAttribute(PasswordChangeController.TRACE_ATTRIBUTE,clientTrace);
            var response=new MockHttpServletResponse();
            new PasswordChangeExceptionHandler().handle(new PasswordChangeException(PasswordChangeFailureReason.INVALID_NEW_PASSWORD,400),request,response);
            String errorTrace=new tools.jackson.databind.ObjectMapper().readTree(response.getContentAsString()).path("traceId").asString();
            assertThat(java.util.UUID.fromString(errorTrace)).isNotNull();
            assertThat(errorTrace).isNotEqualTo(clientTrace).isEqualTo(response.getHeader("X-Trace-Id"));
        }
    }

}
