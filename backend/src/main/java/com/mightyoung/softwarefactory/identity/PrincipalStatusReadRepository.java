package com.mightyoung.softwarefactory.identity;

import java.util.Optional;
import java.util.UUID;

/** Identity-owned read boundary for validating an actor against its persisted principal. */
public interface PrincipalStatusReadRepository {

    Optional<PrincipalStatus> findByActorId(UUID actorId);
}
