package com.mightyoung.softwarefactory.identity;

import java.util.UUID;
import java.util.Objects;

/** Identity and authorization epochs captured in the verified server session. */
public record CurrentTenantContext(
        UUID actorId,
        UUID accountId,
        UUID tenantId,
        UUID memberId,
        long tenantAuthEpoch,
        long memberAuthEpoch) {

    public CurrentTenantContext {
        Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(memberId, "memberId");
        if (tenantAuthEpoch < 0 || memberAuthEpoch < 0) {
            throw new IllegalArgumentException("Authorization epochs must be non-negative");
        }
    }
}
