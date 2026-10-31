package com.mightyoung.softwarefactory.dualprevention;

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
import com.mightyoung.softwarefactory.identity.CurrentTenantContext;
import com.mightyoung.softwarefactory.files.VerifiedFileEvidenceSnapshot;
import com.mightyoung.softwarefactory.identity.SecurityConfiguration;
import com.mightyoung.softwarefactory.identity.SessionContextResolver;
import com.mightyoung.softwarefactory.identity.VerifiedSessionPrincipalTestFixture;
import com.mightyoung.softwarefactory.workflow.WorkflowFacade;
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

    @BeforeEach
    void resetRepository() {
        repository.transactionCalls = 0;
    }

    @Test
    void createRejectsClientCodeBeforeStartingACommand() throws Exception {
        mvc.perform(post("/api/v1/findings")
                        .with(authenticatedMember())
                        .with(validCsrf())
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType("application/json")
                        .content(validCreateRequest().replace("\"grade\":\"UNKNOWN\"}",
                                "\"grade\":\"UNKNOWN\",\"code\":\"CLIENT-CODE\"}")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_QUERY"));

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
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }
        assertThat(repository.transactionCalls).isZero();
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
    void unknownFieldsRemainScopedToFindingCreateAndDoNotChangeOtherRequestDtos() throws Exception {
        mvc.perform(post("/api/v1/findings/40000000-0000-0000-0000-000000000002/confirm/person")
                        .with(authenticatedMember())
                        .with(validCsrf())
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType("application/json")
                        .content("{\"expectedVersion\":1,\"note\":\"ignored by legacy DTO binding\"}"))
                .andExpect(status().isNotFound());
    }

    private static String validCreateRequest() {
        return "{\"organizationId\":\"" + ORGANIZATION + "\",\"description\":\"Synthetic finding\","
                + "\"discoveredAt\":\"2026-10-31T00:30:00+08:00\",\"grade\":\"UNKNOWN\"}";
    }

    private static RequestPostProcessor authenticatedMember() {
        return request -> {
            var principal = VerifiedSessionPrincipalTestFixture.create(ACTOR, ACCOUNT, TENANT, MEMBER, 1L, 1L);
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(new TestingAuthenticationToken(principal, null, "ROLE_AUTHENTICATED"));
            request.setAttribute("SPRING_SECURITY_CONTEXT", context);
            request.getSession(true).setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
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
                    return new InitialRoute(MEMBER, MEMBER, MEMBER, MEMBER);
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
            return Clock.fixed(Instant.parse("2026-10-30T16:30:00Z"), ZoneOffset.UTC);
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

        @Override
        public <T> T inTenantTransaction(UUID tenantId, Function<Transaction, T> work) {
            transactionCalls++;
            return work.apply(transaction);
        }
    }
}
