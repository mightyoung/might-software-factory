package com.mightyoung.softwarefactory.dualprevention.finding.evidenceupload;

import com.mightyoung.softwarefactory.dualprevention.FindingNotVisibleException;
import com.mightyoung.softwarefactory.dualprevention.finding.workflow.FindingWorkflowAccessPolicy;
import com.mightyoung.softwarefactory.identity.CurrentTenantContext;
import com.mightyoung.softwarefactory.workflow.WorkflowFacade;
import org.springframework.stereotype.Component;
import static com.mightyoung.softwarefactory.dualprevention.finding.evidenceupload.FindingEvidenceUploadContracts.*;

@Component
public final class FindingEvidenceUploadAccessPolicy {
    private final FindingWorkflowAccessPolicy actions;
    private final WorkflowFacade workflow;
    public FindingEvidenceUploadAccessPolicy(FindingWorkflowAccessPolicy actions, WorkflowFacade workflow) {
        this.actions = actions; this.workflow = workflow;
    }
    public void requireCurrent(CurrentTenantContext owner, Finding finding, int roundNo, Notice notice, EvidenceSet set) {
        requireAction(owner, finding);
        requireOwnedContext(owner, finding, roundNo, notice, set);
    }
    void requireAction(CurrentTenantContext owner, Finding finding) {
        actions.requireAction(owner, "finding.remediate", finding.organizationId());
    }
    void requireOwnedContext(CurrentTenantContext owner, Finding finding, int roundNo, Notice notice, EvidenceSet set) {
        if (!"RECTIFYING".equals(finding.state()) || finding.roundNo() != roundNo || !notice.openRound()
                || !owner.memberId().equals(notice.assigneeMemberId())
                || !finding.id().equals(set.findingId()) || set.roundNo() != roundNo
                || !owner.memberId().equals(set.ownerMemberId()) || set.sealed()) {
            throw new FindingNotVisibleException();
        }
        var task = workflow.readCurrentTask(owner.tenantId(), "FINDING", finding.id(), "remediation");
        if (task.assignee() == null || task.assignee().isBlank()) {
            throw new FindingEvidenceUploadException(FindingEvidenceUploadException.Reason.ASSIGNMENT_UNRESOLVED);
        }
        if (!task.active() || !owner.memberId().toString().equals(task.assignee())) {
            throw new FindingNotVisibleException();
        }
    }
}
