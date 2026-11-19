package com.mightyoung.softwarefactory.identity;

import static com.mightyoung.softwarefactory.identity.SessionSensitiveContracts.*;

/** Exact existing revoke-consumer bytes, shared only by its finite issuer. */
final class SessionRevokeCanonical {
    private SessionRevokeCanonical(){ }
    static String action(CurrentTenantContext context,SessionHandle source,SessionHandle target,ExpectedAuthority expected){
        return context.accountId()+"|"+context.actorId()+"|"+source+"|"+context.credentialVersion()+"|"+target+"|"+expected.version();
    }
}
