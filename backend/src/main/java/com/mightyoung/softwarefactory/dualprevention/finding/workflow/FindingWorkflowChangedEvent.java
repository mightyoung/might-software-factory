package com.mightyoung.softwarefactory.dualprevention.finding.workflow;

import java.util.UUID;

/** Minimal, non-sensitive event payload for post-commit workflow integrations. */
public record FindingWorkflowChangedEvent(
        UUID tenantId,
        UUID findingId,
        UUID commandId,
        UUID actorId,
        String action,
        FindingWorkflowState fromState,
        FindingWorkflowState toState,
        int roundNo,
        long version) {
}
