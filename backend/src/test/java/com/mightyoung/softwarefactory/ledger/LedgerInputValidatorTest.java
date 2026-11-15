package com.mightyoung.softwarefactory.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static com.mightyoung.softwarefactory.ledger.LedgerDefinition.*;

import com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class LedgerInputValidatorTest {
    private final LedgerInputValidator validator=new LedgerInputValidator();
    private final JsonMapper json=JsonMapper.builder().build();

    @Test void acceptedInputsDoNotDependOnQueryableFieldsAndOptionalNullIsPreserved() {
        var binding=binding(Map.of("description",field(Type.TEXT,false,20),"scopeId",field(Type.REFERENCE,true,0)));
        UUID scope=UUID.randomUUID();
        var values=validator.validate(binding,Use.CREATE,"1",Map.of("scopeId",json.valueToTree(scope.toString())));
        assertThat(values).containsEntry("scopeId",scope).containsEntry("description",null);
        assertThatThrownBy(()->values.put("scopeId",UUID.randomUUID())).isInstanceOf(UnsupportedOperationException.class);
    }
    @Test void protectedUnknownAndWrongUseFieldsNeverDisappearSilently() {
        var binding=binding(Map.of("name",field(Type.TEXT,true,20)));
        for(String field:List.of("id","tenantId","actorId","version","state","organizationId","notRegistered")) {
            assertThatThrownBy(()->validator.validate(binding,Use.CREATE,"1",Map.of("name",json.valueToTree("fixture"),field,json.valueToTree("injected"))))
                    .isInstanceOf(LedgerInputException.class);
        }
        var createOnly=new InputField("Name",Type.TEXT,true,20,List.of(),null,Set.of(Use.CREATE));
        assertThatThrownBy(()->validator.validate(binding(Map.of("name",createOnly)),Use.EDIT,"1",Map.of("name",json.valueToTree("fixture"))))
                .isInstanceOf(LedgerInputException.class);
    }
    @Test void rejectsWrongDefinitionMissingRequiredAndTypedTextCoercion() {
        var binding=binding(Map.of("name",field(Type.TEXT,true,20)));
        for (String version:List.of("0","2")) assertThatThrownBy(()->validator.validate(binding,Use.CREATE,version,Map.of()))
                .isInstanceOf(LedgerInputException.class).extracting("reason").isEqualTo("definition_conflict");
        assertThatThrownBy(()->validator.validate(binding,Use.CREATE,"1",Map.of())).isInstanceOf(LedgerInputException.class);
        assertThatThrownBy(()->validator.validate(binding,Use.CREATE,"1",Map.of("name",json.valueToTree(5)))).isInstanceOf(LedgerInputException.class);
    }
    @Test void measuresUnicodeCodePointsAndRejectsUnpairedSurrogatesAndNul() {
        var binding=binding(Map.of("name",field(Type.TEXT,true,2)));
        assertThat(validator.validate(binding,Use.CREATE,"1",Map.of("name",json.valueToTree("😀😀")))).containsEntry("name","😀😀");
        for(String value:List.of("😀😀😀","\ud800","a\u0000b","   ")) {
            assertThatThrownBy(()->validator.validate(binding,Use.CREATE,"1",Map.of("name",json.valueToTree(value)))).isInstanceOf(LedgerInputException.class);
        }
    }
    @Test void validatesNumbersDatesAndReferencesWithoutGuessingTypes() {
        assertThat(value(Type.INTEGER,json.valueToTree(42))).isEqualTo(42L);
        assertThat(value(Type.DATE_TIME,json.valueToTree("2026-11-15T11:07:00.123456+08:00"))).isEqualTo(java.time.Instant.parse("2026-11-15T03:07:00.123456Z"));
        UUID id=UUID.randomUUID();
        assertThat(value(Type.UUID,json.valueToTree(id.toString()))).isEqualTo(id);
        assertThat(value(Type.REFERENCE,json.valueToTree(id.toString()))).isEqualTo(id);
        assertThat(value(Type.MULTILINE_TEXT,json.valueToTree(" first\nsecond "))).isEqualTo("first\nsecond");
        var enumField=new InputField("State",Type.ENUM,true,20,List.of("NORMAL"),null,Set.of(Use.CREATE));
        assertThat(validator.validate(binding(Map.of("value",enumField)),Use.CREATE,"1",Map.of("value",json.valueToTree("NORMAL")))).containsEntry("value","NORMAL");
        assertThatThrownBy(()->validator.validate(binding(Map.of("value",enumField)),Use.CREATE,"1",Map.of("value",json.valueToTree("UNKNOWN")))).isInstanceOf(LedgerInputException.class);
        for(var example:Map.<Type,tools.jackson.databind.JsonNode>of(Type.INTEGER,json.valueToTree("42"),
                Type.DATE_TIME,json.valueToTree("2026-02-30T00:00:00Z"),Type.REFERENCE,json.valueToTree("not-id")).entrySet()) {
            assertThatThrownBy(()->value(example.getKey(),example.getValue())).isInstanceOf(LedgerInputException.class);
        }
        assertThatThrownBy(()->value(Type.INTEGER,json.valueToTree(9_007_199_254_740_992L))).isInstanceOf(LedgerInputException.class);
        assertThatThrownBy(()->value(Type.DATE_TIME,json.valueToTree("2026-11-15T10:00:00.123456789Z"))).isInstanceOf(LedgerInputException.class);
    }
    private Object value(Type type,tools.jackson.databind.JsonNode value) {
        return validator.validate(binding(Map.of("value",field(type,true,40))),Use.CREATE,"1",Map.of("value",value)).get("value");
    }
    private static InputField field(Type type,boolean required,int maximum) { return new InputField("Input",type,required,maximum,List.of(),null,Set.of(Use.CREATE,Use.EDIT)); }
    private static LedgerBinding binding(Map<String,InputField> fields) {
        var schema=new EntityQueryContract.EntitySchema("sample","1","id",List.of("name"),Map.of());
        var definition=new LedgerDefinition("sample","sample","1","Sample",Map.of(),fields,schema,
                new Layout(List.of(),List.of(),List.of(),List.of(),List.of()),new Actions(new Action("Read","sample.read"),new Action("Create","sample.create"),new Action("Update","sample.update")),Map.of());
        return new LedgerBinding(definition,new LedgerBinding.Storage("sample",Map.of(),Map.of("state","ACTIVE"),Set.of("organizationId"),Set.of("state")),null,Map.of());
    }
}
