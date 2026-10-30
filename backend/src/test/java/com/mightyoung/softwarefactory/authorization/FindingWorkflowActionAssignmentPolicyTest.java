package com.mightyoung.softwarefactory.authorization;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class FindingWorkflowActionAssignmentPolicyTest {

    private static final UUID ORG_A = UUID.fromString("31000000-0000-0000-0000-000000000001");
    private static final UUID ORG_B = UUID.fromString("31000000-0000-0000-0000-000000000002");
    private static final UUID SCOPE = UUID.fromString("32000000-0000-0000-0000-000000000001");

    @Test
    void onlyCurrentOrganizationMembershipAndTheRequiredActionScopeAuthorizeAssignment() {
        RoleScopeDescriptor departmentScope = new RoleScopeDescriptor(SCOPE, "DEPT", null);
        RoleScopeDescriptor explicitOtherOrganization = new RoleScopeDescriptor(SCOPE, "EXPLICIT", ORG_B);
        RoleScopeDescriptor selfScope = new RoleScopeDescriptor(SCOPE, "SELF", null);

        assertThat(FindingWorkflowActionAssignmentPolicy.allows(
                ORG_A, Set.of(ORG_A), Set.of(ORG_A), List.of(departmentScope))).isTrue();
        assertThat(FindingWorkflowActionAssignmentPolicy.allows(
                ORG_B, Set.of(ORG_A), Set.of(ORG_A, ORG_B), List.of(departmentScope))).isFalse();
        assertThat(FindingWorkflowActionAssignmentPolicy.allows(
                ORG_A, Set.of(ORG_A), Set.of(ORG_A), List.of(explicitOtherOrganization))).isFalse();
        assertThat(FindingWorkflowActionAssignmentPolicy.allows(
                ORG_A, Set.of(ORG_A), Set.of(ORG_A), List.of(selfScope))).isFalse();
        assertThat(FindingWorkflowActionAssignmentPolicy.allows(
                ORG_A, Set.of(ORG_A), Set.of(ORG_A), List.of())).isFalse();
    }
}
