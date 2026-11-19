package com.mightyoung.softwarefactory.tooling;
import com.mightyoung.softwarefactory.authorization.*;
import com.mightyoung.softwarefactory.identity.*;
import com.mightyoung.softwarefactory.ledger.*;
import com.mightyoung.softwarefactory.auditops.LedgerAuditAppend;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import java.time.OffsetDateTime;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.function.Function;
import javax.sql.DataSource;
import org.jooq.*;
import org.jooq.impl.DSL;
import org.jooq.conf.Settings;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import tools.jackson.databind.ObjectMapper;
@Service
public final class ToolLoanService {
    public record Input(String definitionVersion,UUID organizationId,UUID custodyScopeId,String purpose,List<UUID> toolIds,Long expectedVersion) { }
    private final ToolLoanWorkflow workflow;
    private final EffectivePermissions effectivePermissions;
    private final DataSource source;
    private final ApplicationActionRuntime actions;
    private final SessionSensitiveRequests requests;
    private final LedgerAuditAppend audit;
    private final ObjectMapper json;
    private final TransactionTemplate read,write;
    public ToolLoanService(DataSource source,ApplicationActionRuntime actions,SessionSensitiveRequests requests,LedgerAuditAppend audit,ObjectMapper json,PlatformTransactionManager manager,ToolLoanWorkflow workflow,EffectivePermissions effectivePermissions) {
        this.effectivePermissions=effectivePermissions;
        this.workflow=workflow;
        this.source=source;
        this.actions=actions;
        this.requests=requests;
        this.audit=audit;
        this.json=json;
        read=new TransactionTemplate(manager);
        read.setReadOnly(true);
        read.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        write=new TransactionTemplate(manager);
        write.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        read.setTimeout(10);
        write.setTimeout(10);
    }
    private record Execution(DSLContext db,ApplicationActionAuthorization.Decision decision) { }
    public record IssueInput(String definitionVersion, Long expectedVersion) { }

    public record ReturnInput(String definitionVersion,Long expectedVersion,List<UUID> toolIds) { }
    public record ReceiveItem(UUID toolId,String condition,String reason) { }
    public record ReceiveInput(String definitionVersion,Long expectedVersion,List<ReceiveItem> items) { }

    public Object lossQueue(HttpServletRequest request) {
        return execute(request,"close_lost",false,e -> {
            var context=e.decision.context();
            String organizations="{"+String.join(",",e.decision.organizationIds().stream().map(UUID::toString).toList())+"}";
            var rows=e.db.fetch("""
                    select l.*,array(select i.tool_id from public.tool_loan_item i
                        where i.tenant_id=l.tenant_id and i.loan_id=l.id and i.version=l.version
                        order by i.line_no) as tool_ids
                    from public.tool_loan l
                    join public.tool_custody_scope s on s.tenant_id=l.tenant_id and s.id=l.custody_scope_id and s.active
                    where s.organization_id=any(cast(? as uuid[])) and l.tenant_id=? and l.state='ISSUED'
                      and l.applicant_member_id<>? and exists(select 1 from public.tool_custodian k
                        where k.tenant_id=s.tenant_id and k.custody_scope_id=s.id and k.member_id=?
                          and k.valid_from<=current_timestamp and (k.valid_to is null or k.valid_to>current_timestamp))
                      and exists(select 1 from public.tool_loan_item r
                        where r.tenant_id=l.tenant_id and r.loan_id=l.id and r.issued_at is not null and r.settled_at is null)
                    order by l.created_at,l.id limit 100
                    """,organizations,context.tenantId(),context.memberId(),context.memberId());
            return Map.of("records",rows.map(row -> Map.of(
                    "id",row.get("id"),"version",row.get("version"),"state",row.get("state"),
                    "definitionVersion",row.get("definition_version"),"organizationId",row.get("organization_id"),
                    "custodyScopeId",row.get("custody_scope_id"),"purpose",row.get("purpose"),
                    "toolIds",Arrays.asList(row.get("tool_ids",UUID[].class)),
                    "createdAt",row.get("created_at"),"updatedAt",row.get("updated_at"))));
        });
    }

    public Object receiveQueue(HttpServletRequest request) {
        return execute(request,"receive",false,e -> {
            var context=e.decision.context();
            String organizations="{"+String.join(",",e.decision.organizationIds().stream().map(UUID::toString).toList())+"}";
            var rows=e.db.fetch("""
                    select l.*,array(select i.tool_id from public.tool_loan_item i
                        where i.tenant_id=l.tenant_id and i.loan_id=l.id and i.version=l.version
                        order by i.line_no) as tool_ids
                    from public.tool_loan l
                    join public.tool_custody_scope s on s.tenant_id=l.tenant_id and s.id=l.custody_scope_id and s.active
                    where s.organization_id=any(cast(? as uuid[])) and l.tenant_id=? and l.state='ISSUED'
                      and l.applicant_member_id<>? and exists(select 1 from public.tool_custodian k
                        where k.tenant_id=s.tenant_id and k.custody_scope_id=s.id and k.member_id=?
                          and k.valid_from<=current_timestamp and (k.valid_to is null or k.valid_to>current_timestamp))
                      and exists(select 1 from public.tool_return_request_item r
                        where r.tenant_id=l.tenant_id and r.loan_id=l.id and r.received_at is null and r.superseded_at is null)
                    order by l.created_at,l.id limit 100
                    """,organizations,context.tenantId(),context.memberId(),context.memberId());
            return Map.of("records",rows.map(row -> Map.of(
                    "id",row.get("id"),"version",row.get("version"),"state",row.get("state"),
                    "definitionVersion",row.get("definition_version"),"organizationId",row.get("organization_id"),
                    "custodyScopeId",row.get("custody_scope_id"),"purpose",row.get("purpose"),
                    "toolIds",Arrays.asList(row.get("tool_ids",UUID[].class)),
                    "createdAt",row.get("created_at"),"updatedAt",row.get("updated_at"))));
        });
    }

    public record LossInput(String definitionVersion,Long expectedVersion,UUID toolId,String reason) { }

    public Object lossDetail(HttpServletRequest request,UUID id) {
        return execute(request,"close_lost",false,e -> record(e,id,null));
    }

