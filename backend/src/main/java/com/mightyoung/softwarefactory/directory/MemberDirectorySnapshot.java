package com.mightyoung.softwarefactory.directory;

import java.util.Set;
import java.util.UUID;

/** Current active organization, position, and group memberships for one tenant member. */
public record MemberDirectorySnapshot(
        Set<UUID> organizationIds,
        Set<UUID> positionIds,
        Set<UUID> groupIds) {

    public MemberDirectorySnapshot {
        organizationIds = Set.copyOf(organizationIds);
        positionIds = Set.copyOf(positionIds);
        groupIds = Set.copyOf(groupIds);
    }
}
