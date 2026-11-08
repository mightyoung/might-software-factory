package com.mightyoung.softwarefactory.dualprevention.finding.workflow;

import com.mightyoung.softwarefactory.dualprevention.FindingNotVisibleException;
import com.mightyoung.softwarefactory.dualprevention.finding.workflow.FindingWorkflowContracts.CommandClaim;
import com.mightyoung.softwarefactory.dualprevention.finding.workflow.FindingWorkflowContracts.CommandResult;
import com.mightyoung.softwarefactory.dualprevention.finding.workflow.FindingWorkflowContracts.Confirmation;
import com.mightyoung.softwarefactory.dualprevention.finding.workflow.FindingWorkflowContracts.ConfirmationDecision;
import com.mightyoung.softwarefactory.dualprevention.finding.workflow.FindingWorkflowContracts.ConfirmationStage;
import com.mightyoung.softwarefactory.dualprevention.finding.workflow.FindingWorkflowContracts.CreateFinding;
import com.mightyoung.softwarefactory.dualprevention.finding.workflow.FindingWorkflowContracts.Dispatch;
import com.mightyoung.softwarefactory.dualprevention.finding.workflow.FindingWorkflowContracts.InitialRoute;
import com.mightyoung.softwarefactory.dualprevention.finding.workflow.FindingWorkflowContracts.Notice;
import com.mightyoung.softwarefactory.dualprevention.finding.workflow.FindingWorkflowContracts.NotEstablishedCommand;
import com.mightyoung.softwarefactory.dualprevention.finding.workflow.FindingWorkflowContracts.PersonConfirmationCommand;
import com.mightyoung.softwarefactory.dualprevention.finding.workflow.FindingWorkflowContracts.SafetyConfirmationCommand;
import com.mightyoung.softwarefactory.dualprevention.finding.workflow.FindingWorkflowContracts.Submission;
import com.mightyoung.softwarefactory.dualprevention.finding.workflow.FindingWorkflowContracts.SubmitRemediation;
import com.mightyoung.softwarefactory.dualprevention.finding.workflow.FindingWorkflowContracts.Transition;
import com.mightyoung.softwarefactory.dualprevention.finding.workflow.FindingWorkflowContracts.Verification;
import com.mightyoung.softwarefactory.dualprevention.finding.workflow.FindingWorkflowContracts.Verify;
import com.mightyoung.softwarefactory.dualprevention.finding.workflow.FindingWorkflowContracts.WorkflowCommand;
import com.mightyoung.softwarefactory.identity.CurrentTenantContext;
import com.mightyoung.softwarefactory.identity.SessionContextResolver;
import com.mightyoung.softwarefactory.files.VerifiedFileEvidenceSnapshot;
import com.mightyoung.softwarefactory.workflow.WorkflowFacade;
import com.mightyoung.softwarefactory.workflow.FindingReviewDefinitionProperties;
import com.mightyoung.softwarefactory.workflow.PinnedFindingReviewDefinitionResolver;
import com.mightyoung.softwarefactory.workflow.WorkflowDefinitionPin;
import com.mightyoung.softwarefactory.workflow.WorkflowCommandTaskAuthority;
import com.mightyoung.softwarefactory.workflow.WorkflowTaskReadSnapshot;
import com.mightyoung.softwarefactory.workflow.WorkflowTaskStateConflictException;
import com.mightyoung.softwarefactory.dualprevention.finding.workflow.FindingReviewCycleContracts.*;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Orchestrates every finding lifecycle change through one authorized, transactional command seam. */
@Service
public final class FindingWorkflowCommandService {

    public static final String BUSINESS_TYPE = "FINDING";
    public static final String PROCESS_DEFINITION_KEY = "findingRemediationV2";

    public static final String REVIEW_PROCESS_DEFINITION_KEY = "findingRemediationV3";
    private static final String REVIEW_DECISION = "finding.review.decision";
    private static final String REPORT_SUPPLEMENT = "finding.report.supplement";

    private static final String CREATE = "finding.create";
    private static final String FINDING_READ = "finding.read";
    private static final String INITIALIZE_DISCOVERED = "finding.workflow.initialize";
    private static final String PERSON_CONFIRM = "finding.confirm.person";
    private static final String SAFETY_CONFIRM = "finding.confirm.safety";
    private static final String CONFIRMATION_FINGERPRINT_VERSION = "confirmation-payload-v2";
    private static final String NOT_ESTABLISHED_FINGERPRINT_VERSION = "finding-not-established-v2";
    private static final String DISPATCH = "finding.dispatch";
    private static final String REMEDIATE = "finding.remediate";
    private static final String VERIFY = "finding.verify";

    private final SessionContextResolver sessionContextResolver;
    private final FindingWorkflowRepository repository;
    private final FindingWorkflowAccessPolicy accessPolicy;
    private final FindingWorkflowAssignmentResolver assignmentResolver;
    private final FindingWorkflowEvidencePolicy evidencePolicy;
    private final WorkflowFacade workflowFacade;
    private final FindingWorkflowEventPublisher eventPublisher;
    private final Clock clock;
    private final FindingReviewDefinitionProperties reviewDefinitions;
    private final PinnedFindingReviewDefinitionResolver reviewDefinitionResolver;