    static void validateLoss(LossInput input) {
        if(input==null) throw new LedgerInputException("request","required");
        validateReturnHeader(input.definitionVersion(),input.expectedVersion());
        if(input.toolId()==null) throw new LedgerInputException("toolId","required");
        if(input.reason()==null || input.reason().isBlank() || input.reason().length()>4000)
            throw new LedgerInputException("reason","invalid_reason");
    }

    public Object closeLost(HttpServletRequest request,UUID id,UUID key,LossInput input) {
        validateLoss(input);
        return execute(request,"close_lost",true,e -> {
            var context=e.decision.context();
            lockReturnKey(e,"CLOSE_LOST",key);
            String hash=fingerprint(id,"CLOSE_LOST",input);
            var previous=returnReceipt(e,"CLOSE_LOST",key,hash);
            var loan=keeperLoan(e,id,true);
            requireBoundDefinition(loan.get("definition_version",String.class),input.definitionVersion());
            if(previous!=null) return record(e,id,previous);
            requireIssuedVersion(loan,input.expectedVersion());
            e.db.fetchOne("select id from public.tool where tenant_id=? and id=? for update",context.tenantId(),input.toolId());
            outstanding(e,id,input.toolId(),true);
            boolean hadPending=pendingReturns(e,id);
            UUID command=UUID.randomUUID();
            String now=OffsetDateTime.ofInstant(e.decision.observedAt(),java.time.ZoneOffset.UTC).toString();
            e.db.execute("""
                    insert into public.tool_loan_loss(tenant_id,loan_id,tool_id,command_id,actor_member_id,occurred_at,reason)
                    values(?,?,?,?,?,cast(? as timestamptz),?)
                    """,context.tenantId(),id,input.toolId(),command,context.memberId(),now,input.reason().trim());
            e.db.execute("""
                    update public.tool_return_request_item set superseded_at=cast(? as timestamptz),superseded_by_command_id=?
                    where tenant_id=? and loan_id=? and tool_id=? and received_at is null and superseded_at is null
                    """,now,command,context.tenantId(),id,input.toolId());
            e.db.execute("""
                    update public.tool_loan_item set settled_at=cast(? as timestamptz)
                    where tenant_id=? and loan_id=? and tool_id=? and issued_at is not null and settled_at is null
                    """,now,context.tenantId(),id,input.toolId());
            e.db.fetch("select public.mark_lost_tool(?::uuid,?::uuid,cast(? as timestamptz),?::uuid)",context.tenantId(),input.toolId(),now,context.actorId());
            boolean allSettled=e.db.fetchOne("select 1 from public.tool_loan_item where tenant_id=? and loan_id=? and issued_at is not null and settled_at is null limit 1",context.tenantId(),id)==null;
            workflow.closeLost(context.tenantId(),id,context.memberId(),loan.get("applicant_member_id",UUID.class),hadPending,pendingReturns(e,id),allSettled);
            return appendReturnCommand(e,loan,"CLOSE_LOST",key,hash,now,allSettled,command);
        });
    }

    public Object receiveDetail(HttpServletRequest request,UUID id) {
        return execute(request,"receive",false,e -> record(e,id,null));
    }

    static void validateReturnHeader(String definitionVersion,Long expectedVersion) {
        if(definitionVersion==null || definitionVersion.isBlank() || definitionVersion.length()>64)
            throw new LedgerInputException("definitionVersion","invalid_version");
        if(expectedVersion==null || expectedVersion<1 || expectedVersion==Long.MAX_VALUE)
            throw new LedgerInputException("expectedVersion","invalid_version");
    }

    static void requireBoundDefinition(String bound,String supplied) {
        if(!Objects.equals(bound,supplied)) throw new LedgerException(409,"DEFINITION_CONFLICT");
    }

    static void validateReturn(ReturnInput input) {
        if(input==null) throw new LedgerInputException("request","required");
        validateReturnHeader(input.definitionVersion(),input.expectedVersion());
        LedgerDetailValidation.validate("toolIds",input.toolIds(),100,(index,id)->{});
    }

    static void validateReceive(ReceiveInput input) {
        if(input==null) throw new LedgerInputException("request","required");
        validateReturnHeader(input.definitionVersion(),input.expectedVersion());
        if(input.items()==null) throw new LedgerInputException("items","required");
        var ids=new ArrayList<UUID>();
        for(int i=0;i<input.items().size();i++) {
            var item=input.items().get(i);
            if(item==null) throw new LedgerInputException("items["+i+"]","required");
            ids.add(item.toolId());
            if(!Set.of("NORMAL","DAMAGED").contains(item.condition()==null?"":item.condition()))
                throw new LedgerInputException("items["+i+"].condition","invalid_condition");
            if(item.reason()!=null && item.reason().length()>4000 || "DAMAGED".equals(item.condition()) && (item.reason()==null || item.reason().isBlank()))
                throw new LedgerInputException("items["+i+"].reason","invalid_reason");
        }
        LedgerDetailValidation.validate("items",ids,100,(index,id)->{});
    }

    public Object requestReturn(HttpServletRequest request,UUID id,UUID key,ReturnInput input) {
        validateReturn(input);
        return execute(request,"create",true,e -> {
            var context=e.decision.context();
            lockReturnKey(e,"RETURN",key);
            String hash=fingerprint(id,"RETURN",input);
            var previous=returnReceipt(e,"RETURN",key,hash);
            var loan=owned(e,id,true);
            requireBoundDefinition(loan.get("definition_version",String.class),input.definitionVersion());
            if(previous!=null) return record(e,id,previous);
            requireIssuedVersion(loan,input.expectedVersion());
            boolean firstPending=!pendingReturns(e,id);
            var ordered=new ArrayList<>(input.toolIds());
            ordered.sort(java.util.Comparator.naturalOrder());
            for(UUID tool:ordered) {
                outstanding(e,id,tool,true);
                if(e.db.fetchOne("select 1 from public.tool_return_request_item where tenant_id=? and loan_id=? and tool_id=? and received_at is null and superseded_at is null",context.tenantId(),id,tool)!=null)
                    throw new LedgerException(409,"RETURN_ALREADY_REQUESTED");
            }
            workflow.requestReturn(context.tenantId(),id,context.memberId(),firstPending);
            UUID requestId=UUID.randomUUID();
            var now=OffsetDateTime.ofInstant(e.decision.observedAt(),java.time.ZoneOffset.UTC).toString();
            e.db.execute("insert into public.tool_return_request(tenant_id,id,loan_id,created_by_member_id,created_at) values(?,?,?,?,cast(? as timestamptz))",
                    context.tenantId(),requestId,id,context.memberId(),now);
            for(UUID tool:input.toolIds()) e.db.execute("insert into public.tool_return_request_item(tenant_id,request_id,loan_id,tool_id) values(?,?,?,?)",context.tenantId(),requestId,id,tool);
            return appendReturnCommand(e,loan,"RETURN",key,hash,now,false,requestId);
        });
    }

