package com.mightyoung.softwarefactory.authorization;

import java.util.UUID;

/** One persisted role scope rule and, for EXPLICIT rules, one selected organization. */
public record RoleScopeDescriptor(UUID scopeRuleId, String kind, UUID explicitOrganizationId) {
}
