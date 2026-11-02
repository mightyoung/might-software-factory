package com.mightyoung.softwarefactory.dualprevention;

import com.mightyoung.softwarefactory.dualprevention.FindingWorkflowApiModels.PersonConfirmationRequest;
import com.mightyoung.softwarefactory.dualprevention.FindingWorkflowApiModels.SafetyConfirmationRequest;
import com.mightyoung.softwarefactory.dualprevention.finding.workflow.FindingWorkflowAccessPolicy;
import com.mightyoung.softwarefactory.dualprevention.finding.workflow.FindingWorkflowAssignmentResolver;
import com.mightyoung.softwarefactory.dualprevention.finding.workflow.FindingWorkflowCase;
import com.mightyoung.softwarefactory.dualprevention.finding.workflow.FindingWorkflowContracts.CommandClaim;
import com.mightyoung.softwarefactory.dualprevention.finding.workflow.FindingWorkflowContracts.CommandResult;
import com.mightyoung.softwarefactory.dualprevention.finding.workflow.FindingWorkflowContracts.Confirmation;
import com.mightyoung.softwarefactory.dualprevention.finding.workflow.FindingWorkflowContracts.InitialRoute;
import com.mightyoung.softwarefactory.dualprevention.finding.workflow.FindingWorkflowContracts.Notice;
import com.mightyoung.softwarefactory.dualprevention.finding.workflow.FindingWorkflowContracts.Submission;
import com.mightyoung.softwarefactory.dualprevention.finding.workflow.FindingWorkflowContracts.Transition;
import com.mightyoung.softwarefactory.dualprevention.finding.workflow.FindingWorkflowContracts.Verification;
import com.mightyoung.softwarefactory.dualprevention.finding.workflow.FindingWorkflowContracts.WorkflowCommand;
import com.mightyoung.softwarefactory.dualprevention.finding.workflow.FindingWorkflowEvidencePolicy;
import com.mightyoung.softwarefactory.dualprevention.finding.workflow.FindingWorkflowEventPublisher;
import com.mightyoung.softwarefactory.dualprevention.finding.workflow.FindingWorkflowRepository;
import com.mightyoung.softwarefactory.dualprevention.finding.workflow.FindingWorkflowCommandService;
import com.mightyoung.softwarefactory.dualprevention.finding.workflow.FindingWorkflowIdempotencyConflictException;
import com.mightyoung.softwarefactory.dualprevention.finding.workflow.FindingWorkflowVersionConflictException;
import com.mightyoung.softwarefactory.identity.CurrentTenantContext;
import com.mightyoung.softwarefactory.files.VerifiedFileEvidenceSnapshot;
import com.mightyoung.softwarefactory.identity.SecurityConfiguration;
import com.mightyoung.softwarefactory.identity.SessionContextResolver;
import com.mightyoung.softwarefactory.identity.VerifiedSessionPrincipalTestFixture;
import com.mightyoung.softwarefactory.workflow.WorkflowFacade;
import com.mightyoung.softwarefactory.workflow.WorkflowTaskStateConflictException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import jakarta.validation.Validator;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = FindingWorkflowController.class)
@Import({SecurityConfiguration.class, SessionContextResolver.class, FindingApiExceptionHandler.class,
        FindingWorkflowControllerHttpTest.TestDependencies.class})
class FindingWorkflowControllerHttpTest {

    private static final Instant TEST_INSTANT = Instant.parse("2026-10-30T16:30:00Z");
    private static final UUID ACTOR = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID ACCOUNT = UUID.fromString("11000000-0000-0000-0000-000000000001");
    private static final UUID TENANT = UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final UUID MEMBER = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID ORGANIZATION = UUID.fromString("31000000-0000-0000-0000-000000000001");
    private static final UUID IDEMPOTENCY_KEY = UUID.fromString("60000000-0000-0000-0000-000000000001");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private TestRepository repository;

    @Autowired
    private Validator validator;

    @BeforeEach
    void resetRepository() {
        repository.transactionCalls = 0;
        repository.nextTransactionFailure = null;
    }

