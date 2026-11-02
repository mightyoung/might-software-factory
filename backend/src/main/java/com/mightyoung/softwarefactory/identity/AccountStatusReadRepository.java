package com.mightyoung.softwarefactory.identity;

import java.util.Optional;
import java.util.UUID;

/** Public identity query for the current security state of a global account. */
public interface AccountStatusReadRepository {

    Optional<AccountSecurityState> findState(UUID accountId);
}
