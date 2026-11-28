package com.mightyoung.softwarefactory.platformadmin.digitaltwin;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import static com.mightyoung.softwarefactory.platformadmin.digitaltwin.StudioPublicationPolicy.*;
import static org.junit.jupiter.api.Assertions.*;

class StudioPublicationPolicyTest {
    private static final UUID NORTH = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID SOUTH = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final Target A = new Target(NORTH, "workshop");
    private static final Target B = new Target(SOUTH, "workshop");
    private static final Caller PUBLISH = new Caller(NORTH, Set.of(Permission.STUDIO_PUBLISH));
    private static final Caller READ = new Caller(NORTH, Set.of(Permission.PLAYER_READ));
    private static final String HASH = "a".repeat(64);
    private static final DraftFacts DRAFT = new DraftFacts("workshop", HASH, 3, List.of(
            new Wall(1, new Point(0, 0), new Point(30, 40))),
            List.of(new Zone(2, List.of(new Point(0,0), new Point(20,0), new Point(20,30), new Point(0,30)))));

    @Test void explicitPermissionsAndTenantOwnershipAreIndependentAndFailClosed() {
        authorizeRead(READ, A);
        assertThrows(Forbidden.class, () -> authorizeRead(PUBLISH, A));
        assertThrows(Forbidden.class, () -> authorizeRead(READ, B));
        assertThrows(Forbidden.class, () -> authorizeRead(null, A));
        assertThrows(Forbidden.class, () -> decide(READ, A, 0, null, DRAFT));
        assertThrows(Forbidden.class, () -> decide(PUBLISH, B, 0, null, DRAFT));
        assertThrows(Forbidden.class, () -> decide(PUBLISH, A, 1, new Head(B, 1, HASH), DRAFT));
        assertEquals(1L, decide(new Caller(SOUTH, Set.of(Permission.STUDIO_PUBLISH)), B, 0, null, DRAFT).version());
    }

    @Test void optimisticRevisionDetectsStaleEvenWhenCanonicalBytesAreUnchanged() {
        var first = decide(PUBLISH, A, 0, null, DRAFT);
        assertEquals(Outcome.APPEND, first.outcome());
        assertEquals(new Metrics(3, 1, 1, 5, 6), first.metrics());
        assertEquals(HASH, first.canonicalSha256());
        var head = new Head(A, 1, HASH);
        assertThrows(VersionConflict.class, () -> decide(PUBLISH, A, 0, head, DRAFT));
        assertEquals(Outcome.UNCHANGED, decide(PUBLISH, A, 1, head, DRAFT).outcome());
        var changed = new DraftFacts("workshop", "b".repeat(64), 3, DRAFT.walls(), DRAFT.zones());
        assertEquals(2L, decide(PUBLISH, A, 1, head, changed).version());
        assertThrows(VersionConflict.class, () ->
                decide(PUBLISH,A,Long.MAX_VALUE,new Head(A,Long.MAX_VALUE,HASH),changed));
    }

    @Test void computedMetricsAcceptConcavePolygonsAndRejectCrossingDuplicateOrReversedGeometry() {
        var zone = new Zone(3, List.of(new Point(0,0), new Point(30,0), new Point(30,10),
                new Point(10,10), new Point(10,30), new Point(0,30)));
        var concave = new DraftFacts("workshop", HASH, 0, List.of(), List.of(zone));
        assertEquals(5, decide(PUBLISH, A, 0, null, concave).metrics().zoneAreaM2());
        for (var zones : List.of(
                List.of(new Zone(2, List.of(new Point(0,0), new Point(20,20), new Point(0,20), new Point(20,0)))),
                List.of(new Zone(1, DRAFT.zones().getFirst().points())))) {
            assertThrows(IllegalArgumentException.class, () -> decide(PUBLISH, A, 0, null,
                    new DraftFacts("workshop", HASH, 0, DRAFT.walls(), zones)));
        }
        var wall = DRAFT.walls().getFirst();
        assertThrows(IllegalArgumentException.class, () -> decide(PUBLISH, A, 0, null, new DraftFacts(
                "workshop", HASH, 0, List.of(wall, new Wall(4, wall.end(), wall.start())), List.of())));
        assertThrows(IllegalArgumentException.class, () -> decide(PUBLISH, A, 0, null,
                new DraftFacts("other", HASH, 0, List.of(), List.of())));
    }
}
