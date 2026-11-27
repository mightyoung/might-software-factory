package com.mightyoung.softwarefactory.platformadmin.digitaltwin;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StudioDraftDecoderTest {
    private static final String SOURCE = """
            {"snapshotVersion":1,"scene":{"schemaVersion":1,"screenId":"factory",
             "devices":[{"id":"pump","label":" 泵 ","position":[0.0,1,2],"state":"normal",
                        "primitive":"box","size":[0.5,1,1]}]},
             "floorplan":{"walls":[{"id":1,"start":[0,0],"end":[10,0]}],
                          "zones":[{"id":2,"points":[[0,0],[10,0],[0,10]]}]}}
            """;

    @Test void derivesFactsAndDigestFromCanonicalBytesWithNoMutableEscape() throws Exception {
        var draft = decode(SOURCE);
        var canonical = draft.canonicalBytes();
        var digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
        assertEquals(digest, draft.facts().canonicalSha256());
        assertEquals("factory", draft.facts().screenKey());
        assertEquals(1, draft.facts().devices());
        assertEquals(10, draft.facts().walls().getFirst().end().xDm());
        assertEquals(3, draft.facts().zones().getFirst().points().size());
        assertArrayEquals(canonical, StudioDraftDecoder.decode(canonical).canonicalBytes());
        var same = SOURCE.replace("\"id\":1,\"start\":[0,0]", "\"start\":[0,0],\"id\":1")
                .replace(" 泵 ", "泵").replace("0.0,1,2", "-0.00,1.000,2e0");
        assertArrayEquals(canonical, decode(same).canonicalBytes());
        assertNotEquals(digest, decode(SOURCE.replace("\"state\":\"normal\"", "\"state\":\"alarm\"")).facts().canonicalSha256());
        canonical[0] = 0;
        assertNotEquals(0, draft.canonicalBytes()[0]);
    }

    @Test void refusesAmbiguousInvalidAndNotYetSupportedInputs() {
        for (String invalid : new String[] {
                SOURCE + " {}", SOURCE.replace("\"snapshotVersion\":1", "\"snapshotVersion\":1,\"snapshotVersion\":1"),
                SOURCE.replace("\"screenId\":\"factory\"", "\"screenId\":\"factory\",\"\\u0073creenId\":\"other\""),
                SOURCE.replace("\"label\":\" 泵 \"", "\"label\":\"泵\",\"url\":\"https://invalid.example/\""),
                SOURCE.replace("\"scene\":", "\"cameraTour\":null,\"scene\":"),
                SOURCE.replace("[10,0]", "[11.5,0]"), SOURCE.replace("\"id\":2", "\"id\":1"),
                SOURCE.replace("[0,10]", "[0,0]"), SOURCE.replace("[0.5,1,1]", "[0.01,1,1]"),
                SOURCE.replace("\"primitive\":\"box\",\"size\":[0.5,1,1]", "\"equipment\":{}"),
                SOURCE.replace("0.0,1,2", "1e-100000,1,2"), SOURCE + " ".repeat(256 * 1024)
        }) assertThrows(IllegalArgumentException.class, () -> decode(invalid));
        assertThrows(IllegalArgumentException.class, () -> StudioDraftDecoder.decode(new byte[] {(byte) 0xc3, 0x28}));
    }

    @Test void assignmentAndDigestUseTheSameServerValidatedGeometry() {
        var draft = decode(SOURCE);
        var tenant = java.util.UUID.randomUUID();
        var caller = new StudioPublicationPolicy.Caller(tenant, java.util.UUID.randomUUID(), java.util.UUID.randomUUID(), true, java.util.Set.of());
        var decision = StudioPublicationPolicy.decide(caller, new StudioPublicationPolicy.Target(tenant, "factory"), 0, null, draft.facts());
        assertEquals(draft.facts().canonicalSha256(), decision.canonicalSha256());
        assertEquals(1.0, decision.metrics().wallLengthM());
        assertEquals(0.5, decision.metrics().zoneAreaM2());
    }

    @Test void acceptsCurrentSyntheticSceneAndCanonicalizesOptionalFields() throws Exception {
        var fixture = syntheticFixture();
        var draft = decode(fixture);
        assertEquals("sample-workshop", draft.facts().screenKey());
        assertEquals(7, draft.facts().devices());
        var canonical = new String(draft.canonicalBytes(), StandardCharsets.UTF_8);
        assertTrue(canonical.contains("\"rotationYRad\":0"));
        assertTrue(canonical.contains("\"label\":\"机械臂巡视\""));
        assertEquals(draft.facts().canonicalSha256(), decode(canonical).facts().canonicalSha256());
        assertNotEquals(draft.facts().canonicalSha256(), decode(fixture.replace("机械臂巡视", "机械臂复查")).facts().canonicalSha256());
    }

    @Test void refusesInvalidOptionalFieldsAndUnknownEquipmentPayloads() throws Exception {
        var fixture = syntheticFixture();
        for (var bad : new String[] {
            fixture.replace("\"deviceId\": \"robot-06\"", "\"deviceId\": \"unregistered\""),
            fixture.replace("\"dwellSeconds\": 3", "\"dwellSeconds\": 0"),
            fixture.replace("\"dwellSeconds\": 3", "\"dwellSeconds\": \"3\""),
            fixture.replace("\"walls\"\n  ]", "\"devices\"\n  ]"),
            fixture.replace("\"CONVEYOR\"", "\"UNKNOWN\""),
            fixture.replace("\"length\": 4", "\"length\": 40"),
            fixture.replace("\"kind\": \"CONVEYOR\"", "\"kind\": \"CONVEYOR\", \"rotationYRad\": 7"),
            fixture.replace("\"kind\": \"CONVEYOR\"", "\"kind\": \"CONVEYOR\", \"texture\": \"about:blank\""),
        }) {
            assertNotEquals(fixture, bad);
            assertThrows(IllegalArgumentException.class, () -> decode(bad));
        }
    }

    @Test void enforcesParametricLimitAndUniqueTourDeviceReferences() {
        String device = """
                {"id":"d%d","label":"Synthetic machine","position":[0,0,0],"state":"normal",
                 "equipment":{"kind":"ROBOT_ARM","dimensions":{"length":2,"width":1,"height":3}}}
                """;
        String payload = """
                {"snapshotVersion":1,"scene":{"schemaVersion":1,"screenId":"factory","devices":[%s]},
                "floorplan":{"walls":[],"zones":[]},"cameraTour":%s}
                """;
        var devices = java.util.stream.IntStream.range(0, 24).mapToObj(i -> device.formatted(i))
                .collect(java.util.stream.Collectors.joining(","));
        String stop = """
                {"deviceId":"d0","label":"巡视","dwellSeconds":30}
                """;
        assertEquals(24, decode(payload.formatted(devices, "[" + stop + "]")).facts().devices());
        assertThrows(IllegalArgumentException.class, () -> decode(payload.formatted(devices + "," + device.formatted(24), "[]")));
        assertThrows(IllegalArgumentException.class, () -> decode(payload.formatted(devices, "[" + stop + "," + stop + "]")));
    }

    private static String syntheticFixture() throws Exception {
        try (var fixture = StudioDraftDecoderTest.class.getResourceAsStream("/digital-twin/combined-studio-v1-synthetic.json")) {
            return new String(java.util.Objects.requireNonNull(fixture).readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static StudioDraftDecoder.Decoded decode(String value) {
        return StudioDraftDecoder.decode(value.getBytes(StandardCharsets.UTF_8));
    }
}
