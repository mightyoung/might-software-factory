package com.mightyoung.softwarefactory.identity;

import java.util.UUID;

/** Persisted password credential joined to its account and human audit principal. */
public record LocalPasswordCredential(
        UUID actorId,
        UUID accountId,
        String accountStatus,
        String principalStatus,
        long credentialVersion,
        String secretHash) {

    public LocalPasswordCredential {
        if (credentialVersion < 1) {
            throw new IllegalArgumentException("Credential version must be positive");
        }
    }

    @Override
    public String toString() {
        return "LocalPasswordCredential[actorId=" + actorId + ", accountId=" + accountId
                + ", accountStatus=" + accountStatus + ", principalStatus=" + principalStatus
                + ", credentialVersion=" + credentialVersion + ", secretHash=[REDACTED]]";
    }
}
