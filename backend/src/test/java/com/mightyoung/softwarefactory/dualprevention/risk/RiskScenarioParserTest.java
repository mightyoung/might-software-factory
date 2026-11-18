package com.mightyoung.softwarefactory.dualprevention.risk;

import com.mightyoung.softwarefactory.applicationregistry.definition.EntityDefinition.Use;
import com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.Direction;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.json.JsonMapper;
import static com.mightyoung.softwarefactory.dualprevention.risk.RiskScenarioContracts.*;
import static org.junit.jupiter.api.Assertions.*;

class RiskScenarioParserTest {
    private static final String ID = "00112233-4455-6677-8899-aabbccddeeff";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final RiskScenarioParser parser = new RiskScenarioParser(JSON);

    @Test void parsesEntireCreateAndUpdateWithoutTrimmingAndCanonicalizesUnorderedIds() {
        var value = parser.create(request(createBody(" d ")));
        assertEquals(" d ", value.description()); assertEquals(List.of(UUID.fromString(ID)), value.hazardIds());
        var update = parser.update(request(updateBody("9007199254740991")));
        assertEquals(MAX_VERSION, update.expectedVersion()); assertEquals("t", update.triggeringConditions());
        assertEquals(409, assertThrows(Failure.class, () -> nextVersion(update.expectedVersion())).status());
    }
    @ParameterizedTest @ValueSource(strings={"0", "-1", "1.0", "1e0", "\"1\"", "9007199254740992", "99999999999999999999999", "null"})
    void rejectsNonIntegerTokensAndUnsafeVersions(String token) {
        assertEquals(400, assertThrows(Failure.class, () -> parser.update(request(updateBody(token)))).status());
    }
    @Test void rejectsDuplicateUnknownAuthorityAndImmutableUpdateInputsEvenWhenNull() {
        String valid = createBody("d");
        assertThrows(Failure.class, () -> parser.create(request(valid.replace("\"code\":\"R_1\"", "\"code\":\"R_1\",\"code\":\"R_2\""))));
        for (String name : List.of("actorId", "tenantId", "grade", "score", "status", "assessmentId", "accidentCodeRef", "visibility", "relationshipsLoaded"))
            assertThrows(Failure.class, () -> parser.create(request(valid.substring(0, valid.length()-1) + ",\"" + name + "\":null}")));
        for (String name : List.of("code", "organizationId")) {
            String body = updateBody("1");
            assertThrows(Failure.class, () -> parser.update(request(body.substring(0, body.length()-1) + ",\"" + name + "\":null}")));
        }
    }
    @Test void narrativeCodePointBoundsAndEntireBodyByteBoundAreIndependent() {
        assertEquals("𠀀".repeat(20_000), parser.create(request(createBody("𠀀".repeat(20_000)))).description());
        assertThrows(Failure.class, () -> parser.create(request(createBody("a".repeat(20_001)))));
        String base = createBody("d");
        assertNotNull(parser.create(request(base + " ".repeat(COMMAND_BODY_BYTES - base.getBytes(StandardCharsets.UTF_8).length))));
        assertEquals(413, assertThrows(Failure.class, () -> parser.create(request(base + " ".repeat(COMMAND_BODY_BYTES)))).status());
    }
    @Test void rejectsEmptyDuplicateAndOversizedHazardSets() {
        String base = createBody("d");
        for (String ids : List.of("[]", "null", "[null]", "[\""+ID+"\",\""+ID+"\"]",
                "["+String.join(",", Collections.nCopies(101, "\""+ID+"\""))+"]"))
            assertThrows(Failure.class, () -> parser.create(request(base.replace("[\""+ID+"\"]", ids))));
    }
    @Test void rejectsMalformedUnicodeBomEncodingTrailingRootsAndNestedDuplicates() {
        String base = createBody("x");
        for (String replacement : List.of("\"\\ud800\"", "\"\\udc00\"", "\"\\u0000\"", "{}", "[]"))
            assertThrows(Failure.class, () -> parser.create(request(base.replace("\"description\":\"x\"", "\"description\":"+replacement))));
        assertThrows(Failure.class, () -> parser.create(request("\uFEFF"+base)));
        assertThrows(Failure.class, () -> parser.create(request(base+"{}")));
        var malformed = request(base); malformed.setContent(new byte[]{(byte) 0xc3, 0x28});
        assertThrows(Failure.class, () -> parser.create(malformed));
        var utf16 = request(base); utf16.setContent(base.getBytes(StandardCharsets.UTF_16LE));
        assertThrows(Failure.class, () -> parser.create(utf16));
        assertThrows(Failure.class, () -> parser.query(request("{\"size\":20,\"page\":{\"number\":1,\"number\":2}}")));
    }
    @ParameterizedTest @ValueSource(strings={"text/json", "application/problem+json", "application/json;charset=UTF-16", "application/json;profile=x", "application/json;charset=UTF-8;charset=UTF-8"})
    void acceptsOnlyOwnedJsonUtf8Media(String media) {
        var request = request(createBody("d")); request.removeHeader("Content-Type"); request.addHeader("Content-Type", media);
        assertEquals(415, assertThrows(Failure.class, () -> parser.create(request)).status());
    }
    @Test void contentTypeAndIdempotencyHeaderMultiplicityAndQueryAreStrict() {
        var command = new jakarta.servlet.http.HttpServletRequestWrapper(request(createBody("d"))) {
            @Override public java.util.Enumeration<String> getHeaders(String name) {
                return "Content-Type".equalsIgnoreCase(name)
                        ? Collections.enumeration(List.of("application/json", "application/json")) : super.getHeaders(name);
            }
        };
        assertEquals(2, Collections.list(command.getHeaders("Content-Type")).size(), "fixture must expose actual duplicate media headers");
        assertEquals(415, assertThrows(Failure.class, () -> parser.create(command)).status());
        var request = request("{}"); request.addHeader("Idempotency-Key", ID);
        assertEquals(1, Collections.list(request.getHeaders("Idempotency-Key")).size());
        assertEquals(UUID.fromString(ID), parser.idempotencyKey(request)); request.addHeader("Idempotency-Key", ID);
        assertEquals(2, Collections.list(request.getHeaders("Idempotency-Key")).size());
        assertThrows(Failure.class, () -> parser.idempotencyKey(request));
        assertNotEquals(ID, ID.toUpperCase(), "uppercase UUID fixture must contain alphabetic hex characters");
        assertThrows(Failure.class, () -> RiskScenarioParser.uuid(ID.toUpperCase()));
        var query = request(createBody("d")); query.setQueryString("x=1"); assertThrows(Failure.class, () -> parser.create(query));
    }

