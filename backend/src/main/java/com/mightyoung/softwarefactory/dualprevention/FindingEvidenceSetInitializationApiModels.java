package com.mightyoung.softwarefactory.dualprevention;

import com.mightyoung.softwarefactory.dualprevention.finding.evidenceupload.FindingEvidenceSetInitializationContracts.Result;
import java.time.OffsetDateTime;
import java.util.UUID;

public final class FindingEvidenceSetInitializationApiModels {
    private FindingEvidenceSetInitializationApiModels() {}
    public record Response(Data data, Meta meta) {}
    /** Immutable initialization facts; this revision is not the set's current editable revision. */
    public record Data(UUID evidenceSetId, String state, long initialEvidenceRevision, OffsetDateTime createdAt) {}
    public record Meta(String requestId, String traceId, String schemaVersion) {}
    static Response response(Result result) {
        return new Response(new Data(result.evidenceSetId(), "UPLOADING", result.initialEvidenceRevision(), result.createdAt()),
                new Meta(UUID.randomUUID().toString(), UUID.randomUUID().toString(), "1"));
    }
}
