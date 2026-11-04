package com.mightyoung.softwarefactory.identity;

import static com.mightyoung.softwarefactory.identity.SessionSensitiveContracts.Enforcement;

/** Mandatory session-source assertion. OFF is a positive control result, never business permission. */
public interface CurrentHumanSessionAuthority {
    default CheckedHumanSessionAuthority requireGuardedCheckedCurrent(HumanSessionAuthorityTransaction.WriteScope scope,
            HumanSessionAuthorityTransaction.GuardedBoundary boundary) { throw SessionSensitiveException.unavailable(); }
    default CheckedHumanSessionAuthority requireReadCheckedCurrent(CapturedHumanRequest request,
            HumanSessionAuthorityTransaction.ReadBoundary boundary) { throw SessionSensitiveException.unavailable(); }
    Enforcement requireGuardedCurrent(HumanSessionAuthorityTransaction.WriteScope scope,HumanSessionAuthorityTransaction.GuardedBoundary boundary);
    Enforcement requireReadSnapshotCurrent(CapturedHumanRequest request,HumanSessionAuthorityTransaction.ReadBoundary boundary);
}