    @Test
    void createRejectsClientCodeBeforeStartingACommand() throws Exception {
        mvc.perform(post("/api/v1/findings")
                        .with(authenticatedMember())
                        .with(validCsrf())
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType("application/json")
                        .content(validCreateRequest().replace("\"grade\":\"UNKNOWN\",",
                                "\"grade\":\"UNKNOWN\",\"code\":\"CLIENT-CODE\",")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_QUERY"))
                .andExpect(jsonPath("$.message").value("The finding request is invalid"))
                .andExpect(jsonPath("$.retryable").value(false))
                .andExpect(jsonPath("$.fieldErrors").isEmpty());

        assertThat(repository.transactionCalls).isZero();
    }

    @Test
    void createRejectsClientSuppliedBackfillFlag() throws Exception {
        mvc.perform(post("/api/v1/findings")
                        .with(authenticatedMember())
                        .with(validCsrf())
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType("application/json")
                        .content(validCreateRequest().replace("\"grade\":\"UNKNOWN\",",
                                "\"grade\":\"UNKNOWN\",\"backfill\":false,")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_QUERY"))
                .andExpect(jsonPath("$.fieldErrors").isEmpty());

        assertThat(repository.transactionCalls).isZero();
    }

    @Test
    void createReturnsFieldValidationForUnsupportedReportSource() throws Exception {
        mvc.perform(post("/api/v1/findings")
                        .with(authenticatedMember())
                        .with(validCsrf())
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType("application/json")
                        .content(validCreateRequest().replace("FIELD_OBSERVATION", "INTERNAL")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("sourceCategory"))
                .andExpect(jsonPath("$.fieldErrors[0].reason").value("Pattern"));

        assertThat(repository.transactionCalls).isZero();
    }

    @Test
    void createReportsStableUnicodeCodePointLengthErrorForLocation() throws Exception {
        String tooLongLocation = "场".repeat(201);
        String response = mvc.perform(post("/api/v1/findings")
                        .with(authenticatedMember())
                        .with(validCsrf())
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType("application/json")
                        .content(validCreateRequest().replace("North loading dock", tooLongLocation)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("location"))
                .andExpect(jsonPath("$.fieldErrors[0].reason").value("CodePointSize"))
                .andReturn().getResponse().getContentAsString();

        assertThat(response).doesNotContain(tooLongLocation);
        assertThat(repository.transactionCalls).isZero();
    }

    @Test
    void createRejectsUnicodeWhiteSpaceOnlyLocationWithPatternFieldError() throws Exception {
        mvc.perform(post("/api/v1/findings")
                        .with(authenticatedMember())
                        .with(validCsrf())
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType("application/json")
                        .content(validCreateRequest().replace("North loading dock", "\u00A0")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("location"))
                .andExpect(jsonPath("$.fieldErrors[0].reason").value("Pattern"));

        assertThat(repository.transactionCalls).isZero();
    }

    @Test
    void malformedJsonUsesGenericQueryErrorWithoutFieldDetails() throws Exception {
        mvc.perform(post("/api/v1/findings")
                        .with(authenticatedMember())
                        .with(validCsrf())
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType("application/json")
                        .content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_QUERY"))
                .andExpect(jsonPath("$.message").value("The finding request is invalid"))
                .andExpect(jsonPath("$.retryable").value(false))
                .andExpect(jsonPath("$.fieldErrors").isEmpty());

        assertThat(repository.transactionCalls).isZero();
    }

    @Test
    void createRejectsProfessionalGradesOverHttp() throws Exception {
        for (String grade : new String[] {"GENERAL", "MAJOR"}) {
            mvc.perform(post("/api/v1/findings")
                            .with(authenticatedMember())
                            .with(validCsrf())
                            .header("Idempotency-Key", IDEMPOTENCY_KEY)
                            .contentType("application/json")
                            .content(validCreateRequest().replace("UNKNOWN", grade)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                    .andExpect(jsonPath("$.fieldErrors[0].field").value("grade"))
                    .andExpect(jsonPath("$.fieldErrors[0].reason").value("Pattern"))
                    .andExpect(jsonPath("$.retryable").value(false));
        }
        assertThat(repository.transactionCalls).isZero();
    }

    @Test
    void fieldValidationReportsJsonFieldAndStableConstraintCodeWithoutEchoingRejectedValue() throws Exception {
        String rejectedValue = "SENSITIVE_REJECTED_VALUE".repeat(1000);
        String request = validCreateRequest().replace("Synthetic finding", rejectedValue);

        var result = mvc.perform(post("/api/v1/findings")
                        .with(authenticatedMember())
                        .with(validCsrf())
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType("application/json")
                        .content(request))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("description"))
                .andExpect(jsonPath("$.fieldErrors[0].reason").value("Size"))
                .andExpect(jsonPath("$.traceId").isString())
                .andExpect(jsonPath("$.retryable").value(false))
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).doesNotContain(rejectedValue);
        assertThat(repository.transactionCalls).isZero();
    }

    @Test
    void versionAndIdempotencyConflictsUseDistinct409CodesAndKeepTaskConflictsCompatible() throws Exception {
        repository.nextTransactionFailure = new FindingWorkflowIdempotencyConflictException();
        mvc.perform(post("/api/v1/findings")
                        .with(authenticatedMember())
                        .with(validCsrf())
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType("application/json")
                        .content(validCreateRequest()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"))
                .andExpect(jsonPath("$.traceId").isString())
                .andExpect(jsonPath("$.retryable").value(false))
                .andExpect(jsonPath("$.fieldErrors").isEmpty());

        repository.nextTransactionFailure = new FindingWorkflowVersionConflictException();
        mvc.perform(post("/api/v1/findings/40000000-0000-0000-0000-000000000002/confirm/person")
                        .with(authenticatedMember())
                        .with(validCsrf())
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType("application/json")
                        .content("{\"expectedVersion\":1,\"reason\":\"Reviewed\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"))
                .andExpect(jsonPath("$.traceId").isString())
                .andExpect(jsonPath("$.retryable").value(false))
                .andExpect(jsonPath("$.fieldErrors").isEmpty());

        repository.nextTransactionFailure = new WorkflowTaskStateConflictException();
        mvc.perform(post("/api/v1/findings/40000000-0000-0000-0000-000000000002/confirm/person")
                        .with(authenticatedMember())
                        .with(validCsrf())
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType("application/json")
                        .content("{\"expectedVersion\":1,\"reason\":\"Reviewed\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("COMMAND_CONFLICT"));
    }

    @Test
    void unauthenticatedAndCsrfDeniedResponsesKeepGenericSecurityEnvelope() throws Exception {
        mvc.perform(post("/api/v1/findings")
                        .with(validCsrf())
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType("application/json")
                        .content(validCreateRequest()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"))
                .andExpect(jsonPath("$.message").value("A verified session is required"))
                .andExpect(jsonPath("$.traceId").isString())
                .andExpect(jsonPath("$.retryable").value(false))
                .andExpect(jsonPath("$.fieldErrors").isEmpty());

        mvc.perform(post("/api/v1/findings")
                        .with(authenticatedMember())
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType("application/json")
                        .content(validCreateRequest()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACTION_DENIED"))
                .andExpect(jsonPath("$.message").value("The request is not allowed"))
                .andExpect(jsonPath("$.traceId").isString())
                .andExpect(jsonPath("$.retryable").value(false))
                .andExpect(jsonPath("$.fieldErrors").isEmpty());
        assertThat(repository.transactionCalls).isZero();
    }

    @Test
    void dependencyFailuresStayGenericRetryable503Responses() throws Exception {
        repository.nextTransactionFailure = new DataAccessResourceFailureException("internal SQL failure");

        var result = mvc.perform(post("/api/v1/findings")
                        .with(authenticatedMember())
                        .with(validCsrf())
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType("application/json")
                        .content(validCreateRequest()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("DEPENDENCY_UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value("The finding command could not be completed safely"))
                .andExpect(jsonPath("$.traceId").isString())
                .andExpect(jsonPath("$.retryable").value(true))
                .andExpect(jsonPath("$.fieldErrors").isEmpty())
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).doesNotContain("internal SQL failure");
    }

    @Test
    void validUnknownGradeRequestReturnsTheServerAssignedCode() throws Exception {
        mvc.perform(post("/api/v1/findings")
                        .with(authenticatedMember())
                        .with(validCsrf())
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType("application/json")
                        .content(validCreateRequest()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.findingId").isString())
                .andExpect(jsonPath("$.data.code").value("YH-20261031-0001"));
        assertThat(repository.transactionCalls).isEqualTo(1);
    }

    @Test
    void personConfirmationRejectsSafetyOnlyGradeField() throws Exception {
        mvc.perform(post("/api/v1/findings/40000000-0000-0000-0000-000000000002/confirm/person")
                        .with(authenticatedMember())
                        .with(validCsrf())
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType("application/json")
                        .content("{\"expectedVersion\":1,\"reason\":\"Reviewed\",\"grade\":\"GENERAL\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_QUERY"));

        assertThat(repository.transactionCalls).isZero();
    }

    @Test
    void personConfirmationRequiresNonblankOpinion() throws Exception {
        for (String body : new String[] {
                "{\"expectedVersion\":1}",
                "{\"expectedVersion\":1,\"reason\":\"   \"}",
                "{\"expectedVersion\":1,\"reason\":\"" + "x".repeat(4001) + "\"}",
                "{\"expectedVersion\":1,\"reason\":\" " + "x".repeat(3999) + " \"}"
        }) {
            mvc.perform(post("/api/v1/findings/40000000-0000-0000-0000-000000000002/confirm/person")
                            .with(authenticatedMember())
                            .with(validCsrf())
                            .header("Idempotency-Key", IDEMPOTENCY_KEY)
                            .contentType("application/json")
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }

        assertThat(repository.transactionCalls).isZero();
    }

    @Test
    void confirmationOpinionApiLimitIncludesExactly4000RawCharacters() {
        PersonConfirmationRequest exactLimit = new PersonConfirmationRequest(1L, "x".repeat(4000));
        PersonConfirmationRequest rawOverLimit = new PersonConfirmationRequest(1L, " " + "x".repeat(3999) + " ");
        SafetyConfirmationRequest exactSafetyLimit = new SafetyConfirmationRequest(1L, "x".repeat(4000), "MAJOR");
        SafetyConfirmationRequest rawSafetyOverLimit = new SafetyConfirmationRequest(
                1L, " " + "x".repeat(3999) + " ", "MAJOR");

        assertThat(validator.validate(exactLimit)).isEmpty();
        assertThat(validator.validate(rawOverLimit))
                .anySatisfy(violation -> assertThat(violation.getPropertyPath().toString()).isEqualTo("reason"));
        assertThat(validator.validate(exactSafetyLimit)).isEmpty();
        assertThat(validator.validate(rawSafetyOverLimit))
                .anySatisfy(violation -> assertThat(violation.getPropertyPath().toString()).isEqualTo("reason"));
    }

    @Test
    void safetyConfirmationRequiresNonblankOpinionAndProfessionalGrade() throws Exception {
        for (String body : new String[] {
                "{\"expectedVersion\":1,\"reason\":\"Reviewed\"}",
                "{\"expectedVersion\":1,\"reason\":\" \" ,\"grade\":\"GENERAL\"}",
                "{\"expectedVersion\":1,\"reason\":\"Reviewed\",\"grade\":\"UNKNOWN\"}",
                "{\"expectedVersion\":1,\"reason\":\"Reviewed\",\"grade\":\"INVALID\"}"
        }) {
            mvc.perform(post("/api/v1/findings/40000000-0000-0000-0000-000000000002/confirm/safety")
                            .with(authenticatedMember())
                            .with(validCsrf())
                            .header("Idempotency-Key", IDEMPOTENCY_KEY)
                            .contentType("application/json")
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }

        assertThat(repository.transactionCalls).isZero();
    }

    @Test
    void personConfirmationRejectsArbitraryUnknownFieldsWithStableQueryError() throws Exception {
        mvc.perform(post("/api/v1/findings/40000000-0000-0000-0000-000000000002/confirm/person")
                        .with(authenticatedMember())
                        .with(validCsrf())
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType("application/json")
                        .content("{\"expectedVersion\":1,\"reason\":\"Reviewed\",\"note\":\"unknown\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_QUERY"))
                .andExpect(jsonPath("$.traceId").isString())
                .andExpect(jsonPath("$.retryable").value(false))
                .andExpect(jsonPath("$.fieldErrors").isEmpty());

        assertThat(repository.transactionCalls).isZero();
    }

    private static String validCreateRequest() {
        return "{\"organizationId\":\"" + ORGANIZATION + "\",\"description\":\"Synthetic finding\","
                + "\"discoveredAt\":\"2026-10-31T00:30:00+08:00\",\"grade\":\"UNKNOWN\","
                + "\"sourceCategory\":\"FIELD_OBSERVATION\",\"urgency\":\"NORMAL\","
                + "\"location\":\"North loading dock\",\"interimMeasure\":\"Temporary barrier installed\"}";
    }

    private static RequestPostProcessor authenticatedMember() {
        return request -> {
            var principal = VerifiedSessionPrincipalTestFixture.create(ACTOR, ACCOUNT, TENANT, MEMBER, 1L, 1L);
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(new TestingAuthenticationToken(principal, null, "ROLE_AUTHENTICATED"));
            request.setAttribute("SPRING_SECURITY_CONTEXT", context);
            request.getSession(true).setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
            VerifiedSessionPrincipalTestFixture.markAuthenticatedSession(request.getSession(false), TEST_INSTANT);
            return request;
        };
    }

    private static RequestPostProcessor validCsrf() {
        return request -> {
            request.setCookies(new jakarta.servlet.http.Cookie("XSRF-TOKEN", "synthetic-csrf"));
            request.addHeader("X-XSRF-TOKEN", "synthetic-csrf");
            return request;
        };
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestDependencies {
        // This HTTP slice supplies identity state explicitly; persisted revocation is tested separately.
        @Bean
        com.mightyoung.softwarefactory.identity.AccountStatusReadRepository accountSecurityStates() {
            return account -> java.util.Optional.of(
                    new com.mightyoung.softwarefactory.identity.AccountSecurityState("ACTIVE", 1));
        }

        @Bean
        AuthenticationProvider localPasswordAuthenticationProvider() {
            return new AuthenticationProvider() {
                @Override
                public org.springframework.security.core.Authentication authenticate(
                        org.springframework.security.core.Authentication authentication) {
                    return null;
                }
                @Override
                public boolean supports(Class<?> authentication) { return false; }
            };
        }

        @Bean
        TestRepository findingWorkflowRepository() {
            return new TestRepository();
        }

        @Bean
        FindingWorkflowAccessPolicy findingWorkflowAccessPolicy() {
            return new FindingWorkflowAccessPolicy() {
                @Override
                public void requireAction(CurrentTenantContext context, String permissionCode, UUID organizationId) { }
                @Override
                public void requireAssignableMember(
                        CurrentTenantContext context, UUID memberId, UUID organizationId, String permissionCode) { }
            };
        }

        @Bean
        FindingWorkflowAssignmentResolver findingWorkflowAssignmentResolver() {
            return new FindingWorkflowAssignmentResolver() {
                @Override
                public InitialRoute resolve(UUID tenantId, UUID organizationId) {
                    return new InitialRoute(
                            UUID.fromString("20000000-0000-0000-0000-000000000002"),
                            UUID.fromString("20000000-0000-0000-0000-000000000003"),
                            UUID.fromString("20000000-0000-0000-0000-000000000004"),
                            UUID.fromString("20000000-0000-0000-0000-000000000005"));
                }
                @Override
                public UUID resolveSafetyDispatcher(UUID tenantId, UUID organizationId) { return MEMBER; }
            };
        }

        @Bean
        FindingWorkflowEvidencePolicy findingWorkflowEvidencePolicy() {
            return new FindingWorkflowEvidencePolicy() {
                @Override
                public List<VerifiedFileEvidenceSnapshot> requireReady(
                        CurrentTenantContext context, UUID findingId, int roundNo, UUID evidenceSetId) {
                    return List.of();
                }
                @Override
                public void requirePinnedEvidence(List<VerifiedFileEvidenceSnapshot> evidenceSnapshots) { }
            };
        }

        @Bean
        WorkflowFacade workflowFacade() {
            return new WorkflowFacade() {
                @Override
                public void startProcess(UUID tenantId, String businessType, UUID businessId, int businessRevision,
                                         String processDefinitionKey, java.util.Map<String, Object> variables) { }
                @Override
                public void completeTask(UUID tenantId, String businessType, UUID businessId,
                                         String taskDefinitionKey, UUID actorMemberId,
                                         java.util.Map<String, Object> variables) { }
            };
        }

        @Bean
        FindingWorkflowEventPublisher findingWorkflowEventPublisher() {
            return event -> { };
        }

        @Bean
        @Primary
        Clock findingWorkflowClock() {
            return Clock.fixed(TEST_INSTANT, ZoneOffset.UTC);
        }

        @Bean
        FindingWorkflowCommandService findingWorkflowCommandService(
                SessionContextResolver resolver,
                FindingWorkflowRepository repository,
                FindingWorkflowAccessPolicy accessPolicy,
                FindingWorkflowAssignmentResolver assignmentResolver,
                FindingWorkflowEvidencePolicy evidencePolicy,
                WorkflowFacade workflowFacade,
                FindingWorkflowEventPublisher eventPublisher,
                Clock clock) {
            return new FindingWorkflowCommandService(resolver, repository, accessPolicy, assignmentResolver,
                    evidencePolicy, workflowFacade, eventPublisher, clock);
        }
    }

    static final class TestRepository implements FindingWorkflowRepository {
        private final Transaction transaction = new Transaction() {
            @Override
            public Optional<FindingWorkflowCase> findForUpdate(UUID findingId) { return Optional.empty(); }
            @Override
            public void insertFinding(FindingWorkflowCase finding) { }
            @Override
            public String nextFindingCode(LocalDate businessDay) { return "YH-20261031-0001"; }
            @Override
            public void updateFinding(FindingWorkflowCase finding, long expectedVersion) { }
            @Override
            public CommandClaim claimCommand(WorkflowCommand command) { return CommandClaim.newClaim(); }
            @Override
            public void completeCommand(UUID commandId, CommandResult result) { }
            @Override
            public void appendTransition(Transition transition) { }
            @Override
            public void appendConfirmation(Confirmation confirmation) { }
            @Override
            public Optional<UUID> findConfirmationActorMemberId(UUID findingId, String stage) {
                return Optional.empty();
            }
            @Override
            public void openRound(UUID findingId, int roundNo, UUID commandId) { }
            @Override
            public void closeRound(UUID findingId, int roundNo, String state, UUID commandId) { }
            @Override
            public Optional<Notice> findNotice(UUID findingId, int roundNo) { return Optional.empty(); }
            @Override
            public void insertNotice(Notice notice, UUID commandId) { }
            @Override
            public void insertSubmission(Submission submission) { }
            @Override
            public Optional<Submission> findSubmission(UUID findingId, int roundNo) { return Optional.empty(); }
            @Override
            public void insertVerification(Verification verification) { }
        };
        private int transactionCalls;
        private RuntimeException nextTransactionFailure;

        @Override
        public <T> T inTenantTransaction(UUID tenantId, Function<Transaction, T> work) {
            transactionCalls++;
            if (nextTransactionFailure != null) {
                RuntimeException failure = nextTransactionFailure;
                nextTransactionFailure = null;
                throw failure;
            }
            return work.apply(transaction);
        }
    }
}
