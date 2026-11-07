package com.mightyoung.softwarefactory.dualprevention.finding.workflow;

import com.mightyoung.softwarefactory.dualprevention.validation.UnicodeWhiteSpace;

import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

/** Immutable aggregate projection changed only by the workflow command service. */
public record FindingWorkflowCase(
        UUID tenantId,
        UUID id,
        UUID organizationId,
        String code,
        String description,
        OffsetDateTime discoveredAt,
        UUID sourceEventId,
        UUID riskId,
        String grade,
        FindingWorkflowState state,
        int roundNo,
        long version,
        UUID createdByMemberId,
        UUID updatedByMemberId,
        FindingSourceCategory sourceCategory,
        FindingUrgency urgency,
        String location,
        String interimMeasure,
        Boolean backfill,
        UUID relatedFindingId,
        int reviewCycle,
        FindingReviewProtocol reviewProtocol) {

    public FindingWorkflowCase {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(organizationId, "organizationId");
        code = required(code, "code");
        description = required(description, "description");
        Objects.requireNonNull(discoveredAt, "discoveredAt");
        if (!Objects.requireNonNull(grade, "grade").matches("UNKNOWN|GENERAL|MAJOR")) {
            throw new IllegalArgumentException("Unsupported finding grade");
        }
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(reviewProtocol, "reviewProtocol");
        if (reviewCycle < 1 || (reviewProtocol == FindingReviewProtocol.LEGACY
                && (reviewCycle != 1 || state == FindingWorkflowState.REPORTER_SUPPLEMENT_PENDING))) {
            throw new IllegalArgumentException("Invalid review cycle/protocol");
        }
        if (roundNo < 1 || version < 1) {
            throw new IllegalArgumentException("Finding round and version must be positive");
        }
        Objects.requireNonNull(createdByMemberId, "createdByMemberId");
        Objects.requireNonNull(updatedByMemberId, "updatedByMemberId");
        if (id.equals(relatedFindingId)) {
            throw new IllegalArgumentException("A finding cannot be related to itself");
        }
        boolean legacyMetadata = sourceCategory == null && urgency == null && location == null
                && interimMeasure == null && backfill == null;
        boolean completeMetadata = sourceCategory != null && urgency != null
                && UnicodeWhiteSpace.containsNonWhiteSpace(location) && backfill != null;
        if (!legacyMetadata && !completeMetadata) {
            throw new IllegalArgumentException("Finding report metadata must be fully present or legacy-null");
        }
        if (location != null && codePointLength(location) > 200) {
            throw new IllegalArgumentException("Finding location exceeds 200 Unicode code points");
        }
        if (interimMeasure != null && codePointLength(interimMeasure) > 2000) {
            throw new IllegalArgumentException("Finding interim measure exceeds 2000 Unicode code points");
        }
    }

    /** Compatibility constructor for existing V1/V2 projections and fixtures. */
    public FindingWorkflowCase(UUID tenantId, UUID id, UUID organizationId, String code, String description,
                               OffsetDateTime discoveredAt, UUID sourceEventId, UUID riskId, String grade,
                               FindingWorkflowState state, int roundNo, long version, UUID createdByMemberId,
                               UUID updatedByMemberId, FindingSourceCategory sourceCategory,
                               FindingUrgency urgency, String location, String interimMeasure, Boolean backfill,
                               UUID relatedFindingId) {
        this(tenantId, id, organizationId, code, description, discoveredAt, sourceEventId, riskId, grade,
                state, roundNo, version, createdByMemberId, updatedByMemberId, sourceCategory, urgency,
                location, interimMeasure, backfill, relatedFindingId, 1, FindingReviewProtocol.LEGACY);
    }

    public FindingWorkflowCase(UUID tenantId, UUID id, UUID organizationId, String code, String description,
                               OffsetDateTime discoveredAt, UUID sourceEventId, UUID riskId, String grade,
                               FindingWorkflowState state, int roundNo, long version, UUID createdByMemberId,
                               UUID updatedByMemberId, FindingSourceCategory sourceCategory,
                               FindingUrgency urgency, String location, String interimMeasure, Boolean backfill) {
        this(tenantId, id, organizationId, code, description, discoveredAt, sourceEventId, riskId, grade,
                state, roundNo, version, createdByMemberId, updatedByMemberId, sourceCategory, urgency,
                location, interimMeasure, backfill, null);
    }

    public FindingWorkflowCase(UUID tenantId, UUID id, UUID organizationId, String code, String description,
                               OffsetDateTime discoveredAt, UUID sourceEventId, UUID riskId, String grade,
                               FindingWorkflowState state, int roundNo, long version, UUID createdByMemberId,
                               UUID updatedByMemberId) {
        this(tenantId, id, organizationId, code, description, discoveredAt, sourceEventId, riskId, grade,
                state, roundNo, version, createdByMemberId, updatedByMemberId, null, null, null, null, null, null);
    }

    public static FindingWorkflowCase create(
            UUID tenantId,
            UUID id,
            UUID organizationId,
            String code,
            String description,
            OffsetDateTime discoveredAt,
            UUID sourceEventId,
            UUID riskId,
            String grade,
            UUID createdByMemberId,
            FindingSourceCategory sourceCategory,
            FindingUrgency urgency,
            String location,
            String interimMeasure,
            boolean backfill) {
        return create(tenantId, id, organizationId, code, description, discoveredAt, sourceEventId, riskId,
                grade, createdByMemberId, sourceCategory, urgency, location, interimMeasure, backfill, null);
    }

    public static FindingWorkflowCase create(
            UUID tenantId,
            UUID id,
            UUID organizationId,
            String code,
            String description,
            OffsetDateTime discoveredAt,
            UUID sourceEventId,
            UUID riskId,
            String grade,
            UUID createdByMemberId,
            FindingSourceCategory sourceCategory,
            FindingUrgency urgency,
            String location,
            String interimMeasure,
            boolean backfill,
            UUID relatedFindingId) {
        return create(tenantId, id, organizationId, code, description, discoveredAt, sourceEventId, riskId,
                grade, createdByMemberId, sourceCategory, urgency, location, interimMeasure, backfill,
                relatedFindingId, FindingReviewProtocol.LEGACY);
    }

    public static FindingWorkflowCase create(
            UUID tenantId, UUID id, UUID organizationId, String code, String description,
            OffsetDateTime discoveredAt, UUID sourceEventId, UUID riskId, String grade, UUID createdByMemberId,
            FindingSourceCategory sourceCategory, FindingUrgency urgency, String location, String interimMeasure,
            boolean backfill, UUID relatedFindingId, FindingReviewProtocol reviewProtocol) {
        return new FindingWorkflowCase(tenantId, id, organizationId, code, description, discoveredAt,
                sourceEventId, riskId, grade, FindingWorkflowState.PERSON_CONFIRM_PENDING, 1, 1,
                createdByMemberId, createdByMemberId, sourceCategory, urgency, location, interimMeasure,
                backfill, relatedFindingId, 1, reviewProtocol);
    }

    /** Explicitly adopts one legacy DISCOVERED row into the person-confirmation workflow. */
    public FindingWorkflowCase initializeDiscoveredWorkflow(UUID memberId, long expectedVersion) {
        return advance(memberId, expectedVersion, FindingWorkflowState.DISCOVERED,
                FindingWorkflowState.PERSON_CONFIRM_PENDING, roundNo);
    }

    /** Explicit adoption used only after the service verifies an unbound DISCOVERED row and an exact V3 pin. */
    public FindingWorkflowCase initializeDiscoveredReviewWorkflow(UUID memberId, long expectedVersion) {
        if (reviewProtocol != FindingReviewProtocol.LEGACY || reviewCycle != 1) {
            throw new FindingWorkflowRuleException("Only a legacy DISCOVERED finding can initialize review cycles");
        }
        FindingWorkflowCase next = initializeDiscoveredWorkflow(memberId, expectedVersion);
        return new FindingWorkflowCase(next.tenantId, next.id, next.organizationId, next.code, next.description,
                next.discoveredAt, next.sourceEventId, next.riskId, next.grade, next.state, next.roundNo,
                next.version, next.createdByMemberId, next.updatedByMemberId, next.sourceCategory, next.urgency,
                next.location, next.interimMeasure, next.backfill, next.relatedFindingId, 1, FindingReviewProtocol.CYCLE_V1);
    }

    public FindingWorkflowCase confirmByPerson(UUID memberId, long expectedVersion) {
        return advance(memberId, expectedVersion, FindingWorkflowState.PERSON_CONFIRM_PENDING,
                FindingWorkflowState.SAFETY_CONFIRM_PENDING, roundNo);
    }

    public FindingWorkflowCase confirmBySafety(UUID memberId, long expectedVersion, String confirmedGrade) {
        if (!"GENERAL".equals(confirmedGrade) && !"MAJOR".equals(confirmedGrade)) {
            throw new FindingWorkflowRuleException("Safety confirmation requires a GENERAL or MAJOR grade");
        }
        return advance(memberId, expectedVersion, FindingWorkflowState.SAFETY_CONFIRM_PENDING,
                FindingWorkflowState.SAFETY_DISPATCH_PENDING, roundNo, confirmedGrade);
    }

    public FindingWorkflowCase closeNotEstablished(UUID memberId, long expectedVersion, String stage) {
        FindingWorkflowState required = switch (stage) {
            case "PERSON" -> FindingWorkflowState.PERSON_CONFIRM_PENDING;
            case "SAFETY" -> FindingWorkflowState.SAFETY_CONFIRM_PENDING;
            default -> throw new FindingWorkflowRuleException("A valid confirmation stage is required");
        };
        return advance(memberId, expectedVersion, required, FindingWorkflowState.NOT_ESTABLISHED, roundNo);
    }

    public FindingWorkflowCase returnForInformation(UUID memberId, long expectedVersion, int expectedCycle,
                                                     FindingWorkflowContracts.ConfirmationStage stage) {
        requireReviewCycle(expectedCycle);
        FindingWorkflowState required = stage == FindingWorkflowContracts.ConfirmationStage.PERSON
                ? FindingWorkflowState.PERSON_CONFIRM_PENDING : FindingWorkflowState.SAFETY_CONFIRM_PENDING;
        return advance(memberId, expectedVersion, required, FindingWorkflowState.REPORTER_SUPPLEMENT_PENDING, roundNo);
    }

    public FindingWorkflowCase supplement(UUID memberId, long expectedVersion, int expectedCycle) {
        requireReviewCycle(expectedCycle);
        if (!createdByMemberId.equals(memberId)) {
            throw new FindingWorkflowRuleException("Only the original reporter may supplement a finding");
        }
        FindingWorkflowCase next = advance(memberId, expectedVersion,
                FindingWorkflowState.REPORTER_SUPPLEMENT_PENDING, FindingWorkflowState.PERSON_CONFIRM_PENDING, roundNo);
        return new FindingWorkflowCase(next.tenantId, next.id, next.organizationId, next.code, next.description,
                next.discoveredAt, next.sourceEventId, next.riskId, next.grade, next.state, next.roundNo,
                next.version, next.createdByMemberId, next.updatedByMemberId, next.sourceCategory, next.urgency,
                next.location, next.interimMeasure, next.backfill, next.relatedFindingId,
                Math.addExact(reviewCycle, 1), reviewProtocol);
    }

    public void requireReviewCycle(int expectedCycle) {
        if (reviewProtocol != FindingReviewProtocol.CYCLE_V1 || reviewCycle != expectedCycle) {
            throw new FindingWorkflowRuleException("Command requires the current review cycle protocol and cycle");
        }
    }

    public FindingWorkflowCase dispatch(UUID memberId, long expectedVersion) {
        FindingWorkflowState next = state == FindingWorkflowState.SAFETY_DISPATCH_PENDING
                || (state == FindingWorkflowState.RECTIFYING && roundNo > 1)
                ? FindingWorkflowState.RECTIFYING : null;
        if (next == null) {
            throw new FindingWorkflowRuleException(
                    "Dispatch requires the initial safety task or a failed-acceptance re-dispatch");
        }
        return advance(memberId, expectedVersion, state, next, roundNo);
    }

    public FindingWorkflowCase submitForAcceptance(UUID memberId, long expectedVersion) {
        return advance(memberId, expectedVersion, FindingWorkflowState.RECTIFYING,
                FindingWorkflowState.ACCEPTANCE_PENDING, roundNo);
    }

    public FindingWorkflowCase verify(UUID memberId, long expectedVersion, VerificationDecision decision) {
        Objects.requireNonNull(decision, "decision");
        FindingWorkflowState next = decision == VerificationDecision.PASS
                ? FindingWorkflowState.CLOSED
                : FindingWorkflowState.RECTIFYING;
        int nextRound = decision == VerificationDecision.PASS ? roundNo : Math.addExact(roundNo, 1);
        return advance(memberId, expectedVersion, FindingWorkflowState.ACCEPTANCE_PENDING, next, nextRound);
    }

    private FindingWorkflowCase advance(
            UUID memberId,
            long expectedVersion,
            FindingWorkflowState required,
            FindingWorkflowState next,
            int nextRound) {
        return advance(memberId, expectedVersion, required, next, nextRound, grade);
    }

    private FindingWorkflowCase advance(
            UUID memberId,
            long expectedVersion,
            FindingWorkflowState required,
            FindingWorkflowState next,
            int nextRound,
            String nextGrade) {
        Objects.requireNonNull(memberId, "memberId");
        if (version != expectedVersion) {
            throw new FindingWorkflowVersionConflictException();
        }
        if (state != required) {
            throw new FindingWorkflowRuleException(
                    "Command requires state " + required + " but finding is " + state);
        }
        return new FindingWorkflowCase(tenantId, id, organizationId, code, description, discoveredAt,
                sourceEventId, riskId, nextGrade, next, nextRound, Math.addExact(version, 1),
                createdByMemberId, memberId, sourceCategory, urgency, location, interimMeasure, backfill,
                relatedFindingId, reviewCycle, reviewProtocol);
    }

    private static int codePointLength(String value) {
        return value.codePointCount(0, value.length());
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }
}
