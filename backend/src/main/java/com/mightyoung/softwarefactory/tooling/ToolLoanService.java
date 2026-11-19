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
            var now=OffsetDateTime.ofInstant(e.decision.observedAt(),java.time.ZoneOffset.UTC);
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
            if(effectivePermissions.readableOrganizations(identity,"tool_loan.issue").contains(organization)) return true;
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
                  and valid_from<=current_timestamp and (valid_to is null or valid_to>current_timestamp)
                """,context.tenantId(),scope,context.memberId())==null)
            throw new org.springframework.security.access.AccessDeniedException("Current custodian required");
        return loan;
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
        var row=e.decision.action().action().equals("issue")? keeperLoan(e,id,false):owned(e,id,false);
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
        if(e.decision.context().memberId().equals(row.get("applicant_member_id")) && "PENDING_ISSUE".equals(revision.get("state")))
            result.put("awaitingOtherCustodian",!otherCustodianExists(e,row));
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
