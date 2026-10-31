package com.mightyoung.softwarefactory.identity;

import java.util.Objects;
import java.util.UUID;

/** Public identity fields for the current, revalidated human tenant membership. */
public record CurrentSessionDetails(
        UUID actorId,
        UUID accountId,
        UUID tenantId,
        String tenantCode,
        String tenantName,
        UUID memberId,
        String displayName) {
    public CurrentSessionDetails {
        Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(tenantCode, "tenantCode");
        Objects.requireNonNull(tenantName, "tenantName");
        Objects.requireNonNull(memberId, "memberId");
        Objects.requireNonNull(displayName, "displayName");
    }
}
