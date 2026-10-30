package com.mightyoung.softwarefactory.authorization;

import com.mightyoung.softwarefactory.identity.CurrentTenantContext;

import java.util.Optional;

/** Resolves current persisted identity and the effective organization scope for one finding action. */
public interface FindingWorkflowAuthorizationRepository {

    Optional<FindingReadAuthorizationSnapshot> loadActionSnapshot(
            CurrentTenantContext context, String permissionCode, String action);
}
