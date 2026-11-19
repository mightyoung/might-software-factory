package com.mightyoung.softwarefactory.tooling;

import com.mightyoung.softwarefactory.authorization.ApplicationEntityAction;
import com.mightyoung.softwarefactory.ledger.LedgerException;
import com.mightyoung.softwarefactory.ledger.LedgerInputException;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ToolLoanIssueInputTest {
    @Test void issueRequiresAnExplicitUsableRevision() {
        for(Long version:new Long[]{null,0L,-1L,Long.MAX_VALUE}) {
            assertThatThrownBy(() -> ToolLoanService.validateIssue(new ToolLoanService.IssueInput("1",version)))
                    .isInstanceOf(LedgerInputException.class);
        }
        assertThatCode(() -> ToolLoanService.validateIssue(new ToolLoanService.IssueInput("1",9L)))
                .doesNotThrowAnyException();
    }

    @Test void definitionConflictIsExplicitBeforeAnyCommandEffects() {
        assertThatThrownBy(() -> ToolLoanService.validateIssue(new ToolLoanService.IssueInput("3",1L)))
                .isInstanceOfSatisfying(LedgerException.class,error -> {
                    assertThat(error.status()).isEqualTo(409);
                    assertThat(error.code()).isEqualTo("DEFINITION_CONFLICT");
                });
    }

    @Test void issuanceIsAnIndependentPermission() {
        assertThat(new ApplicationEntityAction("tooling","tool_loan","issue").permissionCode())
                .isEqualTo("tool_loan.issue");
        assertThatThrownBy(() -> new ApplicationEntityAction("tooling","tool_loan","delete"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
