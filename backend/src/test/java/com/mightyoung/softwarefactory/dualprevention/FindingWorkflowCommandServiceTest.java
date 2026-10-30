package com.mightyoung.softwarefactory.dualprevention;

import com.mightyoung.softwarefactory.dualprevention.finding.workflow.FindingWorkflowAccessPolicy;
import com.mightyoung.softwarefactory.dualprevention.finding.workflow.FindingWorkflowAssignmentResolver;
import com.mightyoung.softwarefactory.dualprevention.finding.workflow.FindingWorkflowCase;
import com.mightyoung.softwarefactory.dualprevention.finding.workflow.FindingWorkflowCommandService;
import com.mightyoung.softwarefactory.dualprevention.finding.workflow.FindingWorkflowConfigurationException;
import com.mightyoung.softwarefactory.dualprevention.finding.workflow.FindingWorkflowContracts;
import com.mightyoung.softwarefactory.dualprevention.finding.workflow.FindingWorkflowEvidencePolicy;
import com.mightyoung.softwarefactory.dualprevention.finding.workflow.FindingWorkflowEvidenceException;
import com.mightyoung.softwarefactory.dualprevention.finding.workflow.FindingWorkflowIdempotencyConflictException;
import com.mightyoung.softwarefactory.dualprevention.finding.workflow.FindingWorkflowChangedEvent;
import com.mightyoung.softwarefactory.dualprevention.finding.workflow.FindingWorkflowRepository;
import com.mightyoung.softwarefactory.dualprevention.finding.workflow.FindingWorkflowState;
import com.mightyoung.softwarefactory.dualprevention.finding.workflow.FindingWorkflowVersionConflictException;
import com.mightyoung.softwarefactory.dualprevention.finding.workflow.VerificationDecision;
import com.mightyoung.softwarefactory.identity.CurrentTenantContext;
import com.mightyoung.softwarefactory.identity.SessionContextResolver;
import com.mightyoung.softwarefactory.identity.VerifiedSessionPrincipalTestFixture;
import com.mightyoung.softwarefactory.workflow.WorkflowFacade;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FindingWorkflowCommandServiceTest {

    private static final UUID ACTOR_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID TENANT_A = UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final UUID TENANT_B = UUID.fromString("30000000-0000-0000-0000-000000000002");
    private static final UUID ORGANIZATION_ID = UUID.fromString("31000000-0000-0000-0000-000000000001");
    private static final UUID CREATOR = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID PERSON_CONFIRMER = UUID.fromString("20000000-0000-0000-0000-000000000002");
    private static final UUID SAFETY_MEMBER = UUID.fromString("20000000-0000-0000-0000-000000000003");
    private static final UUID REMEDIATOR = UUID.fromString("20000000-0000-0000-0000-000000000004");
    private static final UUID REPLACEMENT_REMEDIATOR = UUID.fromString("20000000-0000-0000-0000-000000000005");
    private static final UUID EVIDENCE_SET = UUID.fromString("50000000-0000-0000-0000-000000000001");
    private static final UUID EVIDENCE_SET_REWORK = UUID.fromString("50000000-0000-0000-0000-000000000002");
    private static final OffsetDateTime DISCOVERED_AT = OffsetDateTime.parse("2026-10-30T10:00:00Z");
    private static final OffsetDateTime DUE_AT = OffsetDateTime.parse("2999-11-01T10:00:00Z");

    private final MemoryRepository repository = new MemoryRepository();
    private final MemoryAccessPolicy accessPolicy = new MemoryAccessPolicy();
    private final MemoryAssignmentResolver assignmentResolver = new MemoryAssignmentResolver();
    private final MemoryEvidencePolicy evidencePolicy = new MemoryEvidencePolicy();
    private final MemoryWorkflowFacade workflow = new MemoryWorkflowFacade();
    private final List<FindingWorkflowChangedEvent> publishedEvents = new ArrayList<>();
    private FindingWorkflowCommandService service;

    @BeforeEach
    void setUp() {
        service = new FindingWorkflowCommandService(new SessionContextResolver(), repository,
                accessPolicy, assignmentResolver, evidencePolicy, workflow, publishedEvents::add);
        asMember(CREATOR, TENANT_A);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void findingRunsThroughBothConfirmationsDispatchReworkAndFinalAcceptance() {
        FindingWorkflowContracts.CommandResult created = service.create(
                command("60000000-0000-0000-0000-000000000001"), newFinding());
        UUID findingId = created.findingId();
        evidencePolicy.allow(EVIDENCE_SET, TENANT_A, findingId, 1, REMEDIATOR);
        assertThat(created.state()).isEqualTo(FindingWorkflowState.PERSON_CONFIRM_PENDING);

        asMember(PERSON_CONFIRMER, TENANT_A);
        FindingWorkflowContracts.CommandResult safetyPending = service.confirmPerson(
                command("60000000-0000-0000-0000-000000000002"), findingId, 1L);
        assertThat(safetyPending.state()).isEqualTo(FindingWorkflowState.SAFETY_CONFIRM_PENDING);

        asMember(SAFETY_MEMBER, TENANT_A);
        FindingWorkflowContracts.CommandResult dispatchPending = service.confirmSafety(
                command("60000000-0000-0000-0000-000000000003"), findingId, 2L);
        assertThat(dispatchPending.state()).isEqualTo(FindingWorkflowState.SAFETY_DISPATCH_PENDING);

        FindingWorkflowContracts.CommandResult rectifying = service.dispatch(
                command("60000000-0000-0000-0000-000000000004"), findingId, 3L,
                new FindingWorkflowContracts.Dispatch(REMEDIATOR, DUE_AT, "Replace damaged guard"));
        assertThat(rectifying.state()).isEqualTo(FindingWorkflowState.RECTIFYING);
        assertThat(repository.notices).hasSize(1);

        asMember(REMEDIATOR, TENANT_A);
        FindingWorkflowContracts.CommandResult acceptance = service.submitRemediation(
                command("60000000-0000-0000-0000-000000000005"), findingId, 4L,
                new FindingWorkflowContracts.SubmitRemediation(EVIDENCE_SET, "Guard replaced"));
        assertThat(acceptance.state()).isEqualTo(FindingWorkflowState.ACCEPTANCE_PENDING);

        accessPolicy.inactiveMembers.add(REMEDIATOR);
        asMember(SAFETY_MEMBER, TENANT_A);
        FindingWorkflowContracts.CommandResult returned = service.verify(
                command("60000000-0000-0000-0000-000000000006"), findingId, 5L,
                new FindingWorkflowContracts.Verify(VerificationDecision.FAIL, "Gap remains"));
        assertThat(returned.state()).isEqualTo(FindingWorkflowState.RECTIFYING);
        assertThat(returned.roundNo()).isEqualTo(2);
        assertThat(repository.notices).hasSize(1);
        assertThat(repository.transitions).hasSize(6);
        assertThat(repository.verifications).hasSize(1);

        FindingWorkflowContracts.CommandResult redispatched = service.dispatch(
                command("60000000-0000-0000-0000-000000000009"), findingId, 6L,
                new FindingWorkflowContracts.Dispatch(REPLACEMENT_REMEDIATOR, DUE_AT, "Replace the guard"));
        assertThat(redispatched.state()).isEqualTo(FindingWorkflowState.RECTIFYING);
        assertThat(redispatched.roundNo()).isEqualTo(2);
        assertThat(repository.notices).hasSize(2);
        evidencePolicy.allow(EVIDENCE_SET_REWORK, TENANT_A, findingId, 2, REPLACEMENT_REMEDIATOR);

        asMember(REPLACEMENT_REMEDIATOR, TENANT_A);
        FindingWorkflowContracts.CommandResult acceptanceAgain = service.submitRemediation(
                command("60000000-0000-0000-0000-000000000007"), findingId, 7L,
                new FindingWorkflowContracts.SubmitRemediation(EVIDENCE_SET_REWORK, "Gap corrected"));
        assertThat(acceptanceAgain.state()).isEqualTo(FindingWorkflowState.ACCEPTANCE_PENDING);

        asMember(SAFETY_MEMBER, TENANT_A);
        FindingWorkflowContracts.CommandResult closed = service.verify(
                command("60000000-0000-0000-0000-000000000008"), findingId, 8L,
                new FindingWorkflowContracts.Verify(VerificationDecision.PASS, null));
        assertThat(closed.state()).isEqualTo(FindingWorkflowState.CLOSED);
        assertThat(closed.roundNo()).isEqualTo(2);
        assertThat(repository.submissions).hasSize(2);
        assertThat(repository.verifications).hasSize(2);
        assertThat(publishedEvents).extracting(FindingWorkflowChangedEvent::toState)
                .containsExactly(
                        FindingWorkflowState.PERSON_CONFIRM_PENDING,
                        FindingWorkflowState.SAFETY_CONFIRM_PENDING,
                        FindingWorkflowState.SAFETY_DISPATCH_PENDING,
                        FindingWorkflowState.RECTIFYING,
                        FindingWorkflowState.ACCEPTANCE_PENDING,
                        FindingWorkflowState.RECTIFYING,
                        FindingWorkflowState.RECTIFYING,
                        FindingWorkflowState.ACCEPTANCE_PENDING,
                        FindingWorkflowState.CLOSED);
        assertThat(publishedEvents).extracting(FindingWorkflowChangedEvent::roundNo)
                .containsExactly(1, 1, 1, 1, 1, 2, 2, 2, 2);
        assertThat(publishedEvents).allSatisfy(event -> {
            assertThat(event.tenantId()).isEqualTo(TENANT_A);
            assertThat(event.findingId()).isEqualTo(findingId);
        });
        assertThat(workflow.completedTasks).containsExactly(
                "personConfirm", "safetyConfirm", "safetyDispatch", "remediation",
                "safetyVerification", "safetyDispatch", "remediation", "safetyVerification");
    }

    @Test
    void replayReturnsOriginalResultAndSameKeyWithDifferentPayloadConflicts() {
        UUID key = command("60000000-0000-0000-0000-000000000011");
        FindingWorkflowContracts.CreateFinding request = newFinding();
        FindingWorkflowContracts.CommandResult first = service.create(key, request);
        FindingWorkflowContracts.CommandResult replay = service.create(key, request);

        assertThat(replay).isEqualTo(first);
        assertThat(repository.findingCount()).isEqualTo(1);
        assertThat(workflow.startedProcesses).hasSize(1);
        assertThat(publishedEvents).hasSize(1);

        FindingWorkflowContracts.CreateFinding different = new FindingWorkflowContracts.CreateFinding(
                ORGANIZATION_ID, "FND-TEST-02", "Changed request", DISCOVERED_AT, null, null, "GENERAL");
        assertThatThrownBy(() -> service.create(key, different))
                .isInstanceOf(FindingWorkflowIdempotencyConflictException.class);
        assertThat(repository.findingCount()).isEqualTo(1);
    }

    @Test
    void missingAssignmentConfigurationFailsWithoutCreatingAFindingOrStartingAProcess() {
        assignmentResolver.route = null;

        assertThatThrownBy(() -> service.create(
                command("60000000-0000-0000-0000-000000000012"), newFinding()))
                .isInstanceOf(FindingWorkflowConfigurationException.class);

        assertThat(repository.findingCount()).isZero();
        assertThat(workflow.startedProcesses).isEmpty();
        assertThat(repository.transitions).isEmpty();
    }

    @Test
    void missingEvidenceCannotAdvanceAndFailureRollsBackBusinessWrites() {
        FindingWorkflowContracts.CommandResult created = service.create(
                command("60000000-0000-0000-0000-000000000021"), newFinding());
        UUID findingId = created.findingId();
        asMember(PERSON_CONFIRMER, TENANT_A);
        service.confirmPerson(command("60000000-0000-0000-0000-000000000022"), findingId, 1L);
        asMember(SAFETY_MEMBER, TENANT_A);
        service.confirmSafety(command("60000000-0000-0000-0000-000000000023"), findingId, 2L);
        service.dispatch(command("60000000-0000-0000-0000-000000000024"), findingId, 3L,
                new FindingWorkflowContracts.Dispatch(REMEDIATOR, DUE_AT, "Repair"));
        evidencePolicy.allow(EVIDENCE_SET, TENANT_A, findingId, 1, REMEDIATOR);
        FindingWorkflowContracts.SubmitRemediation missingEvidence =
                new FindingWorkflowContracts.SubmitRemediation(null, "No evidence");

        asMember(REMEDIATOR, TENANT_A);
        assertThatThrownBy(() -> service.submitRemediation(
                command("60000000-0000-0000-0000-000000000025"), findingId, 4L, missingEvidence))
                .isInstanceOf(FindingWorkflowEvidenceException.class);
        assertThat(repository.finding(TENANT_A, findingId).orElseThrow().state())
                .isEqualTo(FindingWorkflowState.RECTIFYING);
        assertThat(repository.submissions).isEmpty();

        workflow.failNextCompletion = true;
        assertThatThrownBy(() -> service.submitRemediation(
                command("60000000-0000-0000-0000-000000000026"), findingId, 4L,
                new FindingWorkflowContracts.SubmitRemediation(EVIDENCE_SET, "Evidence present")))
                .isInstanceOf(IllegalStateException.class);
        assertThat(repository.finding(TENANT_A, findingId).orElseThrow().state())
                .isEqualTo(FindingWorkflowState.RECTIFYING);
        assertThat(repository.submissions).isEmpty();
        assertThat(repository.transitions).hasSize(4);
        assertThat(publishedEvents).hasSize(4);
    }

    @Test
    void currentPermissionAndTenantScopeAreCheckedForEveryCommand() {
        FindingWorkflowContracts.CommandResult created = service.create(
                command("60000000-0000-0000-0000-000000000031"), newFinding());
        UUID findingId = created.findingId();

        accessPolicy.allowed = false;
        asMember(PERSON_CONFIRMER, TENANT_A);
        assertThatThrownBy(() -> service.confirmPerson(
                command("60000000-0000-0000-0000-000000000032"), findingId, 1L))
                .isInstanceOf(AccessDeniedException.class);
        accessPolicy.allowed = true;

        asMember(PERSON_CONFIRMER, TENANT_B);
        assertThatThrownBy(() -> service.confirmPerson(
                command("60000000-0000-0000-0000-000000000033"), findingId, 1L))
                .isInstanceOf(FindingNotVisibleException.class);
        assertThat(repository.finding(TENANT_A, findingId).orElseThrow().state())
                .isEqualTo(FindingWorkflowState.PERSON_CONFIRM_PENDING);
    }

    @Test
    void remediationSubmitterCannotVerifyTheirOwnEvidence() {
        FindingWorkflowContracts.CommandResult created = service.create(
                command("60000000-0000-0000-0000-000000000041"), newFinding());
        UUID findingId = created.findingId();
        evidencePolicy.allow(EVIDENCE_SET, TENANT_A, findingId, 1, REMEDIATOR);
        asMember(PERSON_CONFIRMER, TENANT_A);
        service.confirmPerson(command("60000000-0000-0000-0000-000000000042"), findingId, 1L);
        asMember(SAFETY_MEMBER, TENANT_A);
        service.confirmSafety(command("60000000-0000-0000-0000-000000000043"), findingId, 2L);
        service.dispatch(command("60000000-0000-0000-0000-000000000044"), findingId, 3L,
                new FindingWorkflowContracts.Dispatch(REMEDIATOR, DUE_AT, "Repair"));
        asMember(REMEDIATOR, TENANT_A);
        service.submitRemediation(command("60000000-0000-0000-0000-000000000045"), findingId, 4L,
                new FindingWorkflowContracts.SubmitRemediation(EVIDENCE_SET, "Work complete"));

        assertThatThrownBy(() -> service.verify(command("60000000-0000-0000-0000-000000000046"),
                findingId, 5L, new FindingWorkflowContracts.Verify(VerificationDecision.PASS, null)))
                .isInstanceOf(AccessDeniedException.class);

        assertThat(repository.finding(TENANT_A, findingId).orElseThrow().state())
                .isEqualTo(FindingWorkflowState.ACCEPTANCE_PENDING);
        assertThat(repository.verifications).isEmpty();
    }

    @Test
    void evidenceMustBelongToTheCurrentFindingRoundAndSubmitter() {
        FindingWorkflowContracts.CommandResult created = service.create(
                command("60000000-0000-0000-0000-000000000051"), newFinding());
        UUID findingId = created.findingId();
        asMember(PERSON_CONFIRMER, TENANT_A);
        service.confirmPerson(command("60000000-0000-0000-0000-000000000052"), findingId, 1L);
        asMember(SAFETY_MEMBER, TENANT_A);
        service.confirmSafety(command("60000000-0000-0000-0000-000000000053"), findingId, 2L);
        service.dispatch(command("60000000-0000-0000-0000-000000000054"), findingId, 3L,
                new FindingWorkflowContracts.Dispatch(REMEDIATOR, DUE_AT, "Repair"));
        asMember(REMEDIATOR, TENANT_A);

        evidencePolicy.allow(EVIDENCE_SET, TENANT_A, UUID.randomUUID(), 1, REMEDIATOR);
        assertRejectedEvidence(findingId, "60000000-0000-0000-0000-000000000055");
        evidencePolicy.allow(EVIDENCE_SET, TENANT_A, findingId, 2, REMEDIATOR);
        assertRejectedEvidence(findingId, "60000000-0000-0000-0000-000000000056");
        evidencePolicy.allow(EVIDENCE_SET, TENANT_A, findingId, 1, REPLACEMENT_REMEDIATOR);
        assertRejectedEvidence(findingId, "60000000-0000-0000-0000-000000000057");

        evidencePolicy.allow(EVIDENCE_SET, TENANT_A, findingId, 1, REMEDIATOR);
        FindingWorkflowContracts.CommandResult accepted = service.submitRemediation(
                command("60000000-0000-0000-0000-000000000058"), findingId, 4L,
                new FindingWorkflowContracts.SubmitRemediation(EVIDENCE_SET, "Work complete"));
        assertThat(accepted.state()).isEqualTo(FindingWorkflowState.ACCEPTANCE_PENDING);
    }

    @Test
    void legacyDiscoveredFindingIsInitializedIndividuallyWithoutSkippingPersonApproval() {
        UUID findingId = UUID.fromString("40000000-0000-0000-0000-000000000099");
        FindingWorkflowCase legacy = new FindingWorkflowCase(TENANT_A, findingId, ORGANIZATION_ID,
                "FND-LEGACY-01", "Preserved legacy row", DISCOVERED_AT, null, null, "GENERAL",
                FindingWorkflowState.DISCOVERED, 1, 1, CREATOR, CREATOR);
        repository.seed(legacy);
        UUID key = command("60000000-0000-0000-0000-000000000059");

        FindingWorkflowContracts.CommandResult initialized = service.initializeDiscoveredWorkflow(key, findingId, 1L);
        FindingWorkflowContracts.CommandResult replay = service.initializeDiscoveredWorkflow(key, findingId, 1L);

        assertThat(replay).isEqualTo(initialized);
        assertThat(initialized.state()).isEqualTo(FindingWorkflowState.PERSON_CONFIRM_PENDING);
        assertThat(initialized.version()).isEqualTo(2L);
        assertThat(repository.transitions).singleElement().satisfies(transition -> {
            assertThat(transition.fromState()).isEqualTo(FindingWorkflowState.DISCOVERED);
            assertThat(transition.toState()).isEqualTo(FindingWorkflowState.PERSON_CONFIRM_PENDING);
        });
        assertThat(workflow.startedProcesses).containsExactly(FindingWorkflowCommandService.PROCESS_DEFINITION_KEY);
        assertThat(workflow.tasks.get(findingId).key()).isEqualTo("personConfirm");
        assertThat(repository.finding(TENANT_A, findingId).orElseThrow().state())
                .isEqualTo(FindingWorkflowState.PERSON_CONFIRM_PENDING);
    }

    private void assertRejectedEvidence(UUID findingId, String commandId) {
        assertThatThrownBy(() -> service.submitRemediation(command(commandId), findingId, 4L,
                new FindingWorkflowContracts.SubmitRemediation(EVIDENCE_SET, "Work complete")))
                .isInstanceOf(FindingWorkflowEvidenceException.class);
        assertThat(repository.finding(TENANT_A, findingId).orElseThrow().state())
                .isEqualTo(FindingWorkflowState.RECTIFYING);
        assertThat(repository.submissions).isEmpty();
    }

    private static FindingWorkflowContracts.CreateFinding newFinding() {
        return new FindingWorkflowContracts.CreateFinding(
                ORGANIZATION_ID, "FND-TEST-01", "Synthetic defect", DISCOVERED_AT,
                null, null, "GENERAL");
    }

    private static UUID command(String value) {
        return UUID.fromString(value);
    }

    private static void asMember(UUID memberId, UUID tenantId) {
        var principal = VerifiedSessionPrincipalTestFixture.create(ACTOR_ID, tenantId, memberId);
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(
                principal, null, new SimpleGrantedAuthority("ROLE_AUTHENTICATED")));
    }

    private static final class MemoryAccessPolicy implements FindingWorkflowAccessPolicy {
        private boolean allowed = true;
        private final Set<UUID> inactiveMembers = new java.util.HashSet<>();

        @Override
        public void requireAction(CurrentTenantContext context, String permissionCode, UUID organizationId) {
            if (!allowed || !TENANT_A.equals(context.tenantId()) || organizationId == null) {
                throw new AccessDeniedException("The current member is not authorized");
            }
        }

        @Override
        public void requireAssignableMember(
                CurrentTenantContext context, UUID memberId, UUID organizationId, String permissionCode) {
            if (memberId == null || organizationId == null || inactiveMembers.contains(memberId)) {
                throw new AccessDeniedException("The remediation assignee is not eligible");
            }
        }
    }

    private static final class MemoryAssignmentResolver implements FindingWorkflowAssignmentResolver {
        private FindingWorkflowContracts.InitialRoute route = new FindingWorkflowContracts.InitialRoute(
                PERSON_CONFIRMER, SAFETY_MEMBER, SAFETY_MEMBER, SAFETY_MEMBER);

        @Override
        public FindingWorkflowContracts.InitialRoute resolve(UUID tenantId, UUID organizationId) {
            if (route == null) throw new FindingWorkflowConfigurationException("No route configured");
            return route;
        }

        @Override
        public UUID resolveSafetyDispatcher(UUID tenantId, UUID organizationId) {
            if (route == null) throw new FindingWorkflowConfigurationException("No route configured");
            return route.safetyDispatcherMemberId();
        }
    }

    private static final class MemoryEvidencePolicy implements FindingWorkflowEvidencePolicy {
        private final Map<UUID, EvidenceScope> ready = new HashMap<>();

        private void allow(UUID evidenceSetId, UUID tenantId, UUID findingId, int roundNo, UUID ownerMemberId) {
            ready.put(evidenceSetId, new EvidenceScope(tenantId, findingId, roundNo, ownerMemberId));
        }

        @Override
        public void requireReady(CurrentTenantContext context, UUID findingId, int roundNo, UUID evidenceSetId) {
            EvidenceScope scope = ready.get(evidenceSetId);
            if (scope == null || !scope.tenantId().equals(context.tenantId())
                    || !scope.findingId().equals(findingId) || scope.roundNo() != roundNo
                    || !scope.ownerMemberId().equals(context.memberId())) {
                throw new FindingWorkflowEvidenceException("A ready evidence set is required");
            }
        }

        private record EvidenceScope(UUID tenantId, UUID findingId, int roundNo, UUID ownerMemberId) {}
    }

    private static final class MemoryWorkflowFacade implements WorkflowFacade {
        private final Map<UUID, Task> tasks = new HashMap<>();
        private final Map<UUID, Map<String, Object>> processVariables = new HashMap<>();
        private final List<String> startedProcesses = new ArrayList<>();
        private final List<String> completedTasks = new ArrayList<>();
        private boolean failNextCompletion;

        @Override
        public void startProcess(UUID tenantId, String businessType, UUID businessId, int businessRevision,
                                 String processDefinitionKey, Map<String, Object> variables) {
            startedProcesses.add(processDefinitionKey);
            processVariables.put(businessId, new HashMap<>(variables));
            tasks.put(businessId, new Task("personConfirm", String.valueOf(variables.get("personConfirmerMemberId"))));
        }

        @Override
        public void completeTask(UUID tenantId, String businessType, UUID businessId, String taskDefinitionKey,
                                 UUID actorMemberId, Map<String, Object> variables) {
            if (failNextCompletion) {
                failNextCompletion = false;
                throw new IllegalStateException("Synthetic workflow engine failure");
            }
            Task current = tasks.get(businessId);
            if (current == null || !current.key().equals(taskDefinitionKey)
                    || !current.assignee().equals(actorMemberId.toString())) {
                throw new AccessDeniedException("Current member is not assigned to this workflow task");
            }
            Map<String, Object> activeVariables = processVariables.get(businessId);
            activeVariables.putAll(variables);
            completedTasks.add(taskDefinitionKey);
            String next = switch (taskDefinitionKey) {
                case "personConfirm" -> "safetyConfirm";
                case "safetyConfirm" -> "safetyDispatch";
                case "safetyDispatch" -> "remediation";
                case "remediation" -> "safetyVerification";
                case "safetyVerification" -> "FAIL".equals(activeVariables.get("verificationDecision"))
                        ? "safetyDispatch" : "END";
                default -> throw new IllegalArgumentException("Unexpected workflow task");
            };
            if (next.equals("END")) {
                tasks.remove(businessId);
                processVariables.remove(businessId);
                return;
            }
            Object assigned = activeVariables.get(switch (next) {
                case "safetyConfirm" -> "safetyConfirmerMemberId";
                case "safetyDispatch" -> "safetyDispatcherMemberId";
                case "remediation" -> "remediationAssigneeMemberId";
                case "safetyVerification" -> "safetyVerifierMemberId";
                default -> throw new IllegalArgumentException("Unexpected next workflow task");
            });
            String fallback = current.assignee();
            tasks.put(businessId, new Task(next, assigned == null ? fallback : assigned.toString()));
        }

        private record Task(String key, String assignee) {}
    }

    private static final class MemoryRepository implements FindingWorkflowRepository {
        private Map<UUID, FindingWorkflowCase> findings = new HashMap<>();
        private Map<String, StoredCommand> commands = new HashMap<>();
        private List<FindingWorkflowContracts.Transition> transitions = new ArrayList<>();
        private List<FindingWorkflowContracts.Notice> notices = new ArrayList<>();
        private List<FindingWorkflowContracts.Submission> submissions = new ArrayList<>();
        private List<FindingWorkflowContracts.Verification> verifications = new ArrayList<>();
        private Map<String, String> rounds = new HashMap<>();

        @Override
        public <T> T inTenantTransaction(UUID tenantId,
                                         java.util.function.Function<Transaction, T> work) {
            Map<UUID, FindingWorkflowCase> priorFindings = new HashMap<>(findings);
            Map<String, StoredCommand> priorCommands = new HashMap<>(commands);
            List<FindingWorkflowContracts.Transition> priorTransitions = new ArrayList<>(transitions);
            List<FindingWorkflowContracts.Notice> priorNotices = new ArrayList<>(notices);
            List<FindingWorkflowContracts.Submission> priorSubmissions = new ArrayList<>(submissions);
            List<FindingWorkflowContracts.Verification> priorVerifications = new ArrayList<>(verifications);
            Map<String, String> priorRounds = new HashMap<>(rounds);
            try {
                return work.apply(new Tx(tenantId));
            } catch (RuntimeException failure) {
                findings = priorFindings;
                commands = priorCommands;
                transitions = priorTransitions;
                notices = priorNotices;
                submissions = priorSubmissions;
                verifications = priorVerifications;
                rounds = priorRounds;
                throw failure;
            }
        }

        private Optional<FindingWorkflowCase> finding(UUID tenantId, UUID id) {
            return Optional.ofNullable(findings.get(id)).filter(item -> item.tenantId().equals(tenantId));
        }

        private long findingCount() { return findings.size(); }

        private void seed(FindingWorkflowCase finding) {
            findings.put(finding.id(), finding);
        }

        private final class Tx implements Transaction {
            private final UUID tenantId;
            private Tx(UUID tenantId) { this.tenantId = tenantId; }

            @Override
            public Optional<FindingWorkflowCase> findForUpdate(UUID findingId) {
                return finding(tenantId, findingId);
            }

            @Override
            public void insertFinding(FindingWorkflowCase finding) {
                if (!finding.tenantId().equals(tenantId) || findings.putIfAbsent(finding.id(), finding) != null) {
                    throw new IllegalStateException("Duplicate finding");
                }
            }

            @Override
            public void updateFinding(FindingWorkflowCase finding, long expectedVersion) {
                FindingWorkflowCase previous = findings.get(finding.id());
                if (previous == null || !previous.tenantId().equals(tenantId)
                        || previous.version() != expectedVersion) {
                    throw new FindingWorkflowVersionConflictException();
                }
                findings.put(finding.id(), finding);
            }

            @Override
            public FindingWorkflowContracts.CommandClaim claimCommand(
                    FindingWorkflowContracts.WorkflowCommand command) {
                String key = tenantId + ":" + command.commandId();
                StoredCommand prior = commands.get(key);
                if (prior != null) {
                    if (!prior.actorId().equals(command.actorId()) || !prior.action().equals(command.action())
                            || !prior.hash().equals(command.requestHash())) {
                        throw new FindingWorkflowIdempotencyConflictException();
                    }
                    return FindingWorkflowContracts.CommandClaim.replay(prior.result());
                }
                commands.put(key, new StoredCommand(command.actorId(), command.action(), command.requestHash(), null));
                return FindingWorkflowContracts.CommandClaim.newClaim();
            }

            @Override
            public void completeCommand(UUID commandId, FindingWorkflowContracts.CommandResult result) {
                String key = tenantId + ":" + commandId;
                StoredCommand prior = commands.get(key);
                commands.put(key, new StoredCommand(prior.actorId(), prior.action(), prior.hash(), result));
            }

            @Override
            public void appendTransition(FindingWorkflowContracts.Transition transition) {
                transitions.add(transition);
            }

            @Override
            public void appendConfirmation(FindingWorkflowContracts.Confirmation confirmation) {
                // Domain confirmation records are represented in the workflow transition fixture.
            }

            @Override
            public void openRound(UUID findingId, int roundNo, UUID commandId) {
                rounds.put(roundKey(findingId, roundNo), "OPEN");
            }

            @Override
            public void closeRound(UUID findingId, int roundNo, String state, UUID commandId) {
                rounds.put(roundKey(findingId, roundNo), state);
            }

            @Override
            public Optional<FindingWorkflowContracts.Notice> findNotice(UUID findingId, int roundNo) {
                return notices.stream().filter(value -> value.findingId().equals(findingId)
                        && value.roundNo() == roundNo).findFirst();
            }

            @Override
            public void insertNotice(FindingWorkflowContracts.Notice notice, UUID commandId) {
                notices.add(notice);
            }

            @Override
            public void insertSubmission(FindingWorkflowContracts.Submission submission) {
                submissions.add(submission);
            }

            @Override
            public Optional<FindingWorkflowContracts.Submission> findSubmission(UUID findingId, int roundNo) {
                return submissions.stream().filter(value -> value.findingId().equals(findingId)
                        && value.roundNo() == roundNo).findFirst();
            }

            @Override
            public void insertVerification(FindingWorkflowContracts.Verification verification) {
                verifications.add(verification);
            }
        }

        private static String roundKey(UUID findingId, int roundNo) {
            return findingId + ":" + roundNo;
        }

        private record StoredCommand(UUID actorId, String action, String hash,
                                     FindingWorkflowContracts.CommandResult result) {}
    }
}