    public Object receive(HttpServletRequest request,UUID id,UUID key,ReceiveInput input) {
        validateReceive(input);
        return execute(request,"receive",true,e -> {
            var context=e.decision.context();
            lockReturnKey(e,"RECEIVE",key);
            String hash=fingerprint(id,"RECEIVE",input);
            var previous=returnReceipt(e,"RECEIVE",key,hash);
            var loan=keeperLoan(e,id,true);
            requireBoundDefinition(loan.get("definition_version",String.class),input.definitionVersion());
            if(previous!=null) return record(e,id,previous);
            requireIssuedVersion(loan,input.expectedVersion());
            var ordered=new ArrayList<>(input.items());
            ordered.sort(java.util.Comparator.comparing(ReceiveItem::toolId));
            for(var item:ordered) {
                e.db.fetchOne("select id from public.tool where tenant_id=? and id=? for update",context.tenantId(),item.toolId());
                outstanding(e,id,item.toolId(),true);
                if(e.db.fetchOne("select 1 from public.tool_return_request_item where tenant_id=? and loan_id=? and tool_id=? and received_at is null and superseded_at is null for update",context.tenantId(),id,item.toolId())==null)
                    throw new LedgerException(409,"RETURN_NOT_REQUESTED");
            }
            UUID command=UUID.randomUUID();
            var now=OffsetDateTime.ofInstant(e.decision.observedAt(),java.time.ZoneOffset.UTC).toString();
            for(var item:ordered) {
                e.db.execute("""
                        update public.tool_return_request_item set received_at=cast(? as timestamptz),outcome=?,reason=?,received_by_member_id=?,command_id=?
                        where tenant_id=? and loan_id=? and tool_id=? and received_at is null and superseded_at is null
                        """,now,item.condition(),item.reason()==null?null:item.reason().trim(),context.memberId(),command,context.tenantId(),id,item.toolId());
                e.db.execute("update public.tool_loan_item set settled_at=cast(? as timestamptz) where tenant_id=? and loan_id=? and tool_id=? and issued_at is not null and settled_at is null",now,context.tenantId(),id,item.toolId());
                if("DAMAGED".equals(item.condition())) e.db.fetch("select public.mark_received_tool_damaged(?::uuid,?::uuid,cast(? as timestamptz),?::uuid)",context.tenantId(),item.toolId(),now,context.actorId());
            }
            boolean allSettled=e.db.fetchOne("select 1 from public.tool_loan_item where tenant_id=? and loan_id=? and issued_at is not null and settled_at is null limit 1",context.tenantId(),id)==null;
            workflow.receive(context.tenantId(),id,context.memberId(),loan.get("applicant_member_id",UUID.class),pendingReturns(e,id),allSettled);
            return appendReturnCommand(e,loan,"RECEIVE",key,hash,now,allSettled,command);
        });
    }

    private void requireIssuedVersion(org.jooq.Record loan,long expected) {
        if(loan.get("version",Long.class)!=expected) throw new LedgerException(409,"VERSION_CONFLICT");
        if(!"ISSUED".equals(loan.get("state"))) throw new LedgerException(409,"STATE_CONFLICT");
    }

    private org.jooq.Record outstanding(Execution e,UUID loan,UUID tool,boolean lock) {
        var row=e.db.fetchOne("select * from public.tool_loan_item where tenant_id=? and loan_id=? and tool_id=? and issued_at is not null and settled_at is null"+(lock?" for update":""),e.decision.context().tenantId(),loan,tool);
        if(row==null) throw new LedgerException(409,"ITEM_ALREADY_SETTLED");
        return row;
    }

    private boolean pendingReturns(Execution e,UUID id) {
        return e.db.fetchOne("select 1 from public.tool_return_request_item where tenant_id=? and loan_id=? and received_at is null and superseded_at is null limit 1",e.decision.context().tenantId(),id)!=null;
    }

    private void lockReturnKey(Execution e,String verb,UUID key) {
        var context=e.decision.context();
        e.db.fetch("select pg_advisory_xact_lock(hashtextextended(?,0))",context.tenantId()+":"+context.actorId()+":"+verb+":"+key);
    }

    private Long returnReceipt(Execution e,String verb,UUID key,String hash) {
        var context=e.decision.context();
        var row=e.db.fetchOne("select request_hash,result_version from public.tool_loan_command_receipt where tenant_id=? and actor_id=? and action=? and idempotency_key=?",context.tenantId(),context.actorId(),verb,key);
        if(row==null) return null;
        if(!hash.equals(row.get(0,String.class))) throw new LedgerException(409,"IDEMPOTENCY_CONFLICT");
        return row.get(1,Long.class);
    }

    private Object appendReturnCommand(Execution e,org.jooq.Record loan,String verb,UUID key,String hash,String now,boolean settled) {
        return appendReturnCommand(e,loan,verb,key,hash,now,settled,UUID.randomUUID());
    }

