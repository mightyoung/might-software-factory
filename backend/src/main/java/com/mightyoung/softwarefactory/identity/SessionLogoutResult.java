package com.mightyoung.softwarefactory.identity;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.Objects;
import static com.mightyoung.softwarefactory.identity.SessionSensitiveContracts.FenceResult;

/** A complete owning SQL lookup; absence is not an exception or a fresh-capture failure. */
public final class SessionLogoutResult {
    private final SessionSensitiveSecurityTransaction.LogoutScope scope;
    private final SessionSensitiveSecurityTransaction.LogoutBoundary boundary;
    private final DataSource source;
    private final Connection connection;
    private final LogoutOwnership ownership;
    private final FenceResult fence;
    SessionLogoutResult(SessionSensitiveSecurityTransaction.LogoutScope scope,SessionSensitiveSecurityTransaction.LogoutBoundary boundary,
                        DataSource source,Connection connection,LogoutOwnership ownership,FenceResult fence) {
        this.scope=Objects.requireNonNull(scope);this.boundary=Objects.requireNonNull(boundary);
        this.source=Objects.requireNonNull(source);this.connection=Objects.requireNonNull(connection);
        if((ownership==null)!=(fence==null))throw SessionSensitiveException.unavailable();
        if(ownership!=null){
            ownership.requireOwned(scope,boundary,source,connection);
            if(!ownership.handle().equals(fence.source())||!ownership.handle().equals(fence.target()))throw SessionSensitiveException.unavailable();
        }
        this.ownership=ownership;this.fence=fence;
    }
    public void requireLookup(SessionSensitiveSecurityTransaction.LogoutScope expected,SessionSensitiveSecurityTransaction.LogoutBoundary proof,
                              DataSource expectedSource,Connection current) {
        if(scope!=expected||boundary!=proof||source!=expectedSource||connection!=current)throw SessionSensitiveException.unavailable();
        scope.request().requireUnchanged();boundary.observedAt();
        if(ownership!=null)ownership.requireOwned(expected,proof,expectedSource,current);
    }
    boolean absent(){boundary.observedAt();return ownership==null;}
    LogoutOwnership ownership(){boundary.observedAt();return ownership;}
    FenceResult fence(){boundary.observedAt();return fence;}
    @Override public String toString(){return "SessionLogoutResult[REDACTED]";}
}
