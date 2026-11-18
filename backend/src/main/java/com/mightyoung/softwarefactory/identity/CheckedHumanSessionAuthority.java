package com.mightyoung.softwarefactory.identity;

import java.time.Instant;
import java.util.Objects;
import static com.mightyoung.softwarefactory.identity.SessionSensitiveContracts.Enforcement;

/** Same-callback checked control result; no public constructor, arbitrary time factory or transferable authority. */
public final class CheckedHumanSessionAuthority {
    private final Object scope;
    private final Object boundary;
    private final Enforcement enforcement;
    private final Instant observedAt;
    private final Runnable requireCurrent;
    CheckedHumanSessionAuthority(Object scope, Object boundary, Enforcement enforcement, Instant observedAt, Runnable requireCurrent) {
        this.scope=Objects.requireNonNull(scope); this.boundary=Objects.requireNonNull(boundary);
        this.enforcement=Objects.requireNonNull(enforcement); this.observedAt=Objects.requireNonNull(observedAt);
        this.requireCurrent=Objects.requireNonNull(requireCurrent);
    }
    public Instant observedAt(HumanSessionAuthorityTransaction.WriteScope expectedScope,
                              HumanSessionAuthorityTransaction.GuardedBoundary expectedBoundary) {
        require(expectedScope,expectedBoundary); return observedAt;
    }
    public Instant observedAt(CapturedHumanRequest expectedRequest, HumanSessionAuthorityTransaction.ReadBoundary expectedBoundary) {
        require(expectedRequest,expectedBoundary); return observedAt;
    }
    public Enforcement enforcement() { requireCurrent.run(); return enforcement; }
    private void require(Object expectedScope,Object expectedBoundary) {
        if(scope!=expectedScope || boundary!=expectedBoundary) throw SessionSensitiveException.unavailable();
        requireCurrent.run();
    }
    @Override public String toString() { return "CheckedHumanSessionAuthority[REDACTED]"; }
}
