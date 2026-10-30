package com.mightyoung.softwarefactory.dualprevention.finding;

import java.util.Set;
import java.util.UUID;

/** Immutable scope derived by the server-side authorization policy. */
public record FindingReadScope(UUID actorId, UUID tenantId, UUID memberId, Set<UUID> organizationIds) {

    public FindingReadScope {
        if (actorId == null || tenantId == null || memberId == null || organizationIds == null) {
            throw new IllegalArgumentException("A complete finding read scope is required");
        }
        organizationIds = Set.copyOf(organizationIds);
    }
}
