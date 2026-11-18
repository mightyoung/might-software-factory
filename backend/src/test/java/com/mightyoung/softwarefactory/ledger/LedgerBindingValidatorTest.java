package com.mightyoung.softwarefactory.ledger;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static com.mightyoung.softwarefactory.ledger.LedgerDefinition.*;

import com.mightyoung.softwarefactory.applicationregistry.navigation.ApplicationContribution;
import com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;

class LedgerBindingValidatorTest {
    private final LedgerBindingValidator validator=new LedgerBindingValidator();
    @Test void acceptsIndependentQueryAndWriteFields() {
        assertThatCode(()->validator.validate(binding("sample_table","scope_id",false))).doesNotThrowAnyException();
    }
    @Test void reservedQueryIdNeedsNoBusinessFieldOrStorageMapping() {
        var binding=binding("sample_table","scope_id",false);
        var validated=new EntityQueryContract.Request(null,List.of(new EntityQueryContract.SortField("id",EntityQueryContract.Direction.ASC)),
                new EntityQueryContract.PageRequest(1,null),20,List.of("id","code"));
        assertThatCode(()->new com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryValidator().validate(validated,binding.definition().querySchema())).doesNotThrowAnyException();
        var schema=binding.definition().querySchema();
        var invalid=new EntityQueryContract.EntitySchema("sample","1","code",schema.defaultFields(),schema.fields());
        var original=binding.definition();
        var definition=new LedgerDefinition(original.appKey(),original.entityKey(),original.definitionVersion(),original.title(),original.fields(),
                original.inputFields(),invalid,original.layout(),original.actions(),original.references());
        assertThatThrownBy(()->validator.validate(new LedgerBinding(definition,binding.storage(),binding.scopePolicy(),binding.referenceProviders())))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void startupRejectsOperatorsNotImplementedByLedgerDao() {
        var binding=binding("sample_table","scope_id",false);var original=binding.definition();
        var schema=original.querySchema();var fields=new java.util.LinkedHashMap<>(schema.fields());
        fields.put("id",new EntityQueryContract.FieldSchema(EntityQueryContract.FieldType.UUID,false,true,true,
                Set.of(EntityQueryContract.FilterOperator.IN_SUBTREE),0,Set.of()));
        var definition=new LedgerDefinition(original.appKey(),original.entityKey(),original.definitionVersion(),original.title(),original.fields(),
                original.inputFields(),new EntityQueryContract.EntitySchema("sample","1","id",schema.defaultFields(),fields),original.layout(),original.actions(),original.references());
        assertThatThrownBy(()->validator.validate(new LedgerBinding(definition,binding.storage(),binding.scopePolicy(),binding.referenceProviders())))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Subtree");
    }
    @Test void rejectsStorageInjectionAndTechnicalColumnAliases() {
        for(String table:List.of("public.sample_table", "sample;drop table account", "Sample")) {
            assertThatThrownBy(()->validator.validate(binding(table,"scope_id",false))).isInstanceOf(IllegalArgumentException.class);
        }
        for(String column:List.of("tenant_id","id","version","created_at","updated_by_actor_id")) {
            assertThatThrownBy(()->validator.validate(binding("sample_table",column,false))).isInstanceOf(IllegalArgumentException.class);
        }
    }
    @Test void refusesReadOnlyStateInAcceptedInputEvenWhenNotInVisibleLayout() {
        assertThatThrownBy(()->validator.validate(binding("sample_table","scope_id",true))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void contributionValidatesApplicationEntityRouteAndCanonicalCapabilities() {
        var node=new ApplicationContribution.Node("sample","Sample","apps/app/entities/sample","sample",List.of("sample.read","sample.create","sample.update"));
        assertThatCode(()->new ApplicationContribution("app","App",List.of(node))).doesNotThrowAnyException();
        assertThatThrownBy(()->new ApplicationContribution("other","Other",List.of(node))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new ApplicationContribution.Node("sample","Sample","apps/app/entities/sample","sample",List.of("account.manage")))
                .isInstanceOf(IllegalArgumentException.class);
    }
    private static LedgerBinding binding(String table,String scopeColumn,boolean stateInput) {
        var field=new Field("Code",Type.TEXT,false,Map.of());
        var queryField=new EntityQueryContract.FieldSchema(EntityQueryContract.FieldType.TEXT,false,true,true,
                Set.of(EntityQueryContract.FilterOperator.EQ),40,Set.of());
        var idField=new EntityQueryContract.FieldSchema(EntityQueryContract.FieldType.UUID,false,true,true,
                Set.of(EntityQueryContract.FilterOperator.EQ,EntityQueryContract.FilterOperator.IN),0,Set.of());
        var query=new EntityQueryContract.EntitySchema("sample","1","id",List.of("code"),Map.of("code",queryField,"id",idField));
        var input=new InputField("Code",Type.TEXT,true,40,List.of(),null,Set.of(Use.CREATE,Use.EDIT));
        var inputs=stateInput ? Map.of("code",input,"scopeId",input,"state",input) : Map.of("code",input,"scopeId",input);
        var definition=new LedgerDefinition("app","sample","1","Sample",Map.of("code",field),inputs,query,
                new Layout(List.of("code"),List.of("code"),List.of("code","scopeId"),List.of("code","scopeId"),List.of("code")),
                new Actions(new Action("Read","sample.read"),new Action("Create","sample.create"),new Action("Edit","sample.update")),Map.of());
        var policy=new LedgerBinding.ScopePolicy() {
            public Condition visible(LedgerExecution execution) { return DSL.trueCondition(); }
            public Map<String,Object> prepareCreate(LedgerExecution execution,Map<String,Object> values) { return Map.of(); }
            public void requireUpdate(LedgerExecution execution,UUID id,Map<String,Object> current,Map<String,Object> proposed) { }
        };
        return new LedgerBinding(definition,new LedgerBinding.Storage(table,Map.of("code","code","scopeId",scopeColumn,"state","state"),Map.of("state","ACTIVE"),Set.of(),Set.of("state")),policy,Map.of());
    }
}
