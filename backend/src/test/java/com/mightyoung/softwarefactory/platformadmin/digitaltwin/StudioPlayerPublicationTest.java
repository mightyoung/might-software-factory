package com.mightyoung.softwarefactory.platformadmin.digitaltwin;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static com.mightyoung.softwarefactory.platformadmin.digitaltwin.StudioPublicationPolicy.*;
import static org.junit.jupiter.api.Assertions.*;

class StudioPlayerPublicationTest {
    private static final Target TARGET = new Target(UUID.randomUUID(), "floor");
    private static final Caller READER = new Caller(TARGET.tenantId(), Set.of(Permission.PLAYER_READ));
    private static final byte[] INPUT = """
            {"snapshotVersion":1,"scene":{"schemaVersion":1,"screenId":"floor","devices":[]},
             "floorplan":{"walls":[],"zones":[]}}
            """.getBytes(StandardCharsets.UTF_8);
    private static final StudioDraftDecoder.Decoded DRAFT = StudioDraftDecoder.decode(INPUT);

    @Test void unauthorizedCallerNeverQueriesTargetEvenForMalformedVersion() {
        var calls = new AtomicInteger();
        var reader = new StudioPlayerPublication((target, version) -> { calls.incrementAndGet(); return Optional.empty(); });
        assertThrows(Forbidden.class, () -> reader.read(null, TARGET, -1L));
        assertThrows(Forbidden.class, () -> reader.read(new Caller(UUID.randomUUID(), Set.of(Permission.PLAYER_READ)), TARGET, 8L));
        assertThrows(Forbidden.class, () -> reader.read(new Caller(TARGET.tenantId(), Set.of(Permission.STUDIO_PUBLISH)), TARGET, null));
        assertEquals(0, calls.get());
    }

    @Test void readsPinnedOrLatestVersionWithoutMutableByteEscape() {
        var first = stored(TARGET, 1, DRAFT.facts().canonicalSha256(), DRAFT.canonicalBytes());
        var latest = stored(TARGET, 2, DRAFT.facts().canonicalSha256(), DRAFT.canonicalBytes());
        var read = new StudioPlayerPublication((target, version) -> {
            assertEquals(TARGET, target);
            return Optional.of(version == null ? latest : first);
        });
        var result = read.read(READER, TARGET, 1L).orElseThrow();
        assertEquals(1, result.version());
        assertEquals(DRAFT.facts().canonicalSha256(), result.canonicalSha256());
        byte[] outside = result.canonicalBytes();
        outside[0] = 0;
        assertArrayEquals(DRAFT.canonicalBytes(), result.canonicalBytes());
        assertEquals(2, read.read(READER, TARGET, null).orElseThrow().version());
        assertTrue(new StudioPlayerPublication((t, v) -> Optional.empty()).read(READER, TARGET, 9L).isEmpty());
    }

    @Test void rejectsOutOfScopeOrCorruptAdapterResultsWithoutReturningHead() {
        var wrongTarget = new Target(UUID.randomUUID(), TARGET.screenKey());
        var hash = DRAFT.facts().canonicalSha256();
        var bytes = DRAFT.canonicalBytes();
        assertThrows(Forbidden.class, () -> new StudioPlayerPublication((t,v) ->
                Optional.of(stored(wrongTarget, 2, hash, bytes))).read(READER, TARGET, 1L));
        for (var bad : new StudioPlayerPublication.Published[] {
                stored(TARGET, 2, hash, bytes),
                stored(TARGET, 1, "0".repeat(64), bytes),
                stored(TARGET, 1, hash, INPUT),
                stored(TARGET, 0, hash, bytes),
        }) {
            assertThrows(IllegalStateException.class, () -> new StudioPlayerPublication((t,v) ->
                    Optional.of(bad)).read(READER, TARGET, 1L));
        }
    }

    private static StudioPlayerPublication.Published stored(Target target, long version, String hash, byte[] bytes) {
        return new StudioPlayerPublication.Published(target, version, hash, bytes);
    }
}