    public FindingWorkflowCommandService(
            SessionContextResolver sessionContextResolver,
            FindingWorkflowRepository repository,
            FindingWorkflowAccessPolicy accessPolicy,
            FindingWorkflowAssignmentResolver assignmentResolver,
            FindingWorkflowEvidencePolicy evidencePolicy,
            WorkflowFacade workflowFacade,
            FindingWorkflowEventPublisher eventPublisher,
            Clock clock,
            FindingReviewDefinitionProperties reviewDefinitions,
            PinnedFindingReviewDefinitionResolver reviewDefinitionResolver) {
        this.sessionContextResolver = Objects.requireNonNull(sessionContextResolver, "sessionContextResolver");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.accessPolicy = Objects.requireNonNull(accessPolicy, "accessPolicy");
        this.assignmentResolver = Objects.requireNonNull(assignmentResolver, "assignmentResolver");
        this.evidencePolicy = Objects.requireNonNull(evidencePolicy, "evidencePolicy");
        this.workflowFacade = Objects.requireNonNull(workflowFacade, "workflowFacade");
        this.eventPublisher = Objects.requireNonNull(eventPublisher, "eventPublisher");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.reviewDefinitions = Objects.requireNonNull(reviewDefinitions, "reviewDefinitions");
        this.reviewDefinitionResolver = Objects.requireNonNull(reviewDefinitionResolver, "reviewDefinitionResolver");
    }

    public CommandResult create(UUID commandId, CreateFinding request) {
        Objects.requireNonNull(commandId, "commandId");
        Objects.requireNonNull(request, "request");
        CurrentTenantContext context = sessionContextResolver.currentTenant();
        OffsetDateTime submittedAt = OffsetDateTime.now(clock);
        LocalDate businessDay = submittedAt.toInstant().atZone(ZoneId.of("Asia/Shanghai")).toLocalDate();
        boolean backfill = Duration.between(request.discoveredAt().toInstant(), submittedAt.toInstant())
                .compareTo(Duration.ofHours(24)) > 0;
        WorkflowCommand command = request.relatedFindingId() == null
                ? command(commandId, context, CREATE,
                        request.organizationId(), request.description(), request.discoveredAt(),
                        request.sourceEventId(), request.riskId(), request.grade(), request.sourceCategory(),
                        request.urgency(), request.location(), request.interimMeasure())
                : command(commandId, context, CREATE,
                        request.organizationId(), request.description(), request.discoveredAt(),
                        request.sourceEventId(), request.riskId(), request.grade(), request.sourceCategory(),
                        request.urgency(), request.location(), request.interimMeasure(),
                        "relatedFindingId", request.relatedFindingId());

        return repository.inTenantTransaction(context.tenantId(), tx -> {
            accessPolicy.requireAction(context, CREATE, request.organizationId());
            if (request.relatedFindingId() != null) {
                FindingWorkflowCase related = visibleForCommand(tx, request.relatedFindingId());
                try {
                    accessPolicy.requireAction(context, FINDING_READ, related.organizationId());
                } catch (org.springframework.security.access.AccessDeniedException denied) {
                    throw new FindingNotVisibleException();
                }
                if (related.state() != FindingWorkflowState.NOT_ESTABLISHED) {
                    throw new FindingWorkflowRuleException(
                            "New evidence may only link to a not-established finding");
                }
            }
            WorkflowDefinitionPin reviewPin = null;
            if (reviewDefinitions.initiationEnabled()) {
                var replay = tx.readCommand(command);
                if (replay.isPresent()) return replay.get();
                reviewPin = reviewDefinitionResolver.resolve(context.tenantId());
            }
            CommandClaim claim = tx.claimCommand(command);
            if (!claim.acquired()) return claim.replay();

            InitialRoute route = assignmentResolver.resolve(context.tenantId(), request.organizationId());
            requireRouteMembers(context, route, request.organizationId(), context.memberId());
            String code = tx.nextFindingCode(businessDay);
            UUID findingId = UUID.randomUUID();
            FindingWorkflowCase finding = FindingWorkflowCase.create(
                    context.tenantId(), findingId, request.organizationId(), code, request.description(),
                    request.discoveredAt(), request.sourceEventId(), request.riskId(), request.grade(),
                    context.memberId(), request.sourceCategory(), request.urgency(), request.location(),
                    request.interimMeasure(), backfill, request.relatedFindingId(),
                    reviewPin == null ? FindingReviewProtocol.LEGACY : FindingReviewProtocol.CYCLE_V1);
            tx.insertFinding(finding);
            if (reviewPin == null) {
                workflowFacade.startProcess(context.tenantId(), BUSINESS_TYPE, findingId, 1,
                        PROCESS_DEFINITION_KEY, processVariables(findingId, route));
            } else {
                tx.appendReviewCycle(new ReviewCycle(findingId, 1, commandId, now()));
                workflowFacade.startProcessByDefinitionId(context.tenantId(), BUSINESS_TYPE, findingId, 1,
                        reviewPin, reviewProcessVariables(findingId, finding.createdByMemberId(), route));
            }
            appendTransition(tx, null, finding, commandId, context.actorId(), null);
            CommandResult result = CommandResult.from(finding);
            tx.completeCommand(commandId, result);
            publishChange(context, commandId, CREATE, null, finding);
            return result;
        });
    }

