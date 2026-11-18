package com.mightyoung.softwarefactory.authorization;

import com.mightyoung.softwarefactory.identity.CurrentTenantContext;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;

/** Fresh, persisted answer to "does the current member hold this catalogued permission with a non-empty scope". */
public interface EffectivePermissions {
    boolean has(CurrentTenantContext context, String permissionCode);

    /** Organizations the member may read under this permission; empty when the permission is not held. */
    Set<UUID> readableOrganizations(CurrentTenantContext context, String permissionCode);

    /** The subset of the given permission codes the member currently holds; never cached across requests. */
    Set<String> granted(CurrentTenantContext context, Collection<String> permissionCodes);
}
