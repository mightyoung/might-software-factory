package com.mightyoung.softwarefactory.tooling;

import com.mightyoung.softwarefactory.ledger.LedgerException;
import com.mightyoung.softwarefactory.ledger.LedgerInputException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;

class ToolLoanDefinitionVersionTest {
    private ToolLoanService.Input input(String version,String location) {
        return new ToolLoanService.Input(version,UUID.randomUUID(),UUID.randomUUID(),"借用说明",
                List.of(UUID.randomUUID()),null,location);
    }
    @Test void newDefinitionRequiresBoundedLocationWhileOldVersionRejectsIt() {
        for(String location:Arrays.asList(null,""," ","x".repeat(201))) {
            assertThatThrownBy(()->ToolLoanService.validate(input("2",location),"CREATE"))
                    .isInstanceOfSatisfying(LedgerInputException.class,e->assertThat(e.field()).isEqualTo("usageLocation"));
        }
        assertThatCode(()->ToolLoanService.validate(input("2","总装一线"),"CREATE")).doesNotThrowAnyException();
        assertThatCode(()->ToolLoanService.validate(input("1",null),"CREATE")).doesNotThrowAnyException();
        assertThatThrownBy(()->ToolLoanService.validate(input("1","总装一线"),"CREATE"))
                .isInstanceOf(LedgerInputException.class);
    }
    @Test void commandDefinitionMatchesTheStoredLoanRegardlessOfPublication() {
        for(String version:List.of("1","2")) {
            assertThatCode(()->ToolLoanService.validateIssue(new ToolLoanService.IssueInput(version,1L)))
                    .doesNotThrowAnyException();
            assertThatCode(()->ToolLoanService.requireBoundDefinition(version,version)).doesNotThrowAnyException();
        }
        assertThatThrownBy(()->ToolLoanService.requireBoundDefinition("1","2"))
                .isInstanceOfSatisfying(LedgerException.class,e->assertThat(e.code()).isEqualTo("DEFINITION_CONFLICT"));
    }
    @Test void publicationRejectsUnsupportedConfiguration() {
        for(String version:List.of("1","2")) assertThat(ToolLoanService.publishedVersion(version)).isEqualTo(version);
        assertThatThrownBy(()->ToolLoanService.publishedVersion("3")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void oldRequestFingerprintsRemainByteCompatible() throws Exception {
        var json=new ObjectMapper();
        var request=input("1",null);
        var old=new LinkedHashMap<String,Object>();
        old.put("definitionVersion",request.definitionVersion());
        old.put("organizationId",request.organizationId());old.put("custodyScopeId",request.custodyScopeId());
        old.put("purpose",request.purpose());old.put("toolIds",request.toolIds());old.put("expectedVersion",request.expectedVersion());
        var serialized=json.writeValueAsString(Arrays.asList("CREATE",null,old));
        var expected=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(serialized.getBytes(StandardCharsets.UTF_8)));
        assertThat(ToolLoanService.fingerprint(json,null,"CREATE",request)).isEqualTo(expected);
        var two=new ToolLoanService.Input("2",request.organizationId(),request.custodyScopeId(),request.purpose(),request.toolIds(),null,"一线");
        var changed=new ToolLoanService.Input("2",request.organizationId(),request.custodyScopeId(),request.purpose(),request.toolIds(),null,"二线");
        assertThat(ToolLoanService.fingerprint(json,null,"CREATE",two))
                .isNotEqualTo(ToolLoanService.fingerprint(json,null,"CREATE",changed));
    }
}
