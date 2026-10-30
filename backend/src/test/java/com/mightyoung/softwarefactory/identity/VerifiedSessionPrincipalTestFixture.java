package com.mightyoung.softwarefactory.identity;

import java.util.UUID;

/** Test-only constructor access. Production code has no factory that can mint a verified identity. */
public final class VerifiedSessionPrincipalTestFixture {

    private VerifiedSessionPrincipalTestFixture() {
    }

    public static VerifiedSessionPrincipal create(UUID actorId, UUID tenantId, UUID memberId) {
        return new VerifiedSessionPrincipal(actorId, tenantId, memberId);
    }
}