    /** Starts the new command workflow for a preserved pre-v0.2 DISCOVERED finding, one row at a time. */
    public CommandResult initializeDiscoveredWorkflow(
            UUID commandId, UUID findingId, long expectedVersion) {
        Objects.requireNonNull(commandId, "commandId");
        CurrentTenantContext context = sessionContextResolver.currentTenant();
        return repository.inTenantTransaction(context.tenantId(), tx -> {
            FindingWorkflowCase current = visibleForCommand(tx, findingId);
            accessPolicy.requireAction(context, INITIALIZE_DISCOVERED, current.organizationId());
            WorkflowCommand command = command(commandId, context, INITIALIZE_DISCOVERED, findingId, expectedVersion);
            WorkflowDefinitionPin reviewPin = null;
            FindingWorkflowCase cycleInitialized = null;
            if (reviewDefinitions.initiationEnabled()) {
                var replay = tx.readCommand(command);
                if (replay.isPresent()) return replay.get();
                if (workflowFacade.readBoundDefinition(context.tenantId(), BUSINESS_TYPE, findingId).isPresent()) {
                    throw new WorkflowTaskStateConflictException();
                }
                cycleInitialized = current.initializeDiscoveredReviewWorkflow(context.memberId(), expectedVersion);
                reviewPin = reviewDefinitionResolver.resolve(context.tenantId());
            }
            CommandClaim claim = tx.claimCommand(command);
            if (!claim.acquired()) return claim.replay();
            requireExpectedVersion(current, expectedVersion);

            InitialRoute route = assignmentResolver.resolve(context.tenantId(), current.organizationId());
            requireRouteMembers(context, route, current.organizationId(), current.createdByMemberId());
            FindingWorkflowCase updated = cycleInitialized == null
                    ? current.initializeDiscoveredWorkflow(context.memberId(), expectedVersion) : cycleInitialized;
            tx.updateFinding(updated, current.version());
            if (reviewPin == null) {
                workflowFacade.startProcess(context.tenantId(), BUSINESS_TYPE, findingId, 1,
                        PROCESS_DEFINITION_KEY, processVariables(findingId, route));
            } else {
                tx.appendReviewCycle(new ReviewCycle(findingId, 1, commandId, now()));
                workflowFacade.startProcessByDefinitionId(context.tenantId(), BUSINESS_TYPE, findingId, 1,
                        reviewPin, reviewProcessVariables(findingId, current.createdByMemberId(), route));
            }
            appendTransition(tx, current, updated, commandId, context.actorId(), "Legacy workflow initialized");
            CommandResult result = CommandResult.from(updated);
            tx.completeCommand(commandId, result);
            publishChange(context, commandId, INITIALIZE_DISCOVERED, current, updated);
            return result;
        });
    }

    public CommandResult confirmPerson(
            UUID commandId, UUID findingId, long expectedVersion, PersonConfirmationCommand request) {
        Objects.requireNonNull(request, "request");
        return confirm(commandId, findingId, expectedVersion, PERSON_CONFIRM, "PERSON", "personConfirm",
                request.reason(), null);
    }

    public CommandResult confirmSafety(
            UUID commandId, UUID findingId, long expectedVersion, SafetyConfirmationCommand request) {
        Objects.requireNonNull(request, "request");
        return confirm(commandId, findingId, expectedVersion, SAFETY_CONFIRM, "SAFETY", "safetyConfirm",
                request.reason(), request.grade());
    }

    public CommandResult closeNotEstablished(
            UUID commandId, UUID findingId, long expectedVersion, NotEstablishedCommand request) {
        Objects.requireNonNull(request, "request");
        return legacyReview(commandId, findingId, expectedVersion,
                Objects.requireNonNull(request.stage(), "stage"), ConfirmationDecision.NOT_ESTABLISHED,
                notEstablishedReason(request.reason()), null);
    }

    public CommandResult reviewDecision(UUID commandId, UUID findingId, ReviewDecisionCommand request) {
        Objects.requireNonNull(commandId, "commandId");
        Objects.requireNonNull(request, "request");
        CurrentTenantContext context = sessionContextResolver.currentTenant();
        return repository.inTenantTransaction(context.tenantId(), tx -> {
            FindingWorkflowCase current = visibleForCommand(tx, findingId);
            String permission = permission(request.stage());
            accessPolicy.requireAction(context, permission, current.organizationId());
            requireReviewer(context, current, request.stage());
            WorkflowCommand command = command(commandId, context, REVIEW_DECISION, "finding-review-decision-v1",
                    findingId, request.expectedVersion(), request.reviewCycle(), request.stage(), request.decision(),
                    request.reason(), request.grade());
            var replay = tx.readCommand(command);
            requireProtocol(current, FindingReviewProtocol.CYCLE_V1);
            requireDefinition(context, findingId, REVIEW_PROCESS_DEFINITION_KEY);
            if (replay.isPresent()) {
                requirePersonProof(tx, current, context, request.stage(), request.reviewCycle(), true);
                requireReplayParticipant(tx, context, current, commandId, request.stage().name(),
                        request.reviewCycle(), request.expectedVersion(), request.reason(), request.decision().name(), false);
                return requireReplayResult(replay.get(), findingId, request.reviewCycle());
            }
            requireExpectedVersion(current, request.expectedVersion());
            requireCycle(current, request.reviewCycle());
            requirePersonProof(tx, current, context, request.stage(), request.reviewCycle(), true);
            WorkflowCommandTaskAuthority authority = workflowFacade.acquireTaskAuthority(context.tenantId(), BUSINESS_TYPE,
                    findingId, taskKey(request.stage()), context.memberId());
            requireAuthorityProtocol(authority, REVIEW_PROCESS_DEFINITION_KEY);
            claimFresh(tx, command);
            OffsetDateTime time = now();
            tx.appendParticipant(participant(commandId, current, context, request.stage().name(), authority, time));
            tx.appendReviewDecision(new Decision(findingId, request.reviewCycle(), request.stage(), context.memberId(),
                    request.decision(), request.reason(), request.grade(), request.expectedVersion(), commandId, time));
            FindingWorkflowCase updated = switch (request.decision()) {
                case RETURN_FOR_INFORMATION -> current.returnForInformation(context.memberId(), request.expectedVersion(),
                        request.reviewCycle(), request.stage());
                case NOT_ESTABLISHED -> current.closeNotEstablished(context.memberId(), request.expectedVersion(), request.stage().name());
                case APPROVE -> request.stage() == ConfirmationStage.PERSON
                        ? current.confirmByPerson(context.memberId(), request.expectedVersion())
                        : current.confirmBySafety(context.memberId(), request.expectedVersion(), request.grade());
            };
            tx.updateFinding(updated, current.version());
            appendTransition(tx, current, updated, commandId, context.actorId(), request.reason(), time);
            workflowFacade.completeTask(authority, Map.of("reviewDecision", request.decision().name(),
                    "reporterMemberId", current.createdByMemberId().toString()));
            CommandResult result = CommandResult.from(updated);
            tx.completeCommand(commandId, result);
            publishChange(context, commandId, REVIEW_DECISION, current, updated);
            return result;
        });
    }

