package com.mightyoung.softwarefactory.dualprevention;

import com.mightyoung.softwarefactory.dualprevention.finding.workflow.FindingWorkflowCase;
import com.mightyoung.softwarefactory.dualprevention.finding.workflow.FindingWorkflowRuleException;
import com.mightyoung.softwarefactory.dualprevention.finding.workflow.FindingWorkflowState;
import com.mightyoung.softwarefactory.dualprevention.finding.workflow.FindingWorkflowVersionConflictException;
import com.mightyoung.softwarefactory.dualprevention.finding.workflow.VerificationDecision;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FindingWorkflowLifecycleTest {

    private static final UUID TENANT_ID = UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final UUID FINDING_ID = UUID.fromString("40000000-0000-0000-0000-000000000001");
    private static final UUID ORGANIZATION_ID = UUID.fromString("31000000-0000-0000-0000-000000000001");
    private static final UUID MEMBER_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final OffsetDateTime DISCOVERED_AT = OffsetDateTime.parse("2026-10-30T10:00:00Z");

    @Test
    void findingRequiresBothConfirmationsBeforeDispatchAndAcceptanceBeforeClosure() {
        FindingWorkflowCase finding = createdFinding();

        assertThat(finding.state()).isEqualTo(FindingWorkflowState.PERSON_CONFIRM_PENDING);
        assertThat(finding.version()).isEqualTo(1L);

        FindingWorkflowCase safetyPending = finding.confirmByPerson(MEMBER_ID, 1L);
        assertThat(safetyPending.state()).isEqualTo(FindingWorkflowState.SAFETY_CONFIRM_PENDING);
        assertThat(safetyPending.version()).isEqualTo(2L);

        FindingWorkflowCase dispatchPending = safetyPending.confirmBySafety(MEMBER_ID, 2L);
        assertThat(dispatchPending.state()).isEqualTo(FindingWorkflowState.SAFETY_DISPATCH_PENDING);
        assertThat(dispatchPending.version()).isEqualTo(3L);

        FindingWorkflowCase rectifying = dispatchPending.dispatch(MEMBER_ID, 3L);
        assertThat(rectifying.state()).isEqualTo(FindingWorkflowState.RECTIFYING);
        assertThat(rectifying.version()).isEqualTo(4L);

        FindingWorkflowCase acceptancePending = rectifying.submitForAcceptance(MEMBER_ID, 4L);
        assertThat(acceptancePending.state()).isEqualTo(FindingWorkflowState.ACCEPTANCE_PENDING);
        assertThat(acceptancePending.version()).isEqualTo(5L);

        FindingWorkflowCase closed = acceptancePending.verify(MEMBER_ID, 5L, VerificationDecision.PASS);
        assertThat(closed.state()).isEqualTo(FindingWorkflowState.CLOSED);
        assertThat(closed.version()).isEqualTo(6L);
        assertThat(closed.roundNo()).isEqualTo(1);
    }

    @Test
    void failedVerificationReturnsToRectificationAndStartsANewRound() {
        FindingWorkflowCase acceptancePending = createdFinding()
                .confirmByPerson(MEMBER_ID, 1L)
                .confirmBySafety(MEMBER_ID, 2L)
                .dispatch(MEMBER_ID, 3L)
                .submitForAcceptance(MEMBER_ID, 4L);

        FindingWorkflowCase nextRound = acceptancePending.verify(
                MEMBER_ID, 5L, VerificationDecision.FAIL);

        assertThat(nextRound.state()).isEqualTo(FindingWorkflowState.RECTIFYING);
        assertThat(nextRound.roundNo()).isEqualTo(2);
        assertThat(nextRound.version()).isEqualTo(6L);
    }

    @Test
    void outOfOrderCommandsAndStaleVersionsAreRejected() {
        FindingWorkflowCase finding = createdFinding();

        assertThatThrownBy(() -> finding.confirmBySafety(MEMBER_ID, 1L))
                .isInstanceOf(FindingWorkflowRuleException.class);
        assertThatThrownBy(() -> finding.dispatch(MEMBER_ID, 1L))
                .isInstanceOf(FindingWorkflowRuleException.class);
        assertThatThrownBy(() -> finding.confirmByPerson(MEMBER_ID, 2L))
                .isInstanceOf(FindingWorkflowVersionConflictException.class);

        FindingWorkflowCase acceptancePending = finding
                .confirmByPerson(MEMBER_ID, 1L)
                .confirmBySafety(MEMBER_ID, 2L)
                .dispatch(MEMBER_ID, 3L)
                .submitForAcceptance(MEMBER_ID, 4L)
                .verify(MEMBER_ID, 5L, VerificationDecision.PASS);
        assertThatThrownBy(() -> acceptancePending.verify(MEMBER_ID, 6L, VerificationDecision.PASS))
                .isInstanceOf(FindingWorkflowRuleException.class);
    }

    private static FindingWorkflowCase createdFinding() {
        return FindingWorkflowCase.create(
                TENANT_ID, FINDING_ID, ORGANIZATION_ID, "FND-2026-001", "Synthetic defect",
                DISCOVERED_AT, null, null, "GENERAL", MEMBER_ID);
    }
}
