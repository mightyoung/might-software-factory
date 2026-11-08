package com.mightyoung.softwarefactory.dualprevention;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;

class FindingEvidenceSetInitializationRequestParserTest {
    static final UUID FINDING = UUID.fromString("40000000-0000-0000-0000-000000000001");
    static final UUID KEY = UUID.fromString("60000000-0000-0000-0000-000000000001");
    static final String BODY = "{\"expectedFindingVersion\":7}";
    final FindingEvidenceSetInitializationRequestParser parser = new FindingEvidenceSetInitializationRequestParser(new ObjectMapper());
    static MockHttpServletRequest request(String body) {
        var request = new MockHttpServletRequest(); request.setContentType("application/json;charset=UTF-8");
        request.setContent(body.getBytes(StandardCharsets.UTF_8)); return request;
    }
    @Test void acceptsZeroInt64MaximumAndExactByteLimit() {
        for (long version : new long[]{0, Long.MAX_VALUE}) {
            assertThat(parser.parse(request(BODY.replace(":7", ":" + version)), FINDING, 2, KEY).expectedFindingVersion()).isEqualTo(version);
        }
        assertThat(parser.parse(request(BODY + " ".repeat(1024 - BODY.length())), FINDING, 2, KEY).idempotencyKey()).isEqualTo(KEY);
    }
    @Test void rejectsMissingDuplicateUnknownIdentityNullCoercionNestedTrailingAndInvalidNumbers() {
        for (String body : new String[]{"{}", "[]", BODY + "{}", BODY.replace("{", "{\"expectedFindingVersion\":7,"),
                BODY.replace("{", "{\"tenantId\":\"x\","), BODY.replace("{", "{\"memberId\":\"x\","),
                BODY.replace(":7", ":null"), BODY.replace(":7", ":\"7\""), BODY.replace(":7", ":{}"),
                BODY.replace(":7", ":[]"), BODY.replace(":7", ":-1"), BODY.replace(":7", ":-0"),
                BODY.replace(":7", ":7.0"), BODY.replace(":7", ":7e0"), BODY.replace(":7", ":01"),
                BODY.replace(":7", ":9223372036854775808"), "\uFEFF" + BODY,
                BODY.replace("expectedFindingVersion", "\\uD800"), BODY.replace(":7", ":\"\\uDC00\"")}) {
            assertThatThrownBy(() -> parser.parse(request(body), FINDING, 2, KEY)).as(body)
                    .isInstanceOfSatisfying(FindingEvidenceSetInitializationRequestParser.RequestFailure.class, e -> assertThat(e.status()).isEqualTo(400));
        }
    }
    @Test void rejectsOversizeAndMalformedEncodingOrMediaBeforeParsing() {
        assertThatThrownBy(() -> parser.parse(request(" ".repeat(1025)), FINDING, 2, KEY))
                .isInstanceOfSatisfying(FindingEvidenceSetInitializationRequestParser.RequestFailure.class, e -> assertThat(e.status()).isEqualTo(413));
        var malformed = request(BODY); malformed.setContent(new byte[]{(byte)0xc3, (byte)0x28});
        assertThatThrownBy(() -> parser.parse(malformed, FINDING, 2, KEY)).isInstanceOfSatisfying(FindingEvidenceSetInitializationRequestParser.RequestFailure.class, e -> assertThat(e.status()).isEqualTo(400));
        for (String type : new String[]{"text/plain", "application/*", "application/json;charset=UTF-16", "application/json;profile=x", "application/json;charset=UTF-16;charset=UTF-8"}) {
            var request = request(BODY); request.setContentType(type);
            assertThatThrownBy(() -> parser.parse(request, FINDING, 2, KEY)).isInstanceOfSatisfying(FindingEvidenceSetInitializationRequestParser.RequestFailure.class, e -> assertThat(e.status()).isEqualTo(415));
        }
        var repeated = new jakarta.servlet.http.HttpServletRequestWrapper(request(BODY)) {
            @Override public java.util.Enumeration<String> getHeaders(String name) {
                return name.equalsIgnoreCase("Content-Type") ? java.util.Collections.enumeration(java.util.List.of("application/json", "application/json")) : super.getHeaders(name);
            }
        };
        assertThatThrownBy(() -> parser.parse(repeated, FINDING, 2, KEY)).isInstanceOfSatisfying(FindingEvidenceSetInitializationRequestParser.RequestFailure.class, e -> assertThat(e.status()).isEqualTo(415));
    }
    @Test void unexpectedMapperFailurePropagates() {
        var mapper = org.mockito.Mockito.spy(new ObjectMapper());
        org.mockito.Mockito.doThrow(new NullPointerException("internal defect")).when(mapper).createParser(org.mockito.ArgumentMatchers.anyString());
        assertThatThrownBy(() -> new FindingEvidenceSetInitializationRequestParser(mapper).parse(request(BODY), FINDING, 2, KEY))
                .isInstanceOf(NullPointerException.class).hasMessage("internal defect");
    }
    @Test @SuppressWarnings("unchecked") void openApiDocumentsOnlyInitializationFactsAndNoAdvertisedReadOrByteEndpoint() throws Exception {
        java.util.Map<String,Object> document = new org.yaml.snakeyaml.Yaml().load(java.nio.file.Files.readString(
                java.nio.file.Path.of("..", "contracts", "openapi", "finding-evidence-set-initialization.yaml")));
        var paths = (java.util.Map<String,Object>) document.get("paths");
        assertThat(paths).containsOnlyKeys("/api/v1/findings/{findingId}/remediation-rounds/{roundNo}/evidence-sets");
        var route = (java.util.Map<String,Object>) paths.values().iterator().next();
        assertThat(route).containsOnlyKeys("parameters", "post");
        var post = (java.util.Map<String,Object>) route.get("post");
        assertThat((java.util.Map<String,Object>)post.get("responses")).containsKeys("201", "200", "400", "413", "415", "429", "503").doesNotContainKey("202");
        var components = (java.util.Map<String,Object>) document.get("components");
        var schemas = (java.util.Map<String,Object>) components.get("schemas");
        var request = (java.util.Map<String,Object>)schemas.get("InitializeRequest");
        assertThat(request.get("additionalProperties")).isEqualTo(false);
        assertThat((java.util.Map<String,Object>)request.get("properties")).containsOnlyKeys("expectedFindingVersion");
        var data = (java.util.Map<String,Object>)schemas.get("InitializationData");
        assertThat((java.util.Map<String,Object>)data.get("properties")).containsOnlyKeys("evidenceSetId", "state", "initialEvidenceRevision", "createdAt");
    }
}
