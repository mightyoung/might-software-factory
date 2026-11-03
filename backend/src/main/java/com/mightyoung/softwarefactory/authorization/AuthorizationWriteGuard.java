package com.mightyoung.softwarefactory.authorization;

import java.util.Collection;
import java.util.UUID;

/** Transaction-scoped serialization between authorization readers and security writers.
 * Select one scope before any business row locks or authorization reads. Implementations
 * must reject scope upgrades; callers must declare all affected tenants in one call.
 * Use Spring-managed transactions and savepoints exclusively: direct JDBC savepoint creation,
 * rollback, release, or SQL SAVEPOINT commands bypass synchronization and are unsupported.
 * A Spring savepoint rollback invalidates the guard and requires the entire transaction to roll back.
 */
public interface AuthorizationWriteGuard {
    void forFindingCommand(UUID tenantId);
    /** Validates an existing command guard without acquiring or upgrading any lock. */
    void requireFindingCommand(UUID tenantId);
    void forGlobalSecurityChange();
    /** Global identity writes declare every affected tenant before any row access. */
    default void forGlobalSecurityChange(Collection<UUID> tenantIds) {
        throw new AuthorizationWriteGuardException("Declared global security scopes are not supported");
    }
    /** Assertion only: never acquires or upgrades a global scope. */
    default void requireGlobalSecurityChange(Collection<UUID> tenantIds) {
        throw new AuthorizationWriteGuardException("An existing declared global security scope is required");
    }
    void forTenantSecurityChange(Collection<UUID> tenantIds);
}
