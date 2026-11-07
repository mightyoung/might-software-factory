package com.mightyoung.softwarefactory.dualprevention;

import com.mightyoung.softwarefactory.authorization.AssignableFindingMember;
import com.mightyoung.softwarefactory.authorization.FindingWorkflowAuthorizationRepository;
import com.mightyoung.softwarefactory.dualprevention.finding.workflow.*;
import com.mightyoung.softwarefactory.identity.CurrentTenantContext;
import com.mightyoung.softwarefactory.identity.SessionContextResolver;
import com.mightyoung.softwarefactory.workflow.WorkflowTaskStateConflictException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class FindingDispatchCandidateServiceTest {
    private final UUID tenant=UUID.randomUUID(), organization=UUID.randomUUID(), id=UUID.randomUUID();
    private final CurrentTenantContext context=new CurrentTenantContext(UUID.randomUUID(),UUID.randomUUID(),tenant,UUID.randomUUID(),1,1,1);
    private final SessionContextResolver sessions=mock(SessionContextResolver.class);
    private final FindingWorkflowAccessPolicy access=mock(FindingWorkflowAccessPolicy.class);
    private final FindingWorkflowAuthorizationRepository authorization=mock(FindingWorkflowAuthorizationRepository.class);
    private final Reads reads=new Reads();
    private final FindingDispatchCandidateService service=new FindingDispatchCandidateService(sessions,reads,access,authorization);
    @BeforeEach void identity(){when(sessions.currentTenant()).thenReturn(context);}

    @Test void oneAuthorizedSnapshotUsesLookaheadWithoutSkippingTheLastReturnedEligibleMember(){
        UUID first=UUID.randomUUID(),second=UUID.randomUUID(),lookahead=UUID.randomUUID(),after=UUID.randomUUID();
        when(authorization.findAssignableMembers(tenant,organization,"finding.remediate","literal%_",after,3))
                .thenAnswer(call->{assertThat(reads.active).isTrue();return List.of(new AssignableFindingMember(first,"A"),
                        new AssignableFindingMember(second,"B"),new AssignableFindingMember(lookahead,"C"));});
        doAnswer(call->{assertThat(reads.active).isTrue();return null;}).when(access).requireAction(context,"finding.dispatch",organization);
        var result=service.candidates(id,"  literal%_  ",after,2);
        assertThat(result.data()).extracting(AssignableFindingMember::memberId).containsExactly(first,second);
        assertThat(result.hasMore()).isTrue();assertThat(result.nextAfterMemberId()).isEqualTo(second);
        assertThat(result.findingVersion()).isEqualTo(3);assertThat(result.roundNo()).isEqualTo(1);
        assertThat(reads.active).isFalse();verify(authorization,times(1)).findAssignableMembers(any(),any(),anyString(),anyString(),any(),anyInt());
    }

    @Test void aTerminalEmptyPageHasNoCursorAndDoesNotLoadAnEntireRoster(){
        when(authorization.findAssignableMembers(tenant,organization,"finding.remediate","",null,21)).thenReturn(List.of());
        var result=service.candidates(id,null,null,20);
        assertThat(result.data()).isEmpty();assertThat(result.hasMore()).isFalse();assertThat(result.nextAfterMemberId()).isNull();
        verify(authorization).findAssignableMembers(tenant,organization,"finding.remediate","",null,21);
    }

    @Test void missingOrForbiddenFindingNeverQueriesCandidates(){
        reads.finding=Optional.empty();assertThatThrownBy(()->service.candidates(id,"",null,20)).isInstanceOf(FindingNotVisibleException.class);
        verifyNoInteractions(access,authorization);reads.finding=Optional.of(finding(FindingWorkflowState.SAFETY_DISPATCH_PENDING,1));
        doThrow(new AccessDeniedException("Denied")).when(access).requireAction(context,"finding.dispatch",organization);
        assertThatThrownBy(()->service.candidates(id,"",null,20)).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(authorization);
    }

    @Test void inadmissibleStatesAndAnAlreadyIssuedReworkNoticeRejectBeforeEnumeration(){
        for(var state:List.of(FindingWorkflowState.PERSON_CONFIRM_PENDING,FindingWorkflowState.CLOSED,FindingWorkflowState.NOT_ESTABLISHED)){
            reads.finding=Optional.of(finding(state,1));assertThatThrownBy(()->service.candidates(id,"",null,20))
                    .isInstanceOf(WorkflowTaskStateConflictException.class);
        }
        reads.finding=Optional.of(finding(FindingWorkflowState.RECTIFYING,1));
        assertThatThrownBy(()->service.candidates(id,"",null,20)).isInstanceOf(WorkflowTaskStateConflictException.class);
        reads.finding=Optional.of(finding(FindingWorkflowState.RECTIFYING,2));reads.notice=true;
        assertThatThrownBy(()->service.candidates(id,"",null,20)).isInstanceOf(WorkflowTaskStateConflictException.class);
        verifyNoInteractions(authorization);
    }

    @Test void aFreshReworkRoundIsOnlyAHintAndDoesNotGrantOrExecuteACommand(){
        reads.finding=Optional.of(finding(FindingWorkflowState.RECTIFYING,2));
        when(authorization.findAssignableMembers(tenant,organization,"finding.remediate","",null,2)).thenReturn(List.of());
        var result=service.candidates(id,"",null,1);assertThat(result.roundNo()).isEqualTo(2);
        assertThat(result.state()).isEqualTo(FindingWorkflowState.RECTIFYING);verify(access).requireAction(context,"finding.dispatch",organization);
        verify(access,never()).requireAssignableMember(any(),any(),any(),any());
    }

    @Test void queryBoundsAndInvalidUnicodeFailBeforeAnyIdentityOrDatabaseWork(){
        for(String query:List.of("x".repeat(101),"bad\0value","bad\nvalue","\uD800","\uDC00")){
            assertThatThrownBy(()->service.candidates(id,query,null,20)).isInstanceOf(IllegalArgumentException.class);
        }
        for(int limit:List.of(0,51,Integer.MAX_VALUE))assertThatThrownBy(()->service.candidates(id,"",null,limit)).isInstanceOf(IllegalArgumentException.class);
        verify(sessions,never()).currentTenant();verifyNoInteractions(authorization,access);assertThat(reads.calls).isZero();
    }

    @Test void queryLengthUsesCodePointsRatherThanUtf16Units(){
        String prefix="\uD83D\uDE00".repeat(100);
        when(authorization.findAssignableMembers(tenant,organization,"finding.remediate",prefix,null,2)).thenReturn(List.of());
        assertThat(service.candidates(id,prefix,null,1).data()).isEmpty();
    }

    private FindingDispatchCandidateReadRepository.Finding finding(FindingWorkflowState state,int round){
        return new FindingDispatchCandidateReadRepository.Finding(id,organization,state,round,3);
    }
    private final class Reads implements FindingDispatchCandidateReadRepository {
        Optional<Finding> finding=Optional.of(FindingDispatchCandidateServiceTest.this.finding(FindingWorkflowState.SAFETY_DISPATCH_PENDING,1));
        boolean active,notice;int calls;
        @Override public <T>T inTenantReadSnapshot(UUID value,Function<Snapshot,T> work){
            assertThat(value).isEqualTo(tenant);calls++;active=true;
            try{return work.apply(new Snapshot(){public Optional<Finding> find(UUID key){assertThat(key).isEqualTo(id);return finding;}
                public boolean hasNotice(UUID key,int round){return notice;}});}finally{active=false;}
        }
    }
}
