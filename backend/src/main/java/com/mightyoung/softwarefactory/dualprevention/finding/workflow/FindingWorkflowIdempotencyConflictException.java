package com.mightyoung.softwarefactory.dualprevention.finding.workflow;

/** An idempotency key was reused by a different actor or with a different action/request body. */
public final class FindingWorkflowIdempotencyConflictException extends RuntimeException {

    public FindingWorkflowIdempotencyConflictException() {
        super("The idempotency key was already used for a different command");
    }
}
