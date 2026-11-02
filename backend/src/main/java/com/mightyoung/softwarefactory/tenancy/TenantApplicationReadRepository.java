package com.mightyoung.softwarefactory.tenancy;

import java.util.Optional;
import java.util.UUID;

/** Fresh tenant-owned application entitlement read; tenant must come from verified server context. */
public interface TenantApplicationReadRepository {
    Optional<TenantApplicationState> findCurrent(UUID tenantId, String applicationKey);
}
