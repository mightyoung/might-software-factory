package com.mightyoung.softwarefactory.tooling;

import com.mightyoung.softwarefactory.authorization.ApplicationEntityAction;
import com.mightyoung.softwarefactory.ledger.LedgerInputException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ToolLoanReturnInputTest {
    @Test void historicalLoanDefinitionRemainsUsableAndMustMatchItsBoundVersion() {
        assertThatCode(()->ToolLoanService.validateReturn(new ToolLoanService.ReturnInput("2",5L,List.of(UUID.randomUUID()))))
                .doesNotThrowAnyException();
        assertThatCode(()->ToolLoanService.requireBoundDefinition("1","1")).doesNotThrowAnyException();
        assertThatCode(()->ToolLoanService.requireBoundDefinition("2","2")).doesNotThrowAnyException();
        assertThatThrownBy(()->ToolLoanService.requireBoundDefinition("1","2"))
                .isInstanceOfSatisfying(com.mightyoung.softwarefactory.ledger.LedgerException.class,
                        error->assertThat(error.code()).isEqualTo("DEFINITION_CONFLICT"));
    }

    @Test void returnRequiresDistinctIssuedItemReferencesAndVersion() {
        UUID tool=UUID.randomUUID();
        assertThatCode(()->ToolLoanService.validateReturn(new ToolLoanService.ReturnInput("1",2L,List.of(tool))))
                .doesNotThrowAnyException();
        assertThatThrownBy(()->ToolLoanService.validateReturn(new ToolLoanService.ReturnInput("1",2L,List.of(tool,tool))))
                .isInstanceOf(LedgerInputException.class);
        assertThatThrownBy(()->ToolLoanService.validateReturn(new ToolLoanService.ReturnInput("1",null,List.of(tool))))
                .isInstanceOf(LedgerInputException.class);
    }

    @Test void damagedReceiptRequiresAnActualBoundedReason() {
        UUID tool=UUID.randomUUID();
        for(String reason:new String[]{null," ","a".repeat(4001)}) {
            var input=new ToolLoanService.ReceiveInput("1",3L,List.of(new ToolLoanService.ReceiveItem(tool,"DAMAGED",reason)));
            assertThatThrownBy(()->ToolLoanService.validateReceive(input)).isInstanceOf(LedgerInputException.class);
        }
        assertThatCode(()->ToolLoanService.validateReceive(new ToolLoanService.ReceiveInput("1",3L,
                List.of(new ToolLoanService.ReceiveItem(tool,"DAMAGED","Cracked housing")))))
                .doesNotThrowAnyException();
    }

    @Test void normalReceiptAcceptsNoReasonButRejectsUnknownConditionOrDuplicateTool() {
        var item=new ToolLoanService.ReceiveItem(UUID.randomUUID(),"NORMAL",null);
        assertThatCode(()->ToolLoanService.validateReceive(new ToolLoanService.ReceiveInput("1",3L,List.of(item))))
                .doesNotThrowAnyException();
        assertThatThrownBy(()->ToolLoanService.validateReceive(new ToolLoanService.ReceiveInput("1",3L,List.of(item,item))))
                .isInstanceOf(LedgerInputException.class);
        assertThatThrownBy(()->ToolLoanService.validateReceive(new ToolLoanService.ReceiveInput("1",3L,
                List.of(new ToolLoanService.ReceiveItem(item.toolId(),"REPAIRED",null)))))
                .isInstanceOf(LedgerInputException.class);
    }

    @Test void borrowerSubmissionAndCustodianReceiptUseTheirRegisteredPermissions() {
        assertThat(new ApplicationEntityAction("tooling","tool_loan","create").permissionCode()).isEqualTo("tool_loan.create");
        assertThat(new ApplicationEntityAction("tooling","tool_loan","receive").permissionCode()).isEqualTo("tool_loan.receive");
    }
}
