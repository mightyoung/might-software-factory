package com.mightyoung.softwarefactory.platformadmin.digitaltwin;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static com.mightyoung.softwarefactory.platformadmin.digitaltwin.StudioPublicationPolicy.*;
import static org.junit.jupiter.api.Assertions.*;

class StudioPlayerResponseTest {
    private static final Target TARGET = new Target(UUID.randomUUID(), "floor");
    private static final Caller READER = new Caller(TARGET.tenantId(), Set.of(Permission.PLAYER_READ));
    private static final StudioDraftDecoder.Decoded DRAFT = StudioDraftDecoder.decode("""
            {"snapshotVersion":1,"scene":{"schemaVersion":1,"screenId":"floor","devices":[]},
             "floorplan":{"walls":[],"zones":[]}}
            """.getBytes(StandardCharsets.UTF_8));

    @Test void onlyAllowedPublicationCarriesExactBytesAndQuotedDigest() {
        var reader = new StudioPlayerPublication((target, version) -> Optional.of(stored(target)));
        var response = assertInstanceOf(StudioPlayerResponse.Content.class, StudioPlayerResponse.read(reader, READER, TARGET, 1L));
        assertEquals(200, response.status());
        assertEquals(Map.of("Content-Type", "application/json", "Cache-Control", "private, no-store",
                "ETag", "\"" + DRAFT.facts().canonicalSha256() + "\""), response.headers());
        assertArrayEquals(DRAFT.canonicalBytes(), response.bytes());
        response.bytes()[0] = 0;
        assertArrayEquals(DRAFT.canonicalBytes(), response.bytes());
    }

    @Test void deniedRequestsAreIdenticalAndDoNotQueryOrDiscloseMetadata() {
        var calls = new AtomicInteger();
        var reader = new StudioPlayerPublication((target, version) -> { calls.incrementAndGet(); return Optional.empty(); });
        var foreign = new Caller(UUID.randomUUID(), Set.of(Permission.PLAYER_READ));
        var noRead = new Caller(TARGET.tenantId(), Set.of(Permission.STUDIO_PUBLISH));
        var response = assertInstanceOf(StudioPlayerResponse.Failure.class, StudioPlayerResponse.read(reader, foreign, TARGET, 8L));
        assertEquals(response, StudioPlayerResponse.read(reader, noRead, TARGET, -1L));
        assertEquals(403, response.status());
        assertEquals(Map.of("Cache-Control", "private, no-store"), response.headers());
        assertEquals(0, calls.get());
    }

    @Test void authorizedMissingAndInvalidVersionAreDifferentAndCorruptDataIsNotAnInputError() {
        var reader = new StudioPlayerPublication((t,v) -> Optional.empty());
        assertEquals(404, StudioPlayerResponse.read(reader, READER, TARGET, 10L).status());
        assertEquals(400, StudioPlayerResponse.read(reader, READER, TARGET, 0L).status());
        assertThrows(IllegalArgumentException.class, () -> StudioPlayerResponse.read(
                new StudioPlayerPublication((t,v) -> { throw new IllegalArgumentException("Unexpected adapter issue"); }),
                READER, TARGET, 1L));
        var foreignReturned = new StudioPlayerPublication((t,v) -> Optional.of(stored(new Target(UUID.randomUUID(), "floor"))));
        assertEquals(StudioPlayerResponse.read(reader, null, TARGET, 1L), StudioPlayerResponse.read(foreignReturned, READER, TARGET, 1L));
    }

    private static StudioPlayerPublication.Published stored(Target target) {
        return new StudioPlayerPublication.Published(target, 1, DRAFT.facts().canonicalSha256(), DRAFT.canonicalBytes());
    }
}