    private Object appendReturnCommand(Execution e,org.jooq.Record loan,String verb,UUID key,String hash,String now,boolean settled,UUID command) {
        var context=e.decision.context();
        UUID id=loan.get("id",UUID.class);
        long version=loan.get("version",Long.class)+1;
        String state=settled?"SETTLED":"ISSUED";
        e.db.execute("update public.tool_loan set version=?,state=?,updated_at=cast(? as timestamptz),last_command_id=? where tenant_id=? and id=?",version,state,now,command,context.tenantId(),id);
        e.db.execute("""
                insert into public.tool_loan_revision(tenant_id,loan_id,version,state,purpose,command_id,action,occurred_at,actor_id)
                values(?,?,?,?,?,?,?,cast(? as timestamptz),?)
                """,context.tenantId(),id,version,state,loan.get("purpose"),command,verb,now,context.actorId());
        e.db.execute("""
                insert into public.tool_loan_item(tenant_id,loan_id,version,line_no,tool_id)
                select tenant_id,loan_id,?,line_no,tool_id from public.tool_loan_item
                where tenant_id=? and loan_id=? and version=? order by line_no
                """,version,context.tenantId(),id,loan.get("version"));
        UUID event=audit.appendSuccess(e.decision,command,id,UUID.randomUUID(),Set.of("state","toolIds"));
        e.db.execute("""
                insert into public.tool_loan_command_receipt(tenant_id,actor_id,idempotency_key,action,request_hash,loan_id,result_version,command_id,event_id,occurred_at)
                values(?,?,?,?,?,?,?,?,?,cast(? as timestamptz))
                """,context.tenantId(),context.actorId(),key,verb,hash,id,version,command,event,now);
        return record(e,id,version);
    }

    private List<Map<String,Object>> returnItems(Execution e,UUID id,long version) {
        var rows=e.db.fetch("""
                select i.tool_id,t.code,t.name,r.request_id,loss.command_id as loss_command,loss.reason as loss_reason,
                       received.version as received_version,
                       case when received.version is not null then r.outcome end as outcome,
                       case when received.version is not null then r.reason end as reason
                from public.tool_loan_item i join public.tool t on t.tenant_id=i.tenant_id and t.id=i.tool_id
                left join (
                    public.tool_return_request_item r join public.tool_loan_revision requested
                      on requested.tenant_id=r.tenant_id and requested.loan_id=r.loan_id
                      and requested.command_id=r.request_id and requested.action='RETURN' and requested.version<=?
                ) on r.tenant_id=i.tenant_id and r.loan_id=i.loan_id and r.tool_id=i.tool_id
                left join public.tool_loan_revision received on received.tenant_id=r.tenant_id
                    and received.loan_id=r.loan_id and received.command_id=r.command_id
                    and received.action='RECEIVE' and received.version<=?
                left join (public.tool_loan_loss loss join public.tool_loan_revision lost
                    on lost.tenant_id=loss.tenant_id and lost.loan_id=loss.loan_id and lost.command_id=loss.command_id
                    and lost.action='CLOSE_LOST' and lost.version<=?)
                    on loss.tenant_id=i.tenant_id and loss.loan_id=i.loan_id and loss.tool_id=i.tool_id
                where i.tenant_id=? and i.loan_id=? and i.issued_at is not null and i.version<=?
                order by i.line_no
                """,version,version,version,e.decision.context().tenantId(),id,version);
        return rows.map(row -> {
            Map<String,Object> item=new LinkedHashMap<>();
            item.put("toolId",row.get("tool_id"));
            item.put("label",row.get("code")+" · "+row.get("name"));
            item.put("state",row.get("loss_command")!=null?"LOST":row.get("received_version")!=null?"RECEIVED":row.get("request_id")!=null?"REQUESTED":"OUTSTANDING");
            if(row.get("request_id")!=null) item.put("requestId",row.get("request_id"));
            if(row.get("loss_command")==null && row.get("outcome")!=null) item.put("condition",row.get("outcome"));
            if(row.get("loss_reason")!=null) item.put("reason",row.get("loss_reason"));
            else if(row.get("reason")!=null) item.put("reason",row.get("reason"));
            return item;
        });
    }

    public Object issueQueue(HttpServletRequest request) {
        return execute(request,"issue",false,e -> {
            var context=e.decision.context();
            String organizations="{"+String.join(",",e.decision.organizationIds().stream().map(UUID::toString).toList())+"}";
            var rows=e.db.fetch("""
                    select l.*,array(select i.tool_id from public.tool_loan_item i
                        where i.tenant_id=l.tenant_id and i.loan_id=l.id and i.version=l.version
                        order by i.line_no) as tool_ids
                    from public.tool_loan l
                    join public.tool_custody_scope s on s.tenant_id=l.tenant_id and s.id=l.custody_scope_id and s.active
                    where s.organization_id=any(cast(? as uuid[])) and l.tenant_id=?
                      and l.state='PENDING_ISSUE' and l.applicant_member_id<>?
                      and exists(select 1 from public.tool_custodian k
                        where k.tenant_id=s.tenant_id and k.custody_scope_id=s.id and k.member_id=?
                          and k.valid_from<=current_timestamp and (k.valid_to is null or k.valid_to>current_timestamp))
                    order by l.created_at,l.id limit 100
                    """,organizations,context.tenantId(),context.memberId(),context.memberId());
            return Map.of("records",rows.map(row -> Map.of(
                    "id",row.get("id"),"version",row.get("version"),"state",row.get("state"),
                    "definitionVersion",row.get("definition_version"),"organizationId",row.get("organization_id"),
                    "custodyScopeId",row.get("custody_scope_id"),"purpose",row.get("purpose"),
                    "toolIds",Arrays.asList(row.get("tool_ids",UUID[].class)),
                    "createdAt",row.get("created_at"),"updatedAt",row.get("updated_at"))));
        });
    }

    public Object issueDetail(HttpServletRequest request,UUID id) {
        return execute(request,"issue",false,e -> {
            var loan=keeperLoan(e,id,false);
            return record(e,id,null);
        });
    }

    static void validateIssue(IssueInput input) {
        if(input==null || input.definitionVersion()==null) throw new LedgerInputException("definitionVersion","required");
        if(!"1".equals(input.definitionVersion())) throw new LedgerException(409,"DEFINITION_CONFLICT");
        if(input.expectedVersion()==null || input.expectedVersion()<1 || input.expectedVersion()==Long.MAX_VALUE)
            throw new LedgerInputException("expectedVersion","invalid_version");
    }

