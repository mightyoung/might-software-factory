package com.mightyoung.softwarefactory.identity;

public final class UnauthenticatedSessionException extends RuntimeException {

    public UnauthenticatedSessionException() {
        super("A verified session is required");
    }
}
