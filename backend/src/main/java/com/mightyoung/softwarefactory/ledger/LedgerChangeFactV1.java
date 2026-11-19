package com.mightyoung.softwarefactory.ledger;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Immutable revision fact; persistence does not claim listener delivery or recovery acceptance. */
public record LedgerChangeFactV1(UUID eventId, String eventType, int schemaVersion, String appKey,
        String entityKey, UUID tenantId, UUID recordId, long version, String definitionVersion,
        String action, UUID commandId, UUID actorId, UUID accountId, UUID memberId,
        Instant occurredAt, Map<String,Object> snapshot) {
    public LedgerChangeFactV1 { snapshot=java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(snapshot)); }
    public static final String EVENT_TYPE = "ledger.record.changed";
    public static final int SCHEMA_VERSION = 1;
}
