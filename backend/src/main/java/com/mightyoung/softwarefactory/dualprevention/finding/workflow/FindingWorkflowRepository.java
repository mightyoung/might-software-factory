package com.mightyoung.softwarefactory.dualprevention.finding.workflow;

import com.mightyoung.softwarefactory.dualprevention.finding.workflow.FindingWorkflowContracts.CommandClaim;
import com.mightyoung.softwarefactory.dualprevention.finding.workflow.FindingWorkflowContracts.CommandResult;
import com.mightyoung.softwarefactory.dualprevention.finding.workflow.FindingWorkflowContracts.Confirmation;
import com.mightyoung.softwarefactory.dualprevention.finding.workflow.FindingWorkflowContracts.Notice;
import com.mightyoung.softwarefactory.dualprevention.finding.workflow.FindingWorkflowContracts.Submission;
import com.mightyoung.softwarefactory.dualprevention.finding.workflow.FindingWorkflowContracts.Transition;
import com.mightyoung.softwarefactory.dualprevention.finding.workflow.FindingWorkflowContracts.Verification;
import com.mightyoung.softwarefactory.dualprevention.finding.workflow.FindingWorkflowContracts.WorkflowCommand;

import java.util.Optional;
import com.mightyoung.softwarefactory.dualprevention.finding.workflow.FindingReviewCycleContracts.*;
import java.time.LocalDate;
import java.util.UUID;
import java.util.function.Function;

/** Atomic persistence boundary for the finding projection, workflow evidence, and command receipt. */
public interface FindingWorkflowRepository {

    <T> T inTenantTransaction(UUID tenantId, Function<Transaction, T> work);

    interface Transaction {
        Optional<FindingWorkflowCase> findForUpdate(UUID findingId);

        void insertFinding(FindingWorkflowCase finding);

        String nextFindingCode(LocalDate businessDay);

        void updateFinding(FindingWorkflowCase finding, long expectedVersion);

        /** Read-only receipt lookup for eligibility/participant checks before a replay is released. */
        Optional<CommandResult> readCommand(WorkflowCommand command);

        CommandClaim claimCommand(WorkflowCommand command);

        void appendReviewCycle(ReviewCycle cycle);
        void appendReviewDecision(Decision decision);
        Optional<Decision> findReviewDecision(UUID findingId, int reviewCycle, FindingWorkflowContracts.ConfirmationStage stage);
        void appendSupplement(Supplement supplement);
        Optional<Decision> findReturnDecision(UUID findingId, int reviewCycle);
        void appendParticipant(CommandParticipant participant);
        Optional<CommandParticipant> findParticipant(UUID commandId);
        Optional<LegacyConfirmationProof> findLegacyConfirmation(UUID commandId);

        void completeCommand(UUID commandId, CommandResult result);

        void appendTransition(Transition transition);

        void appendConfirmation(Confirmation confirmation);

        Optional<UUID> findConfirmationActorMemberId(UUID findingId, String stage);

        void openRound(UUID findingId, int roundNo, UUID commandId);

        void closeRound(UUID findingId, int roundNo, String state, UUID commandId);

        Optional<Notice> findNotice(UUID findingId, int roundNo);

        void insertNotice(Notice notice, UUID commandId);

        void insertSubmission(Submission submission);

        Optional<Submission> findSubmission(UUID findingId, int roundNo);

        void insertVerification(Verification verification);
    }
}
