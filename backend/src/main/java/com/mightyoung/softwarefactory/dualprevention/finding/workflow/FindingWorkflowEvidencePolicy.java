package com.mightyoung.softwarefactory.dualprevention.finding.workflow;

import java.util.UUID;

/** Validates a ready, non-empty evidence set in the finding's tenant and current remediation round. */
public interface FindingWorkflowEvidencePolicy {

    void requireReady(UUID tenantId, UUID findingId, int roundNo, UUID evidenceSetId);
}
