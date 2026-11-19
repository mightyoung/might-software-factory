package com.mightyoung.softwarefactory.workflow;

/** Explicit business exception transition. Registration fixes its process, source and terminal node. */
public interface WorkflowExceptionalEnd {
    record Route(String businessType,String definitionKey,String sourceTask,String endNode) { }
    void end(WorkflowTaskReadSnapshot expected,String endNode);
}
