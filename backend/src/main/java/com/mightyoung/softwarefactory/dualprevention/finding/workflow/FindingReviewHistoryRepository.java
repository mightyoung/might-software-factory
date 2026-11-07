package com.mightyoung.softwarefactory.dualprevention.finding.workflow;

import com.mightyoung.softwarefactory.dualprevention.finding.FindingReadScope;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.function.Function;

public interface FindingReviewHistoryRepository {
    <T> T inTenantReadSnapshot(UUID tenantId, Function<Snapshot,T> work);
    interface Snapshot {
        Optional<Current> find(FindingReadScope scope, UUID id);
        List<Entry> entries(FindingReadScope scope, UUID id, Cursor after, int limit);
        Optional<UUID> approvedPerson(UUID id, int cycle, boolean cycleProtocol);
    }
    record Current(UUID id, UUID organizationId, UUID reporterMemberId, String state, long version,
                   int reviewCycle, FindingReviewProtocol reviewProtocol) { }
    record Entry(String id, String origin, int reviewCycle, String stage, String decision, UUID actorMemberId,
                 OffsetDateTime occurredAt, String text, String recordedGrade, Integer nextReviewCycle) { }
    record Cursor(int reviewCycle, OffsetDateTime occurredAt, String kind, String id) { }
}
