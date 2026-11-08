package com.mightyoung.softwarefactory.dualprevention.finding.workflow;

import com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.Metadata;
import com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.ValidationException;
import com.mightyoung.softwarefactory.dualprevention.*;
import com.mightyoung.softwarefactory.dualprevention.finding.FindingReadScope;
import com.mightyoung.softwarefactory.identity.*;
import com.mightyoung.softwarefactory.workflow.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.*;
import static com.mightyoung.softwarefactory.dualprevention.FindingReviewHistoryApiModels.*;
import static com.mightyoung.softwarefactory.dualprevention.finding.workflow.FindingReviewHistoryRepository.*;

@Service
public final class FindingReviewHistoryService {
    // Per-process cursor authentication; restarting safely invalidates old continuation tokens.
    private static final byte[] CURSOR_KEY = new byte[32];
    static { new java.security.SecureRandom().nextBytes(CURSOR_KEY); }
    private final SessionContextResolver sessions;
    private final FindingReadAuthorizer authorizer;
    private final FindingReviewHistoryRepository repository;
    private final FindingWorkflowAccessPolicy access;
    private final FindingWorkflowAssignmentResolver assignments;
    private final WorkflowFacade workflow;
    public FindingReviewHistoryService(SessionContextResolver sessions, FindingReadAuthorizer authorizer,
            FindingReviewHistoryRepository repository, FindingWorkflowAccessPolicy access,
            FindingWorkflowAssignmentResolver assignments, WorkflowFacade workflow) {
        this.sessions=sessions;this.authorizer=authorizer;this.repository=repository;this.access=access;this.assignments=assignments;this.workflow=workflow;
    }
    public Response history(UUID id,int pageSize,String cursor) {
        var context=sessions.currentTenant();
        return repository.inTenantReadSnapshot(context.tenantId(), snapshot -> {
            FindingReadScope scope=authorizer.requireReadScope(context);
            Current current=snapshot.find(scope,id).orElseThrow(FindingNotVisibleException::new);
            if(!Set.of(20,50,100).contains(pageSize)) throw invalid("pageSize");
            Cursor after=decode(cursor,scope,id,pageSize);
            var binding=workflow.readBoundDefinition(context.tenantId(),FindingWorkflowCommandService.BUSINESS_TYPE,id);
            if(binding.isEmpty() && !"DISCOVERED".equals(current.state())) throw unavailable("Initialized finding has no workflow binding");
            String key=binding.map(WorkflowBindingReadSnapshot::processDefinitionKey).orElse(null);
            String stage=switch(current.state()) { case "PERSON_CONFIRM_PENDING" -> "PERSON"; case "SAFETY_CONFIRM_PENDING" -> "SAFETY"; default -> null; };
            Hint hint=actions(context,current,snapshot,key,stage);
            var rows=snapshot.entries(scope,id,after,pageSize+1);
            boolean more=rows.size()>pageSize;
            var page=rows.subList(0,Math.min(rows.size(),pageSize));
            var entries=page.stream().map(e -> new FindingReviewHistoryApiModels.Entry(e.id(),e.origin(),e.reviewCycle(),e.stage(),e.decision(),e.actorMemberId(),new ActorDisplay(null,"UNAVAILABLE"),e.occurredAt(),e.text(),e.recordedGrade(),e.nextReviewCycle())).toList();
            String next=more?encode(page.getLast(),scope,id,pageSize):null;
            return new Response(new Data(entries,current.version(),current.state(),current.reviewCycle(),current.reviewProtocol().name(),key,stage,hint.actions(),hint.reason(),more,next),new Metadata(UUID.randomUUID().toString(),UUID.randomUUID().toString(),"1"));
        });
    }
    private Hint actions(CurrentTenantContext context,Current current,Snapshot snapshot,String key,String stage) {
        boolean cycle=current.reviewProtocol()==FindingReviewProtocol.CYCLE_V1;
        if(key==null) return blocked("WORKFLOW_NOT_INITIALIZED");
        boolean supported=cycle?"findingRemediationV3".equals(key):Set.of("findingRemediationV1","findingRemediationV2").contains(key);
        if(!supported) return blocked("UNSUPPORTED_REVIEW_PROTOCOL");
        boolean supplement="REPORTER_SUPPLEMENT_PENDING".equals(current.state());
        if(stage==null&&!supplement) return blocked("NO_REVIEW_ACTION_AT_CURRENT_STATE");
        if(supplement&&!cycle) return blocked("UNSUPPORTED_REVIEW_PROTOCOL");
        String permission=supplement?"finding.create":"finding.confirm."+stage.toLowerCase(Locale.ROOT);
        String task=supplement?"reporterSupplement":("PERSON".equals(stage)?"personConfirm":"safetyConfirm");
        try {
            access.requireAction(context,permission,current.organizationId());
            var pair=assignments.resolveReviewPair(context.tenantId(),current.organizationId());
            if(pair.personMemberId().equals(pair.safetyMemberId()) || pair.personMemberId().equals(current.reporterMemberId()) || pair.safetyMemberId().equals(current.reporterMemberId())) return blocked("REVIEW_ROUTE_UNAVAILABLE");
            access.requireAssignableMember(context,pair.personMemberId(),current.organizationId(),"finding.confirm.person");
            access.requireAssignableMember(context,pair.safetyMemberId(),current.organizationId(),"finding.confirm.safety");
            UUID expected=supplement?current.reporterMemberId():("PERSON".equals(stage)?pair.personMemberId():pair.safetyMemberId());
            if(!context.memberId().equals(expected)) return blocked("NOT_CURRENT_REVIEW_PARTICIPANT");
            if("SAFETY".equals(stage)) {
                var person=snapshot.approvedPerson(current.id(),current.reviewCycle(),cycle);
                if(person.isEmpty()||person.get().equals(context.memberId())||person.get().equals(current.reporterMemberId())) return blocked("PERSON_APPROVAL_REQUIRED");
            }
            var actual=workflow.readCurrentTask(context.tenantId(),FindingWorkflowCommandService.BUSINESS_TYPE,current.id(),task);
            if(!key.equals(actual.processDefinitionKey())) throw unavailable("Review task definition changed");
            if(!actual.active()||!context.memberId().toString().equals(actual.assignee())) return blocked("CURRENT_TASK_NOT_ASSIGNED");
            if(supplement) return new Hint(List.of("SUPPLEMENT"),null);
            if(cycle) return new Hint(List.of("APPROVE","RETURN_FOR_INFORMATION","NOT_ESTABLISHED"),null);
            return new Hint("findingRemediationV2".equals(key)?List.of("APPROVE","NOT_ESTABLISHED"):List.of("APPROVE"),null);
        } catch(FindingWorkflowConfigurationException failure) { return blocked("REVIEW_ROUTE_UNAVAILABLE"); }
        catch(AccessDeniedException|FindingNotVisibleException failure) { return blocked("ACTION_NOT_AUTHORIZED"); }
        catch(WorkflowTaskStateConflictException failure) { return blocked("CURRENT_TASK_UNAVAILABLE"); }
    }
    private record Hint(List<String> actions,String reason) { }
    private static Hint blocked(String reason) { return new Hint(List.of(),reason); }
    private static WorkflowEngineUnavailableException unavailable(String reason) { return new WorkflowEngineUnavailableException(new IllegalStateException(reason)); }
    private static ValidationException invalid(String field) { return new ValidationException(field,"invalid_history_query","Invalid review history query"); }
    private static String scopeKey(FindingReadScope scope,UUID id,int size) {
        String organizations=scope.organizationIds().stream().map(UUID::toString).sorted().reduce((a,b)->a+","+b).orElse("");
        try {
            String scopeHash=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(organizations.getBytes(StandardCharsets.UTF_8)));
            return "1|"+scope.tenantId()+"|"+id+"|"+size+"|"+scope.memberId()+"|"+scopeHash;
        } catch(java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    static String encode(FindingReviewHistoryRepository.Entry entry,FindingReadScope scope,UUID id,int size) {
        String value=scopeKey(scope,id,size)+"|"+entry.reviewCycle()+"|"+entry.occurredAt()+"|"+entry.origin()+"|"+entry.id();
        String payload=Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
        return payload+"."+Base64.getUrlEncoder().withoutPadding().encodeToString(sign(payload));
    }
    private static byte[] sign(String payload) {
        try {
            var mac=javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(CURSOR_KEY,"HmacSHA256"));
            return mac.doFinal(payload.getBytes(StandardCharsets.US_ASCII));
        } catch(java.security.GeneralSecurityException impossible) { throw new IllegalStateException(impossible); }
    }
    static Cursor decode(String token,FindingReadScope scope,UUID id,int size) {
        if(token==null) return null;
        try {
            if(token.isEmpty()||token.length()>4096||!token.matches("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+")) throw invalid("cursor");
            String[] parts=token.split("\\.",-1);
            if(!java.security.MessageDigest.isEqual(sign(parts[0]),Base64.getUrlDecoder().decode(parts[1]))) throw invalid("cursor");
            byte[] bytes=Base64.getUrlDecoder().decode(parts[0]);
            if(!Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).equals(parts[0])) throw invalid("cursor");
            String raw=new String(bytes,StandardCharsets.UTF_8), prefix=scopeKey(scope,id,size)+"|";
            if(!raw.startsWith(prefix)) throw invalid("cursor");
            String[] fields=raw.substring(prefix.length()).split("\\|",-1);
            if(fields.length!=4) throw invalid("cursor");
            int cycle=Integer.parseInt(fields[0]);
            if(cycle<1||!Integer.toString(cycle).equals(fields[0])) throw invalid("cursor");
            OffsetDateTime time=OffsetDateTime.parse(fields[1]);
            String origin=fields[2],entryId=fields[3];
            boolean valid=switch(origin) {
                case "LEGACY_CONFIRMATION" -> cycle==1&&entryId.matches("legacy-confirmation:"+id+":(PERSON|SAFETY)");
                case "REVIEW_DECISION" -> entryId.matches("review-decision:"+id+":"+cycle+":(PERSON|SAFETY)");
                case "REPORTER_SUPPLEMENT" -> entryId.startsWith("reporter-supplement:")&&UUID.fromString(entryId.substring(20)).toString().equals(entryId.substring(20));
                default -> false;
            };
            if(!valid) throw invalid("cursor");
            return new Cursor(cycle,time,origin,entryId);
        } catch(IllegalArgumentException | java.time.DateTimeException failure) { throw invalid("cursor"); }
    }
}
