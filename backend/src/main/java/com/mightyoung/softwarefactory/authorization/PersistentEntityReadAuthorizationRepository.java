package com.mightyoung.softwarefactory.authorization;

import com.mightyoung.softwarefactory.identity.CurrentTenantContext;

import java.util.Optional;

/** Read-only persistence boundary for one entity-read authorization decision. */
public interface PersistentEntityReadAuthorizationRepository {

    Optional<FindingReadAuthorizationSnapshot> loadFindingReadSnapshot(
            CurrentTenantContext context, String permissionCode);
}
