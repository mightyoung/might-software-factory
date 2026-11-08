package com.mightyoung.softwarefactory.identity;

import static com.mightyoung.softwarefactory.identity.IdentityLifecycleContracts.*;

/** Authorization owns effective grants and entitlement; identity never infers an admin role. */
public interface IdentityLifecycleAuthorization {
    enum Action { CREATE, REVOKE }
    void requireInvitationAction(IdentityLifecycleSecurityTransaction.Scope scope,IssuerBinding issuer,Action action);
    void requireIssuerStillAuthorized(IdentityLifecycleSecurityTransaction.Scope scope,IssuerBinding issuer,Action action);
}
