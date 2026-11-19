package com.mightyoung.softwarefactory.tooling;

import com.mightyoung.softwarefactory.ledger.*;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import org.jooq.*;
import org.springframework.security.access.AccessDeniedException;
import static org.jooq.impl.DSL.*;

/** Normal catalog reads need RBAC organization scope, never keeper membership.
 * Writes additionally require the current keeper. NORMAL is a catalog state, not a promise of availability;
 * Scope transfers share the locked tool row with issuance. Qualification writers use the tenant security writer lock. */
final class ToolScopePolicy implements LedgerBinding.ScopePolicy {
    @Override public Set<String> requiredFields(LedgerDefinition.Use use) {
        return use==LedgerDefinition.Use.CREATE?Set.of("custodyScopeId"):Set.of();
    }
    @Override public Condition visible(LedgerExecution execution) {
        var decision=execution.decision();
        Condition scoped=field(name("root","organization_id"),UUID.class).in(decision.organizationIds())
                .and(field(name("root","item_state"),String.class).eq("NORMAL"));
        Condition scope=scopePredicate(execution)
                .and(field(name("s","id"),UUID.class).eq(field(name("root","custody_scope_id"),UUID.class)))
                .and(field(name("s","organization_id"),UUID.class).eq(field(name("root","organization_id"),UUID.class)));
        if (!decision.action().action().equals("read")) scope=scope.and(keeper(execution));
        return scoped.and(exists(selectOne().from(table(name("public","tool_custody_scope")).as("s")).where(scope)));
    }
    @Override public Map<String,Object> prepareCreate(LedgerExecution execution,Map<String,Object> values) {
        if(values.get("custodyScopeId")==null) throw new LedgerInputException("custodyScopeId","required");
        UUID scope=uuid(values.get("custodyScopeId"));
        UUID organization=execution.dsl().select(field(name("s","organization_id"),UUID.class))
                .from(table(name("public","tool_custody_scope")).as("s"))
                .where(scopePredicate(execution).and(field(name("s","id"),UUID.class).eq(scope)).and(keeper(execution)))
                .fetchOne(field(name("s","organization_id"),UUID.class));
        execution.requireCurrent();
        if(organization==null) throw new LedgerInputException("custodyScopeId","reference_not_visible");
        return Map.of("organizationId",organization);
    }
    @Override public Map<String,Object> prepareUpdate(LedgerExecution execution,UUID id,Map<String,Object> current,Map<String,Object> values) {
        Object requested=values.get("custodyScopeId");
        UUID scope=uuid(requested==null?current.get("custodyScopeId"):requested);
        Map<String,Object> prepared=new LinkedHashMap<>(values);
        prepared.put("custodyScopeId",scope);
        prepared.putAll(prepareCreate(execution,Map.of("custodyScopeId",scope)));
        return Collections.unmodifiableMap(prepared);
    }
    @Override public void requireUpdate(LedgerExecution execution,UUID id,Map<String,Object> current,Map<String,Object> proposed) {
        for(String protectedKey:List.of("code","itemState")) {
            if(!Objects.equals(current.get(protectedKey),proposed.get(protectedKey))) throw new AccessDeniedException("Tool identity and state cannot be edited");
        }
        if(!"NORMAL".equals(current.get("itemState"))) throw new AccessDeniedException("Tool is outside the normal catalog");
        Map<String,Object> derived=prepareCreate(execution,Map.of("custodyScopeId",uuid(current.get("custodyScopeId"))));
        if(!derived.get("organizationId").equals(uuid(current.get("organizationId")))) throw new AccessDeniedException("Tool scope organization changed");
        Map<String,Object> destination=prepareCreate(execution,Map.of("custodyScopeId",uuid(proposed.get("custodyScopeId"))));
        if(!destination.get("organizationId").equals(uuid(proposed.get("organizationId")))) throw new AccessDeniedException("Tool scope organization is invalid");
        if(!uuid(current.get("custodyScopeId")).equals(uuid(proposed.get("custodyScopeId")))
                && execution.dsl().fetchExists(selectOne().from(table(name("public","tool_loan_item")))
                    .where(field(name("tenant_id"),UUID.class).eq(execution.decision().context().tenantId()))
                    .and(field(name("tool_id"),UUID.class).eq(id))
                    .and(field(name("issued_at")).isNotNull()).and(field(name("settled_at")).isNull()))) {
            throw new LedgerException(409,"TOOL_IN_USE");
        }
    }
    LedgerContracts.ReferenceData queryScopes(LedgerExecution execution,LedgerContracts.ReferenceRequest request) {
        int size=request.size()==null?20:request.size();
        String search=request.search()==null?"":request.search();
        Condition condition=scopePredicate(execution).and(keeper(execution));
        // Bounded literal substring search. SQL wildcard characters from the request are not interpreted.
        if(!search.isBlank()) condition=condition.and(field(name("s","name"),String.class).containsIgnoreCase(search)
                .or(field(name("s","code"),String.class).containsIgnoreCase(search)));
        var rows=execution.dsl().select(field(name("s","id"),UUID.class),field(name("s","code"),String.class),field(name("s","name"),String.class))
                .from(table(name("public","tool_custody_scope")).as("s")).where(condition)
                .orderBy(field(name("s","id"),UUID.class)).limit(size+1).fetch();
        execution.requireCurrent();
        return new LedgerContracts.ReferenceData(rows.stream().limit(size)
                .map(row->new LedgerContracts.ReferenceOption(row.value1(),row.value2()+" · "+row.value3())).toList(),rows.size()>size);
    }
    private Condition scopePredicate(LedgerExecution execution) {
        var decision=execution.decision();
        return field(name("s","tenant_id"),UUID.class).eq(decision.context().tenantId())
                .and(field(name("s","organization_id"),UUID.class).in(decision.organizationIds()))
                .and(field(name("s","active"),Boolean.class).isTrue());
    }
    private Condition keeper(LedgerExecution execution) {
        var decision=execution.decision();var at=OffsetDateTime.ofInstant(decision.observedAt(),ZoneOffset.UTC);
        return exists(selectOne().from(table(name("public","tool_custodian")).as("k"))
                .where(field(name("k","tenant_id"),UUID.class).eq(decision.context().tenantId()))
                .and(field(name("k","custody_scope_id"),UUID.class).eq(field(name("s","id"),UUID.class)))
                .and(field(name("k","member_id"),UUID.class).eq(decision.context().memberId()))
                .and(field(name("k","valid_from"),OffsetDateTime.class).le(at))
                .and(field(name("k","valid_to"),OffsetDateTime.class).isNull().or(field(name("k","valid_to"),OffsetDateTime.class).gt(at))));
    }
    private static UUID uuid(Object value) { if(value instanceof UUID id)return id; if(value instanceof String s)try{return UUID.fromString(s);}catch(IllegalArgumentException ignored){}throw new LedgerInputException("custodyScopeId","invalid_uuid"); }
}