    public Object issue(HttpServletRequest request,UUID id,UUID key,IssueInput input) {
        validateIssue(input);
        return execute(request,"issue",true,e -> {
            var context=e.decision.context();
            e.db.fetch("select pg_advisory_xact_lock(hashtextextended(?,0))",
                    context.tenantId()+":"+context.actorId()+":ISSUE:"+key);
            String hash=fingerprint(id,"ISSUE",new Input(input.definitionVersion(),null,null,null,null,input.expectedVersion()));
            var previous=e.db.fetchOne("""
                    select request_hash,result_version from public.tool_loan_command_receipt
                    where tenant_id=? and actor_id=? and action='ISSUE' and idempotency_key=?
                    """,context.tenantId(),context.actorId(),key);
            var loan=keeperLoan(e,id,true);
            if(previous!=null) {
                if(!hash.equals(previous.get(0,String.class))) throw new LedgerException(409,"IDEMPOTENCY_CONFLICT");
                return record(e,id,previous.get(1,Long.class));
            }
            if(!Objects.equals(loan.get("version",Long.class),input.expectedVersion())) throw new LedgerException(409,"VERSION_CONFLICT");
            if(!"PENDING_ISSUE".equals(loan.get("state"))) throw new LedgerException(409,"STATE_CONFLICT");
            List<UUID> ordered=new ArrayList<>(items(e,id,input.expectedVersion()));
            if(ordered.isEmpty()) throw new LedgerException(409,"STATE_CONFLICT");
            ordered.sort(java.util.Comparator.naturalOrder());
            for(UUID tool:ordered) {
                var current=e.db.fetchOne("select custody_scope_id,item_state from public.tool where tenant_id=? and id=? for update",context.tenantId(),tool);
                if(current==null || !loan.get("custody_scope_id").equals(current.get("custody_scope_id")) || !"NORMAL".equals(current.get("item_state")))
                    throw new LedgerException(409,"TOOL_UNAVAILABLE");
                if(e.db.fetchOne("select 1 from public.tool_loan_item where tenant_id=? and tool_id=? and issued_at is not null and settled_at is null",context.tenantId(),tool)!=null)
                    throw new LedgerException(409,"TOOL_UNAVAILABLE");
            }
            workflow.issue(context.tenantId(),id,context.memberId(),loan.get("applicant_member_id",UUID.class));
            long version=input.expectedVersion()+1;
            UUID command=UUID.randomUUID();
            var now=OffsetDateTime.ofInstant(e.decision.observedAt(),java.time.ZoneOffset.UTC).toString();
            e.db.execute("update public.tool_loan set version=?,state='ISSUED',updated_at=cast(? as timestamptz),last_command_id=? where tenant_id=? and id=?",version,now,command,context.tenantId(),id);
            e.db.execute("""
                    insert into public.tool_loan_revision(tenant_id,loan_id,version,state,purpose,command_id,action,occurred_at,actor_id)
                    values(?,?,?,'ISSUED',?,?,'ISSUE',cast(? as timestamptz),?)
                    """,context.tenantId(),id,version,loan.get("purpose"),command,now,context.actorId());
            e.db.execute("""
                    insert into public.tool_loan_item(tenant_id,loan_id,version,line_no,tool_id,issued_at)
                    select tenant_id,loan_id,?,line_no,tool_id,cast(? as timestamptz) from public.tool_loan_item
                    where tenant_id=? and loan_id=? and version=? order by line_no
                    """,version,now,context.tenantId(),id,input.expectedVersion());
            UUID event=audit.appendSuccess(e.decision,command,id,UUID.randomUUID(),Set.of("state","toolIds"));
            e.db.execute("""
                    insert into public.tool_loan_command_receipt(tenant_id,actor_id,idempotency_key,action,request_hash,
                        loan_id,result_version,command_id,event_id,occurred_at)
                    values(?,?,?,'ISSUE',?,?,?,?,?,cast(? as timestamptz))
                    """,context.tenantId(),context.actorId(),key,hash,id,version,command,event,now);
            return record(e,id,version);
        });
    }

    private boolean otherCustodianExists(Execution e,org.jooq.Record loan) {
        var context=e.decision.context();
        var scope=e.db.fetchOne("select organization_id from public.tool_custody_scope where tenant_id=? and id=? and active",context.tenantId(),loan.get("custody_scope_id"));
        if(scope==null) return false;
        UUID organization=scope.get(0,UUID.class);
        var candidates=e.db.fetch("""
                select p.id as actor_id,a.id as account_id,m.id as member_id,
                       t.auth_epoch as tenant_epoch,m.auth_epoch as member_epoch,a.credential_version
                from public.tool_custodian k
                join public.tenant_member m on m.tenant_id=k.tenant_id and m.id=k.member_id and m.status='ACTIVE'
                join public.tenant t on t.id=m.tenant_id and t.status='ACTIVE'
                join public.account a on a.id=m.account_id and a.status='ACTIVE'
                join public.principal p on p.account_id=a.id and p.kind='HUMAN' and p.status='ACTIVE'
                where k.tenant_id=? and k.custody_scope_id=? and k.member_id<>?
                  and k.valid_from<=current_timestamp and (k.valid_to is null or k.valid_to>current_timestamp)
                """,context.tenantId(),loan.get("custody_scope_id"),loan.get("applicant_member_id"));
        for(var candidate:candidates) {
            var identity=new CurrentTenantContext(candidate.get("actor_id",UUID.class),candidate.get("account_id",UUID.class),
                    context.tenantId(),candidate.get("member_id",UUID.class),candidate.get("tenant_epoch",Long.class),
                    candidate.get("member_epoch",Long.class),candidate.get("credential_version",Long.class));
            if(effectivePermissions.readableOrganizations(identity,"ISSUED".equals(loan.get("state"))?"tool_loan.receive":"tool_loan.issue").contains(organization)) return true;
        }
        return false;
    }

