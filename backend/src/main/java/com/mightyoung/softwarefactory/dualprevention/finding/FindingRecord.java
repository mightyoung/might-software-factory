package com.mightyoung.softwarefactory.dualprevention.finding;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Read model. Tenant and organization control columns are intentionally not exposed on the wire. */
public record FindingRecord(
        UUID id,
        String code,
        String description,
        OffsetDateTime discoveredAt,
        UUID sourceEventId,
        UUID riskId,
        String grade,
        String state,
        Integer roundNo,
        Long version,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    public Map<String, Object> toApiMap() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("id", id);
        values.put("code", code);
        values.put("description", description);
        values.put("discoveredAt", discoveredAt);
        values.put("sourceEventId", sourceEventId);
        values.put("riskId", riskId);
        values.put("grade", grade);
        values.put("state", state);
        values.put("roundNo", roundNo);
        values.put("version", version);
        values.put("createdAt", createdAt);
        values.put("updatedAt", updatedAt);
        return values;
    }
}
