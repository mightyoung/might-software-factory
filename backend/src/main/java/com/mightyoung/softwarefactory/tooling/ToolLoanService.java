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
    private final DataSource source;
    private final ApplicationActionRuntime actions;
    private final SessionSensitiveRequests requests;
    private final LedgerAuditAppend audit;
    private final ObjectMapper json;
    private final TransactionTemplate read,write;
    public ToolLoanService(DataSource source,ApplicationActionRuntime actions,SessionSensitiveRequests requests,LedgerAuditAppend audit,ObjectMapper json,PlatformTransactionManager manager) {
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
        if(creation&&e.db.fetchOne("select 1 from public.member_org mo join public.org_unit o on o.tenant_id=mo.tenant_id and o.id=mo.org_id and o.active where mo.tenant_id=? and mo.member_id=? and mo.org_id=? and mo.valid_from<=current_timestamp and (mo.valid_to is null or mo.valid_to>current_timestamp)",c.tenantId(),c.memberId(),input.organizationId())==null)throw new LedgerInputException("organizationId","reference_not_visible");
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
        var row=owned(e,id,false);
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
    private String fingerprint(UUID id,String verb,Input input) {try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsString(Arrays.asList(verb,id,input)).getBytes(StandardCharsets.UTF_8)));
        }catch(Exception failure){throw new LedgerException(503,"DEPENDENCY_UNAVAILABLE");
        }}
    private <T>T execute(HttpServletRequest request,String operation,boolean mutation,Function<Execution,T> work) {
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new LedgerException(503,"DEPENDENCY_UNAVAILABLE");
        var captured=requests.capture(request);
        try{return (mutation?write:read).execute(status->{
                var holder=(ConnectionHolder)TransactionSynchronizationManager.getResource(source);
                if(holder==null)throw new LedgerException(503,"DEPENDENCY_UNAVAILABLE");
                var connection=holder.getConnection();var db=DSL.using(connection,SQLDialect.POSTGRES,new Settings().withExecuteLogging(false).withFetchWarnings(false));
                db.fetch("select set_config('app.tenant_id',?,true)",captured.context().tenantId().toString());
                var action=new ApplicationEntityAction("tooling","tool_loan",operation);
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
