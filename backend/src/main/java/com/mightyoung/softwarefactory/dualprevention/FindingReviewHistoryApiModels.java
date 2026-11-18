package com.mightyoung.softwarefactory.dualprevention;

import com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.Metadata;
import java.time.OffsetDateTime;
import java.util.*;

public final class FindingReviewHistoryApiModels {
    private FindingReviewHistoryApiModels() { }
    public record Response(Data data, Metadata meta) { }
    /** Actions describe this snapshot only. Commands always revalidate current authority. */
    public record Data(List<Entry> entries, long currentVersion, String currentState, int reviewCycle,
                       String reviewProtocol, String processDefinitionKey, String reviewStage,
                       List<String> availableReviewActions, String blockedReason, boolean hasMore, String nextCursor) {
        public Data { entries=List.copyOf(entries);availableReviewActions=List.copyOf(availableReviewActions); }
    }
    public record ActorDisplay(String label, String provenance) { }
    public record Entry(String id, String origin, int reviewCycle, String stage, String decision,
                        UUID actorMemberId, ActorDisplay actorDisplay, OffsetDateTime occurredAt,
                        String text, String recordedGrade, Integer nextReviewCycle) { }
}