    public CommandResult supplement(UUID commandId, UUID findingId, ReportSupplementCommand request) {
        Objects.requireNonNull(commandId, "commandId");
        Objects.requireNonNull(request, "request");
        CurrentTenantContext context = sessionContextResolver.currentTenant();
        return repository.inTenantTransaction(context.tenantId(), tx -> {
            FindingWorkflowCase current = visibleForCommand(tx, findingId);
            accessPolicy.requireAction(context, CREATE, current.organizationId());
            if (!context.memberId().equals(current.createdByMemberId())) {
                throw new org.springframework.security.access.AccessDeniedException("Only the original reporter may supplement");
            }
            WorkflowCommand command = command(commandId, context, REPORT_SUPPLEMENT, "finding-report-supplement-v1",
                    findingId, request.expectedVersion(), request.reviewCycle(), request.statement());
            var replay = tx.readCommand(command);
            requireProtocol(current, FindingReviewProtocol.CYCLE_V1);
            requireDefinition(context, findingId, REVIEW_PROCESS_DEFINITION_KEY);
            if (replay.isPresent()) {
                requireReplayParticipant(tx, context, current, commandId, "REPORTER_SUPPLEMENT", request.reviewCycle(),
                        request.expectedVersion(), request.statement(), null, false);
                return requireReplayResult(replay.get(), findingId, Math.addExact(request.reviewCycle(), 1));
            }
            requireExpectedVersion(current, request.expectedVersion());
            requireCycle(current, request.reviewCycle());
            ReviewPair pair = requireReviewPair(context, current);
            Decision returned = tx.findReturnDecision(findingId, request.reviewCycle())
                    .orElseThrow(() -> new FindingWorkflowConfigurationException("A same-cycle return decision is required"));
            FindingWorkflowCase updated = current.supplement(context.memberId(), request.expectedVersion(), request.reviewCycle());
            WorkflowCommandTaskAuthority authority = workflowFacade.acquireTaskAuthority(context.tenantId(), BUSINESS_TYPE,
                    findingId, "reporterSupplement", context.memberId());
            requireAuthorityProtocol(authority, REVIEW_PROCESS_DEFINITION_KEY);
            claimFresh(tx, command);
            OffsetDateTime time = now();
            tx.appendParticipant(participant(commandId, current, context, "REPORTER_SUPPLEMENT", authority, time));
            tx.appendReviewCycle(new ReviewCycle(findingId, updated.reviewCycle(), commandId, time));
            tx.appendSupplement(new Supplement(UUID.randomUUID(), findingId, current.reviewCycle(), updated.reviewCycle(),
                    returned.commandId(), context.memberId(), request.statement(), request.expectedVersion(), commandId, time));
            tx.updateFinding(updated, current.version());
            appendTransition(tx, current, updated, commandId, context.actorId(), request.statement(), time);
            workflowFacade.completeTask(authority, Map.of("reviewDecision", "",
                    "personConfirmerMemberId", pair.personMemberId().toString(),
                    "safetyConfirmerMemberId", pair.safetyMemberId().toString()));
            CommandResult result = CommandResult.from(updated);
            tx.completeCommand(commandId, result);
            publishChange(context, commandId, REPORT_SUPPLEMENT, current, updated);
            return result;
        });
    }

    public CommandResult dispatch(UUID commandId, UUID findingId, long expectedVersion, Dispatch request) {
        Objects.requireNonNull(request, "request");
        CurrentTenantContext context = sessionContextResolver.currentTenant();
        return repository.inTenantTransaction(context.tenantId(), tx -> {
            FindingWorkflowCase current = visibleForCommand(tx, findingId);
            accessPolicy.requireAction(context, DISPATCH, current.organizationId());
            WorkflowCommand command = command(commandId, context, DISPATCH, findingId, expectedVersion,
                    request.assigneeMemberId(), request.dueAt(), request.requirements());
            CommandClaim claim = tx.claimCommand(command);
            if (!claim.acquired()) return claim.replay();
            requireExpectedVersion(current, expectedVersion);
            validateDispatch(request);
            accessPolicy.requireAssignableMember(context, request.assigneeMemberId(), current.organizationId(), REMEDIATE);

            FindingWorkflowCase updated = current.dispatch(context.memberId(), expectedVersion);
            boolean reDispatch = current.state() == FindingWorkflowState.RECTIFYING && current.roundNo() > 1;
            if (reDispatch && tx.findNotice(findingId, current.roundNo()).isPresent()) {
                throw new FindingWorkflowRuleException("The rework round already has a remediation notice");
            }
            Notice notice = new Notice(UUID.randomUUID(), findingId, current.roundNo(), request.assigneeMemberId(),
                    request.dueAt(), request.dueAt(), request.requirements().trim());
            if (!reDispatch) tx.openRound(findingId, current.roundNo(), commandId);
            tx.insertNotice(notice, commandId);
            tx.updateFinding(updated, current.version());
            appendTransition(tx, current, updated, commandId, context.actorId(), null);
            workflowFacade.completeTask(context.tenantId(), BUSINESS_TYPE, findingId, "safetyDispatch",
                    context.memberId(), Map.of("remediationAssigneeMemberId", request.assigneeMemberId().toString()));
            CommandResult result = CommandResult.from(updated);
            tx.completeCommand(commandId, result);
            publishChange(context, commandId, DISPATCH, current, updated);
            return result;
        });
    }

