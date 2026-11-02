package com.mightyoung.softwarefactory.tenancy;

/** Current tenant entitlement. Invalid persisted versions never grant access. */
public record TenantApplicationState(boolean enabled, long version) {
    public TenantApplicationState {
        if (version < 1) throw new IllegalArgumentException("Application version must be positive");
    }
}