    private org.jooq.Record keeperLoan(Execution e,UUID id,boolean lock) {
        var context=e.decision.context();
        var loan=e.db.fetchOne("select * from public.tool_loan where tenant_id=? and id=?"+(lock?" for update":""),context.tenantId(),id);
        if(loan==null) throw new LedgerException(404,"NOT_FOUND");
        if(context.memberId().equals(loan.get("applicant_member_id"))) {
            if(!otherCustodianExists(e,loan)) throw new LedgerException(403,"NO_OTHER_CUSTODIAN");
            throw new org.springframework.security.access.AccessDeniedException("Self issuance forbidden");
        }
        UUID scope=loan.get("custody_scope_id",UUID.class);
        var scopeRow=e.db.fetchOne("select organization_id from public.tool_custody_scope where tenant_id=? and id=? and active",context.tenantId(),scope);
        UUID organization=scopeRow==null?null:scopeRow.get(0,UUID.class);
        if(organization==null || !e.decision.organizationIds().contains(organization) || e.db.fetchOne("""
                select 1 from public.tool_custodian where tenant_id=? and custody_scope_id=? and member_id=?
                  and valid_from<=current_timestamp and (valid_to is null or valid_to>current_timestamp) limit 1
                """,context.tenantId(),scope,context.memberId())==null)
            throw new org.springframework.security.access.AccessDeniedException("Current custodian required");
        return loan;
    }

    public Object scopedList(HttpServletRequest request,String visibility) {
        return execute(request,readResource(visibility),"read",false,e -> {
            var rows=e.db.select(org.jooq.impl.DSL.table(org.jooq.impl.DSL.name("root")).asterisk()).select(org.jooq.impl.DSL.field("""
                    array(select i.tool_id from public.tool_loan_item i where i.tenant_id=root.tenant_id
                        and i.loan_id=root.id and i.version=root.version order by i.line_no)
                    """).as("tool_ids")).from(org.jooq.impl.DSL.table("public.tool_loan").as("root"))
                    .where(scopedVisibility(e,visibility))
                    .orderBy(org.jooq.impl.DSL.field("root.created_at").desc(),org.jooq.impl.DSL.field("root.id"))
                    .limit(100).fetch();
            return Map.of("records",rows.map(row -> Map.of(
                    "id",row.get("id"),"version",row.get("version"),"state",row.get("state"),
                    "definitionVersion",row.get("definition_version"),"organizationId",row.get("organization_id"),
                    "custodyScopeId",row.get("custody_scope_id"),"purpose",row.get("purpose"),
                    "toolIds",Arrays.asList(row.get("tool_ids",UUID[].class)),
                    "createdAt",row.get("created_at"),"updatedAt",row.get("updated_at"))));
        });
    }

    public Object scopedDetail(HttpServletRequest request,String visibility,UUID id) {
        return execute(request,readResource(visibility),"read",false,e -> record(e,id,null));
    }

    private static String readResource(String visibility) {
        return switch(visibility) {
            case "department" -> "tool_loan_department";
            case "custody" -> "tool_loan_custody";
            default -> throw new LedgerInputException("visibility","invalid_scope");
        };
    }

    static org.jooq.Condition scopedVisibility(Execution e,String visibility) {
        var context=e.decision.context();
        String organizations="{"+String.join(",",e.decision.organizationIds().stream().map(UUID::toString).toList())+"}";
        var tenant=org.jooq.impl.DSL.condition("root.tenant_id=?::uuid",context.tenantId());
        if("department".equals(visibility)) return tenant.and(org.jooq.impl.DSL.condition("root.organization_id=any(?::uuid[])",organizations));
        if(!"custody".equals(visibility)) throw new LedgerInputException("visibility","invalid_scope");
        return tenant.and(org.jooq.impl.DSL.condition("""
                exists(select 1 from public.tool_custody_scope s join public.tool_custodian k
                    on k.tenant_id=s.tenant_id and k.custody_scope_id=s.id
                    where s.tenant_id=root.tenant_id and s.id=root.custody_scope_id and s.active
                      and s.organization_id=any(?::uuid[]) and k.member_id=?::uuid
                      and k.valid_from<=?::timestamptz and (k.valid_to is null or k.valid_to>?::timestamptz))
                """,organizations,context.memberId(),e.decision.observedAt(),e.decision.observedAt()));
    }

    private org.jooq.Record scopedLoan(Execution e,UUID id) {
        String visibility=e.decision.action().resourceType().equals("tool_loan_department")?"department":"custody";
        var row=e.db.selectFrom(org.jooq.impl.DSL.table("public.tool_loan").as("root"))
                .where(scopedVisibility(e,visibility)).and(org.jooq.impl.DSL.condition("root.id=?::uuid",id)).fetchOne();
        if(row==null) throw new LedgerException(404,"NOT_FOUND");
        return row;
    }

