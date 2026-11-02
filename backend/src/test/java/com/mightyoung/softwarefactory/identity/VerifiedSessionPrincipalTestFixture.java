package com.mightyoung.softwarefactory.identity;

import java.util.UUID;

/** Test-only constructor access. Production code has no factory that can mint a verified identity. */
public final class VerifiedSessionPrincipalTestFixture {

    private VerifiedSessionPrincipalTestFixture() {
    }

    public static VerifiedSessionPrincipal create(UUID actorId, UUID tenantId, UUID memberId) {
        return create(actorId, actorId, tenantId, memberId, 1L, 1L);
    }

    public static VerifiedSessionPrincipal create(
            UUID actorId, UUID accountId, UUID tenantId, UUID memberId,
            long tenantAuthEpoch, long memberAuthEpoch) {
        return create(actorId, accountId, tenantId, memberId, tenantAuthEpoch, memberAuthEpoch, 1L);
    }

    public static VerifiedSessionPrincipal create(
            UUID actorId, UUID accountId, UUID tenantId, UUID memberId,
            long tenantAuthEpoch, long memberAuthEpoch, long credentialVersion) {
        return new VerifiedSessionPrincipal(
                actorId, accountId, tenantId, memberId, tenantAuthEpoch, memberAuthEpoch, credentialVersion);
    }
}
