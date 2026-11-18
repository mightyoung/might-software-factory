package com.mightyoung.softwarefactory;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class RiskScenarioHeaderBudgetTest {
    @Test void usesAConservativeStatusAllowanceAndCountsEveryCookieLine() {
        assertThat(RiskScenarioHeaderBudget.bytes(200, Map.of())).isEqualTo(66);
        assertThat(RiskScenarioHeaderBudget.bytes(599, Map.of("Set-Cookie", List.of("a", "b"))))
                .isEqualTo(96);
        assertThat(RiskScenarioHeaderBudget.bytes(100, Map.of("X", List.of("", "\t ~"))))
                .isEqualTo(79);
    }

    @Test void admitsExactBoundaryAndRejectsOneAdditionalByte() {
        var exact = Map.of("X", List.of("a".repeat(16313)));
        assertThat(RiskScenarioHeaderBudget.bytes(201, exact)).isEqualTo(16384);
        assertThatCode(() -> RiskScenarioHeaderBudget.requireFits(201, exact, 0)).doesNotThrowAnyException();
        rejected(() -> RiskScenarioHeaderBudget.bytes(201, Map.of("X", List.of("a".repeat(16314)))));
        rejected(() -> RiskScenarioHeaderBudget.requireFits(201, exact, 1));
    }

    @Test void includesTheExplicitContainerReserveWithoutIntegerOverflow() {
        assertThatCode(() -> RiskScenarioHeaderBudget.requireFits(200, Map.of(), 16318)).doesNotThrowAnyException();
        rejected(() -> RiskScenarioHeaderBudget.requireFits(200, Map.of(), 16319));
        rejected(() -> RiskScenarioHeaderBudget.requireFits(200, Map.of(), -1));
        rejected(() -> RiskScenarioHeaderBudget.requireFits(200, Map.of(), Integer.MAX_VALUE));
    }

    @ParameterizedTest @ValueSource(ints = {-1, 0, 99, 600, Integer.MAX_VALUE})
    void rejectsInvalidStatus(int status) { rejected(() -> RiskScenarioHeaderBudget.bytes(status, Map.of())); }

    @ParameterizedTest @ValueSource(strings = {"", " X", "X ", "X:Y", "X\tY", "X\rY", "X\nY", "X/", "请求", "X\u007f"})
    void rejectsNonTokenHeaderNames(String name) {
        rejected(() -> RiskScenarioHeaderBudget.bytes(200, Map.of(name, List.of("safe"))));
    }

    @ParameterizedTest @ValueSource(strings = {"a\r\nb", "a\nb", "a\rb", "\u0000", "\u0001", "\u001f", "\u007f", "é", "请求", "😀"})
    void rejectsUnsafeOrNonAsciiValues(String value) {
        rejected(() -> RiskScenarioHeaderBudget.bytes(200, Map.of("X", List.of(value))));
    }

    @Test void acceptsAllAsciiTokenPunctuation() {
        assertThatCode(() -> RiskScenarioHeaderBudget.bytes(200,
                Map.of("!#$%&'*+-.^_`|~AZaz09", List.of("\t !~")))).doesNotThrowAnyException();
    }

    @Test void rejectsNullAndMissingHeaderStructure() {
        rejected(() -> RiskScenarioHeaderBudget.bytes(200, null));
        var headers = new HashMap<String, List<String>>();
        headers.put(null, List.of("value"));
        rejected(() -> RiskScenarioHeaderBudget.bytes(200, headers));
        headers.clear(); headers.put("X", null);
        rejected(() -> RiskScenarioHeaderBudget.bytes(200, headers));
        headers.put("X", List.of());
        rejected(() -> RiskScenarioHeaderBudget.bytes(200, headers));
        headers.put("X", Collections.singletonList(null));
        rejected(() -> RiskScenarioHeaderBudget.bytes(200, headers));
    }

    @Test void rejectsHugeVirtualListsAndLongNamesWithoutAllocatingWireBytes() {
        rejected(() -> RiskScenarioHeaderBudget.bytes(200,
                Map.of("X", Collections.nCopies(Integer.MAX_VALUE, ""))));
        rejected(() -> RiskScenarioHeaderBudget.bytes(200, Map.of("X".repeat(16385), List.of(""))));
    }

    private static void rejected(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isExactlyInstanceOf(RiskScenarioHeaderBudget.Rejected.class)
                .hasMessage("Risk response headers exceed the admitted boundary");
    }
}
