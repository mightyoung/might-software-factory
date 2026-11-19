package com.mightyoung.softwarefactory.tooling;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import com.mightyoung.softwarefactory.ledger.*;
class ToolLoanInputTest {
    private ToolLoanService.Input input(List<UUID> ids,Long version) {return new ToolLoanService.Input("1",UUID.randomUUID(),UUID.randomUUID(),"Production test",ids,version);}
    @Test void distinctItemsAreAcceptedWithoutReservingThem() {assertThatCode(()->ToolLoanService.validate(input(List.of(UUID.randomUUID(),UUID.randomUUID()),null),"CREATE")).doesNotThrowAnyException();}
    @Test void repeatedAndNullItemsIdentifyTheirLine() {
        UUID id=UUID.randomUUID();
        assertThatThrownBy(()->ToolLoanService.validate(input(List.of(id,id),null),"CREATE")).isInstanceOfSatisfying(LedgerInputException.class,e->assertThat(e.field()).isEqualTo("toolIds[1]"));
        assertThatThrownBy(()->ToolLoanService.validate(input(Arrays.asList(id,null),null),"CREATE")).isInstanceOf(LedgerInputException.class);
    }
    @Test void emptyAndOversizeApplicationsAreRejected() {for(List<UUID> rows:List.of(List.<UUID>of(),Collections.nCopies(101,UUID.randomUUID())))assertThatThrownBy(()->ToolLoanService.validate(input(rows,null),"CREATE")).isInstanceOf(LedgerInputException.class);}
    @Test void updatesRequireAUsableVersionAndCreatesRejectOne() {
        assertThatThrownBy(()->ToolLoanService.validate(input(List.of(UUID.randomUUID()),null),"UPDATE")).isInstanceOf(LedgerInputException.class);
        assertThatThrownBy(()->ToolLoanService.validate(input(List.of(UUID.randomUUID()),1L),"CREATE")).isInstanceOf(LedgerInputException.class);
    }
    @Test void withdrawalCannotCarryReplacementFields() {assertThatThrownBy(()->ToolLoanService.validate(input(List.of(UUID.randomUUID()),1L),"WITHDRAW")).isInstanceOf(LedgerInputException.class);assertThatCode(()->ToolLoanService.validate(new ToolLoanService.Input("1",null,null,null,null,1L),"WITHDRAW")).doesNotThrowAnyException();}
}
