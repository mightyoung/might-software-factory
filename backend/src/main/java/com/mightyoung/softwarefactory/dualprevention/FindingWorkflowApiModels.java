package com.mightyoung.softwarefactory.dualprevention;

import com.mightyoung.softwarefactory.dualprevention.finding.workflow.FindingWorkflowContracts;
import com.mightyoung.softwarefactory.dualprevention.finding.workflow.VerificationDecision;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.UUID;

/** HTTP DTOs for finding lifecycle commands; actor and tenant are never request fields. */
public final class FindingWorkflowApiModels {

    private FindingWorkflowApiModels() {
    }

    public record CreateRequest(
            @NotNull UUID organizationId,
            @NotBlank @Size(max = 64) String code,
            @NotBlank @Size(max = 20000) String description,
            @NotNull OffsetDateTime discoveredAt,
            UUID sourceEventId,
            UUID riskId,
            @NotBlank @Pattern(regexp = "UNKNOWN|GENERAL|MAJOR") String grade) {
        FindingWorkflowContracts.CreateFinding toCommand() {
            return new FindingWorkflowContracts.CreateFinding(
                    organizationId, code, description, discoveredAt, sourceEventId, riskId, grade);
        }
    }

    public record ConfirmRequest(@Min(1) long expectedVersion) {
    }

    public record DispatchRequest(
            @Min(1) long expectedVersion,
            @NotNull UUID assigneeMemberId,
            @NotNull OffsetDateTime dueAt,
            @NotBlank @Size(max = 4000) String requirements) {
        FindingWorkflowContracts.Dispatch toCommand() {
            return new FindingWorkflowContracts.Dispatch(assigneeMemberId, dueAt, requirements);
        }
    }

    public record SubmitRemediationRequest(
            @Min(1) long expectedVersion,
            @NotNull UUID evidenceSetId,
            @NotBlank @Size(max = 4000) String statement) {
        FindingWorkflowContracts.SubmitRemediation toCommand() {
            return new FindingWorkflowContracts.SubmitRemediation(evidenceSetId, statement);
        }
    }

    public record VerifyRequest(
            @Min(1) long expectedVersion,
            @NotNull VerificationDecision decision,
            @Size(max = 4000) String reason) {
        FindingWorkflowContracts.Verify toCommand() {
            return new FindingWorkflowContracts.Verify(decision, reason);
        }
    }

    public record CommandResponse(FindingWorkflowContracts.CommandResult data) {
    }
}
