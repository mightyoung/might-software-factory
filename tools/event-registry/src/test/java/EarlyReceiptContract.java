import java.time.Duration;
import java.time.Instant;

// Test-only contract for a pinned resume delivery, not an inbox or a Flowable adapter.
record EarlyReceiptContract(DeliveryKey key, Target target, String originalBody, Instant expiresAt) {
    record TransportKey(String tenant, StructuredReceiptEvent.Identity identity) {}
    record DeliveryKey(TransportKey transport, String subscriptionVersion) {}
    record Target(String instance, String batch) {}
    enum Decision { WAIT, DELIVER, EXPIRE }

    static EarlyReceiptContract accept(
            String trustedTenant, String subscriptionVersion, String body, Instant acceptedAt, Duration ttl) {
        if (ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("Expected a positive early-delivery TTL");
        }
        var event = StructuredReceiptEvent.parse(body);
        var fields = event.flowableFields();
        if (!"batchApproved".equals(fields.get("type"))) {
            throw new IllegalArgumentException("Expected a resume event");
        }
        return new EarlyReceiptContract(
                new DeliveryKey(new TransportKey(trustedTenant, event.identity()), subscriptionVersion),
                new Target(fields.get("instance"), fields.get("batch")),
                body, acceptedAt.plus(ttl));
    }

    EarlyReceiptContract replay(EarlyReceiptContract candidate) {
        if (!key.equals(candidate.key) || !originalBody.equals(candidate.originalBody)) {
            throw new IllegalArgumentException("Expected the same delivery key and original body");
        }
        return this;
    }

    Decision evaluate(Instant decisionTime, boolean pinnedTargetReady) {
        if (!decisionTime.isBefore(expiresAt)) return Decision.EXPIRE;
        return pinnedTargetReady ? Decision.DELIVER : Decision.WAIT;
    }
}
