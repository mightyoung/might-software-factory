package com.mightyoung.softwarefactory.identity;

import java.util.UUID;

/** Persisted password credential joined to its account and human audit principal. */
public record LocalPasswordCredential(
        UUID actorId,
        UUID accountId,
        String accountStatus,
        String principalStatus,
        String secretHash) {

    @Override
    public String toString() {
        return "LocalPasswordCredential[actorId=" + actorId + ", accountId=" + accountId
                + ", accountStatus=" + accountStatus + ", principalStatus=" + principalStatus
                + ", secretHash=[REDACTED]]";
    }
}
