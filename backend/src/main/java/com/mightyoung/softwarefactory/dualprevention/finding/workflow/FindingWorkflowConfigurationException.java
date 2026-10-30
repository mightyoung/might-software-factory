package com.mightyoung.softwarefactory.dualprevention.finding.workflow;

/** Required workflow assignment configuration is missing, ambiguous, or inactive. */
public final class FindingWorkflowConfigurationException extends RuntimeException {

    public FindingWorkflowConfigurationException(String message) {
        super(message);
    }
}
