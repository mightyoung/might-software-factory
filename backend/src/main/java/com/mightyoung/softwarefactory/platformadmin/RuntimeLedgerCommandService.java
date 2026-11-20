package com.mightyoung.softwarefactory.platformadmin;

import com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.ValidationException;
import com.mightyoung.softwarefactory.authorization.EffectivePermissions;
import com.mightyoung.softwarefactory.identity.*;
import com.mightyoung.softwarefactory.tenancy.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import static com.mightyoung.softwarefactory.platformadmin.RuntimeLedgerCommandModels.*;

@Service
public final class RuntimeLedgerCommandService {
    private final SessionContextResolver resolver;
    private final IdentitySecurityTransaction transaction;
    private final EffectivePermissions permissions;
    private final TenantApplicationReadRepository applications;
    private final RuntimeLedgerCommandRepository repository;

    public RuntimeLedgerCommandService(SessionContextResolver resolver, IdentitySecurityTransaction transaction,
            EffectivePermissions permissions, TenantApplicationReadRepository applications, RuntimeLedgerCommandRepository repository) {
        this.resolver=resolver; this.transaction=transaction; this.permissions=permissions;
        this.applications=applications; this.repository=repository;
    }

    public RecordView create(String key, CreateRequest request) {
        validKey(key);
        if(request==null || request.requestId()==null || request.organizationId()==null || request.definitionVersion()==null || request.definitionVersion()<1) invalid("request","invalid");
        return guarded(key,"create", (context,scope) -> {
            if(!scope.contains(request.organizationId())) throw denied();
            RecordView replay=repository.replay(context.tenantId(),context.memberId(),key,request.requestId(),"create",request);
            if(replay!=null) return replay;
            var published=repository.published(context.tenantId(),key);
            if(published.version()!=request.definitionVersion()) throw new LedgerConfigurationException(LedgerConfigurationException.Reason.VERSION_CONFLICT);
            validate(published.definition(),request.values());
            return repository.create(context.tenantId(),context.memberId(),key,request);
        });
    }
    public RecordView update(String key, UUID id, UpdateRequest request) {
        validKey(key);
        if(request==null || request.requestId()==null) invalid("request","invalid");
        expected(request.expectedVersion());
        return guarded(key,"update",(context,scope) -> {
            RecordView replay=repository.replay(context.tenantId(),context.memberId(),key,request.requestId(),"update:"+id,request);
            if(replay!=null) {
                if(!scope.contains(replay.organizationId())) throw denied();
                return replay;
            }
            RecordView record=repository.lock(context.tenantId(),key,id);
            if(!scope.contains(record.organizationId())) throw denied();
            if(record.version()!=request.expectedVersion()) conflict();
            validate(repository.definition(context.tenantId(),key,record.definitionVersion()),request.values());
            return repository.update(context.tenantId(),context.memberId(),key,id,request);
        });
    }
    public RecordView delete(String key, UUID id, DeleteRequest request) {
        validKey(key);
        if(request==null || request.requestId()==null) invalid("request","invalid");
        expected(request.expectedVersion());
        return guarded(key,"delete",(context,scope) -> {
            // Receipt lookup precedes record lookup so a deleted draft can be retried.
            RecordView replay=repository.replay(context.tenantId(),context.memberId(),key,request.requestId(),"delete:"+id,request);
            if(replay!=null) {
                if(!scope.contains(replay.organizationId())) throw denied();
                return replay;
            }
            RecordView record=repository.lock(context.tenantId(),key,id);
            if(!scope.contains(record.organizationId())) throw denied();
            if(record.version()!=request.expectedVersion()) conflict();
            return repository.delete(context.tenantId(),context.memberId(),key,id,request);
        });
    }
    private interface Work { RecordView run(CurrentTenantContext context, Set<UUID> scope); }
    private RecordView guarded(String key,String action,Work work) {
        CurrentTenantContext context=resolver.currentTenant();
        return transaction.execute(context,()-> {
            if(applications.findCurrentInCaller(context.tenantId(),"platform").filter(TenantApplicationState::enabled).isEmpty()) throw denied();
            Set<UUID> scope=permissions.readableOrganizations(context,"ledger.record."+key+"."+action);
            if(scope.isEmpty()) throw denied();
            return work.run(context,scope);
        });
    }
    static void validate(LedgerConfigurationModels.Definition definition, Map<String,Object> values) {
        if(values==null || values.size()>100) invalid("values","invalid");
        var fields=definition.fields();
        for(String key:values.keySet()) if(fields.stream().noneMatch(field->field.key().equals(key))) invalid("values."+key,"unknown_field");
        for(var field:fields) {
            Object value=values.get(field.key());
            if(value==null) {
                if(field.required()) invalid("values."+field.key(),"required");
                continue;
            }
            boolean valid=switch(field.type()) {
                case "text" -> value instanceof String text && text.length()<=10000 && (!field.required() || !text.isBlank());
                case "number" -> finiteNumber(value);
                case "boolean" -> value instanceof Boolean;
                case "date" -> date(value);
                default -> false;
            };
            if(!valid) invalid("values."+field.key(),"invalid_type");
        }
    }
    private static boolean finiteNumber(Object value) {
        if(!(value instanceof Number)) return false;
        try { new BigDecimal(value.toString()); return true; } catch(NumberFormatException ignored) { return false; }
    }
    private static boolean date(Object value) {
        if(!(value instanceof String text)) return false;
        try { return LocalDate.parse(text).toString().equals(text); } catch(DateTimeParseException ignored) { return false; }
    }
    private static void validKey(String key) { if(key==null || !key.matches("[a-z][a-z0-9_]{0,63}")) invalid("key","invalid"); }
    private static void expected(Long version) { if(version==null || version<1 || version==Long.MAX_VALUE) invalid("expectedVersion","invalid"); }
    private static void conflict() { throw new LedgerConfigurationException(LedgerConfigurationException.Reason.VERSION_CONFLICT); }
    private static AccessDeniedException denied() { return new AccessDeniedException("Ledger action unavailable"); }
    private static void invalid(String field,String code) { throw new ValidationException(field,code,"Invalid ledger record"); }
}
