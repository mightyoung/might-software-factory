package com.mightyoung.softwarefactory.identity;

import org.springframework.security.core.AuthenticationException;

/** Contains only a bounded retry interval, never the key or exhausted limit. */
public final class LoginRateLimitedException extends AuthenticationException {

    private final long retryAfterSeconds;

    LoginRateLimitedException(long retryAfterSeconds) {
        super("Too many login attempts; try again later");
        this.retryAfterSeconds = Math.max(1, retryAfterSeconds);
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
