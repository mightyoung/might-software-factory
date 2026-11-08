package com.mightyoung.softwarefactory.dualprevention;

import com.mightyoung.softwarefactory.dualprevention.finding.workflow.*;
import com.mightyoung.softwarefactory.identity.*;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static com.mightyoung.softwarefactory.dualprevention.FindingReviewCycleRequestParserTest.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Actual session/CSRF filter chain, mocked command facade: HTTP mapping evidence only. */
@WebMvcTest(controllers = FindingReviewCycleController.class)
@Import({SecurityConfiguration.class, SessionContextResolver.class, FindingApiExceptionHandler.class, FindingReviewCycleRequestParser.class,
        FindingEvidenceSetInitializationControllerHttpTest.Dependencies.class})
class FindingReviewCycleControllerHttpTest {
    static final UUID FINDING = UUID.fromString("40000000-0000-0000-0000-000000000001");
    static final UUID KEY = UUID.fromString("60000000-0000-0000-0000-000000000001");
    static final String REVIEW_PATH = "/api/v1/findings/" + FINDING + "/review-decisions";
    static final String SUPPLEMENT_PATH = "/api/v1/findings/" + FINDING + "/supplements";
    @Autowired MockMvc mvc;
    @MockitoBean FindingWorkflowCommandService service;
    @BeforeEach void resetService() { reset(service); }

    @Test void mapsCommittedHistoricalResultsAndExactReplayWithExplicitCycle() throws Exception {
        var historical = new FindingWorkflowContracts.CommandResult(FINDING, "YH-20261103-0001",
                FindingWorkflowState.REPORTER_SUPPLEMENT_PENDING, 1, 8, 2);
        when(service.reviewDecision(any(), any(), any())).thenReturn(historical);
        when(service.supplement(any(), any(), any())).thenReturn(new FindingWorkflowContracts.CommandResult(FINDING,
                "YH-20261103-0001", FindingWorkflowState.PERSON_CONFIRM_PENDING, 1, 9, 3));
        for (int replay = 0; replay < 2; replay++) {
            mvc.perform(valid(REVIEW_PATH, REVIEW))
                    .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(header().doesNotExist("Location"))
                    .andExpect(jsonPath("$.data.findingId").value(FINDING.toString()))
                    .andExpect(jsonPath("$.data.code").value("YH-20261103-0001"))
                    .andExpect(jsonPath("$.data.state").value("REPORTER_SUPPLEMENT_PENDING"))
                    .andExpect(jsonPath("$.data.roundNo").value(1)).andExpect(jsonPath("$.data.version").value(8))
                    .andExpect(jsonPath("$.data.reviewCycle").value(2))
                    .andExpect(jsonPath("$.meta.requestId").isString()).andExpect(jsonPath("$.meta.traceId").isString())
                    .andExpect(jsonPath("$.meta.schemaVersion").value("1"))
                    .andExpect(jsonPath("$.data.statusUrl").doesNotExist()).andExpect(jsonPath("$.data.completed").doesNotExist());
        }
        mvc.perform(valid(SUPPLEMENT_PATH, SUPPLEMENT)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reviewCycle").value(3)).andExpect(jsonPath("$.data.version").value(9));
        verify(service, times(2)).reviewDecision(eq(KEY), eq(FINDING), argThat(c -> c.expectedVersion() == 7
                && c.reviewCycle() == 2 && c.stage() == FindingWorkflowContracts.ConfirmationStage.PERSON
                && c.decision() == FindingReviewCycleContracts.ReviewDecision.RETURN_FOR_INFORMATION
                && c.reason().equals("need detail") && c.grade() == null));
        verify(service).supplement(eq(KEY), eq(FINDING), argThat(c -> c.expectedVersion() == 8
                && c.reviewCycle() == 2 && c.statement().equals("detail")));
        verifyNoMoreInteractions(service);
    }
    @Test void rejectsRawQueriesNoncanonicalIdsRepeatedOrMissingKeysBeforeService() throws Exception {
        for (String path : new String[]{REVIEW_PATH, SUPPLEMENT_PATH}) {
            String body = path.equals(REVIEW_PATH) ? REVIEW : SUPPLEMENT;
            for (var request : new org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder[]{
                    valid(path + "?x=1", body), valid(path + "?%00=x", body), valid(path, body).queryParam("x", "1", "2"),
                    valid(path, body).header("Idempotency-Key", KEY),
                    valid(path.replace(FINDING.toString(), "4000000-0000-0000-0000-000000000001"), body),
                    post(path).with(authenticatedMember()).with(validCsrf()).contentType("application/json").content(body),
                    post(path).with(authenticatedMember()).with(validCsrf()).header("Idempotency-Key", KEY + "," + KEY).contentType("application/json").content(body)}) {
                mvc.perform(request).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                        .andExpect(header().string("Cache-Control", "no-store"));
            }
        }
        verifyNoInteractions(service);
    }
    @Test void authenticationAndCsrfRunBeforeParsingIncludingInvalidBodies() throws Exception {
        for (String path : new String[]{REVIEW_PATH, SUPPLEMENT_PATH}) {
            mvc.perform(post(path).with(validCsrf()).content("bad")).andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("AUTH_REQUIRED")).andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")));
            mvc.perform(post(path).with(authenticatedMember()).content("bad")).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("ACTION_DENIED")).andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")));
            mvc.perform(post(path).with(authenticatedMember()).with(validCsrf()).header("Idempotency-Key", KEY).contentType("application/x-www-form-urlencoded")
                    .content("reason=bad")).andExpect(status().isUnsupportedMediaType()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }
        verifyNoInteractions(service);
    }
    @Test void strictBodyFailuresUse400InvalidRequestAndNoStore() throws Exception {
        for (String body : new String[]{REVIEW.replace("}", ",\"grade\":null}"),
                REVIEW.replace("{", "{\"actorId\":null,"), REVIEW.replace(":7", ":7e0"),
                REVIEW.replace("need detail", "\\uD800"), REVIEW + "{}"}) {
            mvc.perform(valid(REVIEW_PATH, body)).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST")).andExpect(header().string("Cache-Control", "no-store"));
        }
        mvc.perform(valid(REVIEW_PATH, REVIEW).content(new byte[]{(byte)0xc3, (byte)0x28}))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mvc.perform(valid(REVIEW_PATH, " ".repeat(32769))).andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST")).andExpect(header().string("Cache-Control", "no-store"));
        verifyNoInteractions(service);
    }
    @Test void nullCharactersReturn400BeforeEitherCommandService() throws Exception {
        for (String value : new String[]{"\\u0000", "before\\u0000after"}) {
            mvc.perform(valid(REVIEW_PATH, REVIEW.replace("need detail", value)))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                    .andExpect(header().string("Cache-Control", "no-store"));
            mvc.perform(valid(SUPPLEMENT_PATH, SUPPLEMENT.replace("detail", value)))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                    .andExpect(header().string("Cache-Control", "no-store"));
        }
        verifyNoInteractions(service);
    }

