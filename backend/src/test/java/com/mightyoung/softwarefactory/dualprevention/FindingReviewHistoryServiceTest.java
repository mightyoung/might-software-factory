package com.mightyoung.softwarefactory.dualprevention;

import com.mightyoung.softwarefactory.dualprevention.finding.FindingReadScope;
import com.mightyoung.softwarefactory.dualprevention.finding.workflow.*;
import com.mightyoung.softwarefactory.identity.*;
import com.mightyoung.softwarefactory.workflow.*;
import org.junit.jupiter.api.*;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.function.Function;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
import static com.mightyoung.softwarefactory.dualprevention.finding.workflow.FindingReviewHistoryRepository.*;

class FindingReviewHistoryServiceTest {
    final UUID tenant=UUID.randomUUID(),id=UUID.randomUUID(),org=UUID.randomUUID(),reporter=UUID.randomUUID(),person=UUID.randomUUID(),safety=UUID.randomUUID();
    final SessionContextResolver sessions=mock(SessionContextResolver.class);
    final CurrentTenantContext context=mock(CurrentTenantContext.class);
    final FindingReadAuthorizer authorizer=mock(FindingReadAuthorizer.class);
    final FindingReviewHistoryRepository reads=mock(FindingReviewHistoryRepository.class);
    final Snapshot snapshot=mock(Snapshot.class);
    final FindingWorkflowAccessPolicy access=mock(FindingWorkflowAccessPolicy.class);
    final FindingWorkflowAssignmentResolver assignments=mock(FindingWorkflowAssignmentResolver.class);
    final WorkflowFacade workflow=mock(WorkflowFacade.class);
    final FindingReviewHistoryService service=new FindingReviewHistoryService(sessions,authorizer,reads,access,assignments,workflow);
    final FindingReadScope scope=new FindingReadScope(UUID.randomUUID(),tenant,person,Set.of(org));
    @BeforeEach void setup() {
        when(sessions.currentTenant()).thenReturn(context);when(context.tenantId()).thenReturn(tenant);when(context.memberId()).thenReturn(person);
        when(reads.inTenantReadSnapshot(eq(tenant),any())).thenAnswer(call -> ((Function<Snapshot,Object>)call.getArgument(1)).apply(snapshot));
        when(authorizer.requireReadScope(context)).thenReturn(scope);
        current("PERSON_CONFIRM_PENDING",FindingReviewProtocol.CYCLE_V1);
        when(workflow.readBoundDefinition(tenant,"FINDING",id)).thenReturn(Optional.of(new WorkflowBindingReadSnapshot(tenant,"FINDING",id,1,"def","findingRemediationV3","process")));
        when(assignments.resolveReviewPair(tenant,org)).thenReturn(new FindingReviewCycleContracts.ReviewPair(person,safety));
        when(workflow.readCurrentTask(tenant,"FINDING",id,"personConfirm")).thenReturn(new WorkflowTaskReadSnapshot(tenant,"FINDING",id,1,"def","findingRemediationV3","process","personConfirm","task",person.toString(),true));
        when(snapshot.entries(eq(scope),eq(id),any(),anyInt())).thenReturn(List.of());
    }
    void current(String state,FindingReviewProtocol protocol) { when(snapshot.find(scope,id)).thenReturn(Optional.of(new Current(id,org,reporter,state,9,protocol==FindingReviewProtocol.LEGACY?1:2,protocol))); }
    @Test void exposesPointInTimeActionsAndNullableDirectoryProvenance() {
        var row=new Entry("review-decision:"+id+":1:PERSON","REVIEW_DECISION",1,"PERSON","RETURN_FOR_INFORMATION",person,OffsetDateTime.parse("2026-01-01T00:00:00Z"),"reason",null,null);
        when(snapshot.entries(eq(scope),eq(id),isNull(),eq(21))).thenReturn(List.of(row));
        var result=service.history(id,20,null);
        assertThat(result.data().availableReviewActions()).containsExactly("APPROVE","RETURN_FOR_INFORMATION","NOT_ESTABLISHED");
        assertThat(result.data().reviewStage()).isEqualTo("PERSON");
        assertThat(result.data().entries().getFirst().actorDisplay()).isEqualTo(new FindingReviewHistoryApiModels.ActorDisplay(null,"UNAVAILABLE"));
        assertThat(result.data().entries().getFirst().recordedGrade()).isNull();
    }
    @Test void unauthorizedReadDoesNotReadFindingBindingOrPage() {
        when(authorizer.requireReadScope(context)).thenThrow(new org.springframework.security.access.AccessDeniedException("denied"));
        assertThatThrownBy(() -> service.history(id,20,null)).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        verifyNoInteractions(snapshot,workflow);
    }
    @Test void invisibleFindingDoesNotReadBindingOrPage() {
        when(snapshot.find(scope,id)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.history(id,20,null)).isInstanceOf(FindingNotVisibleException.class);
        verify(snapshot,never()).entries(any(),any(),any(),anyInt());verifyNoInteractions(workflow);
    }
    @Test void terminalHistoryDoesNotRequireCompletedTaskStillActive() {
        current("CLOSED",FindingReviewProtocol.CYCLE_V1);
        assertThat(service.history(id,20,null).data().availableReviewActions()).isEmpty();
        verify(workflow,never()).readCurrentTask(any(),any(),any(),any());
    }
    @Test void exactLegacyV1AndV2KeysKeepTheirDifferentActionAllowlist() {
        current("PERSON_CONFIRM_PENDING",FindingReviewProtocol.LEGACY);
        for(String key:List.of("findingRemediationV1","findingRemediationV2")) {
            when(workflow.readBoundDefinition(tenant,"FINDING",id)).thenReturn(Optional.of(new WorkflowBindingReadSnapshot(tenant,"FINDING",id,1,"def",key,"process")));
            when(workflow.readCurrentTask(tenant,"FINDING",id,"personConfirm")).thenReturn(new WorkflowTaskReadSnapshot(tenant,"FINDING",id,1,"def",key,"process","personConfirm","task",person.toString(),true));
            assertThat(service.history(id,20,null).data().availableReviewActions()).isEqualTo(key.endsWith("V1")?List.of("APPROVE"):List.of("APPROVE","NOT_ESTABLISHED"));
        }
    }
    @Test void reporterSupplementRequiresOriginalReporterAndActualReporterTask() {
        current("REPORTER_SUPPLEMENT_PENDING",FindingReviewProtocol.CYCLE_V1);
        when(context.memberId()).thenReturn(reporter);
        when(workflow.readCurrentTask(tenant,"FINDING",id,"reporterSupplement")).thenReturn(new WorkflowTaskReadSnapshot(tenant,"FINDING",id,1,"def","findingRemediationV3","process","reporterSupplement","task",reporter.toString(),true));
        var result=service.history(id,20,null).data();
        assertThat(result.reviewStage()).isNull();assertThat(result.availableReviewActions()).containsExactly("SUPPLEMENT");
        when(context.memberId()).thenReturn(person);
        assertThat(service.history(id,20,null).data().availableReviewActions()).isEmpty();
    }
    @Test void safetyRequiresSameCyclePersonProof() {
        current("SAFETY_CONFIRM_PENDING",FindingReviewProtocol.CYCLE_V1);when(context.memberId()).thenReturn(safety);
        when(snapshot.approvedPerson(id,2,true)).thenReturn(Optional.empty());
        assertThat(service.history(id,20,null).data().blockedReason()).isEqualTo("PERSON_APPROVAL_REQUIRED");
        verify(workflow,never()).readCurrentTask(any(),any(),any(),any());
    }

