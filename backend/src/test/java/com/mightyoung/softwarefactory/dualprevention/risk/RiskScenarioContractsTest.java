package com.mightyoung.softwarefactory.dualprevention.risk;

import com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.Metadata;
import com.mightyoung.softwarefactory.dualprevention.hazard.HazardSourceContracts.ExistenceState;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import tools.jackson.databind.json.JsonMapper;
import static com.mightyoung.softwarefactory.dualprevention.risk.RiskScenarioContracts.*;
import static org.junit.jupiter.api.Assertions.*;

class RiskScenarioContractsTest {
    private static final UUID ID = UUID.fromString("00112233-4455-6677-8899-aabbccddeeff");
    private static final Instant CUT = Instant.parse("2026-11-07T00:00:00.123456Z");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test void canonicalHazardsUseUnsignedUuidOrderAreImmutableAndRejectDuplicates() {
        UUID high = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff"), low = UUID.fromString("7fffffff-ffff-ffff-ffff-ffffffffffff");
        var input = new ArrayList<>(List.of(high, low));
        var create = new Create("R_1", ID, " d ", "t", "e", "c", input);
        input.clear();
        assertEquals(List.of(low, high), create.hazardIds()); assertEquals(" d ", create.description());
        assertThrows(UnsupportedOperationException.class, () -> create.hazardIds().clear());
        assertThrows(Failure.class, () -> canonicalHazards(List.of(ID, ID)));
        assertThrows(Failure.class, () -> canonicalHazards(List.of()));
        assertThrows(Failure.class, () -> canonicalHazards(java.util.stream.IntStream.range(0, 101).mapToObj(i -> new UUID(0, i)).toList()));
    }
    @ParameterizedTest @EnumSource(ExistenceState.class)
    void existenceDoesNotChangeUnassessedRiskSemantics(ExistenceState existence) {
        var snapshot = new HistoricalHazardSnapshot(ID, ID, 1, "H", "Observed", existence, 3, 4);
        var value = detail(new CompleteHazards(List.of(snapshot.summary())));
        var wire = JSON.readTree(encodeResponse(value, ResponseBudget.DETAIL));
        assertEquals("UNASSESSED", wire.get("assessmentStatus").stringValue());
        assertTrue(wire.has("currentAssessment")); assertTrue(wire.get("currentAssessment").isNull());
        assertTrue(wire.get("accidentCodeRef").isNull()); assertTrue(wire.get("accidentVersion").isNull());
        assertFalse(wire.has("score")); assertFalse(wire.has("grade"));
        assertEquals(existence.name(), wire.get("hazards").get("items").get(0).get("existence").stringValue());
    }
    @Test void restrictedUnionSerializesNoMembershipFieldsAndCannotPretendComplete() {
        var wire = JSON.readTree(encodeResponse(new RestrictedHazards(), ResponseBudget.DETAIL));
        assertEquals(1, wire.size()); assertEquals("RESTRICTED", wire.get("visibility").stringValue());
        for (String forbidden : List.of("items", "ids", "count", "labels", "hasMore", "nextAfter")) assertFalse(wire.has(forbidden));
        assertThrows(Failure.class, () -> new RestrictedHazards(Visibility.COMPLETE));
        assertThrows(Failure.class, () -> new CompleteHazards(List.of()));
        assertThrows(Failure.class, () -> new CompleteHazards(Visibility.RESTRICTED, List.of(snapshot(1).summary())));
    }
    @Test void previewUsesCodePointsAndPreservesFullImmutableSnapshot() {
        String full = "𠀀".repeat(201);
        var snapshot = new HistoricalHazardSnapshot(ID, ID, 1, "H", full, ExistenceState.UNKNOWN, 3, 4);
        var summary = snapshot.summary();
        assertEquals("𠀀".repeat(200), summary.descriptionPreview()); assertTrue(summary.descriptionTruncated());
        assertEquals(full, snapshot.fullDescription());
        assertThrows(Failure.class, () -> new HazardSummary(ID, ID, 1, "H", "short", true, ExistenceState.UNKNOWN, 3, 4));
        assertFalse(new HistoricalHazardSnapshot(ID, ID, 1, "H", "𠀀".repeat(200), ExistenceState.UNKNOWN, 3, 4).summary().descriptionTruncated());
    }
    @Test void actualUtf8EncodingCountsEscapesAndDoesNotAsciiEscapeSupplementaryCodePoints() {
        String value = "𠀀\"\\\n";
        byte[] encoded = encodeResponse(Map.of("value", value), ResponseBudget.COMMAND);
        assertEquals("{\"value\":\"𠀀\\\"\\\\\\n\"}", new String(encoded, StandardCharsets.UTF_8));
        assertTrue(encoded.length > new String(encoded, StandardCharsets.UTF_8).length());
        assertEquals(value, JSON.readTree(encoded).get("value").stringValue());
    }
    @Test void encoderAcceptsExactBudgetStopsOverflowAndRemainsUsable() {
        int maximum = ResponseBudget.COMMAND.bytes();
        assertEquals(maximum, encodeResponse("a".repeat(maximum - 2), ResponseBudget.COMMAND).length);
        var overflow = assertThrows(Failure.class, () -> encodeResponse("a".repeat(maximum - 1), ResponseBudget.COMMAND));
        assertEquals(503, overflow.status()); assertEquals("RESPONSE_BUDGET_EXCEEDED", overflow.code());
        assertEquals("\"ok\"", new String(encodeResponse("ok", ResponseBudget.COMMAND), StandardCharsets.UTF_8));
    }
    @Test void fullNarrativesAndHundredPreviewsFitOneItemWithoutMovingFullDescriptionsIntoList() {
        var summaries = java.util.stream.IntStream.range(1, 101).mapToObj(RiskScenarioContractsTest::snapshot).map(HistoricalHazardSnapshot::summary).toList();
        String longText = "𠀀".repeat(20_000);
        var value = new Detail(ID, ID, 1, "R", longText, longText, longText, longText, CUT, CUT,
                AssessmentStatus.UNASSESSED, null, null, null, new CompleteHazards(summaries));
        byte[] item = encodeResponse(value, ResponseBudget.ITEM);
        assertTrue(item.length <= ResponseBudget.ITEM.bytes());
        var node = JSON.readTree(item);
        assertEquals(100, node.get("hazards").get("items").size());
        assertEquals(200, node.get("hazards").get("items").get(0).get("descriptionPreview").stringValue().codePointCount(0, 400));
        assertFalse(node.get("hazards").get("items").get(0).has("fullDescription"));
        byte[] point = encodeResponse(Map.of("hazardSnapshot", snapshot(1)), ResponseBudget.HISTORICAL_SNAPSHOT);
        assertEquals(longText, JSON.readTree(point).get("hazardSnapshot").get("fullDescription").stringValue());
    }
    @Test void currentAssessmentOrAccidentClassificationCannotBeInvented() {
        assertThrows(Failure.class, () -> new Detail(ID, ID, 1, "R", "d", "t", "e", "c", CUT, CUT,
                null, null, null, null, new RestrictedHazards()));
        assertThrows(Failure.class, () -> new Detail(ID, ID, 1, "R", "d", "t", "e", "c", CUT, CUT,
                AssessmentStatus.UNASSESSED, null, "unverified", "1", new RestrictedHazards()));
        assertThrows(Failure.class, () -> new QueryPage(List.of(Map.of("hazards", "hidden")), new QueryPageInfo(20, 1, false, null, null)));
        assertThrows(Failure.class, () -> new QueryPage(List.of(Map.of("assessmentStatus", "LOW")), new QueryPageInfo(20, 1, false, null, null)));
    }
    @Test void responseBudgetsAndNonAdvancingPagesAreExplicit() {
        assertEquals(16 * 1024, ResponseBudget.COMMAND.bytes()); assertEquals(1024 * 1024, ResponseBudget.HISTORY.bytes());
        assertEquals(128 * 1024, ResponseBudget.SELECTOR.bytes()); assertEquals(8 * 1024, ResponseBudget.ERROR.bytes());
        assertThrows(Failure.class, () -> new HistoryPage(ID, List.of(), true, new HistoryCursor(CUT, ID)));
        assertThrows(Failure.class, () -> new SelectorPage(List.of(), true, ID));
        assertThrows(Failure.class, () -> new QueryPage(List.of(), new QueryPageInfo(20, null, true, "cursor", null)));
        assertEquals(MAX_VERSION, version(MAX_VERSION)); assertEquals(409, assertThrows(Failure.class, () -> nextVersion(MAX_VERSION)).status());
        var result = new Response<>(new CommandResult(ID, "R", 1, CUT), new Metadata(ID.toString(), ID.toString(), "1"));
        var wire = JSON.readTree(encodeResponse(result, ResponseBudget.COMMAND));
        assertEquals("2026-11-07T00:00:00.123456Z", wire.get("data").get("occurredAt").stringValue());
    }
    @Test void ownedEncodingMatchesLegacyUtf8EscapingAndAppendsWithoutExposingItsBuffer()throws Exception {
        Object value=Map.of("value","𠀀\"\\\n");byte[] expected=encodeResponse(value,ResponseBudget.COMMAND);
        var owned=encodeOwnedResponse(value,ResponseBudget.COMMAND);assertEquals(expected.length,owned.size());
        byte[] target=new byte[expected.length+4];java.util.Arrays.fill(target,(byte)7);
        assertEquals(expected.length+2,owned.appendTo(target,2));assertArrayEquals(expected,java.util.Arrays.copyOfRange(target,2,target.length-2));assertEquals(7,target[0]);assertEquals(7,target[target.length-1]);
        target[2]=0;byte[] second=new byte[owned.size()];owned.appendTo(second,0);assertArrayEquals(expected,second);
        var output=new java.io.ByteArrayOutputStream();owned.writeTo(output);assertArrayEquals(expected,output.toByteArray());
    }
    @Test void ownedEncoderAcceptsExactBudgetRejectsOverflowAndPreservesLegacyFailures()throws Exception {
        int max=ResponseBudget.COMMAND.bytes();assertEquals(max,encodeOwnedResponse("a".repeat(max-2),ResponseBudget.COMMAND).size());
        var failure=assertThrows(Failure.class,()->encodeOwnedResponse("a".repeat(max-1),ResponseBudget.COMMAND));assertEquals(503,failure.status());assertEquals("RESPONSE_BUDGET_EXCEEDED",failure.code());
        var fresh=encodeOwnedResponse("ok",ResponseBudget.COMMAND);byte[] bytes=new byte[fresh.size()];fresh.appendTo(bytes,0);assertEquals("\"ok\"",new String(bytes,StandardCharsets.UTF_8));
        assertThrows(NullPointerException.class,()->encodeOwnedResponse(null,ResponseBudget.COMMAND));assertThrows(NullPointerException.class,()->encodeOwnedResponse("ok",null));
    }
    @Test void ownedAppendBoundsFailBeforeMutatingDestination() {
        var owned=encodeOwnedResponse("ok",ResponseBudget.COMMAND);byte[] destination={1,2,3,4,5};byte[] before=destination.clone();
        assertThrows(IndexOutOfBoundsException.class,()->owned.appendTo(destination,-1));assertArrayEquals(before,destination);
        assertThrows(IndexOutOfBoundsException.class,()->owned.appendTo(destination,2));assertArrayEquals(before,destination);
        assertThrows(NullPointerException.class,()->owned.appendTo(null,0));
    }
    @Test void ownedWriteUsesBoundedScratchAndHostileStreamCannotMutatePrivateOutput()throws Exception {
        var value="𠀀".repeat(10_000);byte[] expected=encodeResponse(value,ResponseBudget.ITEM);var owned=encodeOwnedResponse(value,ResponseBudget.ITEM);
        var calls=new java.util.concurrent.atomic.AtomicInteger();var maximum=new java.util.concurrent.atomic.AtomicInteger();var flushed=new java.util.concurrent.atomic.AtomicBoolean();var closed=new java.util.concurrent.atomic.AtomicBoolean();
        owned.writeTo(new java.io.OutputStream(){
            @Override public void write(int value){fail("expected bounded bulk chunks");}
            @Override public void write(byte[] bytes,int offset,int length){calls.incrementAndGet();maximum.accumulateAndGet(length,Math::max);java.util.Arrays.fill(bytes,(byte)0);}
            @Override public void flush(){flushed.set(true);}@Override public void close(){closed.set(true);}
        });
        assertTrue(calls.get()>1);assertTrue(maximum.get()<=8192);assertFalse(flushed.get());assertFalse(closed.get());
        byte[] after=new byte[owned.size()];owned.appendTo(after,0);assertArrayEquals(expected,after);
    }
    @Test void ownedStreamIOExceptionIsPropagatedWithoutCorruptingReusableEncoding()throws Exception {
        var owned=encodeOwnedResponse("ok",ResponseBudget.COMMAND);var refused=new java.io.IOException("synthetic output failure");
        var stream=new java.io.OutputStream(){@Override public void write(int value)throws java.io.IOException{throw refused;}@Override public void write(byte[] bytes,int offset,int length)throws java.io.IOException{throw refused;}};
        assertSame(refused,assertThrows(java.io.IOException.class,()->owned.writeTo(stream)));
        var good=new java.io.ByteArrayOutputStream();owned.writeTo(good);assertArrayEquals(encodeResponse("ok",ResponseBudget.COMMAND),good.toByteArray());
    }
    @Test void ownedAndLegacyKeepNonBudgetSerializationFailuresRedacted503() {
        var value=new BrokenEncoding();
        var owned=assertThrows(Failure.class,()->encodeOwnedResponse(value,ResponseBudget.COMMAND));
        var legacy=assertThrows(Failure.class,()->encodeResponse(value,ResponseBudget.COMMAND));
        assertEquals(503,owned.status());assertEquals("UNAVAILABLE",owned.code());assertEquals(legacy.status(),owned.status());assertEquals(legacy.code(),owned.code());
        assertFalse(owned.getMessage().contains("private serializer fixture"));
    }
    public static final class BrokenEncoding {
        @com.fasterxml.jackson.annotation.JsonProperty("value") public String value(){throw new IllegalStateException("private serializer fixture");}
    }
    private static HistoricalHazardSnapshot snapshot(int number) {
        return new HistoricalHazardSnapshot(new UUID(0, number), new UUID(1, number), 1, "H" + number,
                "𠀀".repeat(20_000), ExistenceState.UNKNOWN, 3, 4);
    }
    private static Detail detail(Hazards hazards) {
        return new Detail(ID, ID, 1, "R", "d", "t", "e", "c", CUT, CUT, AssessmentStatus.UNASSESSED, null, null, null, hazards);
    }
}
