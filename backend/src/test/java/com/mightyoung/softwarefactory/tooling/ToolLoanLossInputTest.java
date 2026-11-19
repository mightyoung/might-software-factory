package com.mightyoung.softwarefactory.tooling;

import com.mightyoung.softwarefactory.ledger.LedgerInputException;
import com.mightyoung.softwarefactory.authorization.ApplicationEntityAction;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class ToolLoanLossInputTest {
    @Test void lostClosureNeedsAToolAndActualBoundedReason() {
        for(String reason:new String[]{null," ","a".repeat(4001)})
            assertThatThrownBy(()->ToolLoanService.validateLoss(new ToolLoanService.LossInput("1",2L,UUID.randomUUID(),reason)))
                    .isInstanceOf(LedgerInputException.class);
        assertThatThrownBy(()->ToolLoanService.validateLoss(new ToolLoanService.LossInput("1",2L,null,"Missing during transfer")))
                .isInstanceOf(LedgerInputException.class);
    }
    @Test void boundDefinitionAndOptimisticVersionAreRequired() {
        assertThatCode(()->ToolLoanService.validateLoss(new ToolLoanService.LossInput("old-definition",4L,UUID.randomUUID(),"Missing during transfer")))
                .doesNotThrowAnyException();
        assertThatThrownBy(()->ToolLoanService.validateLoss(new ToolLoanService.LossInput("1",null,UUID.randomUUID(),"Missing")))
                .isInstanceOf(LedgerInputException.class);
        assertThat(new ApplicationEntityAction("tooling","tool_loan","close_lost").permissionCode())
                .isEqualTo("tool_loan.close_lost");
    }
}
