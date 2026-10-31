package com.mightyoung.softwarefactory.dualprevention;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Resolves display fields only for organization IDs already authorized for finding.create. */
public interface ReportableOrganizationReadRepository {

    List<ReportableOrganization> findActive(UUID tenantId, Set<UUID> authorizedOrganizationIds);
}
