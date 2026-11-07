package com.mightyoung.softwarefactory.dualprevention;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;

class FindingReviewCycleRequestParserTest {
    static final String REVIEW = "{\"expectedVersion\":7,\"reviewCycle\":2,\"stage\":\"PERSON\",\"decision\":\"RETURN_FOR_INFORMATION\",\"reason\":\"need detail\"}";
    static final String SUPPLEMENT = "{\"expectedVersion\":8,\"reviewCycle\":2,\"statement\":\"detail\"}";
    final FindingReviewCycleRequestParser parser = new FindingReviewCycleRequestParser(new ObjectMapper());
    static MockHttpServletRequest request(String body) {
        var request = new MockHttpServletRequest();
        request.setContentType("application/json;charset=UTF-8");
        request.setContent(body.getBytes(StandardCharsets.UTF_8)); return request;
    }
    @Test void acceptsAllDecisionsOnlyGradesSafetyApprovalAndNormalizesNewUnicodeText() {
        for (String stage : new String[]{"PERSON", "SAFETY"}) {
            for (String decision : new String[]{"APPROVE", "RETURN_FOR_INFORMATION", "NOT_ESTABLISHED"}) {
                String body = REVIEW.replace("PERSON", stage).replace("RETURN_FOR_INFORMATION", decision);
                if (stage.equals("SAFETY") && decision.equals("APPROVE")) body = body.replace("}", ",\"grade\":\"GENERAL\"}");
                var command = parser.review(request(body));
                assertThat(command.stage().name()).isEqualTo(stage);
                assertThat(command.decision().name()).isEqualTo(decision);
            }
        }
        assertThat(parser.review(request(REVIEW.replace("need detail", "\\u00a0review\\u3000"))).reason()).isEqualTo("review");
        assertThat(parser.supplement(request(SUPPLEMENT.replace("detail", "\\u0085detail\\u202f"))).statement()).isEqualTo("detail");
        assertThat(parser.review(request(REVIEW.replace(":7", ":9223372036854775807").replace(":2", ":2147483647"))).reviewCycle()).isEqualTo(Integer.MAX_VALUE);
    }
    @Test void rejectsUnknownDuplicateMissingNullCoercionAndMalformedTokens() {
        for (String body : new String[]{"{}", "[]", "null", REVIEW + "{}", REVIEW + " false", REVIEW + "x",
                REVIEW.replace("{", "{\"expectedVersion\":7,"), REVIEW.replace("{", "{\"reason\":\"x\","),
                REVIEW.replace("{", "{\"tenantId\":null,"), REVIEW.replace("{", "{\"actorId\":\"x\","),
                REVIEW.replace("{", "{\"memberId\":\"x\","), REVIEW.replace("{", "{\"role\":\"SAFETY\","),
                REVIEW.replace("\"stage\":\"PERSON\",", ""), REVIEW.replace("\"reason\":\"need detail\"", "\"reason\":null"),
                REVIEW.replace("\"need detail\"", "false"), REVIEW.replace("\"need detail\"", "[]"),
                REVIEW.replace("\"need detail\"", "{}"), REVIEW.replace("PERSON", "person"),
                REVIEW.replace("RETURN_FOR_INFORMATION", "RETURN"), REVIEW.replace("need detail", "\\uD800"),
                REVIEW.replace("need detail", "\\uDC00"), REVIEW.replace("need detail", "\\uD800x"), "\uFEFF" + REVIEW,
                REVIEW.replace("\"reason\"", "\"\\uD800\""), REVIEW.substring(0, REVIEW.length() - 1)}) {
            assertThatThrownBy(() -> parser.review(request(body))).as(body).isInstanceOf(FindingReviewCycleRequestParser.RequestFailure.class);
        }
        for (String number : new String[]{"0", "-1", "-0", "1.0", "1e0", "01", "null", "\"7\"", "9223372036854775808"}) {
            assertThatThrownBy(() -> parser.review(request(REVIEW.replace(":7", ":" + number)))).as(number).isInstanceOf(FindingReviewCycleRequestParser.RequestFailure.class);
            assertThatThrownBy(() -> parser.supplement(request(SUPPLEMENT.replace(":8", ":" + number)))).as(number).isInstanceOf(FindingReviewCycleRequestParser.RequestFailure.class);
        }
        for (String cycle : new String[]{"0", "-1", "1.0", "1e0", "null", "\"2\"", "2147483648"}) {
            assertThatThrownBy(() -> parser.review(request(REVIEW.replace(":2", ":" + cycle)))).isInstanceOf(FindingReviewCycleRequestParser.RequestFailure.class);
        }
    }
    @Test void rejectsJsonEscapedNullCharactersForBothNewTextFields() {
        for (String value : new String[]{"\\u0000", "before\\u0000after"}) {
            assertThatThrownBy(() -> parser.review(request(REVIEW.replace("need detail", value))))
                    .isInstanceOfSatisfying(FindingReviewCycleRequestParser.RequestFailure.class,
                            failure -> assertThat(failure.status()).isEqualTo(400));
            assertThatThrownBy(() -> parser.supplement(request(SUPPLEMENT.replace("detail", value))))
                    .isInstanceOfSatisfying(FindingReviewCycleRequestParser.RequestFailure.class,
                            failure -> assertThat(failure.status()).isEqualTo(400));
        }
    }

