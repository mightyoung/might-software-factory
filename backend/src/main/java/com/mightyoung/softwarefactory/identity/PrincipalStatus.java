package com.mightyoung.softwarefactory.identity;

import java.util.UUID;

/** Persisted principal binding and lifecycle state for authorization checks. */
public record PrincipalStatus(UUID principalId, UUID accountId, String kind, String status) {
}
