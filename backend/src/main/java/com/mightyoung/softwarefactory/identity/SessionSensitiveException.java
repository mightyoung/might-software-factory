package com.mightyoung.softwarefactory.identity;

/** Finite safe failures; never carries SQL, bearer IDs, capability bodies or addresses. */
public final class SessionSensitiveException extends RuntimeException {
    public enum Reason { REJECTED, CONFLICT, UNAVAILABLE, NO_TRUSTED_LINEAGE }
    private final Reason reason;
    public SessionSensitiveException(Reason reason) { super(reason.name()); this.reason=reason; }
    public Reason reason(){return reason;}
    public static SessionSensitiveException rejected(){return new SessionSensitiveException(Reason.REJECTED);}
    public static SessionSensitiveException unavailable(){return new SessionSensitiveException(Reason.UNAVAILABLE);}
}
