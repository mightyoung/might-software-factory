package com.mightyoung.softwarefactory.dualprevention;

import com.mightyoung.softwarefactory.dualprevention.finding.workflow.FindingWorkflowContracts;
import com.mightyoung.softwarefactory.dualprevention.finding.workflow.FindingSourceCategory;
import com.mightyoung.softwarefactory.dualprevention.finding.workflow.FindingUrgency;
import com.mightyoung.softwarefactory.dualprevention.finding.workflow.VerificationDecision;
import com.mightyoung.softwarefactory.dualprevention.validation.CodePointSize;
import com.mightyoung.softwarefactory.dualprevention.validation.UnicodeWhiteSpace;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.annotation.JsonDeserialize;
import tools.jackson.databind.deser.std.StdDeserializer;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;

/** HTTP DTOs for finding lifecycle commands; actor and tenant are never request fields. */
public final class FindingWorkflowApiModels {

    private FindingWorkflowApiModels() {
    }

    @JsonDeserialize(using = CreateRequestDeserializer.class)
    public record CreateRequest(
            @NotNull UUID organizationId,
            @NotBlank @Size(max = 20000) String description,
            @NotNull OffsetDateTime discoveredAt,
            UUID sourceEventId,
            UUID riskId,
            @NotBlank @Pattern(regexp = "UNKNOWN") String grade,
            @NotBlank @Pattern(regexp = "FIELD_OBSERVATION|INSPECTION|PERSON_REPORT") String sourceCategory,
            @NotBlank @Pattern(regexp = "NORMAL|URGENT") String urgency,
            @NotNull @Pattern(regexp = UnicodeWhiteSpace.HAS_NON_WHITE_SPACE_CODE_POINT_PATTERN)
                    @CodePointSize(max = 200) String location,
            @CodePointSize(max = 2000) String interimMeasure) {
        FindingWorkflowContracts.CreateFinding toCommand() {
            return new FindingWorkflowContracts.CreateFinding(
                    organizationId, description, discoveredAt, sourceEventId, riskId, grade,
                    FindingSourceCategory.valueOf(sourceCategory), FindingUrgency.valueOf(urgency),
                    location, interimMeasure);
        }
    }

    /** Rejects unknown create fields locally without changing deserialization for other APIs. */
    public static final class CreateRequestDeserializer extends StdDeserializer<CreateRequest> {

        private static final Set<String> FIELDS = Set.of(
                "organizationId", "description", "discoveredAt", "sourceEventId", "riskId", "grade",
                "sourceCategory", "urgency", "location", "interimMeasure");

        public CreateRequestDeserializer() {
            super(CreateRequest.class);
        }

        @Override
        public CreateRequest deserialize(JsonParser parser, DeserializationContext context) throws JacksonException {
            JsonNode node = context.readTree(parser);
            if (node == null || !node.isObject()) {
                return context.reportInputMismatch(CreateRequest.class,
                        "Finding create request must be a JSON object");
            }
            for (String field : node.propertyNames()) {
                if (!FIELDS.contains(field)) {
                    return context.reportInputMismatch(CreateRequest.class,
                            "Unsupported field in finding create request");
                }
            }
            return new CreateRequest(
                    value(node.get("organizationId"), UUID.class, context),
                    value(node.get("description"), String.class, context),
                    value(node.get("discoveredAt"), OffsetDateTime.class, context),
                    value(node.get("sourceEventId"), UUID.class, context),
                    value(node.get("riskId"), UUID.class, context),
                    value(node.get("grade"), String.class, context),
                    value(node.get("sourceCategory"), String.class, context),
                    value(node.get("urgency"), String.class, context),
                    value(node.get("location"), String.class, context),
                    value(node.get("interimMeasure"), String.class, context));
        }

        private static <T> T value(JsonNode node, Class<T> type, DeserializationContext context)
                throws JacksonException {
            return node == null || node.isNull() ? null : context.readTreeAsValue(node, type);
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
