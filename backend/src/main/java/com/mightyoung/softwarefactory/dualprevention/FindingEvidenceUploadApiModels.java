package com.mightyoung.softwarefactory.dualprevention;

import com.mightyoung.softwarefactory.dualprevention.finding.evidenceupload.FindingEvidenceUploadContracts;
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
    public record SetData(UUID evidenceSetId, long evidenceRevision, java.util.List<Data> uploads) {}
    public record SetResponse(SetData data, Meta meta) {}
    static SetResponse setResponse(FindingEvidenceUploadContracts.CurrentSet set, String findingId, int roundNo) {
        var uploads = set.uploads().stream().map(status -> response(status,
                "/api/v1/findings/" + findingId + "/remediation-rounds/" + roundNo + "/evidence-uploads/" + status.intentId()).data()).toList();
        return new SetResponse(new SetData(set.evidenceSetId(), set.evidenceRevision(), uploads),
                new Meta(UUID.randomUUID().toString(), UUID.randomUUID().toString(), "2"));
    }
    static Response response(Status status, String statusUrl) {
        return new Response(new Data(status.intentId(), status.state().name(), status.expiresAt(),
                status.state().name().equals("EXPIRED") ? "FRESH_KEY_REQUIRED" : null, statusUrl,status.cancelRequested()?Boolean.TRUE:null),
                new Meta(UUID.randomUUID().toString(), UUID.randomUUID().toString(), "2"));
    }
}
