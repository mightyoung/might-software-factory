package com.mightyoung.softwarefactory.tenancy;

/** Current tenant and member state read from the tenancy module's persisted records. */
public record TenantMembershipState(
        String tenantStatus,
        String memberStatus,
        long tenantAuthEpoch,
        long memberAuthEpoch) {
}
