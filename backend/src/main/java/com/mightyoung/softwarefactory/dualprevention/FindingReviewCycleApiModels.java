package com.mightyoung.softwarefactory.dualprevention;

import com.mightyoung.softwarefactory.dualprevention.finding.workflow.FindingWorkflowContracts.CommandResult;
import com.mightyoung.softwarefactory.dualprevention.finding.workflow.FindingWorkflowState;
import java.util.UUID;

/** Explicit V3 result; legacy CommandResult serialization deliberately omits reviewCycle. */
public final class FindingReviewCycleApiModels {
    private FindingReviewCycleApiModels() { }
    public record Response(Data data, Meta meta) { }
    public record Meta(String requestId, String traceId, String schemaVersion) { }
    public record Data(UUID findingId, String code, FindingWorkflowState state, int roundNo,
                       long version, int reviewCycle) { }
    static Response response(CommandResult result) {
        return new Response(new Data(result.findingId(), result.code(), result.state(), result.roundNo(),
                result.version(), result.reviewCycle()),
                new Meta(UUID.randomUUID().toString(), UUID.randomUUID().toString(), "1"));
    }
}
