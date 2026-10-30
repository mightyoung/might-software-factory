package com.mightyoung.softwarefactory.identity;

import java.util.Optional;
import java.util.UUID;

/** Public identity query for the current status of a global account. */
public interface AccountStatusReadRepository {

    Optional<String> findStatus(UUID accountId);
}
