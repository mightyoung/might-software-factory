package com.mightyoung.softwarefactory.identity;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.Objects;
import java.util.UUID;

/** Identity-issued SQL ownership, usable only for this exact logout boundary. */
public final class LogoutOwnership {
    private final SessionSensitiveSecurityTransaction.LogoutScope scope;
    private final SessionSensitiveSecurityTransaction.LogoutBoundary boundary;
    private final DataSource source;
    private final Connection connection;
    private final UUID accountId,actorId;
    private final SessionHandle handle;
    LogoutOwnership(SessionSensitiveSecurityTransaction.LogoutScope scope,SessionSensitiveSecurityTransaction.LogoutBoundary boundary,
                    DataSource source,Connection connection,UUID accountId,UUID actorId,SessionHandle handle) {
        this.scope=Objects.requireNonNull(scope);this.boundary=Objects.requireNonNull(boundary);
        this.source=Objects.requireNonNull(source);this.connection=Objects.requireNonNull(connection);
        this.accountId=Objects.requireNonNull(accountId);this.actorId=actorId;this.handle=Objects.requireNonNull(handle);
    }
    public void requireOwned(SessionSensitiveSecurityTransaction.LogoutScope expected,SessionSensitiveSecurityTransaction.LogoutBoundary proof,
                             DataSource expectedSource,Connection current) {
        if(scope!=expected||boundary!=proof||source!=expectedSource||connection!=current)throw SessionSensitiveException.unavailable();
        scope.request().requireUnchanged();boundary.observedAt();
    }
    public UUID accountId(){boundary.observedAt();return accountId;}
    public UUID actorId(){boundary.observedAt();return actorId;}
    public SessionHandle handle(){boundary.observedAt();return handle;}
    @Override public String toString(){return "LogoutOwnership[REDACTED]";}
}
