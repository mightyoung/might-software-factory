package com.mightyoung.softwarefactory.dualprevention.finding.evidenceupload;

import com.mightyoung.softwarefactory.dualprevention.FindingNotVisibleException;
import com.mightyoung.softwarefactory.dualprevention.finding.workflow.FindingWorkflowAccessPolicy;
import com.mightyoung.softwarefactory.identity.CurrentTenantContext;
import com.mightyoung.softwarefactory.workflow.*;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static com.mightyoung.softwarefactory.dualprevention.finding.evidenceupload.FindingEvidenceUploadContracts.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class FindingEvidenceUploadAccessPolicyTest {
    final UUID tenant=UUID.randomUUID(),member=UUID.randomUUID(),id=UUID.randomUUID(),org=UUID.randomUUID(),setId=UUID.randomUUID();
    final CurrentTenantContext owner=new CurrentTenantContext(UUID.randomUUID(),UUID.randomUUID(),tenant,member,1,1,1);
    final FindingWorkflowAccessPolicy actions=mock(FindingWorkflowAccessPolicy.class);
    final WorkflowFacade workflow=mock(WorkflowFacade.class);
    final FindingEvidenceUploadAccessPolicy policy=new FindingEvidenceUploadAccessPolicy(actions,workflow);
    final Finding finding=new Finding(id,org,"RECTIFYING",2,7);
    final Notice notice=new Notice(UUID.randomUUID(),member,true);
    final EvidenceSet set=new EvidenceSet(setId,id,2,member,3,false);
    WorkflowTaskReadSnapshot task(String assignee,boolean active) {
        return new WorkflowTaskReadSnapshot(tenant,"FINDING",id,1,"definition-id","findingRemediationV2","process-id","remediation","task-id",assignee,active);
    }
    @Test void requiresCurrentActionAndActualTaskOwner() {
        when(workflow.readCurrentTask(tenant,"FINDING",id,"remediation")).thenReturn(task(member.toString(),true));
        policy.requireCurrent(owner,finding,2,notice,set);
        var order=inOrder(actions,workflow); order.verify(actions).requireAction(owner,"finding.remediate",org);
        order.verify(workflow).readCurrentTask(tenant,"FINDING",id,"remediation");
        verify(workflow,never()).completeTask(any(),any(),any(),any(),any(),any());
    }
    @Test void missingAssigneeIsExplicitAndDoesNotGuessOwner() {
        for (String assignee : new String[]{null,""," "}) {
            when(workflow.readCurrentTask(tenant,"FINDING",id,"remediation")).thenReturn(task(assignee,true));
            assertThatThrownBy(() -> policy.requireCurrent(owner,finding,2,notice,set))
                    .isInstanceOfSatisfying(FindingEvidenceUploadException.class,
                            e -> assertThat(e.reason()).isEqualTo(FindingEvidenceUploadException.Reason.ASSIGNMENT_UNRESOLVED));
        }
    }
    @Test void otherAssigneeOrInactiveTaskIsNotVisible() {
        for (WorkflowTaskReadSnapshot task : new WorkflowTaskReadSnapshot[]{task(UUID.randomUUID().toString(),true),task(member.toString(),false)}) {
            when(workflow.readCurrentTask(tenant,"FINDING",id,"remediation")).thenReturn(task);
            assertThatThrownBy(() -> policy.requireCurrent(owner,finding,2,notice,set)).isInstanceOf(FindingNotVisibleException.class);
        }
    }
    @Test void wrongStateRoundNoticeOwnerSetOwnerAndSealRefuseBeforeEngineRead() {
        for (Finding candidate : new Finding[]{new Finding(id,org,"CLOSED",2,7),new Finding(id,org,"RECTIFYING",3,7)}) {
            assertThatThrownBy(() -> policy.requireCurrent(owner,candidate,2,notice,set)).isInstanceOf(FindingNotVisibleException.class);
        }
        for (Notice candidate : new Notice[]{new Notice(notice.id(),UUID.randomUUID(),true),new Notice(notice.id(),member,false)}) {
            assertThatThrownBy(() -> policy.requireCurrent(owner,finding,2,candidate,set)).isInstanceOf(FindingNotVisibleException.class);
        }
        for (EvidenceSet candidate : new EvidenceSet[]{new EvidenceSet(setId,id,2,member,3,true),new EvidenceSet(setId,id,2,UUID.randomUUID(),3,false),
                new EvidenceSet(setId,UUID.randomUUID(),2,member,3,false),new EvidenceSet(setId,id,3,member,3,false)}) {
            assertThatThrownBy(() -> policy.requireCurrent(owner,finding,2,notice,candidate)).isInstanceOf(FindingNotVisibleException.class);
        }
        verifyNoInteractions(workflow);
    }
    @Test void engineUnavailabilityPropagatesWithoutAnyFallback() {
        when(workflow.readCurrentTask(tenant,"FINDING",id,"remediation")).thenThrow(new WorkflowEngineUnavailableException(new IllegalStateException("unavailable")));
        assertThatThrownBy(() -> policy.requireCurrent(owner,finding,2,notice,set)).isInstanceOf(WorkflowEngineUnavailableException.class);
    }
}
