package com.mightyoung.softwarefactory.tooling;

import com.mightyoung.softwarefactory.authorization.ApplicationActionAuthorization;
import com.mightyoung.softwarefactory.authorization.ApplicationEntityAction;
import com.mightyoung.softwarefactory.identity.CurrentTenantContext;
import com.mightyoung.softwarefactory.ledger.LedgerException;
import com.mightyoung.softwarefactory.ledger.LedgerExecution;
import com.mightyoung.softwarefactory.ledger.LedgerInputException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.tools.jdbc.MockConnection;
import org.jooq.tools.jdbc.MockResult;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ToolScopeTransferTest {
    private final ToolScopePolicy policy=new ToolScopePolicy();
    private final UUID source=UUID.randomUUID(),target=UUID.randomUUID();
    private final UUID sourceOrg=UUID.randomUUID(),targetOrg=UUID.randomUUID(),tool=UUID.randomUUID();
    private final List<String> statements=new ArrayList<>();

    @Test void omittedScopeKeepsLegacyEditOnTheCurrentScopeEvenWhileOccupied() {
        var execution=execution(true,true);
        Map<String,Object> values=new HashMap<>();
        values.put("name","Changed");values.put("custodyScopeId",null);
        var prepared=policy.prepareUpdate(execution,tool,current(),values);
        assertThat(prepared).containsEntry("custodyScopeId",source).containsEntry("organizationId",sourceOrg);
        policy.requireUpdate(execution,tool,current(),proposed(prepared));
        assertThat(statements).noneMatch(sql->sql.contains("tool_loan_item"));
    }

    @Test void settledTransferDerivesTargetOrganizationAndChecksBothCurrentCustodies() {
        var execution=execution(false,true);
        var prepared=policy.prepareUpdate(execution,tool,current(),Map.of("custodyScopeId",target));
        assertThat(prepared).containsEntry("custodyScopeId",target).containsEntry("organizationId",targetOrg);
        policy.requireUpdate(execution,tool,current(),proposed(prepared));
        assertThat(statements).filteredOn(sql->sql.contains("tool_custody_scope")).allMatch(sql->
                sql.contains("tool_custodian") && sql.contains("member_id") && sql.contains("active")
                && sql.contains("valid_from") && sql.contains("valid_to") && sql.contains("organization_id"));
        assertThat(statements).anyMatch(sql->sql.contains("tool_loan_item") && sql.contains("issued_at")
                && sql.contains("settled_at") && sql.contains("tenant_id") && sql.contains("tool_id"));
    }

    @Test void activeOccupancyRejectsTransfer() {
        var execution=execution(true,true);
        var prepared=policy.prepareUpdate(execution,tool,current(),Map.of("custodyScopeId",target));
        assertThatThrownBy(()->policy.requireUpdate(execution,tool,current(),proposed(prepared)))
                .isInstanceOf(LedgerException.class).hasMessage("TOOL_IN_USE");
    }

    @Test void targetOutsideCurrentAuthorizationFailsBeforeAnyWrite() {
        assertThatThrownBy(()->policy.prepareUpdate(execution(false,false),tool,current(),Map.of("custodyScopeId",target)))
                .isInstanceOf(LedgerInputException.class);
    }

    @Test void sourceQualificationAndOrganizationAreRechecked() {
        var execution=execution(false,true);
        var proposed=proposed(Map.of("custodyScopeId",target,"organizationId",targetOrg));
        var invalid=new HashMap<>(current());invalid.put("organizationId",UUID.randomUUID());
        assertThatThrownBy(()->policy.requireUpdate(execution,tool,invalid,proposed))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }

    private Map<String,Object> current() {
        return Map.of("code","TOOL-1","name","Fixture","custodyScopeId",source,"organizationId",sourceOrg,"itemState","NORMAL");
    }
    private Map<String,Object> proposed(Map<String,Object> prepared) {
        var result=new HashMap<>(current());result.putAll(prepared);return result;
    }
    private LedgerExecution execution(boolean occupied,boolean targetVisible) {
        var context=new CurrentTenantContext(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),1,1,1);
        var decision=mock(ApplicationActionAuthorization.Decision.class);
        when(decision.context()).thenReturn(context);
        when(decision.action()).thenReturn(new ApplicationEntityAction("tooling","tool","update"));
        when(decision.organizationIds()).thenReturn(Set.of(sourceOrg,targetOrg));
        when(decision.observedAt()).thenReturn(Instant.parse("2026-11-19T00:00:00Z"));
        var fixture=DSL.using(SQLDialect.POSTGRES);
        var connection=new MockConnection(query->{
            statements.add(query.sql());
            if(query.sql().contains("tool_loan_item")) {
                var field=DSL.field("exists",Boolean.class);
                var rows=fixture.newResult(field);rows.add(fixture.newRecord(field).value1(occupied));
                return new MockResult[]{new MockResult(1,rows)};
            }
            var field=DSL.field(DSL.name("s","organization_id"),UUID.class);
            var rows=fixture.newResult(field);
            boolean destination=List.of(query.bindings()).contains(target);
            if(!destination || targetVisible) rows.add(fixture.newRecord(field).value1(destination?targetOrg:sourceOrg));
            return new MockResult[]{new MockResult(rows.size(),rows)};
        });
        var execution=mock(LedgerExecution.class);
        when(execution.decision()).thenReturn(decision);
        when(execution.dsl()).thenReturn(DSL.using(connection,SQLDialect.POSTGRES));
        return execution;
    }
}