    @Test void rejectsEveryForbiddenGradeIncludingNullAndRequiresSafetyApprovalGrade() {
        for (String stage : new String[]{"PERSON", "SAFETY"}) {
            for (String decision : new String[]{"APPROVE", "RETURN_FOR_INFORMATION", "NOT_ESTABLISHED"}) {
                if (stage.equals("SAFETY") && decision.equals("APPROVE")) continue;
                String body = REVIEW.replace("PERSON", stage).replace("RETURN_FOR_INFORMATION", decision);
                for (String grade : new String[]{"null", "\"GENERAL\"", "\"MAJOR\""}) {
                    assertThatThrownBy(() -> parser.review(request(body.replace("}", ",\"grade\":" + grade + "}"))))
                            .isInstanceOf(FindingReviewCycleRequestParser.RequestFailure.class);
                }
            }
        }
        String safety = REVIEW.replace("PERSON", "SAFETY").replace("RETURN_FOR_INFORMATION", "APPROVE");
        assertThatThrownBy(() -> parser.review(request(safety))).isInstanceOf(FindingReviewCycleRequestParser.RequestFailure.class);
        for (String grade : new String[]{"null", "\"UNKNOWN\"", "7"}) {
            assertThatThrownBy(() -> parser.review(request(safety.replace("}", ",\"grade\":" + grade + "}"))))
                    .isInstanceOf(FindingReviewCycleRequestParser.RequestFailure.class);
        }
        assertThat(parser.review(request(safety.replace("}", ",\"grade\":\"MAJOR\"}"))).grade()).isEqualTo("MAJOR");
    }
    @Test void boundsRawUtf16TextAndBytesIncludingWorstCaseEscapedText() {
        for (String value : new String[]{"", "\\u00a0\\u3000", "x".repeat(4001), "😀".repeat(2001), " ".repeat(4000) + "x"}) {
            assertThatThrownBy(() -> parser.review(request(REVIEW.replace("need detail", value)))).isInstanceOf(FindingReviewCycleRequestParser.RequestFailure.class);
            assertThatThrownBy(() -> parser.supplement(request(SUPPLEMENT.replace("detail", value)))).isInstanceOf(FindingReviewCycleRequestParser.RequestFailure.class);
        }
        assertThat(parser.review(request(REVIEW.replace("need detail", "\\u0041".repeat(4000)))).reason()).hasSize(4000);
        assertThat(parser.supplement(request(SUPPLEMENT.replace("detail", "😀".repeat(2000)))).statement()).hasSize(4000);
        assertThat(parser.review(request(REVIEW + " ".repeat(32768 - REVIEW.length()))).expectedVersion()).isEqualTo(7);
        assertThatThrownBy(() -> parser.review(request(REVIEW + " ".repeat(32769 - REVIEW.length())))).isInstanceOf(FindingReviewCycleRequestParser.RequestFailure.class);
    }
    @Test void supplementRejectsAllNonContractFieldsAndMissingFields() {
        for (String body : new String[]{"{}", SUPPLEMENT.replace("\"reviewCycle\":2,", ""),
                SUPPLEMENT.replace("\"statement\":\"detail\"", "\"statement\":null"),
                SUPPLEMENT.replace("{", "{\"statement\":\"duplicate\","), SUPPLEMENT.replace("{", "{\"grade\":null,"),
                SUPPLEMENT.replace("{", "{\"stage\":\"PERSON\","), SUPPLEMENT.replace("{", "{\"reporterMemberId\":null,")}) {
            assertThatThrownBy(() -> parser.supplement(request(body))).isInstanceOf(FindingReviewCycleRequestParser.RequestFailure.class);
        }
    }
    @Test void rejectsMalformedUtf8AndMediaWithoutUnexpectedFailureMasking() {
        for (byte[] bytes : new byte[][]{{(byte)0xc3, (byte)0x28}, {(byte)0xed,(byte)0xa0,(byte)0x80}, {(byte)0xff}}) {
            var request = request(REVIEW); request.setContent(bytes);
            assertThatThrownBy(() -> parser.review(request)).isInstanceOf(FindingReviewCycleRequestParser.RequestFailure.class);
        }
        for (String type : new String[]{"text/plain", "application/json;charset=UTF-16", "application/json;profile=x", "application/x-www-form-urlencoded"}) {
            var request = request(REVIEW); request.setContentType(type);
            assertThatThrownBy(() -> parser.review(request)).isInstanceOf(FindingReviewCycleRequestParser.RequestFailure.class);
        }
        var repeated = new jakarta.servlet.http.HttpServletRequestWrapper(request(REVIEW)) {
            @Override public java.util.Enumeration<String> getHeaders(String name) {
                return name.equalsIgnoreCase("Content-Type") ? java.util.Collections.enumeration(java.util.List.of("application/json", "application/json")) : super.getHeaders(name);
            }
        };
        assertThatThrownBy(() -> parser.review(repeated)).isInstanceOf(FindingReviewCycleRequestParser.RequestFailure.class);
        var mapper = org.mockito.Mockito.spy(new ObjectMapper());
        org.mockito.Mockito.doThrow(new NullPointerException("defect")).when(mapper).createParser(org.mockito.ArgumentMatchers.anyString());
        assertThatThrownBy(() -> new FindingReviewCycleRequestParser(mapper).review(request(REVIEW))).isInstanceOf(NullPointerException.class);
    }
    @Test @SuppressWarnings("unchecked") void openApiKeepsLegacyWireAndDefinesStrictV3RequestsAndHistoricalCycleResult() throws Exception {
        java.util.Map<String,Object> document = new org.yaml.snakeyaml.Yaml().load(java.nio.file.Files.readString(
                java.nio.file.Path.of("..", "contracts", "openapi", "finding-workflow.yaml")));
        var paths = (java.util.Map<String,Object>) document.get("paths");
        for (String route : new String[]{"review-decisions", "supplements"}) {
            var path = (java.util.Map<String,Object>)paths.get("/api/v1/findings/{id}/" + route);
            var post = (java.util.Map<String,Object>)path.get("post");
            assertThat((java.util.Map<String,Object>)post.get("responses")).containsOnlyKeys("200", "400", "401", "403", "404", "409", "413", "415", "422", "503");
        }
        var components = (java.util.Map<String,Object>)document.get("components");
        var schemas = (java.util.Map<String,Object>)components.get("schemas");
        for (String name : new String[]{"PersonReviewDecisionRequest", "SafetyApprovalDecisionRequest", "SafetyOtherDecisionRequest", "ReportSupplementRequest"}) {
            var request = (java.util.Map<String,Object>)schemas.get(name);
            assertThat(request.get("additionalProperties")).isEqualTo(false);
            var properties = (java.util.Map<String,Object>)request.get("properties");
            if (name.equals("ReportSupplementRequest")) assertThat(properties).containsOnlyKeys("expectedVersion", "reviewCycle", "statement");
            else if (name.equals("SafetyApprovalDecisionRequest")) assertThat(properties).containsOnlyKeys("expectedVersion", "reviewCycle", "stage", "decision", "reason", "grade");
            else assertThat(properties).containsOnlyKeys("expectedVersion", "reviewCycle", "stage", "decision", "reason");
        }
        var response = (java.util.Map<String,Object>)schemas.get("ReviewCycleCommandResponse");
        var properties = (java.util.Map<String,Object>)response.get("properties");
        assertThat(properties).containsOnlyKeys("data", "meta");
        var data = (java.util.Map<String,Object>)properties.get("data");
        assertThat((java.util.Map<String,Object>)data.get("properties")).containsOnlyKeys("findingId", "code", "state", "roundNo", "version", "reviewCycle");
        var legacy = (java.util.Map<String,Object>)schemas.get("CommandResponse");
        var legacyProperties = (java.util.Map<String,Object>)legacy.get("properties");
        assertThat(legacyProperties).containsOnlyKeys("data");
        var legacyData = (java.util.Map<String,Object>)legacyProperties.get("data");
        assertThat((java.util.Map<String,Object>)legacyData.get("properties")).doesNotContainKey("reviewCycle");
    }

}
