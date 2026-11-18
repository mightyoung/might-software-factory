package com.mightyoung.softwarefactory.ledger;

import com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract;
import com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryValidator;
import com.mightyoung.softwarefactory.auditops.LedgerAuditAppend;
import com.mightyoung.softwarefactory.authorization.ApplicationActionRuntime;
import com.mightyoung.softwarefactory.authorization.ApplicationEntityAction;
import com.mightyoung.softwarefactory.identity.CapturedHumanRequest;
import com.mightyoung.softwarefactory.identity.HumanSessionAuthorityTransaction;
import com.mightyoung.softwarefactory.identity.SessionSensitiveRequests;
import jakarta.servlet.http.HttpServletRequest;
import java.sql.Connection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;
import javax.sql.DataSource;
import org.jooq.SQLDialect;
import org.jooq.conf.Settings;
import org.jooq.impl.DSL;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Standard commands retain the current authorization, policy and SQL owner throughout one transaction. */
@Service
public final class LedgerRuntime {
    private final LedgerRegistry registry;
    private final DataSource source;
    private final ApplicationActionRuntime actions;
    private final SessionSensitiveRequests requests;
    private final LedgerAuditAppend audit;
    private final LedgerRepository repository;
    private final TransactionTemplate read;
    private final TransactionTemplate write;
    private final LedgerInputValidator inputs=new LedgerInputValidator();
    private final EntityQueryValidator queries;
    public LedgerRuntime(LedgerRegistry registry,DataSource source,ApplicationActionRuntime actions,SessionSensitiveRequests requests,
                         LedgerAuditAppend audit,PlatformTransactionManager manager,ObjectMapper json,EntityQueryValidator queries) {
        this.registry=registry;this.source=source;this.actions=actions;this.requests=requests;this.audit=audit;this.queries=queries;
        repository=new LedgerRepository(json);
        read=new TransactionTemplate(manager);read.setReadOnly(true);read.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        write=new TransactionTemplate(manager);write.setReadOnly(false);write.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        read.setTimeout(10);write.setTimeout(10);
    }
    public LedgerContracts.DefinitionData definition(HttpServletRequest request,String app,String entity,LedgerDefinition.Use use) {
        if(use==null)throw new LedgerInputException("use","required");
        var binding=registry.require(app,entity);
        return reading(request,(captured,boundary)->{
            var decision=actions.authorization().requireRead(captured,boundary,action(binding,operation(use)));
            var context=execution(decision);
            Set<String> effective=new LinkedHashSet<>();effective.add(decision.action().permissionCode());
            for(String other:List.of("read","create","update")) if(!other.equals(operation(use))) {
                try {var extra=actions.authorization().requireRead(captured,boundary,action(binding,other));execution(extra).requireCurrent();effective.add(extra.action().permissionCode());}
                catch(AccessDeniedException denied) { /* Presentation omits an ungranted action; required action above still fails closed. */ }
            }
            context.requireCurrent();return new LedgerContracts.DefinitionData(binding.definition(),use,effective);
        });
    }
    public LedgerContracts.QueryData query(HttpServletRequest request,String app,String entity,LedgerContracts.QueryRequest input) {
        var binding=registry.require(app,entity);requireDefinition(binding,input==null?null:input.definitionVersion());
        if(input==null || input.query()==null)throw new LedgerInputException("query","required");
        var supplied=input.query();
        if(supplied.page()!=null && supplied.page().cursor()!=null)throw new LedgerInputException("query.page","cursor_not_supported");
        var normalized=new EntityQueryContract.Request(supplied.filterAST(),supplied.sort(),supplied.page()==null?new EntityQueryContract.PageRequest(1,null):supplied.page(),
                supplied.size()==null?20:supplied.size(),supplied.fields());
        var validated=queries.validate(normalized,binding.definition().querySchema());
        return reading(request,(captured,boundary)->{
            var context=execution(actions.authorization().requireRead(captured,boundary,action(binding,"read")));
            long total=repository.count(binding,context,validated);
            var records=repository.query(binding,context,validated).stream().map(row->record(binding,row,Set.of(action(binding,"read").permissionCode()),validated.fields())).toList();
            int page=validated.page().number();return new LedgerContracts.QueryData(records,page,validated.size(),total,(long)page*validated.size()<total);
        });
    }
    public LedgerContracts.RecordData detail(HttpServletRequest request,String app,String entity,UUID id) {
        var binding=registry.require(app,entity);
        return reading(request,(captured,boundary)->{
            var context=execution(actions.authorization().requireRead(captured,boundary,action(binding,"read")));
            var row=repository.find(binding,context,id,false);Set<String> effective=new LinkedHashSet<>();effective.add(action(binding,"read").permissionCode());
            try {
                var preview=execution(actions.authorization().requireRead(captured,boundary,action(binding,"update")));
                if(repository.visible(binding,preview,id)) {
                    binding.scopePolicy().requireUpdate(preview,id,row.values(),row.values());preview.requireCurrent();effective.add(action(binding,"update").permissionCode());
                }
            } catch(AccessDeniedException denied) { /* Read remains valid; this record does not advertise editing. */ }
            context.requireCurrent();return record(binding,row,effective,binding.definition().fields().keySet().stream().toList());
        });
    }
    public LedgerContracts.ReferenceData references(HttpServletRequest request,String app,String entity,String key,LedgerContracts.ReferenceRequest input) {
        var binding=registry.require(app,entity);requireDefinition(binding,input==null?null:input.definitionVersion());
        var registration=binding.referenceProviders().get(key);
        if(registration==null)throw new LedgerException(404,"RESOURCE_NOT_VISIBLE");
        if(input.use()==null || !registration.allowedUses().contains(input.use()))throw new LedgerInputException("use","reference_use_not_allowed");
        var context=input.context()==null?Map.<String,tools.jackson.databind.JsonNode>of():input.context();
        for(String field:context.keySet())if(!registration.contextFields().contains(field) || LedgerInputValidator.CORE_PROTECTED.contains(field)
                || binding.storage().protectedFields().contains(field) || binding.storage().derivedFields().contains(field))throw new LedgerInputException(field,"reference_context_not_allowed");
        if(input.search()!=null && input.search().length()>200)throw new LedgerInputException("search","max_length");
        int size=input.size()==null?20:input.size();if(size<1 || size>50)throw new LedgerInputException("size","range");
        var normalized=new LedgerContracts.ReferenceRequest(input.definitionVersion(),input.use(),input.search()==null?"":input.search().strip(),context,size);
        return reading(request,(captured,boundary)->{
            var execution=execution(actions.authorization().requireRead(captured,boundary,action(binding,operation(input.use()))));
            var result=registration.provider().query(execution,normalized);execution.requireCurrent();
            if(result==null || result.options()==null || result.options().size()>size || result.options().stream().anyMatch(option->option.id()==null || option.label()==null))throw new LedgerException(503,"DEPENDENCY_UNAVAILABLE");
            return result;
        });
    }
    public LedgerContracts.CommandData create(HttpServletRequest request,String app,String entity,UUID key,LedgerContracts.CreateRequest input,UUID traceId) {
        var binding=registry.require(app,entity);
        var values=inputs.validate(binding,LedgerDefinition.Use.CREATE,input==null?null:input.definitionVersion(),input==null?null:input.values());
        return command(request,binding,"CREATE",null,null,key,input.definitionVersion(),values,traceId);
    }
    public LedgerContracts.CommandData update(HttpServletRequest request,String app,String entity,UUID id,UUID key,LedgerContracts.UpdateRequest input,UUID traceId) {
        var binding=registry.require(app,entity);
        var values=inputs.validate(binding,LedgerDefinition.Use.EDIT,input==null?null:input.definitionVersion(),input==null?null:input.values());
        if(input.expectedVersion()==null || input.expectedVersion()<1 || input.expectedVersion()==Long.MAX_VALUE)throw new LedgerInputException("expectedVersion","invalid_version");
        return command(request,binding,"UPDATE",id,input.expectedVersion(),key,input.definitionVersion(),values,traceId);
    }
    private LedgerContracts.CommandData command(HttpServletRequest request,LedgerBinding binding,String verb,UUID id,Long expected,UUID key,String definitionVersion,Map<String,Object> values,UUID traceId) {
        if(key==null || traceId==null)throw new LedgerInputException("Idempotency-Key","required");
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new LedgerException(503,"DEPENDENCY_UNAVAILABLE");
        CapturedHumanRequest captured=requests.capture(request);
        try {return write.execute(status->{
            bindTenant(captured.context().tenantId());
            actions.guard().forBusinessCommand(captured.context().tenantId());
            var scope=new HumanSessionAuthorityTransaction.WriteScope(captured,HumanSessionAuthorityTransaction.Guard.FINDING_COMMAND,List.of(captured.context().tenantId()));
            return actions.callers().withGuarded(scope,boundary->{
                var context=execution(actions.authorization().requireCommand(scope,boundary,action(binding,verb.toLowerCase(java.util.Locale.ROOT))));
                repository.lockIdempotency(binding,context,verb,key);
                String hash=repository.fingerprint(verb,id,definitionVersion,expected,values);
                var previous=repository.receipt(binding,context,verb,key);
                if(previous!=null) {
                    if(!previous.hash().equals(hash))throw new LedgerException(409,"IDEMPOTENCY_CONFLICT");
                    var current=repository.find(binding,context,previous.result().id(),false);
                    if(verb.equals("CREATE")) prepared(binding,context,values);
                    else requireUpdate(binding,context,current,values);
                    var old=previous.result();return new LedgerContracts.CommandData(old.commandId(),old.id(),old.version(),old.definitionVersion(),old.eventId(),old.occurredAt(),true);
                }
                LedgerRepository.Row current=null;Map<String,Object> writeValues;
                if(verb.equals("CREATE"))writeValues=prepared(binding,context,values);
                else {
                    current=repository.find(binding,context,id,true);
                    if(current.version()!=expected)throw new LedgerException(409,"VERSION_CONFLICT");
                    requireUpdate(binding,context,current,values);writeValues=values;
                }
                UUID commandId=UUID.randomUUID(),eventId=UUID.randomUUID();UUID target=id==null?UUID.randomUUID():id;
                var row=verb.equals("CREATE")?repository.create(binding,context,target,writeValues):repository.update(binding,context,target,expected,writeValues);
                var result=new LedgerContracts.CommandData(commandId,target,row.version(),definitionVersion,eventId,context.decision().observedAt(),false);
                UUID auditId=audit.appendSuccess(context.decision(),commandId,target,traceId,values.keySet());
                repository.appendRevision(binding,context,row,result,auditId,verb);
                repository.appendReceipt(binding,context,verb,key,hash,result);
                context.requireCurrent();return result;
            });
        });}finally{captured.close();}
    }
    private Map<String,Object> prepared(LedgerBinding binding,LedgerExecution context,Map<String,Object> values) {
        var derived=binding.scopePolicy().prepareCreate(context,values);context.requireCurrent();
        if(derived==null || !binding.storage().derivedFields().containsAll(derived.keySet()))throw new LedgerException(503,"DEPENDENCY_UNAVAILABLE");
        Map<String,Object> prepared=new LinkedHashMap<>(binding.storage().createDefaults());prepared.putAll(values);prepared.putAll(derived);
        return Collections.unmodifiableMap(prepared);
    }
    private void requireUpdate(LedgerBinding binding,LedgerExecution context,LedgerRepository.Row row,Map<String,Object> values) {
        Map<String,Object> proposed=new LinkedHashMap<>(row.values());proposed.putAll(values);
        binding.scopePolicy().requireUpdate(context,row.id(),row.values(),Collections.unmodifiableMap(proposed));context.requireCurrent();
    }
    private <T>T reading(HttpServletRequest request,BiFunction<CapturedHumanRequest,HumanSessionAuthorityTransaction.ReadBoundary,T> work) {
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new LedgerException(503,"DEPENDENCY_UNAVAILABLE");
        CapturedHumanRequest captured=requests.capture(request);
        try{return read.execute(status->{bindTenant(captured.context().tenantId());return actions.callers().withRead(captured,boundary->work.apply(captured,boundary));});}finally{captured.close();}
    }
    private void bindTenant(UUID tenant) {
        if (!(TransactionSynchronizationManager.getResource(source) instanceof ConnectionHolder holder)
                || !holder.isSynchronizedWithTransaction() || holder.isRollbackOnly()) throw new LedgerException(503,"DEPENDENCY_UNAVAILABLE");
        bindTenant(holder.getConnection(),tenant);
    }
    static void bindTenant(Connection connection,UUID tenant) {
        try {
            if(connection==null || tenant==null || connection.getAutoCommit())throw new LedgerException(503,"DEPENDENCY_UNAVAILABLE");
            try(var statement=connection.prepareStatement("select set_config('app.tenant_id', ?, true)")) {
                statement.setString(1,tenant.toString());
                try(var rows=statement.executeQuery()) {
                    if(!rows.next() || !tenant.toString().equals(rows.getString(1)) || rows.next())throw new LedgerException(503,"DEPENDENCY_UNAVAILABLE");
                }
            }
            try(var statement=connection.prepareStatement("select current_setting('app.tenant_id',true)");var rows=statement.executeQuery()) {
                if(!rows.next() || !tenant.toString().equals(rows.getString(1)) || rows.next())throw new LedgerException(503,"DEPENDENCY_UNAVAILABLE");
            }
        } catch(java.sql.SQLException failure) {throw new LedgerException(503,"DEPENDENCY_UNAVAILABLE");}
    }
    private LedgerExecution execution(com.mightyoung.softwarefactory.authorization.ApplicationActionAuthorization.Decision decision) {
        if(!(TransactionSynchronizationManager.getResource(source) instanceof ConnectionHolder holder))throw new LedgerException(503,"DEPENDENCY_UNAVAILABLE");
        Connection connection=holder.getConnection();actions.authorization().requireSqlConnection(decision,source,connection);
        return new LedgerExecution(actions.authorization(),decision,source,connection,DSL.using(connection,SQLDialect.POSTGRES,new Settings().withExecuteLogging(false).withFetchWarnings(false)));
    }
    private static ApplicationEntityAction action(LedgerBinding binding,String action) {return new ApplicationEntityAction(binding.definition().appKey(),binding.definition().entityKey(),action);}
    private static String operation(LedgerDefinition.Use use) {return switch(use){case READ->"read";case CREATE->"create";case EDIT->"update";};}
    private static void requireDefinition(LedgerBinding binding,String version) {if(!binding.definition().definitionVersion().equals(version))throw new LedgerException(409,"DEFINITION_CONFLICT");}
    private static LedgerContracts.RecordData record(LedgerBinding binding,LedgerRepository.Row row,Set<String> effective,List<String> selected) {
        Map<String,Object> visible=new LinkedHashMap<>();for(String field:selected)if(binding.definition().fields().containsKey(field))visible.put(field,row.values().get(field));
        return new LedgerContracts.RecordData(row.id(),row.version(),binding.definition().definitionVersion(),visible,effective);
    }
}
