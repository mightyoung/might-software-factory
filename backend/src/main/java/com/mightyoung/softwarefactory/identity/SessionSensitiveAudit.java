package com.mightyoung.softwarefactory.identity;

import static com.mightyoung.softwarefactory.identity.SessionSensitiveContracts.SensitiveEvent;
public interface SessionSensitiveAudit {
    void success(SessionSensitiveSecurityTransaction.Scope scope,SessionSensitiveSecurityTransaction.GuardedBoundary boundary,SensitiveEvent event);
    void logoutSuccess(SessionSensitiveSecurityTransaction.LogoutScope scope,SessionSensitiveSecurityTransaction.LogoutBoundary boundary,
                       LogoutOwnership ownership,SensitiveEvent event);
    void failure(SensitiveEvent event);
}
