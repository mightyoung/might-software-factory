package com.mightyoung.softwarefactory.authorization;

import com.mightyoung.softwarefactory.identity.CurrentTenantContext;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** The ordinary ledger query decision: absent denies, present empty authorizes zero rows only. */
public interface OrdinaryLedgerQueryAuthorization {
    Optional<Set<UUID>> queryOrganizations(CurrentTenantContext context, String ledgerKey);
}
