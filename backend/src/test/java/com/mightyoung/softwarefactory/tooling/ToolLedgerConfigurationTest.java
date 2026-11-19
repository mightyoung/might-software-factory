package com.mightyoung.softwarefactory.tooling;

import com.mightyoung.softwarefactory.authorization.ApplicationActionAuthorization;
import com.mightyoung.softwarefactory.authorization.ApplicationEntityAction;
import com.mightyoung.softwarefactory.identity.CurrentTenantContext;
import com.mightyoung.softwarefactory.ledger.*;
import java.time.Instant;
import java.util.*;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ToolLedgerConfigurationTest {
    private final LedgerBinding binding=new ToolLedgerConfiguration().toolLedgerBinding();
    private final LedgerInputValidator inputs=new LedgerInputValidator();
    private final JsonMapper json=JsonMapper.builder().build();
    @Test void registrationUsesTheGenericContractAndAuthorizedScopeReferenceForCreateAndEdit() {
        new LedgerBindingValidator().validate(binding);
        assertThat(binding.definition().appKey()).isEqualTo("tooling");
        assertThat(binding.storage().createDefaults()).containsEntry("itemState","NORMAL");
        assertThat(binding.storage().derivedFields()).containsExactly("organizationId");
        assertThat(binding.referenceProviders().get("custodyScope").allowedUses()).containsExactlyInAnyOrder(LedgerDefinition.Use.CREATE,LedgerDefinition.Use.EDIT);
        assertThat(binding.referenceProviders().get("custodyScope").contextFields()).isEmpty();
        assertThat(new ToolLedgerConfiguration().toolingApplication().nodes().getFirst().permissions()).containsExactly("tool.read","tool.create","tool.update");
    }
    @Test void custodyScopeIsRequiredOnCreateAndOptionalForLegacyEdit() {
        assertThat(binding.definitionFor(LedgerDefinition.Use.CREATE).inputFields().get("custodyScopeId").required()).isTrue();
        assertThat(binding.definitionFor(LedgerDefinition.Use.EDIT).inputFields().get("custodyScopeId").required()).isFalse();
        assertThatThrownBy(()->inputs.validate(binding,LedgerDefinition.Use.CREATE,"1",Map.of("code",node("TOOL-1"),"name",node("Fixture"))))
                .isInstanceOf(LedgerInputException.class);
    }
    @Test void createNormalizesDescriptionFieldsAndKeepsOptionalNulls() {
        var result=inputs.validate(binding,LedgerDefinition.Use.CREATE,"1",Map.of("code",node("  TOOL-1  "),"name",node("  Fixture  "),"custodyScopeId",node(UUID.randomUUID().toString())));
        assertThat(result).containsEntry("code","TOOL-1").containsEntry("name","Fixture").containsEntry("model",null).containsEntry("description",null);
        assertThat(result).doesNotContainKeys("organizationId","itemState","tenantId");
    }
    @Test void editCannotForgeCodeOrganizationStateOrIdentity() {
        for(String key:List.of("code","organizationId","itemState","tenantId","id","version")) {
            assertThatThrownBy(()->inputs.validate(binding,LedgerDefinition.Use.EDIT,"1",Map.of("name",node("Changed"),key,node("injected")))).isInstanceOf(LedgerInputException.class);
        }
        var result=inputs.validate(binding,LedgerDefinition.Use.EDIT,"1",Map.of("name",node("Changed")));
        assertThat(result).containsEntry("name","Changed").containsEntry("model",null).containsEntry("description",null).containsEntry("custodyScopeId",null);
        UUID target=UUID.randomUUID();
        assertThat(inputs.validate(binding,LedgerDefinition.Use.EDIT,"1",Map.of("name",node("Changed"),"custodyScopeId",node(target.toString()))))
                .containsEntry("custodyScopeId",target);
    }
    @Test void lengthsUseUnicodeCodePointsAndUnknownDefinitionsFailClosed() {
        var body=new HashMap<String,JsonNode>();body.put("code",node("TOOL-2"));body.put("name",node("𝌆".repeat(200)));body.put("custodyScopeId",node(UUID.randomUUID().toString()));
        assertThat(inputs.validate(binding,LedgerDefinition.Use.CREATE,"1",body).get("name")).isEqualTo("𝌆".repeat(200));
        body.put("name",node("𝌆".repeat(201)));
        assertThatThrownBy(()->inputs.validate(binding,LedgerDefinition.Use.CREATE,"1",body)).isInstanceOf(LedgerInputException.class);
        assertThatThrownBy(()->inputs.validate(binding,LedgerDefinition.Use.CREATE,"old",body)).isInstanceOf(LedgerInputException.class);
    }
    @Test void normalCatalogReadDoesNotRequireKeeperAndDoesNotClaimBorrowingAvailability() {
        String rendered=DSL.using(SQLDialect.POSTGRES).renderInlined(new ToolScopePolicy().visible(execution("read")));
        assertThat(rendered).contains("tool_custody_scope","item_state","NORMAL").doesNotContain("tool_custodian","loan","occupancy");
    }
    @Test void updateAndReplayPoliciesAddTheCurrentKeeperToTheSameCatalogScope() {
        for(String action:List.of("create","update")) {
            String rendered=DSL.using(SQLDialect.POSTGRES).renderInlined(new ToolScopePolicy().visible(execution(action)));
            assertThat(rendered).contains("tool_custody_scope","tool_custodian","member_id","valid_from","valid_to","NORMAL");
        }
    }
    private JsonNode node(Object value){return json.valueToTree(value);}
    private LedgerExecution execution(String action) {
        var execution=mock(LedgerExecution.class);var decision=mock(ApplicationActionAuthorization.Decision.class);
        when(execution.decision()).thenReturn(decision);
        when(decision.action()).thenReturn(new ApplicationEntityAction("tooling","tool",action));
        when(decision.context()).thenReturn(new CurrentTenantContext(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),1,1,1));
        when(decision.organizationIds()).thenReturn(Set.of(UUID.randomUUID()));when(decision.observedAt()).thenReturn(Instant.parse("2026-11-15T00:00:00Z"));return execution;
    }
}
