package com.mightyoung.softwarefactory.dualprevention.finding.workflow;

import com.mightyoung.softwarefactory.files.VerifiedFileEvidenceSnapshot;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Request and result contracts for server-side finding workflow commands. */
public final class FindingWorkflowContracts {

    public static final int MAX_CONFIRMATION_OPINION_LENGTH = 4000;

    private FindingWorkflowContracts() {
    }

    public record CreateFinding(
            UUID organizationId,
            String description,
            OffsetDateTime discoveredAt,
            UUID sourceEventId,
            UUID riskId,
            String grade,
            FindingSourceCategory sourceCategory,
            FindingUrgency urgency,
            String location,
            String interimMeasure) {
    }

    public record Dispatch(
            UUID assigneeMemberId,
            OffsetDateTime dueAt,
            String requirements) {
    }

    public record SubmitRemediation(UUID evidenceSetId, String statement) {
    }

    public record Verify(VerificationDecision decision, String reason) {
    }

    public record PersonConfirmationCommand(String reason) {
    }

    public record SafetyConfirmationCommand(String reason, String grade) {
    }

    /** Current confirmation endpoints approve only; later decisions require an explicit workflow change. */
    public enum ConfirmationDecision {
        APPROVE
    }

    public record CommandResult(UUID findingId, String code, FindingWorkflowState state, int roundNo, long version) {
        public CommandResult {
            Objects.requireNonNull(findingId, "findingId");
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(state, "state");
            if (roundNo < 1 || version < 1) {
                throw new IllegalArgumentException("Finding round and version must be positive");
            }
        }

        public static CommandResult from(FindingWorkflowCase finding) {
            return new CommandResult(finding.id(), finding.code(), finding.state(), finding.roundNo(), finding.version());
        }
    }

    public record InitialRoute(
            UUID personConfirmerMemberId,
            UUID safetyConfirmerMemberId,
            UUID safetyDispatcherMemberId,
            UUID safetyVerifierMemberId) {
        public InitialRoute {
            Objects.requireNonNull(personConfirmerMemberId, "personConfirmerMemberId");
            Objects.requireNonNull(safetyConfirmerMemberId, "safetyConfirmerMemberId");
            Objects.requireNonNull(safetyDispatcherMemberId, "safetyDispatcherMemberId");
            Objects.requireNonNull(safetyVerifierMemberId, "safetyVerifierMemberId");
        }
    }

    public record Notice(
            UUID id,
            UUID findingId,
            int roundNo,
            UUID assigneeMemberId,
            OffsetDateTime dueAt,
            OffsetDateTime originalDueAt,
            String requirements) {
        public Notice {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(findingId, "findingId");
            if (roundNo < 1) throw new IllegalArgumentException("roundNo must be positive");
            Objects.requireNonNull(assigneeMemberId, "assigneeMemberId");
            Objects.requireNonNull(dueAt, "dueAt");
            Objects.requireNonNull(originalDueAt, "originalDueAt");
            Objects.requireNonNull(requirements, "requirements");
        }
    }

    public record Submission(
            UUID tenantId,
            UUID id,
            UUID findingId,
            UUID noticeId,
            int roundNo,
            UUID submittedByMemberId,
            OffsetDateTime submittedAt,
            UUID evidenceSetId,
            List<VerifiedFileEvidenceSnapshot> evidenceSnapshots,
            String statement,
            long expectedVersion,
            UUID commandId) {
        public Submission {
            Objects.requireNonNull(tenantId, "tenantId");
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(findingId, "findingId");
            Objects.requireNonNull(noticeId, "noticeId");
            if (roundNo < 1 || expectedVersion < 1) throw new IllegalArgumentException("Invalid version or round");
            Objects.requireNonNull(submittedByMemberId, "submittedByMemberId");
            Objects.requireNonNull(submittedAt, "submittedAt");
            Objects.requireNonNull(evidenceSetId, "evidenceSetId");
            if (evidenceSnapshots == null || evidenceSnapshots.isEmpty()) {
                throw new IllegalArgumentException("Pinned evidence snapshots are required");
            }
            evidenceSnapshots = List.copyOf(evidenceSnapshots);
            if (evidenceSnapshots.stream().anyMatch(snapshot -> !tenantId.equals(snapshot.tenantId())
                    || !submittedByMemberId.equals(snapshot.ownerMemberId()))) {
                throw new IllegalArgumentException("Evidence snapshots must match the tenant and submitter");
            }
            Objects.requireNonNull(statement, "statement");
            Objects.requireNonNull(commandId, "commandId");
        }
    }

    public record WorkflowCommand(
            UUID commandId,
            UUID actorId,
            String action,
            String requestHash) {
        public WorkflowCommand {
            Objects.requireNonNull(commandId, "commandId");
            Objects.requireNonNull(actorId, "actorId");
            Objects.requireNonNull(action, "action");
            Objects.requireNonNull(requestHash, "requestHash");
        }
    }

    public record CommandClaim(boolean acquired, CommandResult replay) {
        public CommandClaim {
            if (acquired == (replay != null)) {
                throw new IllegalArgumentException("A command claim is either new or a completed replay");
            }
        }

        public static CommandClaim newClaim() {
            return new CommandClaim(true, null);
        }

        public static CommandClaim replay(CommandResult result) {
            return new CommandClaim(false, Objects.requireNonNull(result, "result"));
        }
    }

    public record Transition(
            UUID findingId,
            FindingWorkflowState fromState,
            FindingWorkflowState toState,
            UUID commandId,
            UUID actorId,
            OffsetDateTime occurredAt,
            String reason,
            long fromVersion,
            long toVersion,
            String fromGrade,
            String toGrade) {
        public Transition {
            Objects.requireNonNull(findingId, "findingId");
            Objects.requireNonNull(toState, "toState");
            Objects.requireNonNull(commandId, "commandId");
            Objects.requireNonNull(actorId, "actorId");
            Objects.requireNonNull(occurredAt, "occurredAt");
            Objects.requireNonNull(toGrade, "toGrade");
        }
    }

    public record Confirmation(
            UUID findingId,
            String stage,
            UUID actorMemberId,
            ConfirmationDecision decision,
            String reason,
            OffsetDateTime decidedAt,
            long expectedVersion,
            UUID commandId) {
        public Confirmation {
            Objects.requireNonNull(findingId, "findingId");
            Objects.requireNonNull(stage, "stage");
            Objects.requireNonNull(actorMemberId, "actorMemberId");
            Objects.requireNonNull(decision, "decision");
            Objects.requireNonNull(reason, "reason");
            if (reason.isBlank() || reason.length() > MAX_CONFIRMATION_OPINION_LENGTH) {
                throw new IllegalArgumentException("A confirmation opinion between 1 and 4000 characters is required");
            }
            Objects.requireNonNull(decidedAt, "decidedAt");
            Objects.requireNonNull(commandId, "commandId");
        }
    }

    public record Verification(
            UUID findingId,
            UUID submissionId,
            int roundNo,
            UUID reviewerMemberId,
            VerificationDecision decision,
            String reason,
            OffsetDateTime decidedAt,
            UUID commandId) {
        public Verification {
            Objects.requireNonNull(findingId, "findingId");
            Objects.requireNonNull(submissionId, "submissionId");
            if (roundNo < 1) throw new IllegalArgumentException("roundNo must be positive");
            Objects.requireNonNull(reviewerMemberId, "reviewerMemberId");
            Objects.requireNonNull(decision, "decision");
            Objects.requireNonNull(decidedAt, "decidedAt");
            Objects.requireNonNull(commandId, "commandId");
        }
    }
}
