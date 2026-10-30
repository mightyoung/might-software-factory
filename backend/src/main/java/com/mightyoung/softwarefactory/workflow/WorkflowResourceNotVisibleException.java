package com.mightyoung.softwarefactory.workflow;

/** A workflow binding is not available in the caller's verified tenant context. */
public final class WorkflowResourceNotVisibleException extends RuntimeException {

    public WorkflowResourceNotVisibleException() {
        super("The workflow resource is not visible");
    }
}
