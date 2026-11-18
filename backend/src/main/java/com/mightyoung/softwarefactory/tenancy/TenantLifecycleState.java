package com.mightyoung.softwarefactory.tenancy;

import java.util.UUID;

/** Current persisted tenant facts, independent of any human membership. Epoch zero is valid. */
public record TenantLifecycleState(UUID tenantId,String status,long authEpoch) {
    public TenantLifecycleState {
        if(tenantId==null||!("ACTIVE".equals(status)||"SUSPENDED".equals(status))||authEpoch<0)
            throw new IllegalArgumentException("Tenant lifecycle state is invalid");
    }
}
