package com.mightyoung.softwarefactory.dualprevention;

import com.mightyoung.softwarefactory.dualprevention.finding.workflow.FindingWorkflowAccessPolicy;
import com.mightyoung.softwarefactory.dualprevention.finding.workflow.FindingWorkflowAssignmentResolver;
import com.mightyoung.softwarefactory.dualprevention.finding.workflow.FindingWorkflowCase;
import com.mightyoung.softwarefactory.dualprevention.finding.workflow.FindingWorkflowCommandService;
import com.mightyoung.softwarefactory.dualprevention.finding.workflow.FindingWorkflowConfigurationException;
import com.mightyoung.softwarefactory.dualprevention.finding.workflow.FindingWorkflowContracts;
import com.mightyoung.softwarefactory.dualprevention.finding.workflow.FindingWorkflowContracts.ConfirmationStage;
import com.mightyoung.softwarefactory.dualprevention.finding.workflow.FindingWorkflowContracts.NotEstablishedCommand;
import com.mightyoung.softwarefactory.dualprevention.finding.workflow.FindingWorkflowEvidencePolicy;
import com.mightyoung.softwarefactory.dualprevention.finding.workflow.FindingWorkflowEvidenceException;
import com.mightyoung.softwarefactory.dualprevention.finding.workflow.FindingSourceCategory;
import com.mightyoung.softwarefactory.dualprevention.finding.workflow.FindingUrgency;
import com.mightyoung.softwarefactory.dualprevention.finding.workflow.FindingWorkflowIdempotencyConflictException;
import com.mightyoung.softwarefactory.dualprevention.finding.workflow.FindingWorkflowChangedEvent;
import com.mightyoung.softwarefactory.dualprevention.finding.workflow.FindingWorkflowRepository;
import com.mightyoung.softwarefactory.dualprevention.finding.workflow.FindingWorkflowState;
import com.mightyoung.softwarefactory.dualprevention.finding.workflow.FindingWorkflowVersionConflictException;
import com.mightyoung.softwarefactory.dualprevention.finding.workflow.VerificationDecision;
import com.mightyoung.softwarefactory.files.FileEvidenceVerificationLevel;
import com.mightyoung.softwarefactory.files.VerifiedFileEvidenceSnapshot;
import com.mightyoung.softwarefactory.identity.CurrentTenantContext;
import com.mightyoung.softwarefactory.identity.SessionContextResolver;
import com.mightyoung.softwarefactory.identity.VerifiedSessionPrincipalTestFixture;
import com.mightyoung.softwarefactory.workflow.WorkflowFacade;
import com.mightyoung.softwarefactory.workflow.WorkflowTaskStateConflictException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.OffsetDateTime;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.LocalDate;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
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
        service = serviceAt(Instant.parse("2026-10-30T16:30:00Z"));
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
        FindingWorkflowContracts.CommandResult safetyPending = confirmPerson(
                "60000000-0000-0000-0000-000000000002", findingId, 1L);
        assertThat(safetyPending.state()).isEqualTo(FindingWorkflowState.SAFETY_CONFIRM_PENDING);

        asMember(SAFETY_MEMBER, TENANT_A);
        FindingWorkflowContracts.CommandResult dispatchPending = confirmSafety(
                "60000000-0000-0000-0000-000000000003", findingId, 2L);
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
        assertThat(repository.submissions.getFirst().evidenceSnapshots()).hasSize(1);
        assertThat(repository.submissions.getFirst().evidenceSnapshots().getFirst().storageVersion())
                .isEqualTo("synthetic-version-1");
        assertThat(evidencePolicy.lastPinnedSnapshots)
                .isEqualTo(repository.submissions.getLast().evidenceSnapshots());
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
        FindingWorkflowContracts.CommandResult crossDayReplay =
                serviceAt(Instant.parse("2026-10-31T16:00:00Z")).create(key, request);

        assertThat(replay).isEqualTo(first);
        assertThat(crossDayReplay).isEqualTo(first);
        assertThat(first.code()).isEqualTo("YH-20261031-0001");
        FindingWorkflowCase stored = repository.finding(TENANT_A, first.findingId()).orElseThrow();
        assertThat(stored.sourceCategory()).isEqualTo(FindingSourceCategory.FIELD_OBSERVATION);
        assertThat(stored.urgency()).isEqualTo(FindingUrgency.NORMAL);
        assertThat(stored.location()).isEqualTo("North loading dock");
        assertThat(stored.interimMeasure()).isEqualTo("Temporary barrier installed");
        assertThat(stored.backfill()).isFalse();
        assertThat(repository.findingCount()).isEqualTo(1);
        assertThat(workflow.startedProcesses).hasSize(1);
        assertThat(publishedEvents).hasSize(1);

        FindingWorkflowContracts.CreateFinding different = new FindingWorkflowContracts.CreateFinding(
                ORGANIZATION_ID, "Synthetic defect", DISCOVERED_AT, null, null, "UNKNOWN",
                FindingSourceCategory.INSPECTION, FindingUrgency.NORMAL,
                "North loading dock", "Temporary barrier installed");
        assertThatThrownBy(() -> service.create(key, different))
                .isInstanceOf(FindingWorkflowIdempotencyConflictException.class);
        assertThat(repository.findingCount()).isEqualTo(1);
    }

    @Test
    void computesBackfillOnlyWhenDiscoveryIsStrictlyMoreThanTwentyFourHoursEarlier() {
        OffsetDateTime submittedAt = OffsetDateTime.parse("2026-10-30T16:30:00Z");
        FindingWorkflowCase exactlyTwentyFourHours = createAndRead(
                "60000000-0000-0000-0000-000000000021", newFinding(submittedAt.minusHours(24)));
        FindingWorkflowCase olderThanTwentyFourHours = createAndRead(
                "60000000-0000-0000-0000-000000000022", newFinding(submittedAt.minusHours(24).minusNanos(1)));
        FindingWorkflowCase discoveredInTheFuture = createAndRead(
                "60000000-0000-0000-0000-000000000023", newFinding(submittedAt.plusNanos(1)));

        assertThat(exactlyTwentyFourHours.backfill()).isFalse();
        assertThat(olderThanTwentyFourHours.backfill()).isTrue();
        assertThat(discoveredInTheFuture.backfill()).isFalse();
    }

    @Test
    void workflowTransitionsPreserveNewReportMetadata() {
        FindingWorkflowContracts.CommandResult created = service.create(
                command("60000000-0000-0000-0000-000000000024"), newFinding());
        FindingWorkflowCase stored = repository.finding(TENANT_A, created.findingId()).orElseThrow();

        FindingWorkflowCase confirmed = stored.confirmByPerson(PERSON_CONFIRMER, stored.version());

        assertThat(confirmed.sourceCategory()).isEqualTo(stored.sourceCategory());
        assertThat(confirmed.urgency()).isEqualTo(stored.urgency());
        assertThat(confirmed.location()).isEqualTo(stored.location());
        assertThat(confirmed.interimMeasure()).isEqualTo(stored.interimMeasure());
        assertThat(confirmed.backfill()).isEqualTo(stored.backfill());
    }

    @Test
    void notEstablishedClosePreservesEveryReportedMetadataField() {
        FindingWorkflowContracts.CreateFinding request = new FindingWorkflowContracts.CreateFinding(
                ORGANIZATION_ID, "Reported with full metadata", DISCOVERED_AT.minusHours(30), null, null,
                "UNKNOWN", FindingSourceCategory.PERSON_REPORT, FindingUrgency.URGENT,
                "Boiler room west", "Power isolated pending inspection");
        FindingWorkflowContracts.CommandResult created = service.create(
                command("60000000-0000-0000-0000-000000000144"), request);
        FindingWorkflowCase before = repository.finding(TENANT_A, created.findingId()).orElseThrow();
        assertThat(before.backfill()).isTrue();

        asMember(PERSON_CONFIRMER, TENANT_A);
        service.confirmPerson(command("60000000-0000-0000-0000-000000000145"), created.findingId(), 1L,
                new FindingWorkflowContracts.PersonConfirmationCommand("Person review complete"));
        asMember(SAFETY_MEMBER, TENANT_A);
        service.closeNotEstablished(command("60000000-0000-0000-0000-000000000146"), created.findingId(), 2L,
                new FindingWorkflowContracts.NotEstablishedCommand(
                        FindingWorkflowContracts.ConfirmationStage.SAFETY, "Evidence does not substantiate it"));

        FindingWorkflowCase after = repository.finding(TENANT_A, created.findingId()).orElseThrow();
        assertThat(after.state()).isEqualTo(FindingWorkflowState.NOT_ESTABLISHED);
        assertThat(after.sourceCategory()).isEqualTo(before.sourceCategory());
        assertThat(after.urgency()).isEqualTo(before.urgency());
        assertThat(after.location()).isEqualTo(before.location());
        assertThat(after.interimMeasure()).isEqualTo(before.interimMeasure());
        assertThat(after.backfill()).isEqualTo(before.backfill());
        assertThat(after.relatedFindingId()).isEqualTo(before.relatedFindingId());
    }

    private FindingWorkflowCase createAndRead(String commandId, FindingWorkflowContracts.CreateFinding request) {
        FindingWorkflowContracts.CommandResult result = service.create(command(commandId), request);
        return repository.finding(TENANT_A, result.findingId()).orElseThrow();
    }

    @Test
    void differentFindingsUseTheNextTenantBusinessDaySequence() {
        FindingWorkflowContracts.CommandResult first = service.create(
                command("60000000-0000-0000-0000-000000000013"), newFinding());
        FindingWorkflowContracts.CommandResult second = service.create(
                command("60000000-0000-0000-0000-000000000014"), newFinding());

        assertThat(first.code()).isEqualTo("YH-20261031-0001");
        assertThat(second.code()).isEqualTo("YH-20261031-0002");
    }

    @Test
    void findingNumberSequencesAreIsolatedByTenantForTheSameBusinessDay() {
        FindingWorkflowContracts.CommandResult tenantA = service.create(
                command("60000000-0000-0000-0000-000000000019"), newFinding());

        accessPolicy.permittedTenants.add(TENANT_B);
        asMember(CREATOR, TENANT_B);
        FindingWorkflowContracts.CommandResult tenantB = service.create(
                command("60000000-0000-0000-0000-000000000020"), newFinding());

        assertThat(tenantA.code()).isEqualTo("YH-20261031-0001");
        assertThat(tenantB.code()).isEqualTo("YH-20261031-0001");
        assertThat(repository.numberSequences)
                .containsEntry(new SequenceKey(TENANT_A, LocalDate.of(2026, 10, 31)), 1L)
                .containsEntry(new SequenceKey(TENANT_B, LocalDate.of(2026, 10, 31)), 1L);
    }

    @Test
    void sequenceExpandsPastFourDigitsWithoutTruncatingTheBusinessNumber() {
        repository.numberSequences.put(new SequenceKey(TENANT_A, LocalDate.of(2026, 10, 31)), 9_998L);

        FindingWorkflowContracts.CommandResult lastFourDigit = service.create(
                command("60000000-0000-0000-0000-000000000015"), newFinding());
        FindingWorkflowContracts.CommandResult firstFiveDigit = service.create(
                command("60000000-0000-0000-0000-000000000016"), newFinding());

        assertThat(lastFourDigit.code()).isEqualTo("YH-20261031-9999");
        assertThat(firstFiveDigit.code()).isEqualTo("YH-20261031-10000");
        assertThat(firstFiveDigit.code()).hasSizeLessThanOrEqualTo(64);
    }

    @Test
    void retryingTheSameKeyAfterShanghaiMidnightReplaysTheOriginalCode() {
        UUID key = command("60000000-0000-0000-0000-000000000017");
        FindingWorkflowContracts.CreateFinding request = newFinding();
        FindingWorkflowContracts.CommandResult first = service.create(key, request);

        FindingWorkflowCommandService nextShanghaiDay =
                serviceAt(Instant.parse("2026-10-31T16:00:00Z"));
        FindingWorkflowContracts.CommandResult crossDayReplay = nextShanghaiDay.create(key, request);
        FindingWorkflowContracts.CommandResult nextDayCreate = nextShanghaiDay.create(
                command("60000000-0000-0000-0000-000000000018"), request);

        assertThat(first.code()).isEqualTo("YH-20261031-0001");
        assertThat(crossDayReplay).isEqualTo(first);
        assertThat(nextDayCreate.code()).isEqualTo("YH-20261101-0001");
        assertThat(repository.numberSequences)
                .containsEntry(new SequenceKey(TENANT_A, LocalDate.of(2026, 10, 31)), 1L)
                .containsEntry(new SequenceKey(TENANT_A, LocalDate.of(2026, 11, 1)), 1L);
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
    void creationBlocksTheSameMemberAssignedToBothConfirmationStages() {
        assignmentResolver.route = new FindingWorkflowContracts.InitialRoute(
                PERSON_CONFIRMER, PERSON_CONFIRMER, SAFETY_MEMBER, SAFETY_MEMBER);

        assertThatThrownBy(() -> service.create(
                command("60000000-0000-0000-0000-000000000061"), newFinding()))
                .isInstanceOf(FindingWorkflowConfigurationException.class);

        assertThat(repository.findingCount()).isZero();
        assertThat(repository.transitions).isEmpty();
        assertThat(workflow.startedProcesses).isEmpty();
        assertThat(publishedEvents).isEmpty();
    }

    @Test
    void creationBlocksTheReporterAssignedToEitherConfirmationStage() {
        List<FindingWorkflowContracts.InitialRoute> invalidRoutes = List.of(
                new FindingWorkflowContracts.InitialRoute(CREATOR, SAFETY_MEMBER, SAFETY_MEMBER, SAFETY_MEMBER),
                new FindingWorkflowContracts.InitialRoute(PERSON_CONFIRMER, CREATOR, SAFETY_MEMBER, SAFETY_MEMBER));

        for (FindingWorkflowContracts.InitialRoute route : invalidRoutes) {
            assignmentResolver.route = route;

            assertThatThrownBy(() -> service.create(
                    command(UUID.randomUUID().toString()), newFinding()))
                    .isInstanceOf(FindingWorkflowConfigurationException.class);

            assertThat(repository.findingCount()).isZero();
            assertThat(workflow.startedProcesses).isEmpty();
        }
        assertThat(repository.transitions).isEmpty();
        assertThat(publishedEvents).isEmpty();
    }

    @Test
    void legacyDiscoveredInitializationBlocksDuplicateConfirmationAssignments() {
        UUID findingId = UUID.fromString("40000000-0000-0000-0000-000000000061");
        repository.seed(new FindingWorkflowCase(TENANT_A, findingId, ORGANIZATION_ID, "YH-20261031-0061",
                "Legacy finding", DISCOVERED_AT, null, null, "UNKNOWN", FindingWorkflowState.DISCOVERED,
                1, 1, CREATOR, CREATOR, null, null, null, null, null));
        assignmentResolver.route = new FindingWorkflowContracts.InitialRoute(
                PERSON_CONFIRMER, PERSON_CONFIRMER, SAFETY_MEMBER, SAFETY_MEMBER);

        assertThatThrownBy(() -> service.initializeDiscoveredWorkflow(
                command("60000000-0000-0000-0000-000000000070"), findingId, 1L))
                .isInstanceOf(FindingWorkflowConfigurationException.class);

        assertThat(repository.finding(TENANT_A, findingId).orElseThrow().state())
                .isEqualTo(FindingWorkflowState.DISCOVERED);
        assertThat(repository.transitions).isEmpty();
        assertThat(workflow.startedProcesses).isEmpty();
        assertThat(publishedEvents).isEmpty();
    }

    @Test
    void safetyConfirmationCannotSkipMissingPersonConfirmationHistory() {
        UUID findingId = UUID.fromString("40000000-0000-0000-0000-000000000062");
        repository.seed(new FindingWorkflowCase(TENANT_A, findingId, ORGANIZATION_ID, "YH-20261031-0062",
                "Legacy safety-pending finding", DISCOVERED_AT, null, null, "UNKNOWN",
                FindingWorkflowState.SAFETY_CONFIRM_PENDING, 1, 2, CREATOR, PERSON_CONFIRMER,
                null, null, null, null, null));
        workflow.tasks.put(findingId, new MemoryWorkflowFacade.Task("safetyConfirm", SAFETY_MEMBER.toString()));
        asMember(SAFETY_MEMBER, TENANT_A);

        assertThatThrownBy(() -> confirmSafety(
                "60000000-0000-0000-0000-000000000071", findingId, 2L))
                .isInstanceOf(FindingWorkflowConfigurationException.class);

        assertThat(repository.finding(TENANT_A, findingId).orElseThrow().state())
                .isEqualTo(FindingWorkflowState.SAFETY_CONFIRM_PENDING);
        assertThat(repository.transitions).isEmpty();
        assertThat(repository.confirmations).isEmpty();
        assertThat(workflow.completedTasks).isEmpty();
    }

    @Test
    void reporterCannotConfirmTheirOwnFindingEvenWhenTheWorkflowTaskIsMisassigned() {
        FindingWorkflowContracts.CommandResult created = service.create(
                command("60000000-0000-0000-0000-000000000062"), newFinding());
        workflow.assignCurrentTask(created.findingId(), CREATOR);
        asMember(CREATOR, TENANT_A);

        assertThatThrownBy(() -> confirmPerson(
                "60000000-0000-0000-0000-000000000063", created.findingId(), 1L))
                .isInstanceOf(AccessDeniedException.class);

        assertThat(repository.finding(TENANT_A, created.findingId()).orElseThrow().state())
                .isEqualTo(FindingWorkflowState.PERSON_CONFIRM_PENDING);
        assertThat(repository.transitions).hasSize(1);
        assertThat(repository.confirmations).isEmpty();
        assertThat(workflow.completedTasks).isEmpty();
    }

    @Test
    void reporterCannotPerformSafetyConfirmationEvenWhenTheWorkflowTaskIsMisassigned() {
        FindingWorkflowContracts.CommandResult created = service.create(
                command("60000000-0000-0000-0000-000000000064"), newFinding());
        asMember(PERSON_CONFIRMER, TENANT_A);
        confirmPerson("60000000-0000-0000-0000-000000000065", created.findingId(), 1L);
        workflow.assignCurrentTask(created.findingId(), CREATOR);
        asMember(CREATOR, TENANT_A);

        assertThatThrownBy(() -> confirmSafety(
                "60000000-0000-0000-0000-000000000066", created.findingId(), 2L))
                .isInstanceOf(AccessDeniedException.class);

        assertThat(repository.finding(TENANT_A, created.findingId()).orElseThrow().state())
                .isEqualTo(FindingWorkflowState.SAFETY_CONFIRM_PENDING);
        assertThat(repository.transitions).hasSize(2);
        assertThat(repository.confirmations).hasSize(1);
        assertThat(workflow.completedTasks).containsExactly("personConfirm");
    }

    @Test
    void safetyConfirmationIsBlockedWhenItsActorAlreadyConfirmedAsPerson() {
        FindingWorkflowContracts.CommandResult created = service.create(
                command("60000000-0000-0000-0000-000000000067"), newFinding());
        asMember(PERSON_CONFIRMER, TENANT_A);
        confirmPerson("60000000-0000-0000-0000-000000000068", created.findingId(), 1L);
        workflow.assignCurrentTask(created.findingId(), PERSON_CONFIRMER);

        assertThatThrownBy(() -> confirmSafety(
                "60000000-0000-0000-0000-000000000069", created.findingId(), 2L))
                .isInstanceOf(AccessDeniedException.class);

        assertThat(repository.finding(TENANT_A, created.findingId()).orElseThrow().state())
                .isEqualTo(FindingWorkflowState.SAFETY_CONFIRM_PENDING);
        assertThat(repository.transitions).hasSize(2);
        assertThat(repository.confirmations).hasSize(1);
        assertThat(workflow.completedTasks).containsExactly("personConfirm");
    }

    @Test
    void blankPersonOpinionIsRejectedWithoutAnyCommandSideEffects() {
        FindingWorkflowContracts.CommandResult created = service.create(
                command("60000000-0000-0000-0000-000000000080"), newFinding());
        asMember(PERSON_CONFIRMER, TENANT_A);

        assertThatThrownBy(() -> service.confirmPerson(
                command("60000000-0000-0000-0000-000000000081"), created.findingId(), 1L,
                new FindingWorkflowContracts.PersonConfirmationCommand("   ")))
                .isInstanceOf(com.mightyoung.softwarefactory.dualprevention.finding.workflow
                        .FindingWorkflowRuleException.class);

        assertThat(repository.finding(TENANT_A, created.findingId()).orElseThrow().state())
                .isEqualTo(FindingWorkflowState.PERSON_CONFIRM_PENDING);
        assertThat(repository.confirmations).isEmpty();
        assertThat(repository.transitions).hasSize(1);
        assertThat(workflow.completedTasks).isEmpty();
        assertThat(publishedEvents).hasSize(1);
    }

    @Test
    void safetyConfirmationRequiresProfessionalGradeAndOpinionBeforeAnySideEffects() {
        FindingWorkflowContracts.CommandResult created = service.create(
                command("60000000-0000-0000-0000-000000000082"), newFinding());
        asMember(PERSON_CONFIRMER, TENANT_A);
        service.confirmPerson(command("60000000-0000-0000-0000-000000000083"), created.findingId(), 1L,
                new FindingWorkflowContracts.PersonConfirmationCommand("Personnel review complete"));
        asMember(SAFETY_MEMBER, TENANT_A);

        for (FindingWorkflowContracts.SafetyConfirmationCommand request : List.of(
                new FindingWorkflowContracts.SafetyConfirmationCommand("Safety review complete", null),
                new FindingWorkflowContracts.SafetyConfirmationCommand("Safety review complete", "UNKNOWN"),
                new FindingWorkflowContracts.SafetyConfirmationCommand("Safety review complete", "INVALID"),
                new FindingWorkflowContracts.SafetyConfirmationCommand("   ", "GENERAL"))) {
            assertThatThrownBy(() -> service.confirmSafety(
                    command(UUID.randomUUID().toString()), created.findingId(), 2L, request))
                    .isInstanceOf(com.mightyoung.softwarefactory.dualprevention.finding.workflow
                            .FindingWorkflowRuleException.class);
        }

        FindingWorkflowCase unchanged = repository.finding(TENANT_A, created.findingId()).orElseThrow();
        assertThat(unchanged.state()).isEqualTo(FindingWorkflowState.SAFETY_CONFIRM_PENDING);
        assertThat(unchanged.grade()).isEqualTo("UNKNOWN");
        assertThat(repository.confirmations).hasSize(1);
        assertThat(repository.transitions).hasSize(2);
        assertThat(workflow.completedTasks).containsExactly("personConfirm");
        assertThat(publishedEvents).hasSize(2);
    }

    @Test
    void confirmationOpinionIsPersistedAndIncludedInIdempotencyFingerprint() {
        FindingWorkflowContracts.CommandResult created = service.create(
                command("60000000-0000-0000-0000-000000000084"), newFinding());
        UUID key = command("60000000-0000-0000-0000-000000000085");
        asMember(PERSON_CONFIRMER, TENANT_A);

        FindingWorkflowContracts.CommandResult first = service.confirmPerson(key, created.findingId(), 1L,
                new FindingWorkflowContracts.PersonConfirmationCommand("  Personnel review complete  "));
        FindingWorkflowContracts.CommandResult replay = service.confirmPerson(key, created.findingId(), 1L,
                new FindingWorkflowContracts.PersonConfirmationCommand("Personnel review complete"));

        assertThat(replay).isEqualTo(first);
        assertThat(repository.confirmations).hasSize(1);
        assertThat(repository.confirmations.getFirst().confirmation().reason()).isEqualTo("Personnel review complete");
        assertThat(repository.transitions.getLast().reason()).isEqualTo("Personnel review complete");
        assertThat(workflow.completedTasks).containsExactly("personConfirm");

        assertThatThrownBy(() -> service.confirmPerson(key, created.findingId(), 1L,
                new FindingWorkflowContracts.PersonConfirmationCommand("A different opinion")))
                .isInstanceOf(FindingWorkflowIdempotencyConflictException.class);
        assertThat(repository.confirmations).hasSize(1);
        assertThat(repository.transitions).hasSize(2);
        assertThat(workflow.completedTasks).containsExactly("personConfirm");
    }

    @Test
    void oldSuccessfulConfirmationReceiptIsNotReplayedOrRewrittenByTheNewRequestContract() {
        UUID findingId = UUID.fromString("40000000-0000-0000-0000-000000000097");
        FindingWorkflowCase alreadyConfirmed = safetyPendingFinding(TENANT_A, findingId);
        UUID oldCommandId = command("60000000-0000-0000-0000-000000000097");
        String oldHash = legacyConfirmationHash("finding.confirm.person", findingId, 1L);
        FindingWorkflowContracts.CommandResult oldResult = new FindingWorkflowContracts.CommandResult(
                findingId, alreadyConfirmed.code(), FindingWorkflowState.SAFETY_CONFIRM_PENDING, 1, 2L);
        repository.seed(alreadyConfirmed);
        repository.seedCommand(TENANT_A, oldCommandId, ACTOR_ID, "finding.confirm.person", oldHash, oldResult);
        asMember(PERSON_CONFIRMER, TENANT_A);

        assertThatThrownBy(() -> service.confirmPerson(oldCommandId, findingId, 1L,
                new FindingWorkflowContracts.PersonConfirmationCommand("New required opinion")))
                .isInstanceOf(FindingWorkflowIdempotencyConflictException.class);

        assertThat(repository.finding(TENANT_A, findingId).orElseThrow()).isEqualTo(alreadyConfirmed);
        assertThat(repository.command(TENANT_A, oldCommandId))
                .isEqualTo(new MemoryRepository.StoredCommand(ACTOR_ID, "finding.confirm.person", oldHash, oldResult));
        assertThat(repository.confirmations).isEmpty();
        assertThat(repository.transitions).isEmpty();
        assertThat(workflow.completedTasks).isEmpty();
        assertThat(publishedEvents).isEmpty();
    }

    @Test
    void oldSuccessfulSafetyReceiptIsNotReplayedOrRewrittenByTheNewRequestContract() {
        UUID findingId = UUID.fromString("40000000-0000-0000-0000-000000000104");
        FindingWorkflowCase alreadyConfirmed = new FindingWorkflowCase(TENANT_A, findingId, ORGANIZATION_ID,
                "YH-20261031-0104", "Legacy safety finding", DISCOVERED_AT, null, null, "UNKNOWN",
                FindingWorkflowState.SAFETY_DISPATCH_PENDING, 1, 3, CREATOR, SAFETY_MEMBER,
                null, null, null, null, null);
        UUID oldCommandId = command("60000000-0000-0000-0000-000000000104");
        String oldHash = legacyConfirmationHash("finding.confirm.safety", findingId, 2L);
        FindingWorkflowContracts.CommandResult oldResult = new FindingWorkflowContracts.CommandResult(
                findingId, alreadyConfirmed.code(), FindingWorkflowState.SAFETY_DISPATCH_PENDING, 1, 3L);
        repository.seed(alreadyConfirmed);
        repository.seedConfirmation(TENANT_A, confirmation(findingId, "PERSON", PERSON_CONFIRMER,
                "Legacy person result", "60000000-0000-0000-0000-000000000105"));
        repository.seedCommand(TENANT_A, oldCommandId, ACTOR_ID, "finding.confirm.safety", oldHash, oldResult);
        asMember(SAFETY_MEMBER, TENANT_A);

        assertThatThrownBy(() -> service.confirmSafety(oldCommandId, findingId, 2L,
                new FindingWorkflowContracts.SafetyConfirmationCommand("New required opinion", "GENERAL")))
                .isInstanceOf(FindingWorkflowIdempotencyConflictException.class);

        assertThat(repository.finding(TENANT_A, findingId).orElseThrow()).isEqualTo(alreadyConfirmed);
        assertThat(repository.command(TENANT_A, oldCommandId))
                .isEqualTo(new MemoryRepository.StoredCommand(ACTOR_ID, "finding.confirm.safety", oldHash,
                        oldResult));
        assertThat(repository.confirmation(TENANT_A, findingId, "SAFETY")).isEmpty();
        assertThat(repository.transitions).isEmpty();
        assertThat(workflow.completedTasks).isEmpty();
        assertThat(publishedEvents).isEmpty();
    }

    @Test
    void legacyConfirmationReceiptWithUnicodeBoundaryOpinionIsNotRewrittenOrSilentlyReplayed() {
        UUID findingId = UUID.fromString("40000000-0000-0000-0000-000000000142");
        FindingWorkflowCase alreadyConfirmed = safetyPendingFinding(TENANT_A, findingId);
        UUID oldCommandId = command("60000000-0000-0000-0000-000000000142");
        String legacyOpinion = "\u00a0Person review complete\u00a0";
        String oldHash = legacyConfirmationHashWithOpinion(
                "finding.confirm.person", findingId, 1L, legacyOpinion, null);
        FindingWorkflowContracts.CommandResult oldResult = new FindingWorkflowContracts.CommandResult(
                findingId, alreadyConfirmed.code(), FindingWorkflowState.SAFETY_CONFIRM_PENDING, 1, 2L);
        repository.seed(alreadyConfirmed);
        repository.seedConfirmation(TENANT_A, confirmation(findingId, "PERSON", PERSON_CONFIRMER,
                legacyOpinion, "60000000-0000-0000-0000-000000000143"));
        repository.seedCommand(TENANT_A, oldCommandId, ACTOR_ID, "finding.confirm.person", oldHash, oldResult);
        asMember(PERSON_CONFIRMER, TENANT_A);

        assertThatThrownBy(() -> service.confirmPerson(oldCommandId, findingId, 1L,
                new FindingWorkflowContracts.PersonConfirmationCommand(legacyOpinion)))
                .isInstanceOf(FindingWorkflowIdempotencyConflictException.class);

        assertThat(repository.finding(TENANT_A, findingId).orElseThrow()).isEqualTo(alreadyConfirmed);
        assertThat(repository.command(TENANT_A, oldCommandId))
                .isEqualTo(new MemoryRepository.StoredCommand(ACTOR_ID, "finding.confirm.person", oldHash,
                        oldResult));
        assertThat(repository.confirmation(TENANT_A, findingId, "PERSON").orElseThrow().reason())
                .isEqualTo(legacyOpinion);
        assertThat(repository.confirmation(TENANT_A, findingId, "SAFETY")).isEmpty();
        assertThat(repository.transitions).isEmpty();
        assertThat(workflow.completedTasks).isEmpty();
        assertThat(publishedEvents).isEmpty();
    }

    @Test
    void confirmationOpinionRawLengthIsLimitedBeforeWhitespaceNormalization() {
        FindingWorkflowContracts.CommandResult created = service.create(
                command("60000000-0000-0000-0000-000000000098"), newFinding());
        asMember(PERSON_CONFIRMER, TENANT_A);
        String exactLimit = "x".repeat(4000);

        FindingWorkflowContracts.CommandResult accepted = service.confirmPerson(
                command("60000000-0000-0000-0000-000000000099"), created.findingId(), 1L,
                new FindingWorkflowContracts.PersonConfirmationCommand(exactLimit));

        assertThat(accepted.state()).isEqualTo(FindingWorkflowState.SAFETY_CONFIRM_PENDING);
        assertThat(repository.confirmations.getFirst().confirmation().reason()).hasSize(4000);

        asMember(CREATOR, TENANT_A);
        FindingWorkflowContracts.CommandResult second = service.create(
                command("60000000-0000-0000-0000-000000000100"), newFinding());
        asMember(PERSON_CONFIRMER, TENANT_A);
        String overLimitRaw = " " + "x".repeat(3999) + " ";
        assertThat(overLimitRaw.trim()).hasSize(3999);

        assertThatThrownBy(() -> service.confirmPerson(command("60000000-0000-0000-0000-000000000101"),
                second.findingId(), 1L, new FindingWorkflowContracts.PersonConfirmationCommand(overLimitRaw)))
                .isInstanceOf(com.mightyoung.softwarefactory.dualprevention.finding.workflow
                        .FindingWorkflowRuleException.class);
        assertThat(repository.finding(TENANT_A, second.findingId()).orElseThrow().state())
                .isEqualTo(FindingWorkflowState.PERSON_CONFIRM_PENDING);
        assertThat(repository.confirmations).hasSize(1);
    }

    @Test
    void unicodeWhitespaceOnlyConfirmationOpinionIsRejectedWithoutAdvancingTheFinding() {
        FindingWorkflowContracts.CommandResult created = service.create(
                command("60000000-0000-0000-0000-000000000102"), newFinding());
        asMember(PERSON_CONFIRMER, TENANT_A);

        for (String whitespace : List.of("\u00a0", "\t", " \t\u00a0\u2003")) {
            assertThatThrownBy(() -> service.confirmPerson(command(UUID.randomUUID().toString()),
                    created.findingId(), 1L, new FindingWorkflowContracts.PersonConfirmationCommand(whitespace)))
                    .isInstanceOf(com.mightyoung.softwarefactory.dualprevention.finding.workflow
                            .FindingWorkflowRuleException.class);
        }

        assertThat(repository.finding(TENANT_A, created.findingId()).orElseThrow().state())
                .isEqualTo(FindingWorkflowState.PERSON_CONFIRM_PENDING);
        assertThat(repository.confirmations).isEmpty();
        assertThat(workflow.completedTasks).isEmpty();
    }

    @Test
    void safetyGradeIsPersistedAndIncludedInIdempotencyFingerprint() {
        FindingWorkflowContracts.CommandResult created = service.create(
                command("60000000-0000-0000-0000-000000000086"), newFinding());
        asMember(PERSON_CONFIRMER, TENANT_A);
        service.confirmPerson(command("60000000-0000-0000-0000-000000000087"), created.findingId(), 1L,
                new FindingWorkflowContracts.PersonConfirmationCommand("Personnel review complete"));
        asMember(SAFETY_MEMBER, TENANT_A);
        UUID key = command("60000000-0000-0000-0000-000000000088");

        FindingWorkflowContracts.CommandResult first = service.confirmSafety(key, created.findingId(), 2L,
                new FindingWorkflowContracts.SafetyConfirmationCommand("Safety review complete", "GENERAL"));
        FindingWorkflowContracts.CommandResult replay = service.confirmSafety(key, created.findingId(), 2L,
                new FindingWorkflowContracts.SafetyConfirmationCommand("Safety review complete", "GENERAL"));

        assertThat(replay).isEqualTo(first);
        assertThat(repository.finding(TENANT_A, created.findingId()).orElseThrow().grade()).isEqualTo("GENERAL");
        assertThat(repository.confirmations.getLast().confirmation().reason()).isEqualTo("Safety review complete");
        assertThat(repository.transitions.getLast().reason()).isEqualTo("Safety review complete");
        assertThat(workflow.completedTasks).containsExactly("personConfirm", "safetyConfirm");

        assertThatThrownBy(() -> service.confirmSafety(key, created.findingId(), 2L,
                new FindingWorkflowContracts.SafetyConfirmationCommand("Safety review complete", "MAJOR")))
                .isInstanceOf(FindingWorkflowIdempotencyConflictException.class);
        assertThat(repository.finding(TENANT_A, created.findingId()).orElseThrow().grade()).isEqualTo("GENERAL");
        assertThat(repository.confirmations).hasSize(2);
        assertThat(repository.transitions).hasSize(3);
        assertThat(workflow.completedTasks).containsExactly("personConfirm", "safetyConfirm");
    }

    @Test
    void personCanCloseAsNotEstablishedWithOpinionAndReplayWithoutReopening() {
        FindingWorkflowContracts.CommandResult created = service.create(
                command("60000000-0000-0000-0000-000000000120"), newFinding());
        UUID closeKey = command("60000000-0000-0000-0000-000000000121");
        NotEstablishedCommand request = new NotEstablishedCommand(
                ConfirmationStage.PERSON, "  Evidence does not substantiate the report  ");
        asMember(PERSON_CONFIRMER, TENANT_A);

        FindingWorkflowContracts.CommandResult closed = service.closeNotEstablished(
                closeKey, created.findingId(), 1L, request);

        assertThat(closed.state()).isEqualTo(FindingWorkflowState.NOT_ESTABLISHED);
        assertThat(closed.version()).isEqualTo(2L);
        assertThat(closed.roundNo()).isEqualTo(1);
        assertThat(repository.confirmation(TENANT_A, created.findingId(), "PERSON").orElseThrow().decision())
                .isEqualTo(FindingWorkflowContracts.ConfirmationDecision.NOT_ESTABLISHED);
        assertThat(repository.confirmation(TENANT_A, created.findingId(), "PERSON").orElseThrow().reason())
                .isEqualTo("Evidence does not substantiate the report");
        assertThat(repository.finding(TENANT_A, created.findingId()).orElseThrow().grade()).isEqualTo("UNKNOWN");
        assertThat(repository.transitions.getLast().fromState()).isEqualTo(FindingWorkflowState.PERSON_CONFIRM_PENDING);
        assertThat(repository.transitions.getLast().toState()).isEqualTo(FindingWorkflowState.NOT_ESTABLISHED);
        assertThat(repository.transitions.getLast().reason()).isEqualTo("Evidence does not substantiate the report");
        assertThat(workflow.completedTasks).containsExactly("personConfirm");
        assertThat(workflow.tasks).doesNotContainKey(created.findingId());

        FindingWorkflowContracts.CommandResult replay = service.closeNotEstablished(
                closeKey, created.findingId(), 1L,
                new NotEstablishedCommand(ConfirmationStage.PERSON, "Evidence does not substantiate the report"));
        assertThat(replay).isEqualTo(closed);
        assertThat(repository.confirmations).hasSize(1);
        assertThat(repository.transitions).hasSize(2);
        assertThat(workflow.completedTasks).containsExactly("personConfirm");

        assertThatThrownBy(() -> service.closeNotEstablished(closeKey, created.findingId(), 1L,
                new NotEstablishedCommand(ConfirmationStage.PERSON, "Changed opinion")))
                .isInstanceOf(FindingWorkflowIdempotencyConflictException.class);
        assertThatThrownBy(() -> service.closeNotEstablished(command("60000000-0000-0000-0000-000000000122"),
                created.findingId(), 2L,
                new NotEstablishedCommand(ConfirmationStage.PERSON, "Try to reopen")))
                .isInstanceOf(com.mightyoung.softwarefactory.dualprevention.finding.workflow
                        .FindingWorkflowRuleException.class);
        assertThat(repository.finding(TENANT_A, created.findingId()).orElseThrow().state())
                .isEqualTo(FindingWorkflowState.NOT_ESTABLISHED);
        assertThat(repository.confirmations).hasSize(1);
        assertThat(repository.transitions).hasSize(2);
    }

    @Test
    void safetyNotEstablishedCloseNeedsNoGradeAndPreservesTheExistingGrade() {
        UUID findingId = UUID.fromString("40000000-0000-0000-0000-000000000123");
        repository.seed(new FindingWorkflowCase(TENANT_A, findingId, ORGANIZATION_ID, "YH-20261031-0123",
                "Safety review already graded", DISCOVERED_AT, null, null, "GENERAL",
                FindingWorkflowState.SAFETY_CONFIRM_PENDING, 1, 2, CREATOR, PERSON_CONFIRMER));
        repository.seedConfirmation(TENANT_A, confirmation(findingId, "PERSON", PERSON_CONFIRMER,
                "Person review complete", "60000000-0000-0000-0000-000000000124"));
        workflow.processDefinitionKeys.put(findingId, FindingWorkflowCommandService.PROCESS_DEFINITION_KEY);
        workflow.processVariables.put(findingId, new HashMap<>());
        workflow.tasks.put(findingId, new MemoryWorkflowFacade.Task("safetyConfirm", SAFETY_MEMBER.toString()));
        asMember(SAFETY_MEMBER, TENANT_A);

        FindingWorkflowContracts.CommandResult closed = service.closeNotEstablished(
                command("60000000-0000-0000-0000-000000000126"), findingId, 2L,
                new NotEstablishedCommand(ConfirmationStage.SAFETY, "The report is not substantiated"));
        assertThat(closed.state()).isEqualTo(FindingWorkflowState.NOT_ESTABLISHED);
        assertThat(repository.finding(TENANT_A, findingId).orElseThrow().grade()).isEqualTo("GENERAL");
        assertThat(repository.confirmation(TENANT_A, findingId, "SAFETY").orElseThrow().decision())
                .isEqualTo(FindingWorkflowContracts.ConfirmationDecision.NOT_ESTABLISHED);
        assertThat(workflow.completedTasks).containsExactly("safetyConfirm");
    }

    @Test
    void reporterAndLegacyV1InstanceCannotCloseAsNotEstablished() {
        FindingWorkflowContracts.CommandResult created = service.create(
                command("60000000-0000-0000-0000-000000000127"), newFinding());
        UUID closeKey = command("60000000-0000-0000-0000-000000000128");

        assertThatThrownBy(() -> service.closeNotEstablished(closeKey, created.findingId(), 1L,
                new NotEstablishedCommand(ConfirmationStage.PERSON, "Review")))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(repository.command(TENANT_A, closeKey)).isNull();

        workflow.processDefinitionKeys.put(created.findingId(), "findingRemediationV1");
        asMember(PERSON_CONFIRMER, TENANT_A);
        assertThatThrownBy(() -> service.closeNotEstablished(closeKey, created.findingId(), 1L,
                new NotEstablishedCommand(ConfirmationStage.PERSON, "Review")))
                .isInstanceOf(WorkflowTaskStateConflictException.class);
        assertThat(repository.command(TENANT_A, closeKey)).isNull();
        assertThat(repository.finding(TENANT_A, created.findingId()).orElseThrow().state())
                .isEqualTo(FindingWorkflowState.PERSON_CONFIRM_PENDING);
        assertThat(repository.confirmations).isEmpty();
        assertThat(repository.transitions).hasSize(1);
    }

    @Test
    void workflowFailureRollsBackNotEstablishedDecisionStateAuditAndReceipt() {
        FindingWorkflowContracts.CommandResult created = service.create(
                command("60000000-0000-0000-0000-000000000129"), newFinding());
        asMember(PERSON_CONFIRMER, TENANT_A);
        UUID closeKey = command("60000000-0000-0000-0000-000000000130");
        NotEstablishedCommand request = new NotEstablishedCommand(
                ConfirmationStage.PERSON, "The evidence does not support the report");
        workflow.failNextCompletion = true;

        assertThatThrownBy(() -> service.closeNotEstablished(closeKey, created.findingId(), 1L, request))
                .isInstanceOf(IllegalStateException.class);

        assertThat(repository.finding(TENANT_A, created.findingId()).orElseThrow().state())
                .isEqualTo(FindingWorkflowState.PERSON_CONFIRM_PENDING);
        assertThat(repository.command(TENANT_A, closeKey)).isNull();
        assertThat(repository.confirmations).isEmpty();
        assertThat(repository.transitions).hasSize(1);
        assertThat(publishedEvents).hasSize(1);
        assertThat(workflow.completedTasks).isEmpty();

        assertThat(service.closeNotEstablished(closeKey, created.findingId(), 1L, request).state())
                .isEqualTo(FindingWorkflowState.NOT_ESTABLISHED);
    }

    @Test
    void newRelatedFindingReferencesTheNotEstablishedParentWithoutChangingIt() {
        FindingWorkflowContracts.CommandResult parent = service.create(
                command("60000000-0000-0000-0000-000000000131"), newFinding());
        asMember(PERSON_CONFIRMER, TENANT_A);
        service.closeNotEstablished(command("60000000-0000-0000-0000-000000000132"), parent.findingId(), 1L,
                new NotEstablishedCommand(ConfirmationStage.PERSON, "Not substantiated"));
        FindingWorkflowCase parentAfterClose = repository.finding(TENANT_A, parent.findingId()).orElseThrow();
        FindingWorkflowContracts.CreateFinding relatedRequest = relatedFinding(
                "New evidence after closure", parent.findingId());
        asMember(CREATOR, TENANT_A);

        FindingWorkflowContracts.CommandResult child = service.create(
                command("60000000-0000-0000-0000-000000000133"), relatedRequest);

        assertThat(child.findingId()).isNotEqualTo(parent.findingId());
        assertThat(repository.finding(TENANT_A, child.findingId()).orElseThrow().relatedFindingId())
                .isEqualTo(parent.findingId());
        assertThat(repository.finding(TENANT_A, parent.findingId()).orElseThrow())
                .isEqualTo(parentAfterClose);
        assertThat(repository.finding(TENANT_A, parent.findingId()).orElseThrow().state())
                .isEqualTo(FindingWorkflowState.NOT_ESTABLISHED);
    }

    @Test
    void relatedFindingLookupDoesNotCrossTenantEvenWhenTheUuidMatches() {
        UUID parentId = UUID.fromString("40000000-0000-0000-0000-000000000134");
        repository.seed(new FindingWorkflowCase(TENANT_B, parentId, ORGANIZATION_ID, "YH-TENANT-B-1",
                "Private tenant B finding", DISCOVERED_AT, null, null, "UNKNOWN",
                FindingWorkflowState.NOT_ESTABLISHED, 1, 2, CREATOR, PERSON_CONFIRMER));
        FindingWorkflowContracts.CreateFinding request = relatedFinding("Tenant A report", parentId);

        assertThatThrownBy(() -> service.create(command("60000000-0000-0000-0000-000000000135"), request))
                .isInstanceOf(FindingNotVisibleException.class);

        assertThat(repository.findingCount()).isEqualTo(1);
        assertThat(repository.finding(TENANT_B, parentId).orElseThrow().state())
                .isEqualTo(FindingWorkflowState.NOT_ESTABLISHED);
        assertThat(workflow.startedProcesses).isEmpty();
    }

    @Test
    void relatedFindingRequiresCreatePermissionAndAnAlreadyNotEstablishedParent() {
        FindingWorkflowContracts.CommandResult parent = service.create(
                command("60000000-0000-0000-0000-000000000136"), newFinding());
        FindingWorkflowContracts.CreateFinding request = relatedFinding("Related evidence", parent.findingId());
        long countBefore = repository.findingCount();

        accessPolicy.allowed = false;
        assertThatThrownBy(() -> service.create(command("60000000-0000-0000-0000-000000000137"), request))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(repository.findingCount()).isEqualTo(countBefore);
        accessPolicy.allowed = true;

        assertThatThrownBy(() -> service.create(command("60000000-0000-0000-0000-000000000138"), request))
                .isInstanceOf(com.mightyoung.softwarefactory.dualprevention.finding.workflow
                        .FindingWorkflowRuleException.class);
        assertThat(repository.findingCount()).isEqualTo(countBefore);
        assertThat(repository.finding(TENANT_A, parent.findingId()).orElseThrow().state())
                .isEqualTo(FindingWorkflowState.PERSON_CONFIRM_PENDING);
    }

    @Test
    void relatedFindingRequiresCurrentReadPermissionForTheParent() {
        FindingWorkflowContracts.CommandResult parent = service.create(
                command("60000000-0000-0000-0000-000000000139"), newFinding());
        asMember(PERSON_CONFIRMER, TENANT_A);
        service.closeNotEstablished(command("60000000-0000-0000-0000-000000000140"), parent.findingId(), 1L,
                new NotEstablishedCommand(ConfirmationStage.PERSON, "Not substantiated"));

        FindingWorkflowContracts.CreateFinding request = relatedFinding("Related evidence", parent.findingId());
        long countBefore = repository.findingCount();
        accessPolicy.deniedPermission = "finding.read";
        asMember(CREATOR, TENANT_A);

        assertThatThrownBy(() -> service.create(command("60000000-0000-0000-0000-000000000141"), request))
                .isInstanceOf(FindingNotVisibleException.class);

        assertThat(repository.findingCount()).isEqualTo(countBefore);
        assertThat(repository.finding(TENANT_A, parent.findingId()).orElseThrow().state())
                .isEqualTo(FindingWorkflowState.NOT_ESTABLISHED);
        assertThat(workflow.startedProcesses).hasSize((int) countBefore);
    }

    @Test
    void workflowCompletionFailureRollsBackSafetyGradeOpinionAndCommandReceipt() {
        FindingWorkflowContracts.CommandResult created = service.create(
                command("60000000-0000-0000-0000-000000000089"), newFinding());
        asMember(PERSON_CONFIRMER, TENANT_A);
        service.confirmPerson(command("60000000-0000-0000-0000-000000000090"), created.findingId(), 1L,
                new FindingWorkflowContracts.PersonConfirmationCommand("Personnel review complete"));
        asMember(SAFETY_MEMBER, TENANT_A);
        UUID key = command("60000000-0000-0000-0000-000000000091");
        FindingWorkflowContracts.SafetyConfirmationCommand request =
                new FindingWorkflowContracts.SafetyConfirmationCommand("Safety review complete", "MAJOR");
        workflow.failNextCompletion = true;

        assertThatThrownBy(() -> service.confirmSafety(key, created.findingId(), 2L, request))
                .isInstanceOf(IllegalStateException.class);

        FindingWorkflowCase rolledBack = repository.finding(TENANT_A, created.findingId()).orElseThrow();
        assertThat(rolledBack.state()).isEqualTo(FindingWorkflowState.SAFETY_CONFIRM_PENDING);
        assertThat(rolledBack.grade()).isEqualTo("UNKNOWN");
        assertThat(repository.confirmations).hasSize(1);
        assertThat(repository.transitions).hasSize(2);
        assertThat(workflow.completedTasks).containsExactly("personConfirm");
        assertThat(publishedEvents).hasSize(2);

        FindingWorkflowContracts.CommandResult retried = service.confirmSafety(key, created.findingId(), 2L, request);
        assertThat(retried.state()).isEqualTo(FindingWorkflowState.SAFETY_DISPATCH_PENDING);
        assertThat(repository.finding(TENANT_A, created.findingId()).orElseThrow().grade()).isEqualTo("MAJOR");
        assertThat(repository.confirmations.getLast().confirmation().reason()).isEqualTo("Safety review complete");
        assertThat(repository.transitions.getLast().reason()).isEqualTo("Safety review complete");
    }

    @Test
    void safetyConfirmationCannotBorrowPersonHistoryFromAnotherTenantWithTheSameFindingId() {
        UUID findingId = UUID.fromString("40000000-0000-0000-0000-000000000092");
        repository.seed(safetyPendingFinding(TENANT_A, findingId));
        repository.seed(safetyPendingFinding(TENANT_B, findingId));
        repository.seedConfirmation(TENANT_B, confirmation(findingId, "PERSON", PERSON_CONFIRMER,
                "Tenant B review", "60000000-0000-0000-0000-000000000092"));
        workflow.tasks.put(findingId, new MemoryWorkflowFacade.Task("safetyConfirm", SAFETY_MEMBER.toString()));
        workflow.processVariables.put(findingId, new HashMap<>(Map.of(
                "safetyDispatcherMemberId", SAFETY_MEMBER.toString())));
        asMember(SAFETY_MEMBER, TENANT_A);

        assertThatThrownBy(() -> service.confirmSafety(command("60000000-0000-0000-0000-000000000093"),
                findingId, 2L, new FindingWorkflowContracts.SafetyConfirmationCommand(
                        "Tenant A safety review", "GENERAL")))
                .isInstanceOf(FindingWorkflowConfigurationException.class);

        assertThat(repository.finding(TENANT_A, findingId).orElseThrow().grade()).isEqualTo("UNKNOWN");
        assertThat(repository.finding(TENANT_A, findingId).orElseThrow().state())
                .isEqualTo(FindingWorkflowState.SAFETY_CONFIRM_PENDING);
        assertThat(repository.confirmations).hasSize(1);
        assertThat(repository.transitions).isEmpty();
        assertThat(workflow.completedTasks).isEmpty();
    }

    @Test
    void safetyConfirmationReadsOwnTenantHistoryAndLegitimateReplayStillWorks() {
        UUID findingId = UUID.fromString("40000000-0000-0000-0000-000000000093");
        repository.seed(safetyPendingFinding(TENANT_A, findingId));
        repository.seed(safetyPendingFinding(TENANT_B, findingId));
        repository.seedConfirmation(TENANT_B, confirmation(findingId, "PERSON", SAFETY_MEMBER,
                "Tenant B review", "60000000-0000-0000-0000-000000000094"));
        repository.seedConfirmation(TENANT_A, confirmation(findingId, "PERSON", PERSON_CONFIRMER,
                "Tenant A review", "60000000-0000-0000-0000-000000000095"));
        workflow.tasks.put(findingId, new MemoryWorkflowFacade.Task("safetyConfirm", SAFETY_MEMBER.toString()));
        workflow.processVariables.put(findingId, new HashMap<>(Map.of(
                "safetyDispatcherMemberId", SAFETY_MEMBER.toString())));
        asMember(SAFETY_MEMBER, TENANT_A);
        UUID key = command("60000000-0000-0000-0000-000000000096");
        FindingWorkflowContracts.SafetyConfirmationCommand request =
                new FindingWorkflowContracts.SafetyConfirmationCommand("Tenant A safety review", "MAJOR");

        FindingWorkflowContracts.CommandResult first = service.confirmSafety(key, findingId, 2L, request);
        FindingWorkflowContracts.CommandResult replay = service.confirmSafety(key, findingId, 2L, request);

        assertThat(replay).isEqualTo(first);
        assertThat(repository.finding(TENANT_A, findingId).orElseThrow().grade()).isEqualTo("MAJOR");
        assertThat(repository.finding(TENANT_B, findingId).orElseThrow().grade()).isEqualTo("UNKNOWN");
        assertThat(repository.confirmation(TENANT_A, findingId, "SAFETY")).isPresent();
        assertThat(repository.confirmation(TENANT_B, findingId, "SAFETY")).isEmpty();
        assertThat(repository.transitions).hasSize(1);
        assertThat(repository.transitions.getFirst().reason()).isEqualTo("Tenant A safety review");
        assertThat(workflow.completedTasks).containsExactly("safetyConfirm");
    }

    @Test
    void missingEvidenceCannotAdvanceAndFailureRollsBackBusinessWrites() {
        FindingWorkflowContracts.CommandResult created = service.create(
                command("60000000-0000-0000-0000-000000000021"), newFinding());
        UUID findingId = created.findingId();
        asMember(PERSON_CONFIRMER, TENANT_A);
        confirmPerson("60000000-0000-0000-0000-000000000022", findingId, 1L);
        asMember(SAFETY_MEMBER, TENANT_A);
        confirmSafety("60000000-0000-0000-0000-000000000023", findingId, 2L);
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
        assertThatThrownBy(() -> confirmPerson(
                "60000000-0000-0000-0000-000000000032", findingId, 1L))
                .isInstanceOf(AccessDeniedException.class);
        accessPolicy.allowed = true;

        asMember(PERSON_CONFIRMER, TENANT_B);
        assertThatThrownBy(() -> confirmPerson(
                "60000000-0000-0000-0000-000000000033", findingId, 1L))
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
        confirmPerson("60000000-0000-0000-0000-000000000042", findingId, 1L);
        asMember(SAFETY_MEMBER, TENANT_A);
        confirmSafety("60000000-0000-0000-0000-000000000043", findingId, 2L);
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
        confirmPerson("60000000-0000-0000-0000-000000000052", findingId, 1L);
        asMember(SAFETY_MEMBER, TENANT_A);
        confirmSafety("60000000-0000-0000-0000-000000000053", findingId, 2L);
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
                FindingWorkflowState.DISCOVERED, 1, 1, CREATOR, CREATOR,
                null, null, null, null, null);
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

    private FindingWorkflowContracts.CommandResult confirmPerson(String commandId, UUID findingId, long version) {
        return service.confirmPerson(command(commandId), findingId, version,
                new FindingWorkflowContracts.PersonConfirmationCommand("Personnel review complete"));
    }

    private FindingWorkflowContracts.CommandResult confirmSafety(String commandId, UUID findingId, long version) {
        return service.confirmSafety(command(commandId), findingId, version,
                new FindingWorkflowContracts.SafetyConfirmationCommand("Safety review complete", "GENERAL"));
    }

    private static FindingWorkflowContracts.CreateFinding newFinding() {
        return newFinding(DISCOVERED_AT);
    }

    private static FindingWorkflowContracts.CreateFinding newFinding(OffsetDateTime discoveredAt) {
        return new FindingWorkflowContracts.CreateFinding(
                ORGANIZATION_ID, "Synthetic defect", discoveredAt, null, null, "UNKNOWN",
                FindingSourceCategory.FIELD_OBSERVATION, FindingUrgency.NORMAL,
                "North loading dock", "Temporary barrier installed");
    }

    private static FindingWorkflowContracts.CreateFinding relatedFinding(String description, UUID parentId) {
        return new FindingWorkflowContracts.CreateFinding(
                ORGANIZATION_ID, description, DISCOVERED_AT, null, null, "UNKNOWN",
                FindingSourceCategory.FIELD_OBSERVATION, FindingUrgency.NORMAL,
                "North loading dock", "Temporary barrier installed", parentId);
    }

    private static FindingWorkflowCase safetyPendingFinding(UUID tenantId, UUID findingId) {
        return new FindingWorkflowCase(tenantId, findingId, ORGANIZATION_ID, "YH-20261031-0092",
                "Tenant-scoped safety finding", DISCOVERED_AT, null, null, "UNKNOWN",
                FindingWorkflowState.SAFETY_CONFIRM_PENDING, 1, 2, CREATOR, PERSON_CONFIRMER,
                null, null, null, null, null);
    }

    private static FindingWorkflowContracts.Confirmation confirmation(
            UUID findingId, String stage, UUID memberId, String reason, String commandId) {
        return new FindingWorkflowContracts.Confirmation(findingId, stage, memberId,
                FindingWorkflowContracts.ConfirmationDecision.APPROVE, reason,
                OffsetDateTime.parse("2026-10-31T10:00:00Z"), 1L, command(commandId));
    }

    private static String legacyConfirmationHash(String action, UUID findingId, long expectedVersion) {
        String canonical = hashPart(action) + hashPart(findingId.toString()) + hashPart(Long.toString(expectedVersion));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String legacyConfirmationHashWithOpinion(
            String action, UUID findingId, long expectedVersion, String reason, String grade) {
        String canonical = hashPart(action) + hashPart(findingId.toString()) + hashPart(Long.toString(expectedVersion))
                + hashPart("confirmation-payload-v2") + hashPart(reason)
                + (grade == null ? "-1:" : hashPart(grade));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String hashPart(String value) {
        return value.length() + ":" + value;
    }

    private FindingWorkflowCommandService serviceAt(Instant instant) {
        return new FindingWorkflowCommandService(new SessionContextResolver(), repository,
                accessPolicy, assignmentResolver, evidencePolicy, workflow, publishedEvents::add,
                Clock.fixed(instant, ZoneOffset.UTC));
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
        private String deniedPermission;
        private final Set<UUID> permittedTenants = new java.util.HashSet<>(Set.of(TENANT_A));
        private final Set<UUID> inactiveMembers = new java.util.HashSet<>();

        @Override
        public void requireAction(CurrentTenantContext context, String permissionCode, UUID organizationId) {
            if (!allowed || permissionCode.equals(deniedPermission)
                    || !permittedTenants.contains(context.tenantId()) || organizationId == null) {
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
        private List<VerifiedFileEvidenceSnapshot> lastPinnedSnapshots = List.of();

        private void allow(UUID evidenceSetId, UUID tenantId, UUID findingId, int roundNo, UUID ownerMemberId) {
            ready.put(evidenceSetId, new EvidenceScope(tenantId, findingId, roundNo, ownerMemberId));
        }

        @Override
        public List<VerifiedFileEvidenceSnapshot> requireReady(
                CurrentTenantContext context, UUID findingId, int roundNo, UUID evidenceSetId) {
            EvidenceScope scope = ready.get(evidenceSetId);
            if (scope == null || !scope.tenantId().equals(context.tenantId())
                    || !scope.findingId().equals(findingId) || scope.roundNo() != roundNo
                    || !scope.ownerMemberId().equals(context.memberId())) {
                throw new FindingWorkflowEvidenceException("A ready evidence set is required");
            }
            return List.of(new VerifiedFileEvidenceSnapshot(context.tenantId(),
                    UUID.fromString("51000000-0000-0000-0000-000000000001"),
                    UUID.fromString("52000000-0000-0000-0000-000000000001"),
                    UUID.fromString("53000000-0000-0000-0000-000000000001"),
                    context.memberId(), context.actorId(), "a".repeat(64), "synthetic-version-1",
                    UUID.fromString("54000000-0000-0000-0000-000000000001"),
                    "synthetic-verifier", "synthetic-key-1", "content-policy-v1",
                    FileEvidenceVerificationLevel.CONTENT, OffsetDateTime.parse("2026-10-30T10:30:00Z")));
        }

        @Override
        public void requirePinnedEvidence(List<VerifiedFileEvidenceSnapshot> snapshots) {
            lastPinnedSnapshots = List.copyOf(snapshots);
            if (snapshots.isEmpty()) throw new FindingWorkflowEvidenceException("Pinned evidence is required");
        }

        private record EvidenceScope(UUID tenantId, UUID findingId, int roundNo, UUID ownerMemberId) {}
    }

    private static final class MemoryWorkflowFacade implements WorkflowFacade {
        private final Map<UUID, Task> tasks = new HashMap<>();
        private final Map<UUID, Map<String, Object>> processVariables = new HashMap<>();
        private final Map<UUID, String> processDefinitionKeys = new HashMap<>();
        private final List<String> startedProcesses = new ArrayList<>();
        private final List<String> completedTasks = new ArrayList<>();
        private boolean failNextCompletion;

        private void assignCurrentTask(UUID findingId, UUID memberId) {
            Task current = tasks.get(findingId);
            if (current == null) throw new IllegalStateException("No active synthetic workflow task");
            tasks.put(findingId, new Task(current.key(), memberId.toString()));
        }

        @Override
        public void startProcess(UUID tenantId, String businessType, UUID businessId, int businessRevision,
                                 String processDefinitionKey, Map<String, Object> variables) {
            startedProcesses.add(processDefinitionKey);
            processDefinitionKeys.put(businessId, processDefinitionKey);
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
                case "personConfirm" -> "NOT_ESTABLISHED".equals(activeVariables.get("confirmationDecision"))
                        ? "END" : "safetyConfirm";
                case "safetyConfirm" -> "NOT_ESTABLISHED".equals(activeVariables.get("confirmationDecision"))
                        ? "END" : "safetyDispatch";
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

        @Override
        public Optional<String> processDefinitionKey(UUID tenantId, String businessType, UUID businessId) {
            return Optional.ofNullable(processDefinitionKeys.get(businessId));
        }

        private record Task(String key, String assignee) {}
    }

    private static final class MemoryRepository implements FindingWorkflowRepository {
        private Map<FindingKey, FindingWorkflowCase> findings = new HashMap<>();
        private Map<String, StoredCommand> commands = new HashMap<>();
        private List<FindingWorkflowContracts.Transition> transitions = new ArrayList<>();
        private List<StoredConfirmation> confirmations = new ArrayList<>();
        private List<FindingWorkflowContracts.Notice> notices = new ArrayList<>();
        private List<FindingWorkflowContracts.Submission> submissions = new ArrayList<>();
        private List<FindingWorkflowContracts.Verification> verifications = new ArrayList<>();
        private Map<String, String> rounds = new HashMap<>();
        private Map<SequenceKey, Long> numberSequences = new HashMap<>();

        @Override
        public <T> T inTenantTransaction(UUID tenantId,
                                         java.util.function.Function<Transaction, T> work) {
            Map<FindingKey, FindingWorkflowCase> priorFindings = new HashMap<>(findings);
            Map<String, StoredCommand> priorCommands = new HashMap<>(commands);
            List<FindingWorkflowContracts.Transition> priorTransitions = new ArrayList<>(transitions);
            List<StoredConfirmation> priorConfirmations = new ArrayList<>(confirmations);
            List<FindingWorkflowContracts.Notice> priorNotices = new ArrayList<>(notices);
            List<FindingWorkflowContracts.Submission> priorSubmissions = new ArrayList<>(submissions);
            List<FindingWorkflowContracts.Verification> priorVerifications = new ArrayList<>(verifications);
            Map<String, String> priorRounds = new HashMap<>(rounds);
            Map<SequenceKey, Long> priorSequences = new HashMap<>(numberSequences);
            try {
                return work.apply(new Tx(tenantId));
            } catch (RuntimeException failure) {
                findings = priorFindings;
                commands = priorCommands;
                transitions = priorTransitions;
                confirmations = priorConfirmations;
                notices = priorNotices;
                submissions = priorSubmissions;
                verifications = priorVerifications;
                rounds = priorRounds;
                numberSequences = priorSequences;
                throw failure;
            }
        }

        private Optional<FindingWorkflowCase> finding(UUID tenantId, UUID id) {
            return Optional.ofNullable(findings.get(new FindingKey(tenantId, id)));
        }

        private long findingCount() { return findings.size(); }

        private void seed(FindingWorkflowCase finding) {
            findings.put(new FindingKey(finding.tenantId(), finding.id()), finding);
        }

        private void seedConfirmation(UUID tenantId, FindingWorkflowContracts.Confirmation confirmation) {
            confirmations.add(new StoredConfirmation(tenantId, confirmation));
        }

        private void seedCommand(
                UUID tenantId,
                UUID commandId,
                UUID actorId,
                String action,
                String requestHash,
                FindingWorkflowContracts.CommandResult result) {
            commands.put(tenantId + ":" + commandId,
                    new StoredCommand(actorId, action, requestHash, result));
        }

        private StoredCommand command(UUID tenantId, UUID commandId) {
            return commands.get(tenantId + ":" + commandId);
        }

        private Optional<FindingWorkflowContracts.Confirmation> confirmation(
                UUID tenantId, UUID findingId, String stage) {
            return confirmations.stream()
                    .filter(row -> row.tenantId().equals(tenantId)
                            && row.confirmation().findingId().equals(findingId)
                            && row.confirmation().stage().equals(stage))
                    .map(StoredConfirmation::confirmation)
                    .findFirst();
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
                FindingKey key = new FindingKey(finding.tenantId(), finding.id());
                if (!finding.tenantId().equals(tenantId) || findings.putIfAbsent(key, finding) != null) {
                    throw new IllegalStateException("Duplicate finding");
                }
            }

            @Override
            public String nextFindingCode(LocalDate businessDay) {
                long next = numberSequences.merge(new SequenceKey(tenantId, businessDay), 1L, Long::sum);
                return "YH-" + businessDay.toString().replace("-", "")
                        + "-" + String.format(java.util.Locale.ROOT, "%04d", next);
            }

            @Override
            public void updateFinding(FindingWorkflowCase finding, long expectedVersion) {
                FindingKey key = new FindingKey(tenantId, finding.id());
                FindingWorkflowCase previous = findings.get(key);
                if (previous == null || !previous.tenantId().equals(tenantId)
                        || previous.version() != expectedVersion || !finding.tenantId().equals(tenantId)) {
                    throw new FindingWorkflowVersionConflictException();
                }
                findings.put(key, finding);
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
                confirmations.add(new StoredConfirmation(tenantId, confirmation));
            }

            @Override
            public Optional<UUID> findConfirmationActorMemberId(UUID findingId, String stage) {
                return confirmations.stream()
                        .filter(row -> row.tenantId().equals(tenantId)
                                && row.confirmation().findingId().equals(findingId)
                                && row.confirmation().stage().equals(stage))
                        .map(row -> row.confirmation().actorMemberId())
                        .findFirst();
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
        private record StoredConfirmation(UUID tenantId, FindingWorkflowContracts.Confirmation confirmation) {}
    }

    private record FindingKey(UUID tenantId, UUID findingId) {}
    private record SequenceKey(UUID tenantId, LocalDate businessDay) {}
}
