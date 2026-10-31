package com.mightyoung.softwarefactory.dualprevention.finding;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Read model. Tenant control columns are intentionally not exposed on the wire. */
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
        OffsetDateTime updatedAt,
        UUID organizationId,
        String organizationName,
        String sourceCategory,
        String urgency,
        String location,
        String interimMeasure,
        Boolean backfill) {

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
        values.put("organizationId", organizationId);
        values.put("organizationName", organizationName);
        values.put("sourceCategory", sourceCategory);
        values.put("urgency", urgency);
        values.put("location", location);
        values.put("interimMeasure", interimMeasure);
        values.put("backfill", backfill);
        return values;
    }
}
