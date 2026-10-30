package com.mightyoung.softwarefactory.identity;

import java.util.Objects;
import java.util.UUID;

/**
 * Identity attributes resolved by the identity/session module after authentication and membership
 * checks. There is deliberately no public constructor or production factory until that resolver is
 * implemented; web handlers must not accept this value from request data.
 */
public final class VerifiedSessionPrincipal {

    private final UUID actorId;
    private final UUID tenantId;
    private final UUID memberId;

    VerifiedSessionPrincipal(UUID actorId, UUID tenantId, UUID memberId) {
        this.actorId = Objects.requireNonNull(actorId, "actorId");
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId");
        this.memberId = Objects.requireNonNull(memberId, "memberId");
    }

    public UUID actorId() {
        return actorId;
    }

    public UUID tenantId() {
        return tenantId;
    }

    public UUID memberId() {
        return memberId;
    }

    @Override
    public String toString() {
        return "VerifiedSessionPrincipal";
    }
}
