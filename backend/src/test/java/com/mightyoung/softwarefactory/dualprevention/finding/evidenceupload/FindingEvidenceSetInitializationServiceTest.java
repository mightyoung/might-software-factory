package com.mightyoung.softwarefactory.dualprevention.finding.evidenceupload;

import com.mightyoung.softwarefactory.dualprevention.FindingNotVisibleException;
import com.mightyoung.softwarefactory.dualprevention.finding.workflow.FindingWorkflowAccessPolicy;
import com.mightyoung.softwarefactory.identity.*;
import com.mightyoung.softwarefactory.workflow.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import static com.mightyoung.softwarefactory.dualprevention.finding.evidenceupload.FindingEvidenceSetInitializationContracts.*;
import static com.mightyoung.softwarefactory.dualprevention.finding.evidenceupload.FindingEvidenceSetInitializationException.Reason.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.mightyoung.softwarefactory.dualprevention.finding.evidenceupload.FindingEvidenceUploadContracts.Finding;
import com.mightyoung.softwarefactory.dualprevention.finding.evidenceupload.FindingEvidenceUploadContracts.Notice;
import com.mightyoung.softwarefactory.dualprevention.finding.evidenceupload.FindingEvidenceUploadContracts.EvidenceSet;

class FindingEvidenceSetInitializationServiceTest {
    final UUID tenant=UUID.randomUUID(),member=UUID.randomUUID(),actor=UUID.randomUUID(),account=UUID.randomUUID();
    final UUID findingId=UUID.randomUUID(),noticeId=UUID.randomUUID(),setId=UUID.randomUUID();
    final CurrentTenantContext owner=new CurrentTenantContext(actor,account,tenant,member,1,2,3);
    final InitializeCommand command=new InitializeCommand(UUID.randomUUID(),findingId,2,7);
    final Finding finding=new Finding(findingId,UUID.randomUUID(),"RECTIFYING",2,7);
    final Notice notice=new Notice(noticeId,member,true);
    final OffsetDateTime created=OffsetDateTime.parse("2026-11-03T00:00:00Z");
    final SessionContextResolver sessions=mock(SessionContextResolver.class);
    final FindingEvidenceSetInitializationRepository repository=mock(FindingEvidenceSetInitializationRepository.class);
    final FindingEvidenceSetInitializationRepository.Transaction tx=mock(FindingEvidenceSetInitializationRepository.Transaction.class);
    final FindingWorkflowAccessPolicy actions=mock(FindingWorkflowAccessPolicy.class);
    final WorkflowFacade workflow=mock(WorkflowFacade.class);
    FindingEvidenceSetInitializationService service;
    Receipt receipt(String hash){return new Receipt(setId,account,actor,member,findingId,2,noticeId,hash,created);}
    WorkflowTaskReadSnapshot task(UUID taskTenant,String businessType,UUID businessId,int revision,String definitionId,String definitionKey,
            String processId,String taskKey,String taskId,String assignee,boolean active){
        return new WorkflowTaskReadSnapshot(taskTenant,businessType,businessId,revision,definitionId,definitionKey,processId,taskKey,taskId,assignee,active);
    }
    WorkflowTaskReadSnapshot task(String assignee,boolean active){return task(tenant,"FINDING",findingId,1,"pinned-definition-id","finding-v2","instance-id","remediation","task-id",assignee,active);}
    @BeforeEach @SuppressWarnings("unchecked") void setup(){
        service=new FindingEvidenceSetInitializationService(sessions,repository,actions,workflow,20,2000);
        when(sessions.currentTenant()).thenReturn(owner);
        when(repository.inTenantTransaction(eq(tenant),any())).thenAnswer(call -> ((Function<FindingEvidenceSetInitializationRepository.Transaction,Object>)call.getArgument(1)).apply(tx));
        when(tx.finding(findingId)).thenReturn(Optional.of(finding));when(tx.notice(findingId,2)).thenReturn(Optional.of(notice));
        when(workflow.readCurrentTask(tenant,"FINDING",findingId,"remediation")).thenReturn(task(member.toString(),true));
        when(tx.existing(actor,command.idempotencyKey())).thenReturn(Optional.empty());when(tx.quota(member)).thenReturn(new Quota(0,0));
        when(tx.initialize(owner,command,notice,command.fingerprint(owner,noticeId))).thenReturn(receipt(command.fingerprint(owner,noticeId)));
    }
    @AfterEach void clear(){TransactionSynchronizationManager.clear();}
    @Test void initializesServerOwnedEmptySetAfterCurrentActionNoticeAndActualTask(){
        assertThat(service.initialize(command)).isEqualTo(new Result(setId,0,created,false));
        var order=inOrder(actions,workflow,tx);
        order.verify(actions).requireAction(owner,"finding.remediate",finding.organizationId());
        order.verify(workflow).readCurrentTask(tenant,"FINDING",findingId,"remediation");
        order.verify(tx).existing(actor,command.idempotencyKey());order.verify(tx).quota(member);
        order.verify(tx).initialize(owner,command,notice,command.fingerprint(owner,noticeId));
        verify(workflow,never()).startProcess(any(),any(),any(),anyInt(),any(),any());
        verify(workflow,never()).completeTask(any(),any(),any(),any(),any(),any());
    }
    @Test void matchingReplaySkipsQuotaAndOldVersionButChecksRealSetOwnerAndSeal(){
        when(tx.finding(findingId)).thenReturn(Optional.of(new Finding(findingId,finding.organizationId(),"RECTIFYING",2,9)));
        when(tx.existing(actor,command.idempotencyKey())).thenReturn(Optional.of(receipt(command.fingerprint(owner,noticeId))));
        when(tx.evidenceSet(setId)).thenReturn(Optional.of(new EvidenceSet(setId,findingId,2,member,11,false)));
        assertThat(service.initialize(command)).isEqualTo(new Result(setId,0,created,true));
        verify(tx,never()).quota(any());verify(tx,never()).initialize(any(),any(),any(),any());
    }
    @Test void revokedCurrentActionPreventsReceiptLookupOrReplay(){
        doThrow(new AccessDeniedException("revoked")).when(actions).requireAction(owner,"finding.remediate",finding.organizationId());
        assertThatThrownBy(() -> service.initialize(command)).isInstanceOf(AccessDeniedException.class);
        verify(tx,never()).existing(any(),any());verifyNoInteractions(workflow);
    }
    @Test void sameKeyChangedPayloadIsIdempotencyConflictBeforeQuota(){
        when(tx.existing(actor,command.idempotencyKey())).thenReturn(Optional.of(receipt("0".repeat(64))));
        assertThatThrownBy(() -> service.initialize(command)).isInstanceOfSatisfying(FindingEvidenceSetInitializationException.class,
                e -> assertThat(e.reason()).isEqualTo(IDEMPOTENCY_CONFLICT));
        verify(tx,never()).quota(any());verify(tx,never()).initialize(any(),any(),any(),any());
    }
    @Test void newKeyStaleVersionFailsAfterAuthorizationAndLookupBeforeQuota(){
        when(tx.finding(findingId)).thenReturn(Optional.of(new Finding(findingId,finding.organizationId(),"RECTIFYING",2,8)));
        assertThatThrownBy(() -> service.initialize(command)).isInstanceOfSatisfying(FindingEvidenceSetInitializationException.class,
                e -> assertThat(e.reason()).isEqualTo(VERSION_CONFLICT));
        verify(tx).existing(actor,command.idempotencyKey());verify(tx,never()).quota(any());
    }
    @Test void logicalSetQuotaBoundsNewSetsOnly(){
        for(Quota quota:new Quota[]{new Quota(20,20),new Quota(0,2000)}){
            when(tx.quota(member)).thenReturn(quota);
            assertThatThrownBy(() -> service.initialize(command)).isInstanceOfSatisfying(FindingEvidenceSetInitializationException.class,
                    e -> assertThat(e.reason()).isEqualTo(QUOTA_EXCEEDED));
        }
        verify(tx,never()).initialize(any(),any(),any(),any());
    }
    @Test void wrongFindingStateRoundOrNoticeOwnerCannotLookupKey(){
        for(Finding candidate:new Finding[]{new Finding(findingId,finding.organizationId(),"CLOSED",2,7),new Finding(findingId,finding.organizationId(),"RECTIFYING",3,7)}){
            when(tx.finding(findingId)).thenReturn(Optional.of(candidate));
            assertThatThrownBy(() -> service.initialize(command)).isInstanceOf(FindingNotVisibleException.class);
        }
        when(tx.finding(findingId)).thenReturn(Optional.of(finding));
        for(Notice candidate:new Notice[]{new Notice(noticeId,UUID.randomUUID(),true),new Notice(noticeId,member,false)}){
            when(tx.notice(findingId,2)).thenReturn(Optional.of(candidate));
            assertThatThrownBy(() -> service.initialize(command)).isInstanceOf(FindingNotVisibleException.class);
        }
        verify(tx,never()).existing(any(),any());verifyNoInteractions(workflow);
    }
    @Test void missingAssigneeHasExplicitReasonWhileForeignOrSuspendedTaskIsNotVisible(){
        for(String assignee:new String[]{null,""," "}){
            when(workflow.readCurrentTask(tenant,"FINDING",findingId,"remediation")).thenReturn(task(assignee,true));
            assertThatThrownBy(() -> service.initialize(command)).isInstanceOfSatisfying(FindingEvidenceSetInitializationException.class,
                    e -> assertThat(e.reason()).isEqualTo(ASSIGNMENT_UNRESOLVED));
        }
        for(WorkflowTaskReadSnapshot candidate:new WorkflowTaskReadSnapshot[]{task(UUID.randomUUID().toString(),true),task(member.toString(),false)}){
            when(workflow.readCurrentTask(tenant,"FINDING",findingId,"remediation")).thenReturn(candidate);
            assertThatThrownBy(() -> service.initialize(command)).isInstanceOf(FindingNotVisibleException.class);
        }
        verify(tx,never()).existing(any(),any());
    }
    @Test void malformedOrWrongPinnedTaskIdentityIsUnavailable(){
        for(WorkflowTaskReadSnapshot candidate:new WorkflowTaskReadSnapshot[]{null,
                task(UUID.randomUUID(),"FINDING",findingId,1,"def","key","proc","remediation","task",member.toString(),true),
                task(tenant,"OTHER",findingId,1,"def","key","proc","remediation","task",member.toString(),true),
                task(tenant,"FINDING",UUID.randomUUID(),1,"def","key","proc","remediation","task",member.toString(),true),
                task(tenant,"FINDING",findingId,2,"def","key","proc","remediation","task",member.toString(),true),
                task(tenant,"FINDING",findingId,1,"","key","proc","remediation","task",member.toString(),true),
                task(tenant,"FINDING",findingId,1,"def","","proc","remediation","task",member.toString(),true),
                task(tenant,"FINDING",findingId,1,"def","key","","remediation","task",member.toString(),true),
                task(tenant,"FINDING",findingId,1,"def","key","proc","other","task",member.toString(),true),
                task(tenant,"FINDING",findingId,1,"def","key","proc","remediation","",member.toString(),true)}){
            when(workflow.readCurrentTask(tenant,"FINDING",findingId,"remediation")).thenReturn(candidate);
            assertThatThrownBy(() -> service.initialize(command)).isInstanceOfSatisfying(FindingEvidenceSetInitializationException.class,
                    e -> assertThat(e.reason()).isEqualTo(UNAVAILABLE));
        }
        verify(tx,never()).existing(any(),any());
    }
    @Test void replayCannotUseForeignOrSealedSet(){
        when(tx.existing(actor,command.idempotencyKey())).thenReturn(Optional.of(receipt(command.fingerprint(owner,noticeId))));
        for(EvidenceSet candidate:new EvidenceSet[]{new EvidenceSet(setId,findingId,2,member,0,true),new EvidenceSet(setId,findingId,2,UUID.randomUUID(),0,false),
                new EvidenceSet(setId,UUID.randomUUID(),2,member,0,false),new EvidenceSet(setId,findingId,3,member,0,false)}){
            when(tx.evidenceSet(setId)).thenReturn(Optional.of(candidate));
            assertThatThrownBy(() -> service.initialize(command)).isInstanceOf(FindingNotVisibleException.class);
        }
        verify(tx,never()).initialize(any(),any(),any(),any());
    }
    @Test void transactionCommitFailureCannotBecomeSuccess(){
        when(repository.inTenantTransaction(eq(tenant),any())).thenThrow(new IllegalStateException("commit failed"));
        assertThatThrownBy(() -> service.initialize(command)).isInstanceOf(IllegalStateException.class).hasMessage("commit failed");
    }
    @Test void ambientTransactionAndBoundResourcesRefuseBeforeIdentity(){
        TransactionSynchronizationManager.setActualTransactionActive(true);
        assertThatThrownBy(() -> service.initialize(command)).isInstanceOf(IllegalStateException.class);
        TransactionSynchronizationManager.clear();Object key=new Object();TransactionSynchronizationManager.bindResource(key,new Object());
        try{assertThatThrownBy(() -> service.initialize(command)).isInstanceOf(IllegalStateException.class);}
        finally{TransactionSynchronizationManager.unbindResource(key);}
        verifyNoInteractions(sessions,repository,actions,workflow);
    }
}
