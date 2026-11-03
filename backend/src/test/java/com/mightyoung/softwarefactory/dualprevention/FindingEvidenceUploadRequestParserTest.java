package com.mightyoung.softwarefactory.dualprevention;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;

class FindingEvidenceUploadRequestParserTest {
    static final UUID FINDING = UUID.fromString("40000000-0000-0000-0000-000000000001");
    static final UUID KEY = UUID.fromString("60000000-0000-0000-0000-000000000001");
    static final String BODY = "{\"evidenceSetId\":\"51000000-0000-0000-0000-000000000001\",\"expectedFindingVersion\":7,\"expectedEvidenceRevision\":3,\"filename\":\"证据.pdf\",\"declaredMediaType\":\"application/pdf\",\"declaredSizeBytes\":100}";
    final FindingEvidenceUploadRequestParser parser = new FindingEvidenceUploadRequestParser(new ObjectMapper());
    static MockHttpServletRequest request(String body) {
        var request = new MockHttpServletRequest(); request.setContentType("application/json;charset=UTF-8");
        request.setContent(body.getBytes(StandardCharsets.UTF_8)); return request;
    }
    @Test void acceptsExactFieldsAndInt64BoundsWithoutChangingDisplayName() {
        var value = parser.parse(request(BODY.replace("\"expectedFindingVersion\":7", "\"expectedFindingVersion\":9223372036854775807")), FINDING, 2, KEY);
        assertThat(value.expectedFindingVersion()).isEqualTo(Long.MAX_VALUE);
        assertThat(value.filename()).isEqualTo("证据.pdf");
        assertThat(value.idempotencyKey()).isEqualTo(KEY);
    }
    @Test void rejectsDuplicateUnknownMissingNullCoercionNestedTrailingExponentAndOverflow() {
        for (String body : new String[]{"{}", "[]", BODY + "{}", BODY.replace("{", "{\"tenantId\":1,"),
                BODY.replace("{", "{\"filename\":\"x\","), BODY.replace("\"filename\":\"证据.pdf\"", "\"filename\":null"),
                BODY.replace("\"filename\":\"证据.pdf\"", "\"filename\":[]"), BODY.replace(":7", ":\"7\""),
                BODY.replace(":7", ":7.0"), BODY.replace(":7", ":7e0"), BODY.replace(":7", ":-0"),
                BODY.replace(":7", ":9223372036854775808"), BODY.replace(":7", ":01"), "\uFEFF" + BODY,
                BODY.replace("证据.pdf", "\\uD800"), BODY.replace("证据.pdf", "\\uDC00"),
                BODY.replace("证据.pdf", "../private"), BODY.replace("证据.pdf", "a\\\\b"),
                BODY.replace("application/pdf", "application/pdf; charset=utf-8"), BODY.replace("51000000-", "5100000-")}) {
            assertThatThrownBy(() -> parser.parse(request(body), FINDING, 2, KEY)).as(body)
                    .isInstanceOfSatisfying(FindingEvidenceUploadRequestParser.RequestFailure.class, e -> assertThat(e.status()).isEqualTo(400));
        }
    }
    @Test void enforcesCodepointSizeAndByteBounds() {
        assertThat(parser.parse(request(BODY.replace("证据.pdf", "🌟".repeat(255))), FINDING, 2, KEY).filename()).hasSize(510);
        for (String body : new String[]{BODY.replace("证据.pdf", "🌟".repeat(256)), BODY.replace(":100", ":0")})
            assertThatThrownBy(() -> parser.parse(request(body), FINDING, 2, KEY)).isInstanceOf(FindingEvidenceUploadRequestParser.RequestFailure.class);
        for (String body : new String[]{" ".repeat(4097), BODY.replace(":100", ":67108865")})
            assertThatThrownBy(() -> parser.parse(request(body), FINDING, 2, KEY)).isInstanceOfSatisfying(FindingEvidenceUploadRequestParser.RequestFailure.class, e -> assertThat(e.status()).isEqualTo(413));
        assertThat(parser.parse(request(BODY + " ".repeat(4096 - BODY.getBytes(StandardCharsets.UTF_8).length)), FINDING, 2, KEY)).isNotNull();
    }
    @Test @SuppressWarnings("unchecked") void openApiMatchesIntentOnlyBoundary() throws Exception {
        java.util.Map<String,Object> document = new org.yaml.snakeyaml.Yaml().load(java.nio.file.Files.readString(
                java.nio.file.Path.of("..", "contracts", "openapi", "finding-evidence-upload.yaml")));
        var paths = (java.util.Map<String,Object>) document.get("paths");
        assertThat(paths).hasSize(2);
        var reserve = (java.util.Map<String,Object>) paths.get("/api/v1/findings/{findingId}/remediation-rounds/{roundNo}/evidence-uploads");
        var post = (java.util.Map<String,Object>) reserve.get("post");
        assertThat((java.util.Map<String,Object>) post.get("responses")).containsKeys("201", "200", "400", "413", "415", "429", "503").doesNotContainKey("202");
        var components = (java.util.Map<String,Object>) document.get("components");
        var schemas = (java.util.Map<String,Object>) components.get("schemas");
        var declaration = (java.util.Map<String,Object>) schemas.get("Declaration");
        assertThat(declaration.get("additionalProperties")).isEqualTo(false);
        assertThat((java.util.List<String>) declaration.get("required")).containsExactlyInAnyOrder("evidenceSetId", "expectedFindingVersion", "expectedEvidenceRevision", "filename", "declaredMediaType", "declaredSizeBytes");
        var status = (java.util.Map<String,Object>) schemas.get("StatusData");
        assertThat((java.util.Map<String,Object>) status.get("properties")).containsOnlyKeys("uploadIntentId", "state", "expiresAt", "recoveryAction");
    }
    @Test void unexpectedParserFailureIsNotReportedAsClientInput() {
        var mapper = org.mockito.Mockito.spy(new ObjectMapper());
        org.mockito.Mockito.doThrow(new NullPointerException("unexpected mapper defect"))
                .when(mapper).createParser(org.mockito.ArgumentMatchers.anyString());
        var strict = new FindingEvidenceUploadRequestParser(mapper);
        assertThatThrownBy(() -> strict.parse(request(BODY), FINDING, 2, KEY))
                .isInstanceOf(NullPointerException.class).hasMessage("unexpected mapper defect");
    }
    @Test void rejectsMalformedUtf8AndAmbiguousMediaContract() {
        var malformed = request(BODY); malformed.setContent(new byte[]{(byte)0xc3, (byte)0x28});
        assertThatThrownBy(() -> parser.parse(malformed, FINDING, 2, KEY)).isInstanceOf(FindingEvidenceUploadRequestParser.RequestFailure.class);
        for (String type : new String[]{"text/plain", "application/*", "application/json;charset=UTF-16", "application/json;profile=x", "application/json;charset=UTF-16;charset=UTF-8"}) {
            var request = request(BODY); request.setContentType(type);
            assertThatThrownBy(() -> parser.parse(request, FINDING, 2, KEY)).isInstanceOfSatisfying(FindingEvidenceUploadRequestParser.RequestFailure.class, e -> assertThat(e.status()).isEqualTo(415));
        }
    }
}
