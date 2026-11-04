package com.mightyoung.softwarefactory.dualprevention;

import com.mightyoung.softwarefactory.dualprevention.finding.evidenceupload.*;
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
import static com.mightyoung.softwarefactory.dualprevention.FindingEvidenceSetInitializationRequestParserTest.*;
import static com.mightyoung.softwarefactory.dualprevention.finding.evidenceupload.FindingEvidenceSetInitializationContracts.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Mocked service proves mapping and filter boundaries, not PostgreSQL commit acceptance. */
@WebMvcTest(controllers = FindingEvidenceSetInitializationController.class)
@Import({SecurityConfiguration.class, SessionContextResolver.class, FindingApiExceptionHandler.class, FindingEvidenceSetInitializationRequestParser.class,
        FindingEvidenceSetInitializationControllerHttpTest.Dependencies.class})
class FindingEvidenceSetInitializationControllerHttpTest {
    static final UUID SET = UUID.fromString("51000000-0000-0000-0000-000000000001");
    static final String PATH = "/api/v1/findings/" + FINDING + "/remediation-rounds/2/evidence-sets";
    static final OffsetDateTime CREATED = OffsetDateTime.parse("2026-11-03T01:00:00Z");
    @Autowired MockMvc mvc;
    @MockitoBean FindingEvidenceSetInitializationService service;
    @BeforeEach void resetService() { reset(service); }

