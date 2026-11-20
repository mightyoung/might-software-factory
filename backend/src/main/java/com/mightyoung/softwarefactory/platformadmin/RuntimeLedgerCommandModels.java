package com.mightyoung.softwarefactory.platformadmin;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public final class RuntimeLedgerCommandModels {
    private RuntimeLedgerCommandModels() {}
    public record CreateRequest(UUID requestId, Long definitionVersion, UUID organizationId, Map<String,Object> values) {}
    public record UpdateRequest(UUID requestId, Long expectedVersion, Map<String,Object> values) {}
    public record DeleteRequest(UUID requestId, Long expectedVersion) {}
    public record RecordView(UUID id, String key, long definitionVersion, UUID organizationId,
                             Map<String,Object> values, long version, Instant createdAt, Instant updatedAt) {}
}
