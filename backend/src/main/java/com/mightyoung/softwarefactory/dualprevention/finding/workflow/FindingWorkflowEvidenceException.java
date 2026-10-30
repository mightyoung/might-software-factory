package com.mightyoung.softwarefactory.dualprevention.finding.workflow;

/** Required evidence is absent, incomplete, unverified, or outside the current tenant. */
public final class FindingWorkflowEvidenceException extends RuntimeException {

    public FindingWorkflowEvidenceException(String message) {
        super(message);
    }
}
