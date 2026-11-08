package com.mightyoung.softwarefactory.dualprevention.finding;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Persisted detail facts; member labels reflect the current same-tenant directory. */
public record FindingDetailContext(Member reporter, Member responsible,
                                   List<HistoryEntry> history, boolean historyTruncated) {
    public FindingDetailContext {
        history = List.copyOf(history);
    }

    public Map<String, Object> toApiMap() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("reporter", reporter);
        result.put("responsible", responsible);
        result.put("history", history);
        result.put("historyTruncated", historyTruncated);
        return result;
    }

    public record Member(UUID memberId, String displayName) { }

    public record HistoryEntry(UUID id, OffsetDateTime at, String fromState, String toState,
                               long fromVersion, long toVersion, String actorName, String reason) { }
}
