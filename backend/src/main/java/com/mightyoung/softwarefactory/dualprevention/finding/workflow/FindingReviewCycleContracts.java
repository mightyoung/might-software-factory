package com.mightyoung.softwarefactory.dualprevention.finding.workflow;

import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

import static com.mightyoung.softwarefactory.dualprevention.finding.workflow.FindingWorkflowContracts.ConfirmationStage;

/** Explicit V3 request and append-only history contracts; legacy canonicalization is unchanged. */
public final class FindingReviewCycleContracts {
    private FindingReviewCycleContracts() { }

    public enum ReviewDecision { APPROVE, RETURN_FOR_INFORMATION, NOT_ESTABLISHED }

    public record ReviewDecisionCommand(long expectedVersion, int reviewCycle, ConfirmationStage stage,
                                        ReviewDecision decision, String reason, String grade) {
        public ReviewDecisionCommand {
            positive(expectedVersion, reviewCycle);
            Objects.requireNonNull(stage, "stage");
            Objects.requireNonNull(decision, "decision");
            reason = text(reason);
            boolean graded = stage == ConfirmationStage.SAFETY && decision == ReviewDecision.APPROVE;
            if (graded ? !("GENERAL".equals(grade) || "MAJOR".equals(grade)) : grade != null) {
                throw new IllegalArgumentException("Only SAFETY APPROVE requires a GENERAL or MAJOR grade");
            }
        }
    }

    public record ReportSupplementCommand(long expectedVersion, int reviewCycle, String statement) {
        public ReportSupplementCommand {
            positive(expectedVersion, reviewCycle);
            statement = text(statement);
        }
    }

    public record ReviewCycle(UUID findingId, int reviewCycle, UUID openedByCommandId, OffsetDateTime openedAt) {
        public ReviewCycle {
            Objects.requireNonNull(findingId); Objects.requireNonNull(openedByCommandId);
            Objects.requireNonNull(openedAt); positive(1, reviewCycle);
        }
    }

    public record Decision(UUID findingId, int reviewCycle, ConfirmationStage stage, UUID actorMemberId,
                           ReviewDecision decision, String reason, String confirmedGrade, long expectedVersion,
                           UUID commandId, OffsetDateTime decidedAt) {
        public Decision {
            Objects.requireNonNull(findingId); Objects.requireNonNull(actorMemberId);
            Objects.requireNonNull(commandId); Objects.requireNonNull(decidedAt);
            var validated = new ReviewDecisionCommand(expectedVersion, reviewCycle, stage, decision, reason, confirmedGrade);
            reason = validated.reason();
        }
    }

    public record Supplement(UUID id, UUID findingId, int reviewCycle, int nextReviewCycle, UUID returnCommandId,
                             UUID reporterMemberId, String statement, long expectedVersion, UUID commandId,
                             OffsetDateTime submittedAt) {
        public Supplement {
            Objects.requireNonNull(id); Objects.requireNonNull(findingId); Objects.requireNonNull(returnCommandId);
            Objects.requireNonNull(reporterMemberId); Objects.requireNonNull(commandId); Objects.requireNonNull(submittedAt);
            positive(expectedVersion, reviewCycle);
            if (nextReviewCycle != Math.addExact(reviewCycle, 1)) throw new IllegalArgumentException("Invalid successor cycle");
            statement = text(statement);
        }
    }

    public record CommandParticipant(UUID commandId, UUID findingId, int reviewCycle, UUID actorMemberId,
                                     String stage, String definitionId, String processInstanceId,
                                     String taskId, String taskDefinitionKey, OffsetDateTime recordedAt) {
        public CommandParticipant {
            Objects.requireNonNull(commandId); Objects.requireNonNull(findingId); Objects.requireNonNull(actorMemberId);
            positive(1, reviewCycle); Objects.requireNonNull(recordedAt);
            for (String identifier : new String[]{stage, definitionId, processInstanceId, taskId, taskDefinitionKey}) {
                if (identifier == null || identifier.isBlank()) throw new IllegalArgumentException("Task identity is required");
            }
        }
    }

    /** Historical reason may be null; replay compares it rather than inventing an opinion. */
    public record LegacyConfirmationProof(UUID findingId, String stage, UUID actorMemberId,
                                          String decision, String reason, long expectedVersion, UUID commandId) { }

    public record ReviewPair(UUID personMemberId, UUID safetyMemberId) {
        public ReviewPair { Objects.requireNonNull(personMemberId); Objects.requireNonNull(safetyMemberId); }
    }

    private static void positive(long expectedVersion, int reviewCycle) {
        if (expectedVersion < 1 || reviewCycle < 1) throw new IllegalArgumentException("Version and cycle must be positive");
    }

    private static String text(String value) {
        if (value == null || value.length() > 4000 || !ConfirmationOpinionText.hasContent(value)) {
            throw new IllegalArgumentException("Text must contain content and fit 4000 UTF-16 units before trimming");
        }
        return ConfirmationOpinionText.trim(value);
    }
}
