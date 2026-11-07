package com.mightyoung.softwarefactory.authorization;

import java.util.UUID;

/** Minimal current member identity returned by an action-specific assignment search. */
public record AssignableFindingMember(UUID memberId, String displayName) {
}
