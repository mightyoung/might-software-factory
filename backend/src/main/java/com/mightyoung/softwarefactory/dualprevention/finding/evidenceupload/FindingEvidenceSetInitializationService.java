package com.mightyoung.softwarefactory.dualprevention.finding.evidenceupload;

import com.mightyoung.softwarefactory.dualprevention.FindingNotVisibleException;
import com.mightyoung.softwarefactory.dualprevention.finding.workflow.FindingWorkflowAccessPolicy;
import com.mightyoung.softwarefactory.identity.CurrentTenantContext;
import com.mightyoung.softwarefactory.identity.SessionContextResolver;
import com.mightyoung.softwarefactory.workflow.WorkflowFacade;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import static com.mightyoung.softwarefactory.dualprevention.finding.evidenceupload.FindingEvidenceSetInitializationContracts.*;
import static com.mightyoung.softwarefactory.dualprevention.finding.evidenceupload.FindingEvidenceSetInitializationException.Reason.*;
import com.mightyoung.softwarefactory.dualprevention.finding.evidenceupload.FindingEvidenceUploadContracts.Finding;
import com.mightyoung.softwarefactory.dualprevention.finding.evidenceupload.FindingEvidenceUploadContracts.Notice;

/** Creates an empty editable set only. No producer, evidence item, revision advancement or workflow effect. */
@Service
public final class FindingEvidenceSetInitializationService {
    private final SessionContextResolver sessions;
    private final FindingEvidenceSetInitializationRepository repository;
    private final FindingWorkflowAccessPolicy actions;
    private final WorkflowFacade workflow;
    private final Limits limits;
    public FindingEvidenceSetInitializationService(SessionContextResolver sessions,FindingEvidenceSetInitializationRepository repository,
            FindingWorkflowAccessPolicy actions,WorkflowFacade workflow,
            @Value("${software-factory.findings.evidence-set-initialization.owner-open-sets:20}") int ownerOpenSets,
            @Value("${software-factory.findings.evidence-set-initialization.tenant-open-sets:2000}") int tenantOpenSets) {
        this.sessions=sessions;this.repository=repository;this.actions=actions;this.workflow=workflow;
        limits=new Limits(ownerOpenSets,tenantOpenSets);
    }
    public Result initialize(InitializeCommand command) {
        FindingEvidenceUploadService.requireIndependentTransaction();
        java.util.Objects.requireNonNull(command);
        var owner=sessions.currentTenant();
        return repository.inTenantTransaction(owner.tenantId(),tx -> {
            var finding=tx.finding(command.findingId()).orElseThrow(FindingNotVisibleException::new);
            actions.requireAction(owner,"finding.remediate",finding.organizationId());
            var notice=tx.notice(finding.id(),command.roundNo()).orElseThrow(FindingNotVisibleException::new);
            requireCurrentAssignment(owner,finding,command.roundNo(),notice);
            String hash=command.fingerprint(owner,notice.id());
            var existing=tx.existing(owner.actorId(),command.idempotencyKey());
            if(existing.isPresent()) {
                var receipt=existing.get();
                if(!hash.equals(receipt.payloadSha256()))throw new FindingEvidenceSetInitializationException(IDEMPOTENCY_CONFLICT);
                var set=tx.evidenceSet(receipt.evidenceSetId()).orElseThrow(FindingNotVisibleException::new);
                if(!owner.accountId().equals(receipt.ownerAccountId()) || !owner.actorId().equals(receipt.ownerActorId())
                        || !owner.memberId().equals(receipt.ownerMemberId()) || !finding.id().equals(receipt.findingId())
                        || command.roundNo()!=receipt.roundNo() || !notice.id().equals(receipt.noticeId())
                        || !finding.id().equals(set.findingId()) || command.roundNo()!=set.roundNo()
                        || !owner.memberId().equals(set.ownerMemberId()) || set.sealed())throw new FindingNotVisibleException();
                return receipt.result(true);
            }
            if(finding.version()!=command.expectedFindingVersion())throw new FindingEvidenceSetInitializationException(VERSION_CONFLICT);
            var quota=tx.quota(owner.memberId());
            if(quota.ownerCount()>=limits.ownerOpenSets() || quota.tenantCount()>=limits.tenantOpenSets()) {
                throw new FindingEvidenceSetInitializationException(QUOTA_EXCEEDED);
            }
            return tx.initialize(owner,command,notice,hash).result(false);
        });
    }
    private void requireCurrentAssignment(CurrentTenantContext owner,Finding finding,int roundNo,Notice notice) {
        if(!"RECTIFYING".equals(finding.state()) || finding.roundNo()!=roundNo || !notice.openRound()
                || !owner.memberId().equals(notice.assigneeMemberId()))throw new FindingNotVisibleException();
        var task=workflow.readCurrentTask(owner.tenantId(),"FINDING",finding.id(),"remediation");
        if(task==null || !owner.tenantId().equals(task.tenantId()) || !"FINDING".equals(task.businessType())
                || !finding.id().equals(task.businessId()) || task.businessRevision()!=1
                || !"remediation".equals(task.taskDefinitionKey()) || blank(task.definitionId())
                || blank(task.processDefinitionKey()) || blank(task.processInstanceId()) || blank(task.taskId())) {
            throw new FindingEvidenceSetInitializationException(UNAVAILABLE);
        }
        if(blank(task.assignee()))throw new FindingEvidenceSetInitializationException(ASSIGNMENT_UNRESOLVED);
        if(!task.active() || !owner.memberId().toString().equals(task.assignee()))throw new FindingNotVisibleException();
    }
    private static boolean blank(String value){return value==null || value.isBlank();}
}
