package com.mightyoung.softwarefactory.tooling;

import com.mightyoung.softwarefactory.workflow.WorkflowDefinitionPin;
import com.mightyoung.softwarefactory.workflow.WorkflowEngineUnavailableException;
import com.mightyoung.softwarefactory.workflow.WorkflowFacade;
import com.mightyoung.softwarefactory.workflow.WorkflowTaskClaim;
import com.mightyoung.softwarefactory.workflow.WorkflowTaskReadSnapshot;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/** The guarded issue transaction hands the issued tools to the applicant for return. */
@Component
@EnableConfigurationProperties(ToolLoanWorkflow.Definitions.class)
public final class ToolLoanWorkflow {
    @ConfigurationProperties("software-factory.workflow.tool-loan")
    public record Definitions(Map<UUID, Pin> tenantPins) {
        public Definitions {
            tenantPins = tenantPins == null ? Map.of() : Map.copyOf(tenantPins);
        }
    }
    public record Pin(String definitionId, String resourceSha256) {}

    private final WorkflowFacade workflow;
    private final Definitions definitions;
    private final WorkflowTaskClaim taskClaim;

    public ToolLoanWorkflow(WorkflowFacade workflow, Definitions definitions, WorkflowTaskClaim taskClaim) {
        this.workflow = workflow;
        this.definitions = definitions;
        this.taskClaim = taskClaim;
    }

    public void issue(UUID tenantId, UUID loanId, UUID issuerMemberId, UUID applicantMemberId) {
        var configured = definitions.tenantPins().get(tenantId);
        if (configured == null) {
            throw unavailable("Missing tenant tool loan definition");
        }
        var pin = new WorkflowDefinitionPin(tenantId, configured.definitionId(), "toolLoanV1",
                configured.resourceSha256());
        workflow.startProcessByDefinitionId(tenantId, "tool_loan", loanId, 1, pin, Map.of(
                "issuerMemberId", issuerMemberId.toString(),
                "applicantMemberId", applicantMemberId.toString()));
        var authority = workflow.acquireTaskAuthority(tenantId, "tool_loan", loanId, "issue", issuerMemberId);
        workflow.completeTask(authority, Map.of());
        var handoff = workflow.readCurrentTask(tenantId, "tool_loan", loanId, "returnRequest");
        if (!handoff.active() || !applicantMemberId.toString().equals(handoff.assignee())) {
            throw unavailable("Issued loan has no active return task for its applicant");
        }
    }

    public void requestReturn(UUID tenantId, UUID loanId, UUID borrower, boolean firstPending) {
        if (firstPending) {
            var authority = workflow.acquireTaskAuthority(tenantId, "tool_loan", loanId, "returnRequest", borrower);
            // The eligible custodians are resolved at receipt time. Keep the task open for the pool.
            workflow.completeTask(authority, Map.of("receiverMemberId", ""));
        }
        pendingReceipt(tenantId, loanId);
    }

    public void receive(UUID tenantId, UUID loanId, UUID keeper, UUID applicant, boolean pendingRemains, boolean allSettled) {
        var task = pendingReceipt(tenantId, loanId);
        if (pendingRemains) {
            if (allSettled) throw unavailable("Settled loan still has pending returns");
            return;
        }
        taskClaim.claimAndComplete(task, keeper, Map.of("allItemsSettled", allSettled));
        if (!allSettled) {
            var next = workflow.readCurrentTask(tenantId, "tool_loan", loanId, "returnRequest");
            if (!next.active() || !applicant.toString().equals(next.assignee())) {
                throw unavailable("Missing active return task for the borrower");
            }
        }
    }

    private WorkflowTaskReadSnapshot pendingReceipt(UUID tenantId, UUID loanId) {
        var task = workflow.readCurrentTask(tenantId, "tool_loan", loanId, "receive");
        if (!"toolLoanV1".equals(task.processDefinitionKey()) || !task.active()
                || (task.assignee() != null && !task.assignee().isEmpty())) {
            throw unavailable("Missing open tool receipt task");
        }
        return task;
    }

    private static WorkflowEngineUnavailableException unavailable(String reason) {
        return new WorkflowEngineUnavailableException(new IllegalStateException(reason));
    }
}
