package com.mightyoung.softwarefactory.dualprevention;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class FindingOrganizationReferenceRequestParserTest {
    private final FindingOrganizationReferenceRequestParser parser = new FindingOrganizationReferenceRequestParser(JsonMapper.builder().build());
    private static MockHttpServletRequest request(String body) {
        var request = new MockHttpServletRequest(); request.setContentType("application/json");
        request.setContent(body.getBytes(StandardCharsets.UTF_8)); return request;
    }
    private void rejects(String body, int status) {
        assertThatThrownBy(() -> parser.parse(request(body)))
                .isInstanceOfSatisfying(FindingOrganizationReferenceRequestParser.RequestFailure.class,
                        failure -> assertThat(failure.status()).isEqualTo(status));
    }
    @Test void defaultsOnlyAnOmittedLimitAndRetainsLiteralSearchAndCanonicalCursor() {
        var parsed = parser.parse(request("{\"query\":\"工厂%_\\\\\",\"afterId\":null}"));
        assertThat(parsed.query()).isEqualTo("工厂%_\\"); assertThat(parsed.limit()).isEqualTo(20); assertThat(parsed.afterId()).isNull();
        assertThat(parser.parse(request("{\"query\":\"\",\"limit\":50,\"afterId\":\"10000000-0000-0000-0000-000000000001\"}")).afterId())
                .hasToString("10000000-0000-0000-0000-000000000001");
    }
    @Test void rejectsMissingDuplicateUnknownCoercedAndUnboundedValues() {
        for (String body : new String[]{"{}", "[]", "{\"query\":null}", "{\"query\":1}",
                "{\"query\":\"\",\"query\":\"x\"}", "{\"query\":\"\",\"tenantId\":\"x\"}",
                "{\"query\":\"\",\"limit\":null}", "{\"query\":\"\",\"limit\":\"20\"}",
                "{\"query\":\"\",\"limit\":0}", "{\"query\":\"\",\"limit\":51}",
                "{\"query\":\"\",\"limit\":1.0}", "{\"query\":\"\",\"afterId\":\"1-1-1-1-1\"}",
                "{\"query\":\"\"} {}", "{\"query\":\"\\u0000\"}", "{\"query\":\"\\ud800\"}"}) rejects(body, 400);
    }
    @Test void enforcesUnicodeCodePointsInsteadOfUtf16UnitsAndBodyBytes() {
        assertThat(parser.parse(request("{\"query\":\"" + "😀".repeat(100) + "\"}")).query().codePointCount(0, 200)).isEqualTo(100);
        rejects("{\"query\":\"" + "😀".repeat(101) + "\"}", 400);
        rejects(" ".repeat(2049), 413);
        var invalid = request(""); invalid.setContent(new byte[]{(byte) 0xc3, (byte) 0x28});
        assertThatThrownBy(() -> parser.parse(invalid)).isInstanceOf(FindingOrganizationReferenceRequestParser.RequestFailure.class);
        rejects("\uFEFF{\"query\":\"\"}", 400);
    }
    @Test void permitsOnlyTheDeclaredUtf8JsonMediaAndNoQueryParameters() {
        var good = request("{\"query\":\"\"}"); good.setContentType("application/json; charset=UTF-8");
        assertThat(parser.parse(good).limit()).isEqualTo(20);
        for (String type : new String[]{"text/plain", "application/json;charset=UTF-16", "application/json;profile=x"}) {
            var bad = request("{\"query\":\"\"}"); bad.setContentType(type);
            assertThatThrownBy(() -> parser.parse(bad)).isInstanceOfSatisfying(FindingOrganizationReferenceRequestParser.RequestFailure.class,
                    failure -> assertThat(failure.status()).isEqualTo(415));
        }
        var bad = request("{\"query\":\"\"}"); bad.setQueryString("limit=5");
        assertThatThrownBy(() -> parser.parse(bad)).isInstanceOf(FindingOrganizationReferenceRequestParser.RequestFailure.class);
    }
}
