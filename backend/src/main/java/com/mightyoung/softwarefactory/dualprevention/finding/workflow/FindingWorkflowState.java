package com.mightyoung.softwarefactory.dualprevention.finding.workflow;

/** Business projection of the finding remediation process. */
public enum FindingWorkflowState {
    DISCOVERED,
    PERSON_CONFIRM_PENDING,
    SAFETY_CONFIRM_PENDING,
    SAFETY_DISPATCH_PENDING,
    RECTIFYING,
    ACCEPTANCE_PENDING,
    CLOSED
}
