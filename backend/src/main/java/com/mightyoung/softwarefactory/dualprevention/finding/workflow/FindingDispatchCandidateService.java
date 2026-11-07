package com.mightyoung.softwarefactory.dualprevention.finding.workflow;

import com.mightyoung.softwarefactory.authorization.AssignableFindingMember;
import com.mightyoung.softwarefactory.authorization.FindingWorkflowAuthorizationRepository;
import com.mightyoung.softwarefactory.dualprevention.FindingNotVisibleException;
import com.mightyoung.softwarefactory.identity.SessionContextResolver;
import com.mightyoung.softwarefactory.workflow.WorkflowTaskStateConflictException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Candidate hints are not assignment authority; the dispatch command always validates again. */
@Service
public final class FindingDispatchCandidateService {
    private final SessionContextResolver sessions;
    private final FindingDispatchCandidateReadRepository reads;
    private final FindingWorkflowAccessPolicy access;
    private final FindingWorkflowAuthorizationRepository authorization;

    public FindingDispatchCandidateService(SessionContextResolver sessions, FindingDispatchCandidateReadRepository reads,
            FindingWorkflowAccessPolicy access, FindingWorkflowAuthorizationRepository authorization) {
        this.sessions=sessions;this.reads=reads;this.access=access;this.authorization=authorization;
    }

    public Result candidates(UUID findingId, String prefix, UUID afterMemberId, int limit) {
        Objects.requireNonNull(findingId);
        String query=prefix==null?"":prefix;
        if(limit<1||limit>50||query.codePointCount(0,query.length())>100) throw new IllegalArgumentException("Invalid candidate query");
        for(int i=0;i<query.length();i++) {
            char character=query.charAt(i);
            if(Character.isISOControl(character)) throw new IllegalArgumentException("Invalid candidate query");
            if(Character.isHighSurrogate(character)) {
                if(i+1>=query.length()||!Character.isLowSurrogate(query.charAt(++i))) throw new IllegalArgumentException("Invalid candidate query");
            } else if(Character.isLowSurrogate(character)) throw new IllegalArgumentException("Invalid candidate query");
        }
        String normalized=query.strip();
        var context=sessions.currentTenant();
        return reads.inTenantReadSnapshot(context.tenantId(),snapshot->{
            var finding=snapshot.find(findingId).orElseThrow(FindingNotVisibleException::new);
            access.requireAction(context,"finding.dispatch",finding.organizationId());
            boolean rework=finding.state()==FindingWorkflowState.RECTIFYING&&finding.roundNo()>1;
            if((finding.state()!=FindingWorkflowState.SAFETY_DISPATCH_PENDING&&!rework)
                    || (rework&&snapshot.hasNotice(findingId,finding.roundNo()))) {
                throw new WorkflowTaskStateConflictException();
            }
            var rows=authorization.findAssignableMembers(context.tenantId(),finding.organizationId(),
                    "finding.remediate",normalized,afterMemberId,limit+1);
            boolean more=rows.size()>limit;
            var page=List.copyOf(rows.subList(0,Math.min(limit,rows.size())));
            return new Result(page,finding.version(),finding.roundNo(),finding.state(),more,
                    more?page.getLast().memberId():null);
        });
    }

    public record Result(List<AssignableFindingMember> data, long findingVersion, int roundNo,
                         FindingWorkflowState state, boolean hasMore, UUID nextAfterMemberId) {
        public Result { data=List.copyOf(data); }
    }
}
