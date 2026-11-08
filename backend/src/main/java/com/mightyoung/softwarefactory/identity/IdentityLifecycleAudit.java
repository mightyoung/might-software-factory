package com.mightyoung.softwarefactory.identity;

/** Successful events join the exact writer; rejected events are bounded independent records. */
public interface IdentityLifecycleAudit {
    void success(IdentityLifecycleSecurityTransaction.Scope scope,IdentityLifecycleContracts.LifecycleEvent event);
    void failure(IdentityLifecycleContracts.LifecycleEvent event);
}
