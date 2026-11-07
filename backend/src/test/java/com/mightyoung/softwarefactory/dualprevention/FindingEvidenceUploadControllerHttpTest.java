package com.mightyoung.softwarefactory.dualprevention;

import com.mightyoung.softwarefactory.dualprevention.finding.evidenceupload.*;
import com.mightyoung.softwarefactory.files.FileEvidenceTrustUnavailableException;
import com.mightyoung.softwarefactory.identity.*;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static com.mightyoung.softwarefactory.dualprevention.FindingEvidenceUploadRequestParserTest.*;
import static com.mightyoung.softwarefactory.dualprevention.finding.evidenceupload.FindingEvidenceUploadContracts.*;
import static org.mockito.Mockito.*;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Mocked service proves HTTP mapping only, never database commit or producer acceptance. */
// MockHttpServletRequest appends charset to the raw Content-Type when its character encoding is set.
// Keep raw binary headers intact in this slice; the full session/CSRF security chain remains enabled.
@WebMvcTest(controllers = FindingEvidenceUploadController.class, properties = "spring.servlet.encoding.enabled=false")
@Import({SecurityConfiguration.class, FindingApiExceptionHandler.class, FindingEvidenceUploadRequestParser.class,
        FindingEvidenceUploadControllerHttpTest.Dependencies.class})
class FindingEvidenceUploadControllerHttpTest {
    static final UUID INTENT = UUID.fromString("52000000-0000-0000-0000-000000000001");
    static final String PATH = "/api/v1/findings/" + FINDING + "/remediation-rounds/2/evidence-uploads";
    static final OffsetDateTime EXPIRY = OffsetDateTime.parse("2026-11-03T01:00:00Z");
    @Autowired MockMvc mvc;
    @MockitoBean FindingEvidenceUploadService service;
    @MockitoBean FindingEvidenceReceiveService receiver;
    @BeforeEach void resetService() { reset(service,receiver); }