    public CommandResult submitRemediation(
            UUID commandId, UUID findingId, long expectedVersion, SubmitRemediation request) {
        Objects.requireNonNull(request, "request");
        CurrentTenantContext context = sessionContextResolver.currentTenant();
        return repository.inTenantTransaction(context.tenantId(), tx -> {
            FindingWorkflowCase current = visibleForCommand(tx, findingId);
            accessPolicy.requireAction(context, REMEDIATE, current.organizationId());
            WorkflowCommand command = command(commandId, context, REMEDIATE, findingId, expectedVersion,
                    request.evidenceSetId(), request.statement());
            CommandClaim claim = tx.claimCommand(command);
            if (!claim.acquired()) return claim.replay();
            requireExpectedVersion(current, expectedVersion);
            if (request.statement() == null || request.statement().isBlank()) {
                throw new FindingWorkflowRuleException("A remediation statement is required");
            }
            List<VerifiedFileEvidenceSnapshot> evidenceSnapshots = evidencePolicy.requireReady(
                    context, findingId, current.roundNo(), request.evidenceSetId());
            Notice notice = tx.findNotice(findingId, current.roundNo())
                    .orElseThrow(() -> new FindingWorkflowConfigurationException(
                            "The active remediation notice is missing"));
            if (!notice.assigneeMemberId().equals(context.memberId())) {
                throw new org.springframework.security.access.AccessDeniedException(
                        "Only the assigned remediation member can submit this round");
            }

            FindingWorkflowCase updated = current.submitForAcceptance(context.memberId(), expectedVersion);
            OffsetDateTime now = now();
            Submission submission = new Submission(context.tenantId(), UUID.randomUUID(), findingId, notice.id(), current.roundNo(),
                    context.memberId(), now, request.evidenceSetId(), evidenceSnapshots,
                    request.statement().trim(), expectedVersion, commandId);
            tx.insertSubmission(submission);
            tx.updateFinding(updated, current.version());
            appendTransition(tx, current, updated, commandId, context.actorId(), null);
            workflowFacade.completeTask(context.tenantId(), BUSINESS_TYPE, findingId, "remediation",
                    context.memberId(), Map.of());
            CommandResult result = CommandResult.from(updated);
            tx.completeCommand(commandId, result);
            publishChange(context, commandId, REMEDIATE, current, updated);
            return result;
        });
    }

