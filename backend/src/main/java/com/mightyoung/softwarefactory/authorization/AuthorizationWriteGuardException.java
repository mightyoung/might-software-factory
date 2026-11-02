package com.mightyoung.softwarefactory.authorization;

/** Guard protocol violation or failure: the entire calling transaction must roll back. */
public class AuthorizationWriteGuardException extends IllegalStateException {
    public AuthorizationWriteGuardException(String message) { super(message); }
    public AuthorizationWriteGuardException(String message, Throwable cause) { super(message, cause); }
}
