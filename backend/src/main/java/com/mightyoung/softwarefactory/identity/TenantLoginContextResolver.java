package com.mightyoung.softwarefactory.identity;

import java.util.Optional;
import java.util.UUID;

/** Port implemented by tenancy to resolve a requested tenant and the account's active membership. */
public interface TenantLoginContextResolver {

    Optional<TenantLoginContext> resolve(UUID accountId, String tenantCode);
}
