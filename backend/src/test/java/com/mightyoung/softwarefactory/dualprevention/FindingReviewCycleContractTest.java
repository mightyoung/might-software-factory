package com.mightyoung.softwarefactory.dualprevention;

import com.mightyoung.softwarefactory.dualprevention.finding.workflow.*;
import org.junit.jupiter.api.Test;
import java.time.OffsetDateTime;
import java.util.UUID;
import static com.mightyoung.softwarefactory.dualprevention.finding.workflow.FindingReviewCycleContracts.*;
import static com.mightyoung.softwarefactory.dualprevention.finding.workflow.FindingWorkflowContracts.ConfirmationStage.*;
import static org.assertj.core.api.Assertions.*;

class FindingReviewCycleContractTest {
    private final UUID reporter = UUID.randomUUID();
    private final UUID person = UUID.randomUUID();
    private final UUID safety = UUID.randomUUID();

    @Test void repeatedReviewCyclesRemainIndependentFromRemediationRoundsAndGrade() {
        var initial = finding(FindingReviewProtocol.CYCLE_V1);
        var returned = initial.returnForInformation(person, 1, 1, PERSON);
        assertThat(returned.reviewCycle()).isEqualTo(1);
        assertThat(returned.grade()).isEqualTo("UNKNOWN");
        var cycle2 = returned.supplement(reporter, 2, 1).confirmByPerson(person, 3);
        var safetyReturn = cycle2.returnForInformation(safety, 4, 2, SAFETY);
        var dispatch = safetyReturn.supplement(reporter, 5, 2).confirmByPerson(person, 6)
                .confirmBySafety(safety, 7, "MAJOR");
        var failed = dispatch.dispatch(safety, 8).submitForAcceptance(person, 9)
                .verify(safety, 10, VerificationDecision.FAIL);
        assertThat(failed.reviewCycle()).isEqualTo(3);
        assertThat(failed.roundNo()).isEqualTo(2);
        assertThat(failed.grade()).isEqualTo("MAJOR");
        assertThat(failed.dispatch(safety, 11).submitForAcceptance(person, 12)
                .verify(safety, 13, VerificationDecision.PASS).state()).isEqualTo(FindingWorkflowState.CLOSED);
        assertThat(initial.description()).isEqualTo(dispatch.description());
    }

    @Test void legacyAndStaleCycleCannotReturnOrSupplement() {
        assertThatThrownBy(() -> finding(FindingReviewProtocol.LEGACY).returnForInformation(person, 1, 1, PERSON))
                .isInstanceOf(FindingWorkflowRuleException.class);
        var returned = finding(FindingReviewProtocol.CYCLE_V1).returnForInformation(person, 1, 1, PERSON);
        assertThatThrownBy(() -> returned.supplement(person, 2, 1)).isInstanceOf(FindingWorkflowRuleException.class);
        assertThatThrownBy(() -> returned.supplement(reporter, 2, 2)).isInstanceOf(FindingWorkflowRuleException.class);
    }

    @Test void newTextUsesUnicodeTrimAndOriginalUtf16LimitAndGradeShape() {
        assertThat(new ReportSupplementCommand(1, 1, "\u00a0statement\u3000").statement()).isEqualTo("statement");
        assertThat(new ReportSupplementCommand(1, 1, "😀".repeat(2000)).statement()).hasSize(4000);
        assertThatThrownBy(() -> new ReportSupplementCommand(1, 1, " " + "😀".repeat(2000)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReportSupplementCommand(1, 1, "\u0085\u3000"))
                .isInstanceOf(IllegalArgumentException.class);
        for (var decision : ReviewDecision.values()) {
            if (decision != ReviewDecision.APPROVE) {
                assertThatThrownBy(() -> new ReviewDecisionCommand(1, 1, SAFETY, decision, "reason", "MAJOR"))
                        .isInstanceOf(IllegalArgumentException.class);
            }
        }
        assertThatThrownBy(() -> new ReviewDecisionCommand(1, 1, SAFETY, ReviewDecision.APPROVE, "reason", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReviewDecisionCommand(1, 1, PERSON, ReviewDecision.APPROVE, "reason", "GENERAL"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void legacyResultConstructorKeepsCycleOne() {
        var row = finding(FindingReviewProtocol.LEGACY);
        assertThat(new FindingWorkflowContracts.CommandResult(row.id(), row.code(), row.state(), 1, 1).reviewCycle()).isEqualTo(1);
    }

    @Test void legacyCommandWireRetainsExactlyItsPublishedFields() {
        var result = new FindingWorkflowContracts.CommandResult(UUID.fromString("40000000-0000-0000-0000-000000000001"),
                "F1", FindingWorkflowState.PERSON_CONFIRM_PENDING, 1, 4, 3);
        String json = tools.jackson.databind.json.JsonMapper.builder().build()
                .writeValueAsString(new FindingWorkflowApiModels.CommandResponse(result));
        assertThat(json).isEqualTo("{\"data\":{\"findingId\":\"40000000-0000-0000-0000-000000000001\",\"code\":\"F1\",\"state\":\"PERSON_CONFIRM_PENDING\",\"roundNo\":1,\"version\":4}}");
        assertThat(result.reviewCycle()).isEqualTo(3);
    }

    private FindingWorkflowCase finding(FindingReviewProtocol protocol) {
        return new FindingWorkflowCase(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "F1", "initial",
                OffsetDateTime.now(), null, null, "UNKNOWN", FindingWorkflowState.PERSON_CONFIRM_PENDING,
                1, 1, reporter, reporter, null, null, null, null, null, null, 1, protocol);
    }
}
