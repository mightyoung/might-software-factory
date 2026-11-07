package com.mightyoung.softwarefactory.identity;

/** A single append after authentication and Session transactions have unwound; never retry an unknown commit. */
public interface AuthenticationEvents {
    void record(AuthenticationEvent event);
}
