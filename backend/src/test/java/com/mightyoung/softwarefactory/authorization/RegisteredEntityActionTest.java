package com.mightyoung.softwarefactory.authorization;

import org.junit.jupiter.api.Test;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import static org.assertj.core.api.Assertions.*;

class RegisteredEntityActionTest {
    @Test void catalogHasOnlyFiniteAssetAndRiskDraftActionsAndNoFindingAlias() {
        var actions = Arrays.asList(RegisteredEntityAction.values());
        assertThat(actions).hasSize(32);
        assertThat(actions.stream().filter(RegisteredEntityAction::isRead)).hasSize(7);
        assertThat(actions.stream().filter(a -> !a.isRead())).hasSize(25);
        assertThat(actions.stream().map(RegisteredEntityAction::permissionCode).collect(Collectors.toSet())).hasSize(32);
        assertThat(actions).allSatisfy(a -> assertThat(a.permissionCode()).isEqualTo(a.resourceType() + "." + a.action()));
        assertThat(actions.stream().map(RegisteredEntityAction::resourceType).collect(Collectors.toSet()))
                .isEqualTo(Set.of("workshop", "production_line", "process_stage", "equipment", "work_unit", "hazard_source",
                        "line_workshop", "stage_equipment", "responsibility_binding", "risk_scenario"));
        assertThat(actions.stream().map(RegisteredEntityAction::permissionCode)).doesNotContain("finding.read", "finding.create", "hazard_source.archive",
                "risk_scenario.archive", "risk_scenario.publish", "risk_scenario.assess");
        assertThat(actions.stream().filter(a -> a.resourceType().equals("risk_scenario"))
                .map(RegisteredEntityAction::permissionCode).collect(Collectors.toSet()))
                .isEqualTo(Set.of("risk_scenario.read", "risk_scenario.create", "risk_scenario.update"));
    }
}
