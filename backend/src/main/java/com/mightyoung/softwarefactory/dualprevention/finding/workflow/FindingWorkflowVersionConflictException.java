package com.mightyoung.softwarefactory.dualprevention.finding.workflow;

/** Optimistic version supplied by a client did not match the current finding version. */
public final class FindingWorkflowVersionConflictException extends RuntimeException {

    public FindingWorkflowVersionConflictException() {
        super("The finding has changed; reload it before retrying the command");
    }
}
