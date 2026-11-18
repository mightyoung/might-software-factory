package com.mightyoung.softwarefactory.directory;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import static com.mightyoung.softwarefactory.directory.DirectoryReferenceContracts.*;

/** Joins the owning caller transaction; never opens a transaction or grants an entity action. */
public interface DirectoryReferenceReadPort {
    Optional<Organization> findOrganizationAt(UUID tenantId, UUID id, Instant observedAt);
    Optional<Position> findPositionAt(UUID tenantId, UUID id, Instant observedAt);
    Page queryOrganizationsAt(UUID tenantId, Set<UUID> allowedOrgIds, Query query, Instant observedAt);
    Page queryPositionsAt(UUID tenantId, UUID orgId, Set<UUID> allowedOrgIds, Query query, Instant observedAt);
}
