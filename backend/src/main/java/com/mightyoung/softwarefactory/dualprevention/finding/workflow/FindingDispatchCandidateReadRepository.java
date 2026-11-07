package com.mightyoung.softwarefactory.dualprevention.finding.workflow;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/** One read snapshot for the finding, current permission and eligible-member query. */
public interface FindingDispatchCandidateReadRepository {
    <T> T inTenantReadSnapshot(UUID tenantId, Function<Snapshot, T> work);

    interface Snapshot {
        Optional<Finding> find(UUID findingId);
        boolean hasNotice(UUID findingId, int roundNo);
    }

    record Finding(UUID id, UUID organizationId, FindingWorkflowState state, int roundNo, long version) { }
}
