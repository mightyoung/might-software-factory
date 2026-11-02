package com.mightyoung.softwarefactory.identity;

import java.util.Objects;

/** Server-side preparation state; never a response or session attribute. */
public record PasswordCredentialState(long credentialVersion, String encodedPassword) {
    public PasswordCredentialState {
        if (credentialVersion < 1) throw new IllegalArgumentException("Credential version must be positive");
        Objects.requireNonNull(encodedPassword);
    }
    @Override public String toString() {
        return "PasswordCredentialState[credentialVersion=" + credentialVersion + ", encodedPassword=[REDACTED]]";
    }
}