    @Test void mapsReturnedCommittedInitializationAndStableReplayWithoutAdvertisingOtherEndpoints() throws Exception {
        when(service.initialize(any())).thenReturn(new Result(SET, 0, CREATED, false), new Result(SET, 0, CREATED, true));
        for (int expected : new int[]{201, 200}) {
            mvc.perform(post(PATH).with(authenticatedMember()).with(validCsrf()).header("Idempotency-Key", KEY).contentType("application/json").content(BODY))
                    .andExpect(status().is(expected)).andExpect(header().doesNotExist("Location"))
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("$.data.evidenceSetId").value(SET.toString())).andExpect(jsonPath("$.data.state").value("UPLOADING"))
                    .andExpect(jsonPath("$.data.initialEvidenceRevision").value(0)).andExpect(jsonPath("$.data.createdAt").isString())
                    .andExpect(jsonPath("$.data.currentRevision").doesNotExist()).andExpect(jsonPath("$.data.statusUrl").doesNotExist())
                    .andExpect(jsonPath("$.data.uploadUrl").doesNotExist()).andExpect(jsonPath("$.meta.schemaVersion").value("1"));
        }
        verify(service, times(2)).initialize(argThat(c -> c.findingId().equals(FINDING) && c.roundNo() == 2 && c.idempotencyKey().equals(KEY) && c.expectedFindingVersion() == 7));
    }
    @Test void strictQueriesHeadersPathsAndBodiesFailWithoutServiceEffects() throws Exception {
        for (var request : new org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder[]{
                post(PATH + "?unknown=x"), post(PATH).queryParam("x", "1", "2"), post(PATH).header("Idempotency-Key", KEY),
                post(PATH.replace("/2/", "/02/")), post(PATH.replace(FINDING.toString(), "4000000-0000-0000-0000-000000000001"))}) {
            mvc.perform(request.with(authenticatedMember()).with(validCsrf()).header("Idempotency-Key", KEY).contentType("application/json").content(BODY))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST")).andExpect(header().string("Cache-Control", "no-store"));
        }
        for (var entry : new Object[][]{{"{\"expectedFindingVersion\":7,\"expectedFindingVersion\":7}","application/json",400},
                {" ".repeat(1025),"application/json",413}, {BODY,"application/x-www-form-urlencoded",415}}) {
            mvc.perform(post(PATH).with(authenticatedMember()).with(validCsrf()).header("Idempotency-Key", KEY).contentType((String)entry[1]).content((String)entry[0]))
                    .andExpect(status().is((Integer)entry[2])).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }
        mvc.perform(post(PATH).with(authenticatedMember()).with(validCsrf()).contentType("application/json").content(BODY)).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
    @Test void authenticationAndCsrfAreEnforcedBeforeParserAndService() throws Exception {
        mvc.perform(post(PATH).with(validCsrf()).content("invalid")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
        mvc.perform(post(PATH).with(authenticatedMember()).content("invalid")).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACTION_DENIED"));
        verifyNoInteractions(service);
    }
    @Test void mapsPreciseDomainFailuresAndPreservesSafeNoStoreEnvelope() throws Exception {
        String[][] cases = {{"IDEMPOTENCY_CONFLICT","409","IDEMPOTENCY_CONFLICT"}, {"VERSION_CONFLICT","409","VERSION_CONFLICT"},
                {"QUOTA_EXCEEDED","429","RATE_LIMITED"}, {"ASSIGNMENT_UNRESOLVED","422","WORKFLOW_ASSIGNMENT_UNRESOLVED"}, {"UNAVAILABLE","503","DEPENDENCY_UNAVAILABLE"}};
        for (String[] entry : cases) {
            doThrow(new FindingEvidenceSetInitializationException(FindingEvidenceSetInitializationException.Reason.valueOf(entry[0]))).when(service).initialize(any());
            var result = mvc.perform(post(PATH).with(authenticatedMember()).with(validCsrf()).header("Idempotency-Key", KEY).contentType("application/json").content(BODY))
                    .andExpect(status().is(Integer.parseInt(entry[1]))).andExpect(jsonPath("$.code").value(entry[2]))
                    .andExpect(jsonPath("$.traceId").isString()).andExpect(header().string("Cache-Control", "no-store"));
            if (entry[0].equals("QUOTA_EXCEEDED")) result.andExpect(header().string("Retry-After", "1"));
        }
    }
    @Test void missingOrChangedRemediationTaskMapsToCommandConflict() throws Exception {
        doThrow(new com.mightyoung.softwarefactory.workflow.WorkflowTaskStateConflictException()).when(service).initialize(any());
        mvc.perform(post(PATH).with(authenticatedMember()).with(validCsrf()).header("Idempotency-Key", KEY).contentType("application/json").content(BODY))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("COMMAND_CONFLICT"))
                .andExpect(header().string("Cache-Control", "no-store"));
    }
    @Test void currentActionDenialAndInvisibleContextRetain403And404() throws Exception {
        doThrow(new org.springframework.security.access.AccessDeniedException("private authority detail")).when(service).initialize(any());
        mvc.perform(post(PATH).with(authenticatedMember()).with(validCsrf()).header("Idempotency-Key", KEY).contentType("application/json").content(BODY))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACTION_DENIED"));
        doThrow(new FindingNotVisibleException()).when(service).initialize(any());
        mvc.perform(post(PATH).with(authenticatedMember()).with(validCsrf()).header("Idempotency-Key", KEY).contentType("application/json").content(BODY))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("RESOURCE_NOT_VISIBLE"));
    }
    @Test void neverTriggersServletFormParameterParsing() {
        var request = new org.springframework.mock.web.MockHttpServletRequest() {
            @Override public java.util.Map<String,String[]> getParameterMap() { throw new AssertionError("No Servlet form parsing"); }
            @Override public String getParameter(String name) { throw new AssertionError("No Servlet form parsing"); }
        };
        request.setContentType("application/x-www-form-urlencoded"); request.addHeader("Idempotency-Key", KEY.toString());
        var controller = new FindingEvidenceSetInitializationController(service, new FindingEvidenceSetInitializationRequestParser(new tools.jackson.databind.ObjectMapper()));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> controller.initialize(FINDING.toString(), "2", request, new org.springframework.mock.web.MockHttpServletResponse()))
                .isInstanceOfSatisfying(FindingEvidenceSetInitializationRequestParser.RequestFailure.class,
                        failure -> org.assertj.core.api.Assertions.assertThat(failure.status()).isEqualTo(415));
        verifyNoInteractions(service);
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
