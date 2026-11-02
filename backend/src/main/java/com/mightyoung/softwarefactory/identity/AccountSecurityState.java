package com.mightyoung.softwarefactory.identity;

import java.util.Objects;

/** Current account status and the credential generation used to revoke issued sessions. */
public record AccountSecurityState(String status, long credentialVersion) {

    public AccountSecurityState {
        Objects.requireNonNull(status, "status");
        if (credentialVersion < 1) {
            throw new IllegalArgumentException("Credential version must be positive");
        }
    }
}
