package com.mightyoung.softwarefactory.tenancy;

import java.util.Optional;
import java.util.UUID;

/** Fresh tenant-owned application entitlement read; tenant must come from verified server context. */
public interface TenantApplicationReadRepository {
    /** Current entitlement in the caller's existing snapshot; never opens a transaction or changes tenant context. */
    default Optional<TenantApplicationState> findCurrentInCaller(UUID tenantId, String applicationKey) {
        throw new IllegalStateException("Caller application entitlement boundary is unavailable");
    }
    Optional<TenantApplicationState> findCurrent(UUID tenantId, String applicationKey);
}
