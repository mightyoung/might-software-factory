package com.mightyoung.softwarefactory.dualprevention.finding.workflow;

import com.mightyoung.softwarefactory.identity.CurrentTenantContext;

import java.util.UUID;

/** Validates evidence using the server-resolved actor context and the current remediation round. */
public interface FindingWorkflowEvidencePolicy {

    void requireReady(CurrentTenantContext context, UUID findingId, int roundNo, UUID evidenceSetId);
}