    @Test void queryPublishesIdDefaultAndPreservesExactDecimalThresholds() {
        var plain = parser.query(request("{\"page\":{\"number\":1},\"size\":20}"));
        assertEquals(List.of("id"), plain.fields()); assertEquals(List.of(new Sort("id", Direction.ASC)), plain.sort());
        var numbers = parser.query(request("{\"page\":{\"number\":1},\"size\":20,\"filterAST\":{\"operator\":\"AND\",\"conditions\":[{\"field\":\"version\",\"operator\":\"GT\",\"value\":1.25},{\"field\":\"version\",\"operator\":\"LTE\",\"value\":1e-999999}]}}"));
        assertEquals(new BigDecimal("1.25"), numbers.filters().get(0).values().getFirst());
        assertEquals(new BigDecimal("1e-999999"), numbers.filters().get(1).values().getFirst());
    }
    @Test void queryRejectsEveryRelationshipProjectionFilterAndSort() {
        for (String field : List.of("hazardIds", "hazards", "hazardCount", "existence", "hazardVersionAtLink", "hazardVersionAtScenarioRevision", "score", "currentAssessment")) {
            String root = "{\"page\":{\"number\":1},\"size\":20,";
            assertThrows(Failure.class, () -> parser.query(request(root+"\"fields\":[\""+field+"\"]}")));
            assertThrows(Failure.class, () -> parser.query(request(root+"\"sort\":[{\"field\":\""+field+"\",\"direction\":\"ASC\"}]}")));
            assertThrows(Failure.class, () -> parser.query(request(root+"\"filterAST\":{\"operator\":\"AND\",\"conditions\":[{\"field\":\""+field+"\",\"operator\":\"EQ\",\"value\":\"x\"}]}}")));
        }
    }
    @Test void queryPageOffsetsSortAndAstBoundsStayFinite() {
        assertEquals(10_000, parser.query(request("{\"page\":{\"number\":501},\"size\":20}")).offset());
        for (String page : List.of("{}", "{\"number\":1,\"cursor\":\"x\"}", "{\"number\":502}", "{\"cursor\":\" \"}"))
            assertThrows(Failure.class, () -> parser.query(request("{\"page\":"+page+",\"size\":20}")));
        assertThrows(Failure.class, () -> parser.query(request("{\"page\":{\"number\":102},\"size\":100}")));
        assertThrows(Failure.class, () -> parser.query(request("{\"page\":{\"number\":1},\"size\":20,\"sort\":[{\"field\":\"id\",\"direction\":\"ASC\"}]}")));
        String condition="{\"field\":\"code\",\"operator\":\"EQ\",\"value\":\"R\"}";
        assertThrows(Failure.class, () -> parser.query(request("{\"page\":{\"number\":1},\"size\":20,\"filterAST\":{\"operator\":\"AND\",\"conditions\":["+String.join(",",Collections.nCopies(51,condition))+"]}}")));
        assertThrows(Failure.class, () -> parser.query(request("{\"page\":{\"number\":1},\"size\":20,\"filterAST\":{\"operator\":\"OR\",\"conditions\":[]}}")));
    }
    @Test void selectorUsesSeparateCreateOrUpdateAnchorAndEmptyQueryIsLegal() {
        var create = parser.eligibleHazards(request("{\"use\":\"CREATE\",\"organizationId\":\""+ID+"\",\"query\":\"\"}"));
        assertEquals(SelectorUse.CREATE, create.use()); assertEquals(20, create.limit()); assertNull(create.scenarioId());
        var update = parser.eligibleHazards(request("{\"use\":\"UPDATE\",\"scenarioId\":\""+ID+"\",\"query\":\"𠀀\",\"afterId\":null,\"limit\":50}"));
        assertEquals(SelectorUse.UPDATE, update.use()); assertNull(update.organizationId());
        for (String extra : List.of(",\"scenarioId\":null", ",\"permission\":null", ",\"limit\":51", ",\"limit\":1.0"))
            assertThrows(Failure.class, () -> parser.eligibleHazards(request("{\"use\":\"CREATE\",\"organizationId\":\""+ID+"\",\"query\":\"\""+extra+"}")));
        String base = "{\"use\":\"CREATE\",\"organizationId\":\""+ID+"\",\"query\":\"";
        assertNotNull(parser.eligibleHazards(request(base+"𠀀".repeat(100)+"\"}")));
        assertThrows(Failure.class, () -> parser.eligibleHazards(request(base+"𠀀".repeat(101)+"\"}")));
        assertEquals(413, assertThrows(Failure.class, () -> parser.eligibleHazards(request(base+"\"}"+" ".repeat(2049)))).status());
    }
    @Test void getHistoryRequiresPairedCursorAndSingleFiniteParametersWithoutBody() {
        var first = new MockHttpServletRequest(); assertEquals(20, parser.history(first).pageSize());
        var after = new MockHttpServletRequest(); after.addParameter("afterOccurredAt", "2026-11-07T08:00:00.123456+08:00"); after.addParameter("afterCommandId", ID); after.addParameter("pageSize", "50");
        assertEquals(Instant.parse("2026-11-07T00:00:00.123456Z"), parser.history(after).after().occurredAt());
        after.addParameter("pageSize", new String[]{"20", "20"}); assertThrows(Failure.class, () -> parser.history(after));
        var partial = new MockHttpServletRequest(); partial.addParameter("afterCommandId", ID); assertThrows(Failure.class, () -> parser.history(partial));
        var body = request("{}"); assertThrows(Failure.class, () -> parser.history(body));
        for (String bad : List.of("0", "01", "1e0", "9007199254740992", " 1")) assertThrows(Failure.class, () -> RiskScenarioParser.pathVersion(bad));
        assertEquals(MAX_VERSION, RiskScenarioParser.pathVersion("9007199254740991"));
    }
    @Test void definitionAcceptsOnlyExplicitReadOrCreateUse() {
        var read = new MockHttpServletRequest(); read.addParameter("use", "READ"); assertEquals(Use.READ, parser.definitionUse(read));
        var create = new MockHttpServletRequest(); create.addParameter("use", "CREATE"); assertEquals(Use.CREATE, parser.definitionUse(create));
        var bad = new MockHttpServletRequest(); bad.addParameter("use", "UPDATE"); assertThrows(Failure.class, () -> parser.definitionUse(bad));
        assertThrows(Failure.class, () -> parser.definitionUse(new MockHttpServletRequest()));
        var unknown = new MockHttpServletRequest(); unknown.addParameter("unexpected", "x");
        assertThrows(Failure.class, () -> parser.noParameters(unknown));
    }
    private static MockHttpServletRequest request(String body) {
        var request = new MockHttpServletRequest(); request.addHeader("Content-Type", "application/json; charset=UTF-8");
        request.setContent(body.getBytes(StandardCharsets.UTF_8)); return request;
    }
    private static String createBody(String description) {
        return "{\"code\":\"R_1\",\"organizationId\":\""+ID+"\",\"description\":"+JSON.writeValueAsString(description)
                +",\"triggeringConditions\":\"t\",\"exposureDescription\":\"e\",\"consequence\":\"c\",\"hazardIds\":[\""+ID+"\"]}";
    }
    private static String updateBody(String version) {
        return "{\"expectedVersion\":"+version+",\"description\":\"d\",\"triggeringConditions\":\"t\",\"exposureDescription\":\"e\",\"consequence\":\"c\",\"hazardIds\":[\""+ID+"\"]}";
    }
}
