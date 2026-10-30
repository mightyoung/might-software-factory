package com.mightyoung.softwarefactory.identity;

import java.io.Serial;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * Identity attributes resolved by the identity/session module after authentication and membership
 * checks. There is deliberately no public constructor or production factory until that resolver is
 * implemented; web handlers must not accept this value from request data.
 */
public final class VerifiedSessionPrincipal implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private final UUID actorId;
    private final UUID accountId;
    private final UUID tenantId;
    private final UUID memberId;
    private final long tenantAuthEpoch;
    private final long memberAuthEpoch;

    VerifiedSessionPrincipal(
            UUID actorId, UUID accountId, UUID tenantId, UUID memberId,
            long tenantAuthEpoch, long memberAuthEpoch) {
        this.actorId = Objects.requireNonNull(actorId, "actorId");
        this.accountId = Objects.requireNonNull(accountId, "accountId");
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId");
        this.memberId = Objects.requireNonNull(memberId, "memberId");
        if (tenantAuthEpoch < 0 || memberAuthEpoch < 0) {
            throw new IllegalArgumentException("Authorization epochs must be non-negative");
        }
        this.tenantAuthEpoch = tenantAuthEpoch;
        this.memberAuthEpoch = memberAuthEpoch;
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

    @Override
    public String toString() {
        return "VerifiedSessionPrincipal";
    }
}
