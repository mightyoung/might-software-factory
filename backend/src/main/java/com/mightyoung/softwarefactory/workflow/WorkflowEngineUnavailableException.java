package com.mightyoung.softwarefactory.workflow;

/** Flowable could not safely complete an engine operation. */
public final class WorkflowEngineUnavailableException extends RuntimeException {

    public WorkflowEngineUnavailableException(Throwable cause) {
        super("The workflow engine could not complete the command", cause);
    }
}
