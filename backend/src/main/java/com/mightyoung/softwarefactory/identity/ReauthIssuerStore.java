package com.mightyoung.softwarefactory.identity;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import static com.mightyoung.softwarefactory.identity.SessionSensitiveContracts.*;

/** Identity-owned preparation read and pinned global-writer issuance. */
interface ReauthIssuerStore {
    void bindIssuer(ReauthIssuerService issuer);
    Optional<PasswordCredentialState> readCredential(CurrentTenantContext context);
    Stored issue(SessionSensitiveSecurityTransaction.Scope scope,SessionSensitiveSecurityTransaction.GuardedBoundary boundary,
            ReauthIssuerService.VerifiedPreparation prepared);
    record Stored(ReauthIssuerContracts.Metadata metadata,SessionHandle source,UUID eventId,Instant issuedAt,boolean created){
        public Stored{java.util.Objects.requireNonNull(metadata);java.util.Objects.requireNonNull(source);java.util.Objects.requireNonNull(eventId);java.util.Objects.requireNonNull(issuedAt);}
        @Override public String toString(){return "StoredReauthIssue[REDACTED]";}
    }
}
