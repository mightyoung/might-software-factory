package com.mightyoung.softwarefactory.dualprevention.finding.workflow;

import com.mightyoung.softwarefactory.dualprevention.finding.workflow.FindingWorkflowContracts.InitialRoute;

import java.util.UUID;

/** Resolves configured, effective members for every initial approval stage. */
public interface FindingWorkflowAssignmentResolver {

    InitialRoute resolve(UUID tenantId, UUID organizationId);

    FindingReviewCycleContracts.ReviewPair resolveReviewPair(UUID tenantId, UUID organizationId);

    UUID resolveSafetyDispatcher(UUID tenantId, UUID organizationId);
}
