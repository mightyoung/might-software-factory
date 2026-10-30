package com.mightyoung.softwarefactory.identity;

import java.util.UUID;

/** Tenant identity derived from the verified server session, never from request parameters. */
public record CurrentTenantContext(UUID actorId, UUID tenantId, UUID memberId) {
}
