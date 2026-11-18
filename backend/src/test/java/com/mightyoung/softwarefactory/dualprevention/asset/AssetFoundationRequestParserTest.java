package com.mightyoung.softwarefactory.dualprevention.asset;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

class AssetFoundationRequestParserTest {
    private final AssetFoundationRequestParser parser=new AssetFoundationRequestParser(new ObjectMapper());
    private static final String ORG="12345678-1234-1234-1234-123456789abc";
    private static MockHttpServletRequest request(String body) {
        var request=new MockHttpServletRequest();request.addHeader("Content-Type","application/json; charset=UTF-8");
        request.setContent(body.getBytes(StandardCharsets.UTF_8));return request;
    }
    @ParameterizedTest @ValueSource(ints={0,1000000})
    void stageOrdinalAcceptsBothClosedBounds(int ordinal) {
        var value=parser.processStageCreate(request("{\"code\":\"S\",\"name\":\"Stage\",\"organizationId\":\""+ORG+"\",\"lineId\":\""+ORG+"\",\"ordinal\":"+ordinal+"}"));
        assertEquals(ordinal,value.ordinal());
    }
    @ParameterizedTest @ValueSource(strings={"-1","1000001","1.0","1e0","\"1\"","null","-0"})
    void stageOrdinalRejectsNonIntegerAndOutsideBounds(String token) {
        assertThrows(AssetFoundationFailure.class,()->parser.processStageUpdate(request("{\"expectedVersion\":1,\"name\":\"Stage\",\"ordinal\":"+token+"}")));
    }
    @Test void lineUpdateCannotReparentAndEquipmentCannotSupplyLifecycle() {
        assertThrows(AssetFoundationFailure.class,()->parser.productionLineUpdate(request("{\"expectedVersion\":1,\"name\":\"Line\",\"workshopId\":null}")));
        assertThrows(AssetFoundationFailure.class,()->parser.equipmentCreate(request("{\"code\":\"E\",\"name\":\"Equipment\",\"organizationId\":\""+ORG+"\",\"typeDescription\":\"Pump\",\"operationalState\":\"UNKNOWN\",\"lifecycle\":\"ACTIVE\"}")));
    }
    @Test void bindingPeriodNormalizesMissingAndNullEndButRejectsEmptyOrReversedPeriods() {
        String base="{\"expectedLineVersion\":1,\"workshopId\":\""+ORG+"\",\"validFrom\":\"2026-11-04T00:00:00Z\"";
        assertNull(parser.lineWorkshopBind(request(base+"}")).validTo());
        assertNull(parser.lineWorkshopBind(request(base+",\"validTo\":null}")).validTo());
        assertThrows(AssetFoundationFailure.class,()->parser.lineWorkshopBind(request(base+",\"validTo\":\"2026-11-04T00:00:00Z\"}")));
        assertThrows(AssetFoundationFailure.class,()->parser.lineWorkshopBind(request(base+",\"validTo\":\"2026-11-03T00:00:00Z\"}")));
    }
    @Test void endKeepsOmittedTimeUnresolvedUntilActualCommonDatabaseCut() {
        var command=parser.endBinding(request("{\"expectedParentVersion\":2,\"expectedBindingVersion\":3}"));
        assertNull(command.endAt());assertEquals(2,command.expectedParentVersion());assertEquals(3,command.expectedBindingVersion());
    }
    @Test void acceptsSupplementaryNameAtCodePointBoundaryWithoutNormalization() {
        String name="𠀀".repeat(200);
        var command=parser.workshopCreate(request("{\"code\":\"A-1\",\"name\":\""+name+"\",\"organizationId\":\""+ORG+"\"}"));
        assertEquals(name,command.name());assertNull(command.boundaryDescription());
    }
    @ParameterizedTest @ValueSource(strings={"0","-1","1.0","1e0","\"1\"","9007199254740992","999999999999999999999999","null"})
    void rejectsNonCanonicalOrUnsafeVersionTokens(String token) {
        assertThrows(AssetFoundationFailure.class,()->parser.workshopUpdate(request("{\"expectedVersion\":"+token+",\"name\":\"new\"}")));
    }
    @Test void acceptsLastSafeVersionButRefusesItsIncrement() {
        var command=parser.workshopUpdate(request("{\"expectedVersion\":9007199254740991,\"name\":\"new\"}"));
        assertEquals(AssetContracts.MAX_VERSION,command.expectedVersion());
        assertEquals(409,assertThrows(AssetFoundationFailure.class,()->AssetContracts.nextVersion(command.expectedVersion())).status());
    }
    @ParameterizedTest @ValueSource(strings={"\"name\":\"b\",","\"actorId\":null,","\"tenantId\":null,","\"riskGrade\":null,"})
    void rejectsDuplicateAndUnknownPropertiesEvenNull(String extra) {
        assertThrows(AssetFoundationFailure.class,()->parser.workshopCreate(request("{\"code\":\"A\",\"name\":\"a\","+extra+"\"organizationId\":\""+ORG+"\"}")));
    }
    @Test void standaloneRejectsSourceKeyEvenWhenNull() {
        assertThrows(AssetFoundationFailure.class,()->parser.workUnitCreate(request("{\"code\":\"A\",\"name\":\"A\",\"organizationId\":\""+ORG+"\",\"kind\":\"STANDALONE\",\"workshopId\":null}")));
    }
    @Test void typedSourceRequiresExactlyItsNonNullSource() {
        var command=parser.workUnitCreate(request("{\"code\":\"A\",\"name\":\"A\",\"organizationId\":\""+ORG+"\",\"kind\":\"WORKSHOP\",\"workshopId\":\""+ORG+"\"}"));
        assertEquals(UUID.fromString(ORG),command.workshopId());assertNull(command.lineId());
    }
    @ParameterizedTest @ValueSource(strings={"\"\\ud800\"","\"\\udc00\"","{\"nested\":{\"nested\":{\"nested\":{}}}}","[]"})
    void rejectsInvalidUnicodeAndNestedValueShapes(String value) {
        assertThrows(AssetFoundationFailure.class,()->parser.workshopUpdate(request("{\"expectedVersion\":1,\"name\":"+value+"}")));
    }
    @Test void rejectsMalformedUtf8AndRawByteOverflow() {
        var bad=request("{}");bad.setContent(new byte[]{(byte)0xc3,0x28});assertEquals(400,assertThrows(AssetFoundationFailure.class,()->parser.archive(bad)).status());
        var large=request(" ".repeat(32769));assertEquals(413,assertThrows(AssetFoundationFailure.class,()->parser.archive(large)).status());
    }
    @ParameterizedTest @ValueSource(strings={"text/json","application/problem+json","application/json; charset=UTF-16","application/json; profile=x","application/json;charset=UTF-8;charset=UTF-8"})
    void rejectsUnsupportedMedia(String media) {
        var request=request("{\"expectedVersion\":1}");request.removeHeader("Content-Type");request.addHeader("Content-Type",media);
        assertEquals(415,assertThrows(AssetFoundationFailure.class,()->parser.archive(request)).status());
    }
    @Test void idempotencyRequiresExactlyOneLowercaseCanonicalUuid() {
        var request=request("{}");request.addHeader("Idempotency-Key",ORG);assertEquals(UUID.fromString(ORG),parser.idempotencyKey(request));
        request.addHeader("Idempotency-Key",ORG);assertThrows(AssetFoundationFailure.class,()->parser.idempotencyKey(request));
        assertThrows(AssetFoundationFailure.class,()->AssetFoundationRequestParser.uuid(ORG.toUpperCase()));
    }
    @Test void instantNormalizedToUtcButSubmicrosecondRejected() {
        var command=parser.hazardCreate(request(hazard("2026-11-04T10:00:00.123456+08:00","")));
        assertEquals(Instant.parse("2026-11-04T02:00:00.123456Z"),command.identifiedAt());
        assertThrows(AssetFoundationFailure.class,()->parser.hazardCreate(request(hazard("2026-11-04T10:00:00.1234567+08:00",""))));
    }
    @Test void hazardDoesNotAcceptUnverifiedFactorFields() {
        assertThrows(AssetFoundationFailure.class,()->parser.hazardCreate(request(hazard("2026-11-04T10:00:00Z",",\"factorCodeRef\":null"))));
    }
    @Test void optionalMissingAndNullAgreeButEmptyDoesNot() {
        var omitted=parser.workshopUpdate(request("{\"expectedVersion\":1,\"name\":\"A\"}"));
        var explicit=parser.workshopUpdate(request("{\"expectedVersion\":1,\"name\":\"A\",\"boundaryDescription\":null}"));assertEquals(omitted,explicit);
        assertThrows(AssetFoundationFailure.class,()->parser.workshopUpdate(request("{\"expectedVersion\":1,\"name\":\"A\",\"boundaryDescription\":\"\"}")));
    }
    private static String hazard(String time,String extra) { return "{\"code\":\"H\",\"organizationId\":\""+ORG+"\",\"workUnitId\":\""+ORG+"\",\"description\":\"Observed\",\"identifiedAt\":\""+time+"\",\"existenceState\":\"UNKNOWN\""+extra+"}"; }
}
