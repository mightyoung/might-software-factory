package com.mightyoung.softwarefactory.authorization;

import java.util.Set;
import java.util.UUID;

/** One repeatable-read view of the persisted identity state and finding row scope. */
public record FindingReadAuthorizationSnapshot(
        String accountStatus,
        String tenantStatus,
        String memberStatus,
        long tenantAuthEpoch,
        long memberAuthEpoch,
        Set<UUID> organizationIds) {

    public FindingReadAuthorizationSnapshot {
        organizationIds = organizationIds == null ? null : Set.copyOf(organizationIds);
    }
}
