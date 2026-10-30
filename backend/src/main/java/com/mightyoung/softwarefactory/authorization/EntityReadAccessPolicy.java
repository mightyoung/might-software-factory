package com.mightyoung.softwarefactory.authorization;

import com.mightyoung.softwarefactory.identity.CurrentTenantContext;

import java.util.Optional;

/** Server-side action permission and effective data-scope lookup. */
public interface EntityReadAccessPolicy {

    Optional<EntityReadGrant> resolveEntityReadGrant(CurrentTenantContext context, String entityKey);
}
