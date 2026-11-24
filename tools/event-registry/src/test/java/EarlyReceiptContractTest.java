import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EarlyReceiptContractTest {
    private static final Instant ACCEPTED = Instant.parse("2026-11-25T00:00:00Z");
    private static final Duration TTL = Duration.ofSeconds(30);
    private static final String BODY = StructuredReceiptEventTest.APPROVED;

    @Test
    void deliversOnlyWhenThePinnedTargetIsReadyBeforeExpiry() {
        var entry = entry("tenant-a", "subscription-v1", BODY);
        var justBeforeExpiry = ACCEPTED.plus(TTL).minusNanos(1);
        assertEquals(EarlyReceiptContract.Decision.WAIT, entry.evaluate(justBeforeExpiry, false));
        assertEquals(EarlyReceiptContract.Decision.DELIVER, entry.evaluate(justBeforeExpiry, true));
        assertEquals(EarlyReceiptContract.Decision.EXPIRE, entry.evaluate(ACCEPTED.plus(TTL), true));
        assertEquals(EarlyReceiptContract.Decision.EXPIRE, entry.evaluate(ACCEPTED.plus(TTL), false));
    }

    @Test
    void retryPreservesTheOriginalBodyAndDeadline() {
        var entry = entry("tenant-a", "subscription-v1", BODY);
        var acceptedLater = EarlyReceiptContract.accept(
                "tenant-a", "subscription-v1", BODY, ACCEPTED.plusSeconds(20), TTL);
        assertEquals(entry.key(), acceptedLater.key());
        assertSame(entry, entry.replay(acceptedLater));
        assertEquals(EarlyReceiptContract.Decision.EXPIRE, entry.evaluate(ACCEPTED.plus(TTL), true));
    }

    @Test
    void changedPayloadWithTheSameTransportIdentityConflicts() {
        var original = entry("tenant-a", "subscription-v1", BODY);
        var changed = entry("tenant-a", "subscription-v1", BODY.replace("\"batch\":\"A\"", "\"batch\":\"B\""));
        assertEquals(original.key(), changed.key());
        assertNotEquals(original.target(), changed.target());
        assertThrows(IllegalArgumentException.class, () -> original.replay(changed));
    }

    @Test
    void transportIdentityAndPinnedDeliveryAreNotBusinessDeduplication() {
        var original = entry("tenant-a", "subscription-v1", BODY);
        var nextSubscription = entry("tenant-a", "subscription-v2", BODY);
        var otherTenant = entry("tenant-b", "subscription-v1", BODY);
        var otherSource = entry("tenant-a", "subscription-v1", BODY.replace("fixture:inspection", "fixture:manual"));
        assertEquals(original.target(), otherSource.target());
        for (var separate : new EarlyReceiptContract[] { nextSubscription, otherTenant, otherSource }) {
            assertNotEquals(original.key(), separate.key());
            assertThrows(IllegalArgumentException.class, () -> original.replay(separate));
        }
    }

    @Test
    void rejectsNonPositiveTtlAndEventsThatDoNotResumeAnInstance() {
        for (var duration : new Duration[] { Duration.ZERO, Duration.ofSeconds(-1) }) {
            assertThrows(IllegalArgumentException.class,
                    () -> EarlyReceiptContract.accept("tenant-a", "subscription-v1", BODY, ACCEPTED, duration));
        }
        assertThrows(IllegalArgumentException.class,
                () -> entry("tenant-a", "subscription-v1", StructuredReceiptEventTest.REQUESTED));
    }

    private static EarlyReceiptContract entry(String tenant, String subscription, String body) {
        return EarlyReceiptContract.accept(tenant, subscription, body, ACCEPTED, TTL);
    }
}
