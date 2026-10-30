package com.mightyoung.softwarefactory.authorization;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Fail-closed assignment check for current organization membership and the exact finding action scope. */
public final class FindingWorkflowActionAssignmentPolicy {

    private FindingWorkflowActionAssignmentPolicy() {
    }

    public static boolean allows(
            UUID organizationId,
            Set<UUID> currentMemberOrganizationIds,
            Set<UUID> departmentTreeOrganizationIds,
            List<RoleScopeDescriptor> actionScopes) {
        if (organizationId == null || !currentMemberOrganizationIds.contains(organizationId)) return false;
        return actionScopes.stream().anyMatch(scope -> switch (scope.kind()) {
            case "DEPT" -> true;
            case "DEPT_TREE" -> departmentTreeOrganizationIds.contains(organizationId);
            case "EXPLICIT" -> organizationId.equals(scope.explicitOrganizationId());
            default -> false;
        });
    }
}
