package com.mightyoung.softwarefactory.identity;

import java.io.Serial;
import java.io.Serializable;
import java.security.Principal;
import java.util.Objects;
import java.util.UUID;

/**
 * Identity attributes resolved by the identity/session module after authentication and membership
 * checks. Construction is restricted to the identity module; web handlers must not accept this value
 * from request data. The serialized form keeps its existing UID so older sessions can be read and
 * rejected for their missing (zero) credential generation rather than treated as current.
 */
public final class VerifiedSessionPrincipal implements Principal, Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private final UUID actorId;
    private final UUID accountId;
    private final UUID tenantId;
    private final UUID memberId;
    private final long tenantAuthEpoch;
    private final long memberAuthEpoch;
    private final long credentialVersion;

    VerifiedSessionPrincipal(
            UUID actorId, UUID accountId, UUID tenantId, UUID memberId,
            long tenantAuthEpoch, long memberAuthEpoch, long credentialVersion) {
        this.actorId = Objects.requireNonNull(actorId, "actorId");
        this.accountId = Objects.requireNonNull(accountId, "accountId");
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId");
        this.memberId = Objects.requireNonNull(memberId, "memberId");
        if (tenantAuthEpoch < 0 || memberAuthEpoch < 0) {
            throw new IllegalArgumentException("Authorization epochs must be non-negative");
        }
        if (credentialVersion < 1) {
            throw new IllegalArgumentException("Credential version must be positive");
        }
        this.tenantAuthEpoch = tenantAuthEpoch;
        this.memberAuthEpoch = memberAuthEpoch;
        this.credentialVersion = credentialVersion;
    }

    public UUID actorId() {
        return actorId;
    }

    public UUID tenantId() {
        return tenantId;
    }

    public UUID accountId() {
        return accountId;
    }

    public UUID memberId() {
        return memberId;
    }

    public long tenantAuthEpoch() {
        return tenantAuthEpoch;
    }

    public long memberAuthEpoch() {
        return memberAuthEpoch;
    }

    public long credentialVersion() {
        return credentialVersion;
    }

    /** Stable account scope for server-side session indexing; never a tenant or display name. */
    @Override
    public String getName() {
        // Deserialization can bypass the constructor; a missing account must not share an index.
        return accountId == null ? null : accountId.toString();
    }

    @Override
    public String toString() {
        return "VerifiedSessionPrincipal";
    }
}
