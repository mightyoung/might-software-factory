package com.mightyoung.softwarefactory.authorization;

import org.junit.jupiter.api.Test;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import static org.assertj.core.api.Assertions.*;

class RegisteredEntityActionTest {
    @Test void catalogHasOnlyFiniteAssetActionsAndNoFindingAlias() {
        var actions = Arrays.asList(RegisteredEntityAction.values());
        assertThat(actions).hasSize(29);
        assertThat(actions.stream().filter(RegisteredEntityAction::isRead)).hasSize(6);
        assertThat(actions.stream().filter(a -> !a.isRead())).hasSize(23);
        assertThat(actions.stream().map(RegisteredEntityAction::permissionCode).collect(Collectors.toSet())).hasSize(29);
        assertThat(actions).allSatisfy(a -> assertThat(a.permissionCode()).isEqualTo(a.resourceType() + "." + a.action()));
        assertThat(actions.stream().map(RegisteredEntityAction::resourceType).collect(Collectors.toSet()))
                .isEqualTo(Set.of("workshop", "production_line", "process_stage", "equipment", "work_unit", "hazard_source",
                        "line_workshop", "stage_equipment", "responsibility_binding"));
        assertThat(actions.stream().map(RegisteredEntityAction::permissionCode)).doesNotContain("finding.read", "finding.create", "hazard_source.archive");
    }
}
