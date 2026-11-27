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
                SOURCE.replace("\"scene\":", "\"hudWidgets\":[],\"scene\":"),
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
        var caller = new StudioPublicationPolicy.Caller(tenant, java.util.Set.of(StudioPublicationPolicy.Permission.STUDIO_PUBLISH));
        var decision = StudioPublicationPolicy.decide(caller, new StudioPublicationPolicy.Target(tenant, "factory"), 0, null, draft.facts());
        assertEquals(draft.facts().canonicalSha256(), decision.canonicalSha256());
        assertEquals(1.0, decision.metrics().wallLengthM());
        assertEquals(0.5, decision.metrics().zoneAreaM2());
    }

    private static StudioDraftDecoder.Decoded decode(String value) {
        return StudioDraftDecoder.decode(value.getBytes(StandardCharsets.UTF_8));
    }
}
