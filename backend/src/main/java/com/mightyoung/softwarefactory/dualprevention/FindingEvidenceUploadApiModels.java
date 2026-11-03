package com.mightyoung.softwarefactory.dualprevention;

import com.mightyoung.softwarefactory.dualprevention.finding.evidenceupload.FindingEvidenceUploadContracts.Status;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.OffsetDateTime;
import java.util.UUID;

public final class FindingEvidenceUploadApiModels {
    private FindingEvidenceUploadApiModels() {}
    public record Response(Data data, Meta meta) {}
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Data(UUID uploadIntentId, String state, OffsetDateTime expiresAt, String recoveryAction, String statusUrl, Boolean cancelRequested) {}
    public record Meta(String requestId, String traceId, String schemaVersion) {}
    static Response response(Status status, String statusUrl) {
        return new Response(new Data(status.intentId(), status.state().name(), status.expiresAt(),
                status.state().name().equals("EXPIRED") ? "FRESH_KEY_REQUIRED" : null, statusUrl,status.cancelRequested()?Boolean.TRUE:null),
                new Meta(UUID.randomUUID().toString(), UUID.randomUUID().toString(), "2"));
    }
}
