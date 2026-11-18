package com.mightyoung.softwarefactory.ledger;

import com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

public final class LedgerContracts {
    private LedgerContracts() { }
    public record Envelope<T>(T data, Meta meta) { }
    public record Meta(String traceId) { }
    public record DefinitionData(LedgerDefinition definition, LedgerDefinition.Use use, java.util.Set<String> effectiveActions) {
        public DefinitionData { effectiveActions=java.util.Set.copyOf(effectiveActions); }
    }
    public record CreateRequest(String definitionVersion, Map<String,JsonNode> values) { }
    /** values is the complete editable form, not a partial patch; omitted protected fields stay server owned. */
    public record UpdateRequest(String definitionVersion, Long expectedVersion, Map<String,JsonNode> values) { }
    public record QueryRequest(String definitionVersion, EntityQueryContract.Request query) { }
    public record ReferenceRequest(String definitionVersion, LedgerDefinition.Use use, String search,
                                   Map<String,JsonNode> context, Integer size) { }
    public record RecordData(UUID id, long version, String definitionVersion, Map<String,Object> values, java.util.Set<String> effectiveActions) {
        public RecordData { values=java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(values)); effectiveActions=java.util.Set.copyOf(effectiveActions); }
    }
    public record QueryData(List<RecordData> records, int page, int size, long total, boolean hasMore) { }
    public record ReferenceOption(UUID id, String label) { }
    public record ReferenceData(List<ReferenceOption> options, boolean hasMore) { }
    public record CommandData(UUID commandId, UUID id, long version, String definitionVersion, UUID eventId, Instant occurredAt, boolean replay) { }
}
