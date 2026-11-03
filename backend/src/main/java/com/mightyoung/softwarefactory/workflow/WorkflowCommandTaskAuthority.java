package com.mightyoung.softwarefactory.workflow;

import org.springframework.transaction.support.TransactionSynchronization;
import java.sql.Connection;

/** Valid only in the physical managed command transaction that acquired this task identity. */
public final class WorkflowCommandTaskAuthority implements TransactionSynchronization {
    private final WorkflowTaskReadSnapshot snapshot;
    final Connection connection;
    final FlowableWorkflowFacade owner;
    boolean invalidated;

    WorkflowCommandTaskAuthority(WorkflowTaskReadSnapshot snapshot, Connection connection,
                                 FlowableWorkflowFacade owner) {
        this.snapshot = snapshot;
        this.connection = connection;
        this.owner = owner;
    }
    public WorkflowTaskReadSnapshot snapshot() { return snapshot; }
    @Override public void savepointRollback(Object savepoint) { invalidated = true; }
    @Override public void afterCompletion(int status) { invalidated = true; }
}
