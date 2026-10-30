package com.mightyoung.softwarefactory.dualprevention.finding.workflow;

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
        UUID updatedByMemberId) {

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
        if (roundNo < 1 || version < 1) {
            throw new IllegalArgumentException("Finding round and version must be positive");
        }
        Objects.requireNonNull(createdByMemberId, "createdByMemberId");
        Objects.requireNonNull(updatedByMemberId, "updatedByMemberId");
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
            UUID createdByMemberId) {
        return new FindingWorkflowCase(tenantId, id, organizationId, code, description, discoveredAt,
                sourceEventId, riskId, grade, FindingWorkflowState.PERSON_CONFIRM_PENDING, 1, 1,
                createdByMemberId, createdByMemberId);
    }

    public FindingWorkflowCase confirmByPerson(UUID memberId, long expectedVersion) {
        return advance(memberId, expectedVersion, FindingWorkflowState.PERSON_CONFIRM_PENDING,
                FindingWorkflowState.SAFETY_CONFIRM_PENDING, roundNo);
    }

    public FindingWorkflowCase confirmBySafety(UUID memberId, long expectedVersion) {
        return advance(memberId, expectedVersion, FindingWorkflowState.SAFETY_CONFIRM_PENDING,
                FindingWorkflowState.SAFETY_DISPATCH_PENDING, roundNo);
    }

    public FindingWorkflowCase dispatch(UUID memberId, long expectedVersion) {
        return advance(memberId, expectedVersion, FindingWorkflowState.SAFETY_DISPATCH_PENDING,
                FindingWorkflowState.RECTIFYING, roundNo);
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
        Objects.requireNonNull(memberId, "memberId");
        if (version != expectedVersion) {
            throw new FindingWorkflowVersionConflictException();
        }
        if (state != required) {
            throw new FindingWorkflowRuleException(
                    "Command requires state " + required + " but finding is " + state);
        }
        return new FindingWorkflowCase(tenantId, id, organizationId, code, description, discoveredAt,
                sourceEventId, riskId, grade, next, nextRound, Math.addExact(version, 1),
                createdByMemberId, memberId);
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }
}