    @Test void mapsDomainErrorsForBothRoutesWithoutLeakingInternalMessages() throws Exception {
        RuntimeException[] failures = {new UnauthenticatedSessionException(),
                new org.springframework.security.access.AccessDeniedException("private"), new FindingNotVisibleException(),
                new FindingWorkflowVersionConflictException(), new FindingWorkflowIdempotencyConflictException(),
                new com.mightyoung.softwarefactory.workflow.WorkflowTaskStateConflictException(),
                new FindingWorkflowRuleException("private"), new com.mightyoung.softwarefactory.workflow.WorkflowEngineUnavailableException(new IllegalStateException("private"))};
        int[] statuses = {401,403,404,409,409,409,422,503};
        String[] codes = {"AUTH_REQUIRED","ACTION_DENIED","RESOURCE_NOT_VISIBLE","VERSION_CONFLICT","IDEMPOTENCY_CONFLICT","COMMAND_CONFLICT","WORKFLOW_RULE","DEPENDENCY_UNAVAILABLE"};
        for (int i = 0; i < failures.length; i++) {
            when(service.reviewDecision(any(), any(), any())).thenThrow(failures[i]);
            when(service.supplement(any(), any(), any())).thenThrow(failures[i]);
            for (String path : new String[]{REVIEW_PATH, SUPPLEMENT_PATH}) {
                mvc.perform(valid(path, path.equals(REVIEW_PATH) ? REVIEW : SUPPLEMENT)).andExpect(status().is(statuses[i]))
                        .andExpect(jsonPath("$.code").value(codes[i])).andExpect(jsonPath("$.traceId").isString())
                        .andExpect(jsonPath("$.retryable").value(statuses[i] == 503))
                        .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private"))))
                        .andExpect(header().string("Cache-Control", "no-store"));
            }
            reset(service);
        }
    }
    @Test void neverInvokesServletParameterParsingEvenForFormMedia() {
        var request = new org.springframework.mock.web.MockHttpServletRequest() {
            @Override public java.util.Map<String,String[]> getParameterMap() { throw new AssertionError("Servlet form parsing"); }
            @Override public String getParameter(String name) { throw new AssertionError("Servlet form parsing"); }
            @Override public java.util.Enumeration<String> getParameterNames() { throw new AssertionError("Servlet form parsing"); }
            @Override public String[] getParameterValues(String name) { throw new AssertionError("Servlet form parsing"); }
        };
        request.setContentType("application/x-www-form-urlencoded"); request.addHeader("Idempotency-Key", KEY.toString());
        var controller = new FindingReviewCycleController(service, new FindingReviewCycleRequestParser(new tools.jackson.databind.ObjectMapper()));
        assertThatThrownBy(() -> controller.review(FINDING.toString(), request, new org.springframework.mock.web.MockHttpServletResponse()))
                .isInstanceOf(FindingReviewCycleRequestParser.RequestFailure.class);
        assertThatThrownBy(() -> controller.supplement(FINDING.toString(), request, new org.springframework.mock.web.MockHttpServletResponse()))
                .isInstanceOf(FindingReviewCycleRequestParser.RequestFailure.class);
        verifyNoInteractions(service);
    }
    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder valid(String path, String body) {
        return post(path).with(authenticatedMember()).with(validCsrf()).header("Idempotency-Key", KEY).contentType("application/json").content(body);
    }
    private static org.springframework.test.web.servlet.request.RequestPostProcessor authenticatedMember() {
        return request -> {
            var principal = VerifiedSessionPrincipalTestFixture.create(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1L, 1L);
            var context = org.springframework.security.core.context.SecurityContextHolder.createEmptyContext();
            context.setAuthentication(new org.springframework.security.authentication.TestingAuthenticationToken(principal, null, "ROLE_AUTHENTICATED"));
            request.getSession(true).setAttribute(org.springframework.security.web.context.HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
            VerifiedSessionPrincipalTestFixture.markAuthenticatedSession(request.getSession(false), java.time.Instant.now()); return request;
        };
    }
    private static org.springframework.test.web.servlet.request.RequestPostProcessor validCsrf() {
        return request -> { request.setCookies(new jakarta.servlet.http.Cookie("XSRF-TOKEN", "synthetic-csrf"));
            request.addHeader("X-XSRF-TOKEN", "synthetic-csrf"); return request; };
    }
}
