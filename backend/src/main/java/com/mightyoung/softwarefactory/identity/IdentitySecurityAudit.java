package com.mightyoung.softwarefactory.identity;

import java.time.Instant;
import java.util.UUID;

/** Successful audit and compact security event must join the password write transaction. */
public interface IdentitySecurityAudit {
    void passwordChanged(CurrentTenantContext context, long previousVersion, long newVersion, UUID traceId, Instant occurredAt);
}
