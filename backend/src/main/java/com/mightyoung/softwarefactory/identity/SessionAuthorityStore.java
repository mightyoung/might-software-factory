package com.mightyoung.softwarefactory.identity;

import static com.mightyoung.softwarefactory.identity.SessionSensitiveContracts.*;

/** Identity-owned SQL; source-only until every mandatory authority consumer is connected. */
public interface SessionAuthorityStore {
    Enforcement enforcement(SessionSensitiveSecurityTransaction.Scope scope,SessionSensitiveSecurityTransaction.GuardedBoundary boundary);
    Registration register(SessionSensitiveSecurityTransaction.Scope scope,SessionSensitiveSecurityTransaction.GuardedBoundary boundary,java.util.UUID eventId);
    SessionHandle requireCurrent(SessionSensitiveSecurityTransaction.Scope scope,SessionSensitiveSecurityTransaction.GuardedBoundary boundary);
    SessionHandle requireCurrent(CapturedHumanRequest request,SessionSensitiveSecurityTransaction.ReadSnapshotBoundary boundary);
    FenceResult revoke(SessionSensitiveSecurityTransaction.Scope scope,SessionSensitiveSecurityTransaction.GuardedBoundary boundary,RevokeCommand command,java.util.UUID eventId);
    FenceResult logout(SessionSensitiveSecurityTransaction.Scope scope,SessionSensitiveSecurityTransaction.GuardedBoundary boundary,java.util.UUID eventId);
    SessionPage readOwnPage(CapturedHumanRequest request,SessionSensitiveSecurityTransaction.ReadSnapshotBoundary boundary,SessionHandle after,int limit);
}
