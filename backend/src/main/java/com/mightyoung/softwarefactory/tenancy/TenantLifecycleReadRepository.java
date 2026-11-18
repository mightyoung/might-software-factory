package com.mightyoung.softwarefactory.tenancy;

import java.util.Optional;
import java.util.UUID;

/** Tenant-only current lifecycle read in an existing writable READ_COMMITTED transaction.
 * Confers no SERVICE/HUMAN authority and neither starts a transaction nor changes tenant context.
 */
public interface TenantLifecycleReadRepository {
    Optional<TenantLifecycleState> findCurrentInCaller(UUID tenantId);
}
