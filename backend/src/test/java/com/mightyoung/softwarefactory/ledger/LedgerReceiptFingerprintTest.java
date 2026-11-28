package com.mightyoung.softwarefactory.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.mightyoung.softwarefactory.tooling.ToolLedgerConfiguration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class LedgerReceiptFingerprintTest {
    private static final UUID RECORD=UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID SCOPE=UUID.fromString("22222222-2222-4222-8222-222222222222");
    private final ObjectMapper json=new ObjectMapper();
    private final LedgerRepository repository=new LedgerRepository(json);
    private final LedgerBinding tool=new ToolLedgerConfiguration().toolLedgerBinding();

    @Test void recognizesPreTransferReceiptsForTheSameToolEditWithoutScope() {
        var current=toolEdit(Map.of("name",json.valueToTree("夹具")));
        // The old registered EDIT fields were name, model and description, without custodyScopeId.
        var beforeTransfer=new LinkedHashMap<String,Object>();
        beforeTransfer.put("name","夹具");beforeTransfer.put("model",null);beforeTransfer.put("description",null);
        String oldReceipt=repository.fingerprint("UPDATE",RECORD,"1",7L,beforeTransfer);
        assertThat(current).containsEntry("custodyScopeId",null);
        assertThat(repository.fingerprint("UPDATE",RECORD,"1",7L,current)).isNotEqualTo(oldReceipt);

        assertThat(matches(tool,"UPDATE",RECORD,"1",7L,current,oldReceipt)).isTrue();
        assertThat(matches(tool,"UPDATE",RECORD,"1",7L,toolEdit(Map.of("name",json.valueToTree("夹具"),
                "custodyScopeId",json.nullNode())),oldReceipt)).isTrue();

        assertThat(matches(tool,"UPDATE",RECORD,"1",7L,toolEdit(Map.of("name",json.valueToTree("另一件工装"))),oldReceipt)).isFalse();
        assertThat(matches(tool,"UPDATE",RECORD,"1",7L,toolEdit(Map.of("name",json.valueToTree("夹具"),
                "custodyScopeId",json.valueToTree(SCOPE.toString()))),oldReceipt)).isFalse();
        assertThat(matches(tool,"UPDATE",RECORD,"1",8L,current,oldReceipt)).isFalse();
        assertThat(matches(tool,"UPDATE",SCOPE,"1",7L,current,oldReceipt)).isFalse();

        var other=mock(LedgerBinding.class);
        var definition=mock(LedgerDefinition.class);
        when(other.definition()).thenReturn(definition);
        when(definition.appKey()).thenReturn("tooling");
        when(definition.entityKey()).thenReturn("another");
        assertThat(matches(other,"UPDATE",RECORD,"1",7L,current,oldReceipt)).isFalse();
        assertThat(matches(tool,"CREATE",null,"1",null,current,
                repository.fingerprint("CREATE",null,"1",null,beforeTransfer))).isFalse();
        assertThat(matches(tool,"UPDATE",RECORD,"2",7L,current,
                repository.fingerprint("UPDATE",RECORD,"2",7L,beforeTransfer))).isFalse();
    }

    @Test void keepsExactHashesForCurrentReceiptsWithAndWithoutScope() {
        for (var current : java.util.List.of(
                toolEdit(Map.of("name",json.valueToTree("夹具"))),
                toolEdit(Map.of("name",json.valueToTree("夹具"),"custodyScopeId",json.valueToTree(SCOPE.toString()))))) {
            String exact=repository.fingerprint("UPDATE",RECORD,"1",7L,current);
            assertThat(matches(tool,"UPDATE",RECORD,"1",7L,current,exact)).isTrue();
            assertThat(matches(tool,"UPDATE",RECORD,"1",9L,current,exact)).isFalse();
        }
    }

    private Map<String,Object> toolEdit(Map<String,tools.jackson.databind.JsonNode> supplied) {
        return new LedgerInputValidator().validate(tool,LedgerDefinition.Use.EDIT,"1",supplied);
    }

    private boolean matches(LedgerBinding binding,String action,UUID id,String definitionVersion,Long version,
                             Map<String,Object> values,String receiptHash) {
        return repository.matchesReceiptFingerprint(binding,action,id,definitionVersion,version,values,receiptHash);
    }
}
