package com.mightyoung.softwarefactory.workflow;

/** BPMN task state no longer matches the requested business command. */
public final class WorkflowTaskStateConflictException extends RuntimeException {

    public WorkflowTaskStateConflictException() {
        super("The workflow task is no longer active for this command");
    }
}
