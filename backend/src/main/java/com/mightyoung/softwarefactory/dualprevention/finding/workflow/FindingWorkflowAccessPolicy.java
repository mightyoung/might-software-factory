package com.mightyoung.softwarefactory.dualprevention.finding.workflow;

import com.mightyoung.softwarefactory.identity.CurrentTenantContext;

import java.util.UUID;

/** Current, persisted action permission and tenant/member assignment checks for workflow commands. */
public interface FindingWorkflowAccessPolicy {

    void requireAction(CurrentTenantContext context, String permissionCode, UUID organizationId);

    void requireAssignableMember(CurrentTenantContext context, UUID memberId, UUID organizationId);
}