    public Object list(HttpServletRequest request) {
        return execute(request,"read",false,e -> {
            var context = e.decision.context();
            var rows = e.db.fetch("""
                    select root.*, array(select i.tool_id from public.tool_loan_item i
                        where i.tenant_id=root.tenant_id and i.loan_id=root.id
                          and i.version=root.version order by i.line_no) as tool_ids
                    from public.tool_loan root
                    where root.tenant_id=? and root.applicant_member_id=?
                    order by root.created_at desc,root.id limit 100
                    """, context.tenantId(), context.memberId());
            return Map.of("records", rows.map(row -> Map.of(
            "id", row.get("id"), "version", row.get("version"), "state", row.get("state"),
            "definitionVersion", row.get("definition_version"), "organizationId", row.get("organization_id"),
            "custodyScopeId", row.get("custody_scope_id"), "purpose", row.get("purpose"),
            "toolIds", Arrays.asList(row.get("tool_ids",UUID[].class)),
            "createdAt", row.get("created_at"), "updatedAt", row.get("updated_at"))));
        });
    }
    public Object detail(HttpServletRequest request,UUID id) { return execute(request,"read",false,e->record(e,id,null));
    }
    public Object candidates(HttpServletRequest request,UUID scope,UUID loanId) {
        return execute(request,loanId==null?"create":"update",false,e->{
            if(loanId!=null) owned(e,loanId,false);
            var c=e.decision.context();
            var scopes=e.db.fetch("select id,code,name,organization_id from public.tool_custody_scope where tenant_id=? and active order by code,id",c.tenantId()).stream()
            .filter(r->e.decision.organizationIds().contains(r.get("organization_id",UUID.class)))
            .map(r->Map.of("id",r.get("id"),"label",r.get("code")+" · "+r.get("name"),"organizationId",r.get("organization_id"))).toList();
            var departments=e.db.fetch("select o.id,o.code,o.name from public.member_org mo join public.org_unit o on o.tenant_id=mo.tenant_id and o.id=mo.org_id and o.active where mo.tenant_id=? and mo.member_id=? and mo.valid_from<=current_timestamp and (mo.valid_to is null or mo.valid_to>current_timestamp) order by o.code,o.id",c.tenantId(),c.memberId()).map(r->Map.of("id",r.get("id"),"label",r.get("code")+" · "+r.get("name")));
            boolean visible=scopes.stream().anyMatch(s->s.get("id").equals(scope));
            var tools=visible?e.db.fetch("select id,code,name from public.tool where tenant_id=? and custody_scope_id=? and item_state='NORMAL' order by code,id limit 200",c.tenantId(),scope).map(r->Map.of("id",r.get("id"),"label",r.get("code")+" · "+r.get("name"))):List.of();
            return Map.of("scopes",scopes,"tools",tools,"departments",departments);
        });
    }
    public Object command(HttpServletRequest request,UUID id,UUID key,String verb,Input input) {
        validate(input,verb);
        return execute(request,verb.equals("CREATE")?"create":"update",true,e->{
            var c=e.decision.context();
            e.db.fetch("select pg_advisory_xact_lock(hashtextextended(?,0))",c.tenantId()+":"+c.actorId()+":"+verb+":"+key);
            String hash=fingerprint(id,verb,input);
            var receipt=e.db.fetchOne("select request_hash,loan_id,result_version from public.tool_loan_command_receipt where tenant_id=? and actor_id=? and action=? and idempotency_key=?",c.tenantId(),c.actorId(),verb,key);
            if(receipt!=null) {
                if(!hash.equals(receipt.get(0,String.class)))throw new LedgerException(409,"IDEMPOTENCY_CONFLICT");
                return record(e,receipt.get(1,UUID.class),receipt.get(2,Long.class));
            }
            org.jooq.Record current=null;
            if(id!=null) {
                current=owned(e,id,true);
                if(!Objects.equals(input.expectedVersion(),current.get("version",Long.class)))throw new LedgerException(409,"VERSION_CONFLICT");
                if(!"PENDING_ISSUE".equals(current.get("state")))throw new LedgerException(409,"STATE_CONFLICT");
                if(!input.definitionVersion().equals(current.get("definition_version")))throw new LedgerException(409,"DEFINITION_CONFLICT");
            }
            boolean cancel=verb.equals("WITHDRAW");
            UUID organization=cancel?current.get("organization_id",UUID.class):input.organizationId();
            UUID custody=cancel?current.get("custody_scope_id",UUID.class):input.custodyScopeId();
            if(current!=null&&(!custody.equals(current.get("custody_scope_id"))||!organization.equals(current.get("organization_id"))))throw new LedgerInputException("custodyScopeId","immutable");
            if(!cancel) validateReferences(e,input,current==null);
            String purpose=cancel?current.get("purpose",String.class):input.purpose().trim();
            List<UUID> items=cancel?items(e,id,current.get("version",Long.class)):input.toolIds();
            UUID loan=id==null?UUID.randomUUID():id,command=UUID.randomUUID();
            long version=current==null?1:current.get("version",Long.class)+1;
            String state=cancel?"WITHDRAWN":"PENDING_ISSUE";
            var at=OffsetDateTime.ofInstant(e.decision.observedAt(),java.time.ZoneOffset.UTC);
            if(current==null)e.db.execute("insert into public.tool_loan(tenant_id,id,version,state,applicant_member_id,organization_id,custody_scope_id,definition_version,purpose,created_at,updated_at,last_command_id) values(?,?,1,?,?,?,?,?,?,cast(? as timestamptz),cast(? as timestamptz),?)",c.tenantId(),loan,state,c.memberId(),organization,custody,input.definitionVersion(),purpose,at,at,command);
            else e.db.execute("update public.tool_loan set version=?,state=?,purpose=?,updated_at=cast(? as timestamptz),last_command_id=? where tenant_id=? and id=?",version,state,purpose,at,command,c.tenantId(),loan);
            e.db.execute("insert into public.tool_loan_revision(tenant_id,loan_id,version,state,purpose,command_id,action,occurred_at,actor_id) values(?,?,?,?,?,?,?,cast(? as timestamptz),?)",c.tenantId(),loan,version,state,purpose,command,verb,at,c.actorId());
            for(int i=0;i<items.size();i++)e.db.execute("insert into public.tool_loan_item(tenant_id,loan_id,version,line_no,tool_id) values(?,?,?,?,?)",c.tenantId(),loan,version,i+1,items.get(i));
            UUID event=audit.appendSuccess(e.decision,command,loan,UUID.randomUUID(),Set.of("purpose","toolIds","state"));
            e.db.execute("insert into public.tool_loan_command_receipt(tenant_id,actor_id,idempotency_key,action,request_hash,loan_id,result_version,command_id,event_id,occurred_at) values(?,?,?,?,?,?,?,?,?,cast(? as timestamptz))",c.tenantId(),c.actorId(),key,verb,hash,loan,version,command,event,at);
            return record(e,loan,version);
        });
    }
    static void validate(Input input,String verb) {
        if(input==null||input.definitionVersion()==null)throw new LedgerInputException("definitionVersion","required");
        if(!"1".equals(input.definitionVersion()))throw new LedgerException(409,"DEFINITION_CONFLICT");
        if(!verb.equals("CREATE")&&(input.expectedVersion()==null||input.expectedVersion()<1||input.expectedVersion()==Long.MAX_VALUE))throw new LedgerInputException("expectedVersion","invalid_version");
        if(verb.equals("WITHDRAW")) {
            if(input.organizationId()!=null||input.custodyScopeId()!=null||input.purpose()!=null||input.toolIds()!=null)throw new LedgerInputException("request","unknown_field");
            return;
        }
        if(verb.equals("CREATE")&&input.expectedVersion()!=null)throw new LedgerInputException("expectedVersion","not_allowed");
        if(input.organizationId()==null||input.custodyScopeId()==null)throw new LedgerInputException("custodyScopeId","required");
        if(input.purpose()==null||input.purpose().isBlank()||input.purpose().length()>4000)throw new LedgerInputException("purpose","invalid_text");
        LedgerDetailValidation.validate("toolIds",input.toolIds(),100,(index,id)->{});
    }
    private void validateReferences(Execution e,Input input,boolean creation) {
        var c=e.decision.context();
        if(creation&&e.db.fetchOne("select 1 from public.member_org mo join public.org_unit o on o.tenant_id=mo.tenant_id and o.id=mo.org_id and o.active where mo.tenant_id=? and mo.member_id=? and mo.org_id=? and mo.valid_from<=current_timestamp and (mo.valid_to is null or mo.valid_to>current_timestamp) limit 1",c.tenantId(),c.memberId(),input.organizationId())==null)throw new LedgerInputException("organizationId","reference_not_visible");
        var custody=e.db.fetchOne("select organization_id from public.tool_custody_scope where tenant_id=? and id=? and active",c.tenantId(),input.custodyScopeId());
        if(custody==null||!e.decision.organizationIds().contains(custody.get(0,UUID.class)))throw new LedgerInputException("custodyScopeId","reference_not_visible");
        LedgerDetailValidation.validate("toolIds",input.toolIds(),100,(i,tool)->{if(e.db.fetchOne("select 1 from public.tool where tenant_id=? and id=? and custody_scope_id=? and organization_id=? and item_state='NORMAL'",c.tenantId(),tool,input.custodyScopeId(),custody.get(0,UUID.class))==null)throw new LedgerInputException("toolIds["+i+"]","reference_not_visible");});
    }
    private org.jooq.Record owned(Execution e,UUID id,boolean lock) {
        var row=e.db.fetchOne("select * from public.tool_loan where tenant_id=? and id=? and applicant_member_id=?"+(lock?" for update":""),e.decision.context().tenantId(),id,e.decision.context().memberId());
        if(row==null)throw new LedgerException(404,"NOT_FOUND");
        return row;
    }
    private List<UUID> items(Execution e,UUID id,long version) {return e.db.fetch("select tool_id from public.tool_loan_item where tenant_id=? and loan_id=? and version=? order by line_no",e.decision.context().tenantId(),id,version).getValues(0,UUID.class);
    }
    private Object record(Execution e,UUID id,Long historical) {
        var row=!e.decision.action().resourceType().equals("tool_loan")?scopedLoan(e,id)
                :Set.of("issue","receive","close_lost").contains(e.decision.action().action())?keeperLoan(e,id,false):owned(e,id,false);
        long version=historical==null?row.get("version",Long.class):historical;
        var revision=e.db.fetchOne("select state,purpose,occurred_at from public.tool_loan_revision where tenant_id=? and loan_id=? and version=?",e.decision.context().tenantId(),id,version);
        var result = new LinkedHashMap<String,Object>();
        result.put("id",id);
        result.put("version",version);
        result.put("state",revision.get("state"));
        result.put("definitionVersion",row.get("definition_version"));
        result.put("organizationId",row.get("organization_id"));
        result.put("custodyScopeId",row.get("custody_scope_id"));
        result.put("purpose",revision.get("purpose"));
        result.put("toolIds",items(e,id,version));
        if(e.decision.context().memberId().equals(row.get("applicant_member_id")) && Set.of("PENDING_ISSUE","ISSUED").contains(revision.get("state")))
            result.put("awaitingOtherCustodian",!otherCustodianExists(e,row));
        result.put("returnItems",returnItems(e,id,version));
        result.put("createdAt",row.get("created_at"));
        result.put("updatedAt",revision.get("occurred_at"));
        result.put("tools",e.db.fetch("""
                select t.id,t.code,t.name from public.tool_loan_item i
                join public.tool t on t.tenant_id=i.tenant_id and t.id=i.tool_id
                where i.tenant_id=? and i.loan_id=? and i.version=? order by i.line_no
                """,e.decision.context().tenantId(),id,version).map(item->Map.of(
        "id",item.get("id"),"label",item.get("code")+" · "+item.get("name"))));
        return Collections.unmodifiableMap(result);
    }
    private String fingerprint(UUID id,String verb,Object input) {try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsString(Arrays.asList(verb,id,input)).getBytes(StandardCharsets.UTF_8)));
        }catch(Exception failure){throw new LedgerException(503,"DEPENDENCY_UNAVAILABLE");
        }}
    private <T>T execute(HttpServletRequest request,String operation,boolean mutation,Function<Execution,T> work) {
        return execute(request,"tool_loan",operation,mutation,work);
    }
    private <T>T execute(HttpServletRequest request,String resource,String operation,boolean mutation,Function<Execution,T> work) {
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new LedgerException(503,"DEPENDENCY_UNAVAILABLE");
        var captured=requests.capture(request);
        try{return (mutation?write:read).execute(status->{
                var holder=(ConnectionHolder)TransactionSynchronizationManager.getResource(source);
                if(holder==null)throw new LedgerException(503,"DEPENDENCY_UNAVAILABLE");
                var connection=holder.getConnection();var db=DSL.using(connection,SQLDialect.POSTGRES,new Settings().withExecuteLogging(false).withFetchWarnings(false));
                db.fetch("select set_config('app.tenant_id',?,true)",captured.context().tenantId().toString());
                var action=new ApplicationEntityAction("tooling",resource,operation);
                Function<ApplicationActionAuthorization.Decision,T> call=decision->{actions.authorization().requireSqlConnection(decision,source,connection);T result=work.apply(new Execution(db,decision));actions.authorization().requireSqlConnection(decision,source,connection);return result;};
                if(!mutation)return actions.callers().withRead(captured,boundary->call.apply(actions.authorization().requireRead(captured,boundary,action)));
                actions.guard().forBusinessCommand(captured.context().tenantId());
                var scope=new HumanSessionAuthorityTransaction.WriteScope(captured,HumanSessionAuthorityTransaction.Guard.FINDING_COMMAND,List.of(captured.context().tenantId()));
                return actions.callers().withGuarded(scope,boundary->call.apply(actions.authorization().requireCommand(scope,boundary,action)));
            });
        }finally{captured.close();
        }
    }
}
