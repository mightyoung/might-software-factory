package com.mightyoung.softwarefactory.dualprevention.finding.workflow;

/** Command cannot be applied to the finding's current business state. */
public final class FindingWorkflowRuleException extends RuntimeException {

    public FindingWorkflowRuleException(String message) {
        super(message);
    }
}
