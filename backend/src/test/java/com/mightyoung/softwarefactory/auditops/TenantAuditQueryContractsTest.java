package com.mightyoung.softwarefactory.auditops;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static com.mightyoung.softwarefactory.auditops.TenantAuditQueryContracts.*;
import static org.assertj.core.api.Assertions.*;

class TenantAuditQueryContractsTest {
    private static final Instant FROM = Instant.parse("2026-11-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-11-02T00:00:00Z");
    private static final UUID ACTOR = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final String FINGERPRINT = "a".repeat(64);

    @Test void immutablePageKeepsCompleteItemsAndTheExactLastItemCursor() {
        Item first = item("ffffffff-ffff-ffff-ffff-ffffffffffff", FROM.plusSeconds(2));
        Item last = item("77777777-7777-7777-7777-777777777777", FROM.plusSeconds(1));
        var input = new ArrayList<>(List.of(first, last));
        var cursor = new Cursor(last.occurredAt(), last.id(), FINGERPRINT);
        var page = new Data(input, new Window(FROM, TO), true, cursor);
        input.clear();
        assertThat(page.items()).containsExactly(first, last);
        assertThatThrownBy(() -> page.items().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(page.nextCursor()).isEqualTo(cursor);
        var response = new Response(page, new Meta(ACTOR, first.id()));
        assertThat(response.meta().schemaVersion()).isEqualTo("1");
        assertThat(response.meta().requestId()).isEqualTo(ACTOR); assertThat(response.meta().traceId()).isEqualTo(first.id());
    }

    @Test void equalTimeUsesPostgresUnsignedUuidOrderingInsteadOfJavaSignedOrdering() {
        Item high = item("ffffffff-ffff-ffff-ffff-ffffffffffff", FROM);
        Item low = item("77777777-7777-7777-7777-777777777777", FROM);
        assertThatCode(() -> new Data(List.of(high, low), new Window(FROM, TO), false, null)).doesNotThrowAnyException();
        bad(() -> new Data(List.of(low, high), new Window(FROM, TO), false, null));
        bad(() -> new Data(List.of(high, high), new Window(FROM, TO), false, null));
    }

    @Test void rejectsAHasMorePageWithoutAnExactCompleteLastItem() {
        Item row = item("11111111-1111-1111-1111-111111111111", FROM);
        Window window = new Window(FROM, TO);
        bad(() -> new Data(List.of(), window, true, new Cursor(FROM, row.id(), FINGERPRINT)));
        bad(() -> new Data(List.of(row), window, true, null));
        bad(() -> new Data(List.of(row), window, false, new Cursor(FROM, row.id(), FINGERPRINT)));
        bad(() -> new Data(List.of(row), window, true, new Cursor(FROM.plusSeconds(1), row.id(), FINGERPRINT)));
        bad(() -> new Data(List.of(row), window, true, new Cursor(FROM, UUID.randomUUID(), FINGERPRINT)));
    }

    @Test void enforcesWindowPrecisionAndInclusiveExclusiveBoundaries() {
        assertThatCode(() -> new Window(FROM, FROM.plus(31, ChronoUnit.DAYS))).doesNotThrowAnyException();
        bad(() -> new Window(FROM, FROM.plus(31, ChronoUnit.DAYS).plusNanos(1000)));
        bad(() -> new Window(FROM, FROM));
        bad(() -> new Window(FROM.plusNanos(1), TO));
        bad(() -> new Window(Instant.MIN, TO));
        bad(() -> new Data(List.of(item("11111111-1111-1111-1111-111111111111", TO)), new Window(FROM, TO), false, null));
        bad(() -> new Data(List.of(item("11111111-1111-1111-1111-111111111111", FROM.minusNanos(1000))), new Window(FROM, TO), false, null));
    }

    @Test void queryRequiresAnExplicitWindowForACursorAndTypedResourcePairing() {
        assertThat(new Query(null, null, null, null, null, null, null, 20, null).from()).isNull();
        bad(() -> new Query(FROM, null, null, null, null, null, null, 20, null));
        bad(() -> new Query(null, null, null, null, ACTOR, null, null, 20, null));
        bad(() -> new Query(null, null, null, null, null, null, null, 20, new Cursor(FROM, ACTOR, FINGERPRINT)));
        bad(() -> new Query(FROM, TO, null, null, null, null, null, 20, new Cursor(TO, ACTOR, FINGERPRINT)));
        bad(() -> new Query(FROM, TO, null, null, null, null, null, 51, null));
    }

    @ParameterizedTest @ValueSource(strings = {"", "has space", "line\nbreak", "é", "secret/value"})
    void metadataIdentifiersCannotCarryArbitraryText(String value) {
        bad(() -> new Item(ACTOR, ACTOR, null, value, "finding", null, Result.SUCCESS, null, "policy-v1", FROM));
        bad(() -> new Item(ACTOR, ACTOR, null, "finding.create", "finding", null, Result.SUCCESS, null, value, FROM));
    }

    @Test void requiredValuesFingerprintAndSchemaVersionStayClosed() {
        bad(() -> new Item(null, ACTOR, null, "finding.create", "finding", null, Result.SUCCESS, null, "v1", FROM));
        bad(() -> new Cursor(FROM, ACTOR, "A".repeat(64)));
        bad(() -> new Cursor(FROM, ACTOR, "a".repeat(63)));
        bad(() -> new Meta(ACTOR, ACTOR, "2"));
        bad(() -> new Meta(null, ACTOR));
        bad(() -> new Response(null, new Meta(ACTOR, ACTOR)));
    }

    @Test void declaredIdentifierLengthsAreEnforcedBeforeResponseEncoding() {
        assertThatCode(() -> new Query(null, null, null, "r".repeat(64), null, "a".repeat(128), null, 1, null)).doesNotThrowAnyException();
        bad(() -> new Query(null, null, null, "r".repeat(65), null, null, null, 1, null));
        bad(() -> new Query(null, null, null, null, null, "a".repeat(129), null, 1, null));
        bad(() -> new Item(ACTOR, ACTOR, null, "finding.create", "finding", null, Result.SUCCESS, null, "p".repeat(65), FROM));
    }

    @Test void theValidatedSnapshotIsExactlyTheCollectionRetainedByThePage() {
        Item expected = item("11111111-1111-1111-1111-111111111111", FROM);
        Item changed = item("22222222-2222-2222-2222-222222222222", TO);
        // Models a weakly consistent caller changing the source between iterator validation and toArray.
        var changing = new java.util.AbstractList<Item>() {
            @Override public int size() { return 1; }
            @Override public Item get(int index) { if (index != 0) throw new IndexOutOfBoundsException(); return expected; }
            @Override public Object[] toArray() { return new Object[]{changed}; }
        };
        var page = new Data(changing, new Window(FROM, TO), false, null);
        assertThat(page.items()).containsExactly(expected);
        assertThat(page.items()).allSatisfy(row -> assertThat(page.window().contains(row.occurredAt())).isTrue());
        var nullEntry = new ArrayList<Item>(); nullEntry.add(null);
        bad(() -> new Data(nullEntry, new Window(FROM, TO), false, null));
    }

    private static Item item(String id, Instant at) {
        return new Item(UUID.fromString(id), ACTOR, null, "finding.create", "finding", null, Result.SUCCESS, null, "policy-v1", at);
    }
    private static void bad(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isExactlyInstanceOf(Failure.class).satisfies(error -> assertThat(((Failure) error).code()).isEqualTo(Code.BAD_REQUEST));
    }
}
