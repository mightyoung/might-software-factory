package com.mightyoung.softwarefactory.identity;

/** Supplies explicitly retained keys; an unavailable required key must fail closed. */
@FunctionalInterface
public interface SessionSensitiveKeyProvider {
    SessionSensitiveKeys requireRetainedKeys();
}
