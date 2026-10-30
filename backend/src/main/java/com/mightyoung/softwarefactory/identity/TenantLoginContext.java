package com.mightyoung.softwarefactory.identity;

import java.util.UUID;

/** Tenant/member binding resolved by the tenancy module from persisted state. */
public record TenantLoginContext(
        UUID tenantId,
        UUID memberId,
        String tenantStatus,
        String memberStatus,
        long tenantAuthEpoch,
        long memberAuthEpoch) {
}
