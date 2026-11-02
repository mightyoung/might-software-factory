package com.mightyoung.softwarefactory.identity;

import java.time.Instant;
import java.util.Optional;

/** The writer must require the exact active IdentitySecurityTransaction before issuing SQL. */
public interface PasswordCredentialStore {
    Optional<PasswordCredentialState> findOwn(CurrentTenantContext context);
    void replace(CurrentTenantContext context, PasswordCredentialState expected, String encodedPassword, Instant changedAt);
}
