package com.mightyoung.softwarefactory.authorization;

/** Transient database contention or connectivity failure; retry only in a new transaction. */
public final class AuthorizationWriteGuardUnavailableException extends AuthorizationWriteGuardException {
    public AuthorizationWriteGuardUnavailableException(String message, Throwable cause) { super(message, cause); }
}