    @Test void dependencyFailurePropagatesInsteadOfEmptyActions() {
        when(workflow.readCurrentTask(any(),any(),any(),any())).thenThrow(new WorkflowEngineUnavailableException(new IllegalStateException("engine")));
        assertThatThrownBy(() -> service.history(id,20,null)).isInstanceOf(WorkflowEngineUnavailableException.class);
    }
    @Test void routeFailureIsExplicitlyBlocked() {
        when(assignments.resolveReviewPair(tenant,org)).thenThrow(new FindingWorkflowConfigurationException("missing"));
        assertThat(service.history(id,20,null).data().blockedReason()).isEqualTo("REVIEW_ROUTE_UNAVAILABLE");
    }
    @Test void unboundDiscoveredIsValidAndHasNoActions() {
        current("DISCOVERED",FindingReviewProtocol.LEGACY);
        when(workflow.readBoundDefinition(tenant,"FINDING",id)).thenReturn(Optional.empty());
        var result=service.history(id,20,null).data();
        assertThat(result.processDefinitionKey()).isNull();assertThat(result.blockedReason()).isEqualTo("WORKFLOW_NOT_INITIALIZED");
    }
    @Test void paginationHasNoTotalAndRejectsCrossFindingOrChangedPageSizeCursor() {
        var rows=new ArrayList<Entry>();
        for(int i=0;i<21;i++) rows.add(new Entry("review-decision:"+id+":"+(i+1)+":PERSON","REVIEW_DECISION",i+1,"PERSON","APPROVE",person,OffsetDateTime.parse("2026-01-01T00:00:00Z"),"ok",null,null));
        when(snapshot.entries(eq(scope),eq(id),isNull(),eq(21))).thenReturn(rows);
        var result=service.history(id,20,null).data();
        assertThat(result.entries()).hasSize(20);assertThat(result.hasMore()).isTrue();assertThat(result.nextCursor()).isNotBlank();
        assertThatThrownBy(() -> service.history(id,50,result.nextCursor())).isInstanceOf(com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.ValidationException.class);
    }
    @Test void malformedCursorAndPageSizesAreRejectedBeforePageQueries() {
        assertThatThrownBy(() -> service.history(id,21,null)).isInstanceOf(com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.ValidationException.class);
        assertThatThrownBy(() -> service.history(id,20,"???")).isInstanceOf(com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.ValidationException.class);
        verify(snapshot,never()).entries(any(),any(),any(),anyInt());
    }
}
