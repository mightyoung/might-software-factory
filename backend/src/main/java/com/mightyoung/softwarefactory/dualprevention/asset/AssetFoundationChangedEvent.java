package com.mightyoung.softwarefactory.dualprevention.asset;

import java.time.Instant;
import java.util.UUID;

/** Minimal committed change envelope; no raw descriptions or asserted risk determinations. */
public record AssetFoundationChangedEvent(UUID tenantId,UUID commandId,String resourceType,UUID resourceId,
                                          String action,long version,Instant occurredAt,UUID traceId,String changeKind) {}