    @Test void mapsReturnedNewReservationAndResponseLossReplayWithoutAdvertisingBytes() throws Exception {
        when(service.reserve(any())).thenReturn(new Reservation(new Status(INTENT, State.INTENT_CREATED, EXPIRY), false),
                new Reservation(new Status(INTENT, State.INTENT_CREATED, EXPIRY), true));
        for (int status : new int[]{201, 200}) {
            mvc.perform(post(PATH).with(authenticatedMember()).with(validCsrf()).header("Idempotency-Key", KEY)
                    .contentType("application/json").content(BODY))
                    .andExpect(status().is(status)).andExpect(header().string("Location", PATH + "/" + INTENT))
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("$.data.uploadIntentId").value(INTENT.toString()))
                    .andExpect(jsonPath("$.data.state").value("INTENT_CREATED"))
                    .andExpect(jsonPath("$.data.statusUrl").value(PATH + "/" + INTENT))
                    .andExpect(jsonPath("$.data.filename").doesNotExist()).andExpect(jsonPath("$.data.uploadUrl").doesNotExist())
                    .andExpect(jsonPath("$.data.sha256").doesNotExist()).andExpect(jsonPath("$.meta.traceId").isString());
        }
        verify(service, times(2)).reserve(argThat(c -> c.idempotencyKey().equals(KEY) && c.findingId().equals(FINDING) && c.roundNo() == 2));
    }
    @Test void expiredReplayAndStatusGiveFreshKeyRecoveryWithoutReactivation() throws Exception {
        var expired = new Status(INTENT, State.EXPIRED, EXPIRY);
        when(service.reserve(any())).thenReturn(new Reservation(expired, true));
        when(service.status(FINDING, 2, INTENT)).thenReturn(expired);
        mvc.perform(post(PATH).with(authenticatedMember()).with(validCsrf()).header("Idempotency-Key", KEY).contentType("application/json").content(BODY))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.state").value("EXPIRED"))
                .andExpect(jsonPath("$.data.recoveryAction").value("FRESH_KEY_REQUIRED"));
        mvc.perform(get(PATH + "/" + INTENT).with(authenticatedMember())).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.recoveryAction").value("FRESH_KEY_REQUIRED"))
                .andExpect(jsonPath("$.data.statusUrl").doesNotExist()).andExpect(jsonPath("$.data.objectKey").doesNotExist());
    }
    @Test void rejectsQueriesRepeatedKeysInvalidPathsAndGetBodiesBeforeService() throws Exception {
        for (var request : new org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder[]{
                post(PATH + "?x=1"), post(PATH).queryParam("x", "1", "2"), post(PATH).header("Idempotency-Key", KEY),
                post(PATH.replace("/2/", "/02/")), post(PATH.replace(FINDING.toString(), "4000000-0000-0000-0000-000000000001"))}) {
            mvc.perform(request.with(authenticatedMember()).with(validCsrf()).header("Idempotency-Key", KEY).contentType("application/json").content(BODY))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                    .andExpect(header().string("Cache-Control", "no-store"));
        }
        mvc.perform(get(PATH + "/" + INTENT).with(authenticatedMember()).content("{}")) .andExpect(status().isBadRequest());
        mvc.perform(post(PATH).with(authenticatedMember()).with(validCsrf()).contentType("application/json").content(BODY)).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
    @Test void controllerNeverAsksServletToParseFormParameters() {
        var request = new org.springframework.mock.web.MockHttpServletRequest() {
            @Override public java.util.Map<String,String[]> getParameterMap() { throw new AssertionError("Servlet form parsing forbidden"); }
            @Override public String getParameter(String name) { throw new AssertionError("Servlet form parsing forbidden"); }
        };
        request.setContentType("application/x-www-form-urlencoded");
        request.addHeader("Idempotency-Key", KEY.toString());
        request.setContent("x=".repeat(10000).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var controller = new FindingEvidenceUploadController(service,
                new FindingEvidenceUploadRequestParser(new tools.jackson.databind.ObjectMapper()),receiver);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> controller.reserve(FINDING.toString(), "2", request,
                new org.springframework.mock.web.MockHttpServletResponse()))
                .isInstanceOfSatisfying(FindingEvidenceUploadRequestParser.RequestFailure.class,
                        failure -> org.assertj.core.api.Assertions.assertThat(failure.status()).isEqualTo(415));
        verifyNoInteractions(service);
    }
    @Test void strictParserFailuresHave400413415AndNeverReachService() throws Exception {
        for (var entry : new Object[][]{{BODY.replace("{", "{\"filename\":\"duplicate\","), "application/json", 400},
                {" ".repeat(4097), "application/json", 413}, {BODY, "text/plain", 415}}) {
            mvc.perform(post(PATH).with(authenticatedMember()).with(validCsrf()).header("Idempotency-Key", KEY)
                    .contentType((String)entry[1]).content((String)entry[0]))
                    .andExpect(status().is((Integer)entry[2])).andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                    .andExpect(header().string("Cache-Control", "no-store"));
        }
        verifyNoInteractions(service);
    }
    @Test void jsonDeclarationStillEnforcesExplicitUtf8AndRejectsOtherOrMalformedEncoding() throws Exception {
        when(service.reserve(any())).thenReturn(new Reservation(new Status(INTENT,State.INTENT_CREATED,EXPIRY),false));
        mvc.perform(post(PATH).with(authenticatedMember()).with(validCsrf()).header("Idempotency-Key",KEY)
                .contentType("application/json;charset=UTF-8").content(BODY.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .andExpect(status().isCreated());
        mvc.perform(post(PATH).with(authenticatedMember()).with(validCsrf()).header("Idempotency-Key",KEY)
                .contentType("application/json;charset=UTF-16").content(BODY.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .andExpect(status().isUnsupportedMediaType()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mvc.perform(post(PATH).with(authenticatedMember()).with(validCsrf()).header("Idempotency-Key",KEY)
                .contentType("application/json;charset=UTF-8").content(new byte[]{(byte)0xc3,0x28}))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        verify(service).reserve(any());
    }
    @Test void configuredPolicyFailureMapsTo413And415WithoutExposingPolicyInternals() throws Exception {
        for (var reason : new FindingEvidenceUploadException.Reason[]{FindingEvidenceUploadException.Reason.DECLARED_SIZE_EXCEEDED, FindingEvidenceUploadException.Reason.MEDIA_TYPE_NOT_ALLOWED}) {
            doThrow(new FindingEvidenceUploadException(reason)).when(service).reserve(any());
            mvc.perform(post(PATH).with(authenticatedMember()).with(validCsrf()).header("Idempotency-Key", KEY).contentType("application/json").content(BODY))
                    .andExpect(status().is(reason == FindingEvidenceUploadException.Reason.DECLARED_SIZE_EXCEEDED ? 413 : 415))
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST")).andExpect(header().string("Cache-Control", "no-store"));
        }
    }
    @Test void domainConflictPreservesPreciseCodeAndNoStore() throws Exception {
        when(service.reserve(any())).thenThrow(new FindingEvidenceUploadException(FindingEvidenceUploadException.Reason.IDEMPOTENCY_CONFLICT));
        mvc.perform(post(PATH).with(authenticatedMember()).with(validCsrf()).header("Idempotency-Key", KEY).contentType("application/json").content(BODY))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"))
                .andExpect(header().string("Cache-Control", "no-store"));
    }
    @Test void securityRejectsMissingSessionOrCsrfBeforeParsing() throws Exception {
        mvc.perform(post(PATH).with(validCsrf()).contentType("application/json").content("invalid"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
        mvc.perform(post(PATH).with(authenticatedMember()).contentType("application/json").content("invalid"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACTION_DENIED"));
        verifyNoInteractions(service);
    }
    @Test void unavailableAndInvisibleFailuresStaySafeAndUncacheable() throws Exception {
        when(service.reserve(any())).thenThrow(new FileEvidenceTrustUnavailableException());
        mvc.perform(post(PATH).with(authenticatedMember()).with(validCsrf()).header("Idempotency-Key", KEY).contentType("application/json").content(BODY))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("DEPENDENCY_UNAVAILABLE"))
                .andExpect(header().string("Cache-Control", "no-store"));
        when(service.status(FINDING, 2, INTENT)).thenThrow(new FindingNotVisibleException());
        mvc.perform(get(PATH + "/" + INTENT).with(authenticatedMember())).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_VISIBLE"));
    }
    @Test void acceptedBytesAndCancelRemainTechnicalStatusesOnly() throws Exception {
        var quarantined=new Status(INTENT,State.QUARANTINED,EXPIRY);
        when(receiver.receive(eq(FINDING),eq(2),eq(INTENT),eq("image/png"),eq(3L),any()))
                .thenReturn(new FindingEvidenceReceiveService.ReceiveResult(quarantined));
        mvc.perform(put(PATH+"/"+INTENT+"/content").with(authenticatedMember()).with(validCsrf())
                .header("Content-Length","3").contentType("image/png").content(new byte[]{1,2,3}))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.data.state").value("QUARANTINED"))
                .andExpect(jsonPath("$.meta.schemaVersion").value("2")).andExpect(jsonPath("$.data.receipt").doesNotExist());
        when(receiver.cancel(FINDING,2,INTENT)).thenReturn(new Status(INTENT,State.SCANNING,EXPIRY,true));
        mvc.perform(post(PATH+"/"+INTENT+"/cancel").with(authenticatedMember()).with(validCsrf()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.cancelRequested").value(true))
                .andExpect(jsonPath("$.data.state").value("SCANNING"));
    }
    @Test void alreadyReceivedHasSafeConflictAndStatusPointer() throws Exception {
        when(receiver.receive(any(),anyInt(),any(),any(),anyLong(),any()))
                .thenThrow(new FindingEvidenceReceiveService.AlreadyReceived(FINDING,2,INTENT));
        mvc.perform(put(PATH+"/"+INTENT+"/content").with(authenticatedMember()).with(validCsrf())
                .header("Content-Length","3").contentType("image/png").content(new byte[]{1,2,3}))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ALREADY_RECEIVED"))
                .andExpect(header().string("Location",PATH+"/"+INTENT)).andExpect(header().string("Cache-Control","no-store"));
    }
    @Test void binaryMediaParametersRemainUnsupportedBeforeReceiver() throws Exception {
        for(String media:new String[]{"image/png;charset=UTF-8","image/png;arbitrary=parameter"}) {
            mvc.perform(put(PATH+"/"+INTENT+"/content").with(authenticatedMember()).with(validCsrf())
                    .header("Content-Length","3").contentType(media).content(new byte[]{1,2,3}))
                    .andExpect(status().isUnsupportedMediaType()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                    .andExpect(header().string("Cache-Control","no-store"));
        }
        verifyNoInteractions(receiver);
    }
    @Test void binaryFramingAndCsrfRejectBeforeReceiver() throws Exception {
        for(String header:new String[]{"Transfer-Encoding","Content-Encoding","Content-Range","Range","Trailer"}) {
            mvc.perform(put(PATH+"/"+INTENT+"/content").with(authenticatedMember()).with(validCsrf())
                    .header("Content-Length","3").header(header,"untrusted").contentType("image/png").content(new byte[]{1,2,3}))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(put(PATH+"/"+INTENT+"/content").with(authenticatedMember())
                .header("Content-Length","3").contentType("image/png").content(new byte[]{1,2,3})).andExpect(status().isForbidden());
        verifyNoInteractions(receiver);
    }
    private static org.springframework.test.web.servlet.request.RequestPostProcessor authenticatedMember() {
        return request -> {
            var principal = VerifiedSessionPrincipalTestFixture.create(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1L, 1L);
            var context = org.springframework.security.core.context.SecurityContextHolder.createEmptyContext();
            context.setAuthentication(new org.springframework.security.authentication.TestingAuthenticationToken(principal, null, "ROLE_AUTHENTICATED"));
            request.getSession(true).setAttribute(org.springframework.security.web.context.HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
            VerifiedSessionPrincipalTestFixture.markAuthenticatedSession(request.getSession(false), java.time.Instant.now());
            return request;
        };
    }
    private static org.springframework.test.web.servlet.request.RequestPostProcessor validCsrf() {
        return request -> {
            request.setCookies(new jakarta.servlet.http.Cookie("XSRF-TOKEN", "synthetic-csrf"));
            request.addHeader("X-XSRF-TOKEN", "synthetic-csrf"); return request;
        };
    }
    @TestConfiguration(proxyBeanMethods = false)
    static class Dependencies {
        @Bean AuthenticationEvents authenticationEvents() { return event -> {}; }
        @Bean AccountStatusReadRepository accountSecurityStates() { return account -> java.util.Optional.of(new AccountSecurityState("ACTIVE", 1)); }
        @Bean AuthenticationProvider localPasswordAuthenticationProvider() { return new AuthenticationProvider() {
            public org.springframework.security.core.Authentication authenticate(org.springframework.security.core.Authentication authentication) { return null; }
            public boolean supports(Class<?> authentication) { return false; }
        }; }
    }
}
