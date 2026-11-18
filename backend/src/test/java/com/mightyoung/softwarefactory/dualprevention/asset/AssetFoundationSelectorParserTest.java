package com.mightyoung.softwarefactory.dualprevention.asset;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class AssetFoundationSelectorParserTest {
    private final AssetFoundationSelectorParser parser = new AssetFoundationSelectorParser(JsonMapper.builder().build());
    private static final String ID = "a1234567-1234-1234-1234-123456789abc";

    @Test void emptyLiteralAndDefaultLimitAreValidAndNullCursorIsEquivalentToOmitted() {
        var first = parser.reference(request("{\"query\":\"\"}"));
        var second = parser.reference(request("{\"query\":\"\",\"afterId\":null}"));
        assertThat(first).isEqualTo(second);
        assertThat(first.limit()).isEqualTo(20);
        assertThat(first.afterId()).isNull();
    }
    @Test void countsSupplementaryUnicodeAsCodePointsAndAppliesRawByteLimitSeparately() {
        String accepted = "😀".repeat(100);
        assertThat(parser.reference(request("{\"query\":\"" + accepted + "\"}")).query()).isEqualTo(accepted);
        rejects("{\"query\":\"" + accepted + "😀\"}", 400);
        String padded = "{\"query\":\"\"}" + " ".repeat(2048 - 12);
        assertThat(padded.getBytes(StandardCharsets.UTF_8)).hasSize(2048);
        assertThat(parser.reference(request(padded)).query()).isEmpty();
        rejects(padded + " ", 413);
    }
    @ParameterizedTest
    @ValueSource(strings = {"{}", "[]", "{\"query\":null}", "{\"query\":1}", "{\"query\":{}}",
            "{\"query\":\"a\",\"query\":\"b\"}", "{\"query\":\"\",\"afterId\":null,\"afterId\":null}",
            "{\"query\":\"\",\"unknown\":null}", "{\"query\":\"\"}{}", "{\"query\":\"\\u0000\"}",
            "{\"query\":\"\\uD800\"}", "{\"query\":\"\\uDC00\"}", "{\"query\":\"\",\"limit\":0}",
            "{\"query\":\"\",\"limit\":51}", "{\"query\":\"\",\"limit\":1.0}", "{\"query\":\"\",\"limit\":1e0}",
            "{\"query\":\"\",\"limit\":-0}", "{\"query\":\"\",\"limit\":null}", "{\"query\":\"\",\"limit\":\"20\"}"})
    void rejectsMalformedUnknownDuplicateAndCoercedTokens(String json) { rejects(json, 400); }
    @Test void fixedDtoShapesDoNotAcceptAnAlternativeAnchorOrNoncanonicalUuid() {
        assertThat(parser.anchor(request("{\"query\":\"\",\"organizationId\":\"" + ID + "\"}")).organizationId()).hasToString(ID);
        assertThat(parser.position(request("{\"query\":\"\",\"orgId\":\"" + ID + "\"}")).organizationId()).hasToString(ID);
        assertThatThrownBy(() -> parser.anchor(request("{\"query\":\"\",\"orgId\":\"" + ID + "\"}"))).isInstanceOf(AssetFoundationFailure.class);
        rejects("{\"query\":\"\",\"organizationId\":\"" + ID + "\"}", 400);
        rejects("{\"query\":\"\",\"afterId\":\"" + ID.toUpperCase() + "\"}", 400);
    }
    @Test void rejectsInvalidUtf8BomAndUnsupportedOrDuplicateContentType() {
        var broken = request(""); broken.setContent(new byte[] {(byte) 0xc3, 0x28});
        assertThatThrownBy(() -> parser.reference(broken)).isInstanceOfSatisfying(AssetFoundationFailure.class, e -> assertThat(e.status()).isEqualTo(400));
        rejects("\uFEFF{\"query\":\"\"}", 400);
        var type = request("{\"query\":\"\"}"); type.setContentType("application/json;charset=UTF-16");
        assertThatThrownBy(() -> parser.reference(type)).isInstanceOfSatisfying(AssetFoundationFailure.class, e -> assertThat(e.status()).isEqualTo(415));
        var duplicate = org.mockito.Mockito.spy(request("{\"query\":\"\"}"));
        org.mockito.Mockito.doReturn(java.util.Collections.enumeration(java.util.List.of("application/json", "application/json")))
                .when(duplicate).getHeaders("Content-Type");
        assertThatThrownBy(() -> parser.reference(duplicate)).isInstanceOfSatisfying(AssetFoundationFailure.class, e -> assertThat(e.status()).isEqualTo(415));
    }
    private void rejects(String json, int status) {
        assertThatThrownBy(() -> parser.reference(request(json))).isInstanceOfSatisfying(AssetFoundationFailure.class,
                error -> assertThat(error.status()).isEqualTo(status));
    }
    static MockHttpServletRequest request(String json) {
        var request = new MockHttpServletRequest("POST", "/api/v1/entities/workshop/references/create-organization/query");
        request.setContentType("application/json"); request.setContent(json.getBytes(StandardCharsets.UTF_8)); return request;
    }
}
