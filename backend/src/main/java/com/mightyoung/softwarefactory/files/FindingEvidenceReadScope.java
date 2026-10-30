package com.mightyoung.softwarefactory.files;

import java.util.Set;
import java.util.UUID;

/** Finding organizations resolved by the current server-side read permission. */
public record FindingEvidenceReadScope(UUID tenantId, Set<UUID> organizationIds) {

    public FindingEvidenceReadScope {
        if (tenantId == null || organizationIds == null) {
            throw new IllegalArgumentException("A tenant and finding organization scope are required");
        }
        organizationIds = Set.copyOf(organizationIds);
    }
}
