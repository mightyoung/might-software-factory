package com.mightyoung.softwarefactory.dualprevention.finding.evidenceupload;

import com.mightyoung.softwarefactory.authorization.FindingWorkflowAuthorizationRepository;
import com.mightyoung.softwarefactory.dualprevention.FindingNotVisibleException;
import com.mightyoung.softwarefactory.files.FileEvidenceTrustUnavailableException;
import com.mightyoung.softwarefactory.identity.AccountStatusReadRepository;
import com.mightyoung.softwarefactory.identity.PrincipalStatusReadRepository;
import com.mightyoung.softwarefactory.tenancy.TenantApplicationReadRepository;
import com.mightyoung.softwarefactory.tenancy.TenantMembershipReadRepository;
import com.mightyoung.softwarefactory.workflow.WorkflowFacade;
import java.util.UUID;
import org.springframework.stereotype.Component;
import static com.mightyoung.softwarefactory.dualprevention.finding.evidenceupload.FindingEvidenceIngestionRepository.*;

/** Fresh persisted eligibility without constructing a human session for a technical worker. */
@Component
public final class FindingEvidenceTechnicalEligibilityPolicy {
    private final AccountStatusReadRepository accounts;
    private final PrincipalStatusReadRepository principals;
    private final TenantMembershipReadRepository memberships;
    private final TenantApplicationReadRepository applications;
    private final FindingWorkflowAuthorizationRepository authorization;
    private final WorkflowFacade workflow;
    private final FindingEvidenceIngestionProperties properties;
    public FindingEvidenceTechnicalEligibilityPolicy(AccountStatusReadRepository accounts,PrincipalStatusReadRepository principals,
            TenantMembershipReadRepository memberships,TenantApplicationReadRepository applications,
            FindingWorkflowAuthorizationRepository authorization,WorkflowFacade workflow,FindingEvidenceIngestionProperties properties){
        this.accounts=accounts;this.principals=principals;this.memberships=memberships;this.applications=applications;
        this.authorization=authorization;this.workflow=workflow;this.properties=properties;
    }
    public UUID requireService(Transaction tx){
        tx.assertOpen();var i=tx.current();UUID actor=properties.technicalActorId();
        if(!properties.enabled()||actor==null||!properties.tenants().contains(i.tenantId()))throw new FileEvidenceTrustUnavailableException();
        var principal=principals.findByActorId(actor).orElseThrow(FileEvidenceTrustUnavailableException::new);tx.assertOpen();
        if(!actor.equals(principal.principalId())||principal.accountId()!=null||!"SERVICE".equals(principal.kind())
                ||!"ACTIVE".equals(principal.status()))throw new FileEvidenceTrustUnavailableException();
        return actor;
    }
    public void requireOwner(Transaction tx){
        tx.assertOpen();var i=tx.current();var owner=i.declaration();
        var principal=principals.findByActorId(owner.ownerActorId()).orElseThrow(FindingNotVisibleException::new);tx.assertOpen();
        var account=accounts.findState(owner.ownerAccountId()).orElseThrow(FindingNotVisibleException::new);tx.assertOpen();
        var member=memberships.findCurrentMembership(i.tenantId(),owner.ownerAccountId(),owner.ownerMemberId()).orElseThrow(FindingNotVisibleException::new);tx.assertOpen();
        var app=applications.findCurrent(i.tenantId(),"safety").orElseThrow(FindingNotVisibleException::new);tx.assertOpen();
        if(!owner.ownerActorId().equals(principal.principalId())||!owner.ownerAccountId().equals(principal.accountId())
                ||!"HUMAN".equals(principal.kind())||!"ACTIVE".equals(principal.status())||!"ACTIVE".equals(account.status())
                ||!"ACTIVE".equals(member.tenantStatus())||!"ACTIVE".equals(member.memberStatus())||!app.enabled())throw new FindingNotVisibleException();
        var finding=tx.finding(owner.findingId()).orElseThrow(FindingNotVisibleException::new);
        var notice=tx.notice(owner.findingId(),owner.roundNo()).orElseThrow(FindingNotVisibleException::new);
        var set=tx.evidenceSet(owner.evidenceSetId()).orElseThrow(FindingNotVisibleException::new);
        if(!"RECTIFYING".equals(finding.state())||finding.roundNo()!=owner.roundNo()||!notice.openRound()
                ||!owner.noticeId().equals(notice.id())||!owner.ownerMemberId().equals(notice.assigneeMemberId())
                ||!owner.findingId().equals(set.findingId())||owner.roundNo()!=set.roundNo()
                ||!owner.ownerMemberId().equals(set.ownerMemberId())||set.sealed())throw new FindingNotVisibleException();
        if(!authorization.isAssignableMemberAuthorizedForAction(i.tenantId(),owner.ownerMemberId(),finding.organizationId(),"finding.remediate"))throw new FindingNotVisibleException();
        tx.assertOpen();var task=workflow.readCurrentTask(i.tenantId(),"FINDING",owner.findingId(),"remediation");tx.assertOpen();
        if(task==null||!task.active()||!i.tenantId().equals(task.tenantId())||!owner.findingId().equals(task.businessId())
                ||!"FINDING".equals(task.businessType())||task.businessRevision()!=1||!"remediation".equals(task.taskDefinitionKey())
                ||!owner.ownerMemberId().toString().equals(task.assignee()))throw new FindingNotVisibleException();
    }
}
