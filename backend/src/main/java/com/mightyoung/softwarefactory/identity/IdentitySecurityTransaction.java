package com.mightyoung.softwarefactory.identity;

import java.util.function.Supplier;

/** Top-level same-datasource RW/RC security write with the global authorization lock.
 * Implementations reject an ambient transaction and bind the exact trusted context.
 */
public interface IdentitySecurityTransaction {
    <T> T execute(CurrentTenantContext context, Supplier<T> work);
    void requireActive(CurrentTenantContext context);
}
