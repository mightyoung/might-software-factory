package com.mightyoung.softwarefactory.dualprevention.risk;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Committed draft revision reference; contains no narrative or hazard membership. */
public record RiskScenarioDraftChangedEvent(UUID tenantId, UUID commandId, String resourceType,
        UUID resourceId, String actionCode, long version, Instant occurredAt, UUID traceId,
        String changeKind) {
    public RiskScenarioDraftChangedEvent {
        Objects.requireNonNull(tenantId);
        Objects.requireNonNull(commandId);
        Objects.requireNonNull(resourceId);
        Objects.requireNonNull(occurredAt);
        Objects.requireNonNull(traceId);
        if (occurredAt.getNano() % 1000 != 0
                || occurredAt.isBefore(Instant.parse("0001-01-01T00:00:00Z"))
                || !occurredAt.isBefore(Instant.parse("+10000-01-01T00:00:00Z")))
            throw new IllegalArgumentException("Invalid risk draft event time");
        if (!"risk_scenario".equals(resourceType) || version < 1 || version > 9007199254740991L)
            throw new IllegalArgumentException("Invalid risk draft event");
        boolean create = "risk_scenario.create".equals(actionCode) && "CREATE".equals(changeKind) && version == 1;
        boolean update = "risk_scenario.update".equals(actionCode) && "UPDATE".equals(changeKind) && version > 1;
        if (!create && !update) throw new IllegalArgumentException("Invalid risk draft event");
    }
}
