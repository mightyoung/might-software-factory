package com.mightyoung.softwarefactory.dualprevention.finding.workflow;

import com.mightyoung.softwarefactory.identity.CurrentTenantContext;
import com.mightyoung.softwarefactory.files.VerifiedFileEvidenceSnapshot;

import java.util.List;
import java.util.UUID;

/** Validates evidence using the server-resolved actor context and the current remediation round. */
public interface FindingWorkflowEvidencePolicy {

    List<VerifiedFileEvidenceSnapshot> requireReady(
            CurrentTenantContext context, UUID findingId, int roundNo, UUID evidenceSetId);

    void requirePinnedEvidence(List<VerifiedFileEvidenceSnapshot> evidenceSnapshots);
}
