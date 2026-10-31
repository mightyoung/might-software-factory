package com.mightyoung.softwarefactory.identity;

import java.util.Optional;

/** Reads and revalidates the current session's identity against persisted account and membership state. */
public interface CurrentSessionReadRepository {

    Optional<CurrentSessionDetails> findCurrent(CurrentTenantContext context);
}
