package com.mightyoung.softwarefactory.identity;

import java.time.Instant;
import java.util.UUID;

/** Record only after the security write transaction has ended; implementation uses REQUIRES_NEW.
 * A whole database outage may prevent this record too and must never be reported as persisted.
 */
public interface IdentitySecurityEvents {
    void passwordChangeFailed(CurrentTenantContext context, PasswordChangeFailureReason reason, UUID traceId, Instant occurredAt);
}
