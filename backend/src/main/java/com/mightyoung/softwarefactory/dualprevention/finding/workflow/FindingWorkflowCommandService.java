package com.mightyoung.softwarefactory.dualprevention.finding.workflow;

import com.mightyoung.softwarefactory.dualprevention.FindingNotVisibleException;
import com.mightyoung.softwarefactory.dualprevention.finding.workflow.FindingWorkflowContracts.CommandClaim;
import com.mightyoung.softwarefactory.dualprevention.finding.workflow.FindingWorkflowContracts.CommandResult;
import com.mightyoung.softwarefactory.dualprevention.finding.workflow.FindingWorkflowContracts.Confirmation;
import com.mightyoung.softwarefactory.dualprevention.finding.workflow.FindingWorkflowContracts.ConfirmationDecision;
import com.mightyoung.softwarefactory.dualprevention.finding.workflow.FindingWorkflowContracts.CreateFinding;
import com.mightyoung.softwarefactory.dualprevention.finding.workflow.FindingWorkflowContracts.Dispatch;
import com.mightyoung.softwarefactory.dualprevention.finding.workflow.FindingWorkflowContracts.InitialRoute;
import com.mightyoung.softwarefactory.dualprevention.finding.workflow.FindingWorkflowContracts.Notice;
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
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
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
    public static final String PROCESS_DEFINITION_KEY = "findingRemediationV1";

    private static final String CREATE = "finding.create";
    private static final String INITIALIZE_DISCOVERED = "finding.workflow.initialize";
    private static final String PERSON_CONFIRM = "finding.confirm.person";
    private static final String SAFETY_CONFIRM = "finding.confirm.safety";
    private static final String CONFIRMATION_FINGERPRINT_VERSION = "confirmation-payload-v2";
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

    public FindingWorkflowCommandService(
            SessionContextResolver sessionContextResolver,
            FindingWorkflowRepository repository,
            FindingWorkflowAccessPolicy accessPolicy,
            FindingWorkflowAssignmentResolver assignmentResolver,
            FindingWorkflowEvidencePolicy evidencePolicy,
            WorkflowFacade workflowFacade,
            FindingWorkflowEventPublisher eventPublisher,
            Clock clock) {
        this.sessionContextResolver = Objects.requireNonNull(sessionContextResolver, "sessionContextResolver");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.accessPolicy = Objects.requireNonNull(accessPolicy, "accessPolicy");
        this.assignmentResolver = Objects.requireNonNull(assignmentResolver, "assignmentResolver");
        this.evidencePolicy = Objects.requireNonNull(evidencePolicy, "evidencePolicy");
        this.workflowFacade = Objects.requireNonNull(workflowFacade, "workflowFacade");
        this.eventPublisher = Objects.requireNonNull(eventPublisher, "eventPublisher");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public CommandResult create(UUID commandId, CreateFinding request) {
        Objects.requireNonNull(commandId, "commandId");
        Objects.requireNonNull(request, "request");
        CurrentTenantContext context = sessionContextResolver.currentTenant();
        LocalDate businessDay = LocalDate.now(clock.withZone(ZoneId.of("Asia/Shanghai")));
        WorkflowCommand command = command(commandId, context, CREATE,
                request.organizationId(), request.description(), request.discoveredAt(),
                request.sourceEventId(), request.riskId(), request.grade());

        return repository.inTenantTransaction(context.tenantId(), tx -> {
            accessPolicy.requireAction(context, CREATE, request.organizationId());
            CommandClaim claim = tx.claimCommand(command);
            if (!claim.acquired()) return claim.replay();

            InitialRoute route = assignmentResolver.resolve(context.tenantId(), request.organizationId());
            requireRouteMembers(context, route, request.organizationId(), context.memberId());
            String code = tx.nextFindingCode(businessDay);
            UUID findingId = UUID.randomUUID();
            FindingWorkflowCase finding = FindingWorkflowCase.create(
                    context.tenantId(), findingId, request.organizationId(), code, request.description(),
                    request.discoveredAt(), request.sourceEventId(), request.riskId(), request.grade(),
                    context.memberId());
            tx.insertFinding(finding);
            workflowFacade.startProcess(context.tenantId(), BUSINESS_TYPE, findingId, 1,
                    PROCESS_DEFINITION_KEY, Map.of(
                            "findingId", findingId.toString(),
                            "personConfirmerMemberId", route.personConfirmerMemberId().toString(),
                            "safetyConfirmerMemberId", route.safetyConfirmerMemberId().toString(),
                            "safetyDispatcherMemberId", route.safetyDispatcherMemberId().toString(),
                            "safetyVerifierMemberId", route.safetyVerifierMemberId().toString()));
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
            CommandClaim claim = tx.claimCommand(command);
            if (!claim.acquired()) return claim.replay();
            requireExpectedVersion(current, expectedVersion);

            InitialRoute route = assignmentResolver.resolve(context.tenantId(), current.organizationId());
            requireRouteMembers(context, route, current.organizationId(), current.createdByMemberId());
            FindingWorkflowCase updated = current.initializeDiscoveredWorkflow(context.memberId(), expectedVersion);
            tx.updateFinding(updated, current.version());
            workflowFacade.startProcess(context.tenantId(), BUSINESS_TYPE, findingId, 1,
                    PROCESS_DEFINITION_KEY, processVariables(findingId, route));
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
        CurrentTenantContext context = sessionContextResolver.currentTenant();
        return repository.inTenantTransaction(context.tenantId(), tx -> {
            FindingWorkflowCase current = visibleForCommand(tx, findingId);
            accessPolicy.requireAction(context, permission, current.organizationId());
            if (context.memberId().equals(current.createdByMemberId())) {
                throw new org.springframework.security.access.AccessDeniedException(
                        "The finding reporter cannot confirm their own finding");
            }
            if ("SAFETY".equals(stage)) {
                UUID personConfirmer = tx.findConfirmationActorMemberId(findingId, "PERSON")
                        .orElseThrow(() -> new FindingWorkflowConfigurationException(
                                "A person confirmation is required before safety confirmation"));
                if (context.memberId().equals(personConfirmer)) {
                    throw new org.springframework.security.access.AccessDeniedException(
                            "The safety confirmer must be different from the person confirmer");
                }
            }
            WorkflowCommand command = command(commandId, context, permission, findingId, expectedVersion,
                    CONFIRMATION_FINGERPRINT_VERSION, reason, grade);
            CommandClaim claim = tx.claimCommand(command);
            if (!claim.acquired()) return claim.replay();
            requireExpectedVersion(current, expectedVersion);
            FindingWorkflowCase updated = "PERSON".equals(stage)
                    ? current.confirmByPerson(context.memberId(), expectedVersion)
                    : current.confirmBySafety(context.memberId(), expectedVersion, grade);
            OffsetDateTime decidedAt = now();
            tx.appendConfirmation(new Confirmation(findingId, stage, context.memberId(),
                    ConfirmationDecision.APPROVE, reason, decidedAt, expectedVersion, commandId));
            tx.updateFinding(updated, current.version());
            appendTransition(tx, current, updated, commandId, context.actorId(), reason, decidedAt);
            workflowFacade.completeTask(context.tenantId(), BUSINESS_TYPE, findingId, taskKey,
                    context.memberId(), Map.of());
            CommandResult result = CommandResult.from(updated);
            tx.completeCommand(commandId, result);
            publishChange(context, commandId, permission, current, updated);
            return result;
        });
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
                previous == null ? null : previous.grade(), updated.grade()));
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
        String normalized = reason.strip();
        if (normalized.isEmpty()) {
            throw new FindingWorkflowRuleException("A confirmation opinion is required");
        }
        return normalized;
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
