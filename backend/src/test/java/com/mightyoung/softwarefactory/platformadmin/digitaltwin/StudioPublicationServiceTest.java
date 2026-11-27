package com.mightyoung.softwarefactory.platformadmin.digitaltwin;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

import static com.mightyoung.softwarefactory.platformadmin.digitaltwin.StudioPublicationPolicy.*;
import static org.junit.jupiter.api.Assertions.*;

class StudioPublicationServiceTest {
    private static final UUID TENANT = UUID.randomUUID();
    private static final Target TARGET = new Target(TENANT, "floor");
    private static final Caller PUBLISHER = new Caller(TENANT, UUID.randomUUID(), UUID.randomUUID(), true, Set.of());
    private static final byte[] VALID = """
            {"snapshotVersion":1,"scene":{"schemaVersion":1,"screenId":"floor","devices":[]},
             "floorplan":{"walls":[],"zones":[]}}
            """.getBytes(StandardCharsets.UTF_8);

    @Test void refusesUnknownAndForeignCallersBeforeDecodingOrOpeningATransaction() {
        var fixture = new Fixture();
        var service = new StudioPublicationService(fixture);
        assertThrows(Forbidden.class, () -> service.publish(null, TARGET, -1, null));
        assertThrows(Forbidden.class, () -> service.publish(new Caller(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), true, Set.of()), TARGET, 0, null));
        assertThrows(Forbidden.class, () -> service.publish(new Caller(TENANT, UUID.randomUUID(), UUID.randomUUID(), false, Set.of(TARGET.screenKey())), TARGET, 0, null));
        assertEquals(0, fixture.calls);
        assertThrows(IllegalArgumentException.class, () -> service.publish(PUBLISHER, TARGET, 0, null));
        assertEquals(0, fixture.calls);
    }

    @Test void onlyMatchingCurrentVersionsCanReturnTheExistingVersionAndAuditCommitsOnce() {
        var fixture = new Fixture();
        var service = new StudioPublicationService(fixture);
        var first = service.publish(PUBLISHER, TARGET, 0, VALID);
        assertEquals(Outcome.APPEND, first.outcome());
        assertEquals(TARGET, fixture.saved.target());
        assertEquals(first.canonicalSha256(), fixture.saved.canonicalSha256());
        assertEquals(java.util.List.of("lock", "append", "audit", "commit"), fixture.trace);
        byte[] returnedBytes = fixture.saved.canonicalBytes();
        returnedBytes[0] = 0;
        assertNotEquals(0, fixture.saved.canonicalBytes()[0]);
        fixture.trace.clear();
        assertThrows(VersionConflict.class, () -> service.publish(PUBLISHER, TARGET, 0, VALID));
        assertEquals(java.util.List.of("lock", "rollback"), fixture.trace);
        fixture.trace.clear();
        assertEquals(Outcome.UNCHANGED, service.publish(PUBLISHER, TARGET, 1, VALID).outcome());
        assertEquals(java.util.List.of("lock", "commit"), fixture.trace);
        assertEquals(1, fixture.audits);
    }

    @Test void auditFailureCannotExposeSuccessOrCommitSnapshot() {
        var fixture = new Fixture();
        fixture.failAudit = true;
        assertThrows(IllegalStateException.class, () -> new StudioPublicationService(fixture).publish(PUBLISHER, TARGET, 0, VALID));
        assertNull(fixture.saved);
        assertEquals(0, fixture.audits);
        assertEquals(java.util.List.of("lock", "append", "audit", "rollback"), fixture.trace);
    }

    @Test void repositoryResultFromAnotherTargetNeverLeaksOrPublishes() {
        var fixture = new Fixture();
        fixture.otherHead = new Head(new Target(UUID.randomUUID(), "floor"), 14, "0".repeat(64));
        assertThrows(Forbidden.class, () -> new StudioPublicationService(fixture).publish(PUBLISHER, TARGET, 0, VALID));
        assertNull(fixture.saved);
        assertEquals(java.util.List.of("lock", "rollback"), fixture.trace);
    }

    private static final class Fixture implements StudioPublicationService.Transaction {
        private final ArrayList<String> trace = new ArrayList<>();
        private StudioPublicationService.Snapshot saved;
        private Head otherHead;
        private int calls;
        private int audits;
        private boolean failAudit;

        @Override public Decision execute(Function<StudioPublicationService.PublicationUnit, Decision> command) {
            calls++;
            class Staged implements StudioPublicationService.PublicationUnit {
                StudioPublicationService.Snapshot pending;
                boolean audited;
                public Head lockHead(Target target) {
                    trace.add("lock");
                    assertEquals(TARGET, target);
                    return otherHead != null ? otherHead : saved == null ? null : new Head(saved.target(), saved.version(), saved.canonicalSha256());
                }
                public void append(StudioPublicationService.Snapshot snapshot) { trace.add("append"); pending = snapshot; }
                public void audit(Caller caller, StudioPublicationService.Snapshot snapshot) {
                    trace.add("audit");
                    assertSame(pending, snapshot);
                    assertEquals(PUBLISHER, caller);
                    if (failAudit) throw new IllegalStateException("Fixture audit failure");
                    audited = true;
                }
            }
            var staged = new Staged();
            try {
                var decision = command.apply(staged);
                if (staged.pending != null) saved = staged.pending;
                if (staged.audited) audits++;
                trace.add("commit");
                return decision;
            } catch (RuntimeException failure) {
                trace.add("rollback");
                throw failure;
            }
        }
    }
}