    public CommandResult verify(UUID commandId, UUID findingId, long expectedVersion, Verify request) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(request.decision(), "decision");
        if (request.decision() == VerificationDecision.FAIL
                && (request.reason() == null || request.reason().isBlank())) {
            throw new FindingWorkflowRuleException("A failed verification requires a reason");
        }
        CurrentTenantContext context = sessionContextResolver.currentTenant();
        return repository.inTenantTransaction(context.tenantId(), tx -> {
            FindingWorkflowCase current = visibleForCommand(tx, findingId);
            accessPolicy.requireAction(context, VERIFY, current.organizationId());
            WorkflowCommand command = command(commandId, context, VERIFY, findingId, expectedVersion,
                    request.decision(), request.reason());
            CommandClaim claim = tx.claimCommand(command);
            if (!claim.acquired()) return claim.replay();
            requireExpectedVersion(current, expectedVersion);
            Submission submission = tx.findSubmission(findingId, current.roundNo())
                    .orElseThrow(() -> new FindingWorkflowRuleException(
                            "A submitted remediation is required before verification"));
            evidencePolicy.requirePinnedEvidence(submission.evidenceSnapshots());
            if (submission.submittedByMemberId().equals(context.memberId())) {
                throw new org.springframework.security.access.AccessDeniedException(
                        "The remediation member cannot verify their own submission");
            }
            FindingWorkflowCase updated = current.verify(context.memberId(), expectedVersion, request.decision());
            OffsetDateTime now = now();
            tx.insertVerification(new Verification(findingId, submission.id(), current.roundNo(),
                    context.memberId(), request.decision(), cleanReason(request.reason()), now, commandId));
            tx.closeRound(findingId, current.roundNo(),
                    request.decision() == VerificationDecision.PASS ? "CLOSED" : "FAILED", commandId);
            Map<String, Object> taskVariables;
            if (request.decision() == VerificationDecision.FAIL) {
                UUID safetyDispatcher = assignmentResolver.resolveSafetyDispatcher(
                        context.tenantId(), current.organizationId());
                accessPolicy.requireAssignableMember(
                        context, safetyDispatcher, current.organizationId(), DISPATCH);
                tx.openRound(findingId, updated.roundNo(), commandId);
                taskVariables = Map.of(
                        "verificationDecision", request.decision().name(),
                        "safetyDispatcherMemberId", safetyDispatcher.toString());
            } else {
                taskVariables = Map.of("verificationDecision", request.decision().name());
            }
            tx.updateFinding(updated, current.version());
            appendTransition(tx, current, updated, commandId, context.actorId(), cleanReason(request.reason()));
            workflowFacade.completeTask(context.tenantId(), BUSINESS_TYPE, findingId, "safetyVerification",
                    context.memberId(), taskVariables);
            CommandResult result = CommandResult.from(updated);
            tx.completeCommand(commandId, result);
            publishChange(context, commandId, VERIFY, current, updated);
            return result;
        });
    }

    private CommandResult confirm(
            UUID commandId,
            UUID findingId,
            long expectedVersion,
            String permission,
            String stage,
            String taskKey,
            String requestedReason,
            String requestedGrade) {
        String reason = confirmationReason(requestedReason);
        String grade = "SAFETY".equals(stage) ? professionalGrade(requestedGrade) : null;
        return legacyReview(commandId, findingId, expectedVersion, ConfirmationStage.valueOf(stage),
                ConfirmationDecision.APPROVE, reason, grade);
    }

    private CommandResult legacyReview(UUID commandId, UUID findingId, long expectedVersion, ConfirmationStage stage,
                                       ConfirmationDecision decision, String reason, String grade) {
        Objects.requireNonNull(commandId, "commandId");
        CurrentTenantContext context = sessionContextResolver.currentTenant();
        String action = decision == ConfirmationDecision.APPROVE ? permission(stage) : "finding.close-not-established";
        return repository.inTenantTransaction(context.tenantId(), tx -> {
            FindingWorkflowCase current = visibleForCommand(tx, findingId);
            accessPolicy.requireAction(context, permission(stage), current.organizationId());
            // Keep original segregation checks ahead of route resolution and all receipt writes.
            if (context.memberId().equals(current.createdByMemberId())) {
                throw new org.springframework.security.access.AccessDeniedException("The finding reporter cannot confirm their own finding");
            }
            requireReviewer(context, current, stage);
            WorkflowCommand command = decision == ConfirmationDecision.APPROVE
                    ? command(commandId, context, action, findingId, expectedVersion,
                            CONFIRMATION_FINGERPRINT_VERSION, reason, grade)
                    : command(commandId, context, action, NOT_ESTABLISHED_FINGERPRINT_VERSION,
                            findingId, expectedVersion, stage.name(), reason);
            var replay = tx.readCommand(command);
            if (replay.isPresent()) {
                requirePersonProof(tx, current, context, stage, 1, false);
                requireReplayParticipant(tx, context, current, commandId, stage.name(), 1,
                        expectedVersion, reason, decision.name(), true);
                return requireReplayResult(replay.get(), findingId, 1);
            }
            requireProtocol(current, FindingReviewProtocol.LEGACY);
            requirePersonProof(tx, current, context, stage, 1, false);
            String key = workflowFacade.processDefinitionKey(context.tenantId(), BUSINESS_TYPE, findingId)
                    .orElseThrow(WorkflowTaskStateConflictException::new);
            if (!(key.equals("findingRemediationV1") || key.equals(PROCESS_DEFINITION_KEY))
                    || (decision == ConfirmationDecision.NOT_ESTABLISHED && !key.equals(PROCESS_DEFINITION_KEY))) {
                throw new WorkflowTaskStateConflictException();
            }
            requireExpectedVersion(current, expectedVersion);
            FindingWorkflowCase updated = decision == ConfirmationDecision.NOT_ESTABLISHED
                    ? current.closeNotEstablished(context.memberId(), expectedVersion, stage.name())
                    : stage == ConfirmationStage.PERSON ? current.confirmByPerson(context.memberId(), expectedVersion)
                    : current.confirmBySafety(context.memberId(), expectedVersion, grade);
            WorkflowCommandTaskAuthority authority = workflowFacade.acquireTaskAuthority(context.tenantId(), BUSINESS_TYPE,
                    findingId, taskKey(stage), context.memberId());
            requireAuthorityProtocol(authority, key);
            claimFresh(tx, command);
            OffsetDateTime time = now();
            tx.appendParticipant(participant(commandId, current, context, stage.name(), authority, time));
            tx.appendConfirmation(new Confirmation(findingId, stage.name(), context.memberId(), decision, reason,
                    time, expectedVersion, commandId));
            tx.updateFinding(updated, current.version());
            appendTransition(tx, current, updated, commandId, context.actorId(), reason, time);
            workflowFacade.completeTask(authority, Map.of("confirmationDecision", decision.name()));
            CommandResult result = CommandResult.from(updated);
            tx.completeCommand(commandId, result);
            publishChange(context, commandId, action, current, updated);
            return result;
        });
    }

    private ReviewPair requireReviewPair(CurrentTenantContext context, FindingWorkflowCase finding) {
        ReviewPair pair = assignmentResolver.resolveReviewPair(context.tenantId(), finding.organizationId());
        if (pair.personMemberId().equals(pair.safetyMemberId()) || pair.personMemberId().equals(finding.createdByMemberId())
                || pair.safetyMemberId().equals(finding.createdByMemberId())) {
            throw new FindingWorkflowConfigurationException("Review members must be distinct from each other and the reporter");
        }
        accessPolicy.requireAssignableMember(context, pair.personMemberId(), finding.organizationId(), PERSON_CONFIRM);
        accessPolicy.requireAssignableMember(context, pair.safetyMemberId(), finding.organizationId(), SAFETY_CONFIRM);
        return pair;
    }

    private void requireReviewer(CurrentTenantContext context, FindingWorkflowCase finding, ConfirmationStage stage) {
        if (context.memberId().equals(finding.createdByMemberId())) {
            throw new org.springframework.security.access.AccessDeniedException("The finding reporter cannot review their own finding");
        }
        ReviewPair pair = requireReviewPair(context, finding);
        UUID assigned = stage == ConfirmationStage.PERSON ? pair.personMemberId() : pair.safetyMemberId();
        if (!context.memberId().equals(assigned)) {
            throw new org.springframework.security.access.AccessDeniedException("Current member is not the effective review stage assignee");
        }
    }

    private static void requirePersonProof(FindingWorkflowRepository.Transaction tx, FindingWorkflowCase finding,
            CurrentTenantContext context, ConfirmationStage stage, int cycle, boolean cycleProtocol) {
        if (stage != ConfirmationStage.SAFETY) return;
        UUID person = cycleProtocol
                ? tx.findReviewDecision(finding.id(), cycle, ConfirmationStage.PERSON)
                    .filter(d -> d.decision() == ReviewDecision.APPROVE).map(Decision::actorMemberId)
                    .orElseThrow(() -> new FindingWorkflowConfigurationException("Same-cycle PERSON approval is required"))
                : tx.findConfirmationActorMemberId(finding.id(), "PERSON")
                    .orElseThrow(() -> new FindingWorkflowConfigurationException("A person confirmation is required before safety confirmation"));
        if (context.memberId().equals(person) || finding.createdByMemberId().equals(person)) {
            throw new org.springframework.security.access.AccessDeniedException("Safety must differ from the person reviewer and reporter");
        }
    }

    private void requireReplayParticipant(FindingWorkflowRepository.Transaction tx, CurrentTenantContext context,
            FindingWorkflowCase finding, UUID commandId, String stage, int cycle, long expectedVersion,
            String text, String decision, boolean legacyFallback) {
        var proof = tx.findParticipant(commandId);
        if (proof.isPresent()) {
            CommandParticipant p = proof.get();
            if (!p.findingId().equals(finding.id()) || p.reviewCycle() != cycle || !p.stage().equals(stage)
                    || !p.actorMemberId().equals(context.memberId())) throw new WorkflowTaskStateConflictException();
            String key = workflowFacade.processDefinitionKey(context.tenantId(), BUSINESS_TYPE, finding.id())
                    .orElseThrow(WorkflowTaskStateConflictException::new);
            if (!(legacyFallback ? java.util.Set.of("findingRemediationV1", PROCESS_DEFINITION_KEY).contains(key)
                    : REVIEW_PROCESS_DEFINITION_KEY.equals(key))) throw new WorkflowTaskStateConflictException();
            workflowFacade.validateReplayParticipant(context.tenantId(), BUSINESS_TYPE, finding.id(),
                    new WorkflowTaskReadSnapshot(context.tenantId(), BUSINESS_TYPE, finding.id(), 1,
                            p.definitionId(), key, p.processInstanceId(), p.taskDefinitionKey(), p.taskId(),
                            context.memberId().toString(), false), context.memberId());
            return;
        }
        if (!legacyFallback || cycle != 1) throw new WorkflowTaskStateConflictException();
        LegacyConfirmationProof old = tx.findLegacyConfirmation(commandId).orElseThrow(WorkflowTaskStateConflictException::new);
        if (!old.findingId().equals(finding.id()) || !old.stage().equals(stage)
                || !old.actorMemberId().equals(context.memberId()) || old.expectedVersion() != expectedVersion
                || !Objects.equals(old.reason(), text) || !Objects.equals(old.decision(), decision)) {
            throw new WorkflowTaskStateConflictException();
        }
    }

    private static CommandParticipant participant(UUID commandId, FindingWorkflowCase finding,
            CurrentTenantContext context, String stage, WorkflowCommandTaskAuthority authority, OffsetDateTime time) {
        WorkflowTaskReadSnapshot task = authority.snapshot();
        return new CommandParticipant(commandId, finding.id(), finding.reviewCycle(), context.memberId(), stage,
                task.definitionId(), task.processInstanceId(), task.taskId(), task.taskDefinitionKey(), time);
    }

    private static CommandResult requireReplayResult(CommandResult result, UUID findingId, int cycle) {
        if (!result.findingId().equals(findingId) || result.reviewCycle() != cycle) throw new WorkflowTaskStateConflictException();
        return result;
    }

    private static void claimFresh(FindingWorkflowRepository.Transaction tx, WorkflowCommand command) {
        if (!tx.claimCommand(command).acquired()) throw new WorkflowTaskStateConflictException();
    }

    private static void requireAuthorityProtocol(WorkflowCommandTaskAuthority authority, String key) {
        if (!key.equals(authority.snapshot().processDefinitionKey())) throw new WorkflowTaskStateConflictException();
    }

    private void requireDefinition(CurrentTenantContext context, UUID findingId, String key) {
        if (workflowFacade.processDefinitionKey(context.tenantId(), BUSINESS_TYPE, findingId).filter(key::equals).isEmpty()) {
            throw new WorkflowTaskStateConflictException();
        }
    }

    private static void requireProtocol(FindingWorkflowCase finding, FindingReviewProtocol protocol) {
        if (finding.reviewProtocol() != protocol) throw new WorkflowTaskStateConflictException();
    }

    private static void requireCycle(FindingWorkflowCase finding, int cycle) {
        if (finding.reviewCycle() != cycle) throw new WorkflowTaskStateConflictException();
    }

    private static String taskKey(ConfirmationStage stage) {
        return stage == ConfirmationStage.PERSON ? "personConfirm" : "safetyConfirm";
    }

    private static String permission(ConfirmationStage stage) {
        return stage == ConfirmationStage.PERSON ? PERSON_CONFIRM : SAFETY_CONFIRM;
    }

    private void requireRouteMembers(
            CurrentTenantContext context, InitialRoute route, UUID organizationId, UUID reporterMemberId) {
        if (route.personConfirmerMemberId().equals(route.safetyConfirmerMemberId())) {
            throw new FindingWorkflowConfigurationException(
                    "Person and safety confirmation require different members");
        }
        if (route.personConfirmerMemberId().equals(reporterMemberId)
                || route.safetyConfirmerMemberId().equals(reporterMemberId)) {
            throw new FindingWorkflowConfigurationException(
                    "Neither confirmation member may be the finding reporter");
        }
        accessPolicy.requireAssignableMember(context, route.personConfirmerMemberId(), organizationId, PERSON_CONFIRM);
        accessPolicy.requireAssignableMember(context, route.safetyConfirmerMemberId(), organizationId, SAFETY_CONFIRM);
        accessPolicy.requireAssignableMember(context, route.safetyDispatcherMemberId(), organizationId, DISPATCH);
        accessPolicy.requireAssignableMember(context, route.safetyVerifierMemberId(), organizationId, VERIFY);
    }

    private static Map<String, Object> processVariables(UUID findingId, InitialRoute route) {
        return Map.of(
                "findingId", findingId.toString(),
                "personConfirmerMemberId", route.personConfirmerMemberId().toString(),
                "safetyConfirmerMemberId", route.safetyConfirmerMemberId().toString(),
                "safetyDispatcherMemberId", route.safetyDispatcherMemberId().toString(),
                "safetyVerifierMemberId", route.safetyVerifierMemberId().toString());
    }

    private static Map<String, Object> reviewProcessVariables(UUID findingId, UUID reporterMemberId, InitialRoute route) {
        Map<String, Object> variables = new java.util.HashMap<>(processVariables(findingId, route));
        variables.put("reporterMemberId", reporterMemberId.toString());
        variables.put("reviewDecision", "");
        return Map.copyOf(variables);
    }

    private static FindingWorkflowCase visibleForCommand(FindingWorkflowRepository.Transaction tx, UUID findingId) {
        return tx.findForUpdate(Objects.requireNonNull(findingId, "findingId"))
                .orElseThrow(FindingNotVisibleException::new);
    }

    private static void requireExpectedVersion(FindingWorkflowCase current, long expectedVersion) {
        if (current.version() != expectedVersion) throw new FindingWorkflowVersionConflictException();
    }

    private static void validateDispatch(Dispatch request) {
        if (request.assigneeMemberId() == null || request.dueAt() == null
                || request.requirements() == null || request.requirements().isBlank()) {
            throw new FindingWorkflowRuleException("Assignee, due time, and remediation requirements are required");
        }
        if (!request.dueAt().isAfter(now())) {
            throw new FindingWorkflowRuleException("The remediation due time must be in the future");
        }
    }

    private static void appendTransition(
            FindingWorkflowRepository.Transaction tx,
            FindingWorkflowCase previous,
            FindingWorkflowCase updated,
            UUID commandId,
            UUID actorId,
            String reason) {
        appendTransition(tx, previous, updated, commandId, actorId, reason, now());
    }

    private static void appendTransition(
            FindingWorkflowRepository.Transaction tx,
            FindingWorkflowCase previous,
            FindingWorkflowCase updated,
            UUID commandId,
            UUID actorId,
            String reason,
            OffsetDateTime occurredAt) {
        tx.appendTransition(new Transition(updated.id(), previous == null ? null : previous.state(),
                updated.state(), commandId, actorId, occurredAt, reason,
                previous == null ? 0 : previous.version(), updated.version(),
                previous == null ? null : previous.grade(), updated.grade(),
                previous == null ? updated.reviewCycle() : previous.reviewCycle(), updated.reviewCycle()));
    }

    private void publishChange(
            CurrentTenantContext context,
            UUID commandId,
            String action,
            FindingWorkflowCase previous,
            FindingWorkflowCase updated) {
        eventPublisher.publish(new FindingWorkflowChangedEvent(
                context.tenantId(), updated.id(), commandId, context.actorId(), action,
                previous == null ? null : previous.state(), updated.state(), updated.roundNo(), updated.version()));
    }

    private static WorkflowCommand command(
            UUID commandId, CurrentTenantContext context, String action, Object... values) {
        return new WorkflowCommand(commandId, context.actorId(), action, requestHash(action, values));
    }

    private static String requestHash(String action, Object... values) {
        StringBuilder canonical = new StringBuilder();
        append(canonical, action);
        for (Object value : values) append(canonical, value == null ? null : value.toString());
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void append(StringBuilder target, String value) {
        if (value == null) {
            target.append("-1:");
        } else {
            target.append(value.length()).append(':').append(value);
        }
    }

    private static String cleanReason(String reason) {
        return reason == null || reason.isBlank() ? null : reason.trim();
    }

    private static String confirmationReason(String reason) {
        if (reason == null || reason.length() > FindingWorkflowContracts.MAX_CONFIRMATION_OPINION_LENGTH) {
            throw new FindingWorkflowRuleException("A confirmation opinion cannot exceed 4000 characters");
        }
        // Preserve the published confirmation-payload-v2 canonicalization and immutable receipts.
        String normalized = reason.strip();
        if (!ConfirmationOpinionText.hasContent(normalized)) {
            throw new FindingWorkflowRuleException("A confirmation opinion is required");
        }
        return normalized;
    }

    private static String notEstablishedReason(String reason) {
        // The new close action has its own fingerprint namespace and Unicode normalization.
        return ConfirmationOpinionText.trim(confirmationReason(reason));
    }

    private static String professionalGrade(String grade) {
        if (!"GENERAL".equals(grade) && !"MAJOR".equals(grade)) {
            throw new FindingWorkflowRuleException("Safety confirmation requires a GENERAL or MAJOR grade");
        }
        return grade;
    }

    private static OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }
}
