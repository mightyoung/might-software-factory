package com.mightyoung.softwarefactory.platformadmin;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import static com.mightyoung.softwarefactory.platformadmin.RuntimeLedgerReadModels.*;

public interface RuntimeLedgerReadRepository {
    List<Published> published(UUID tenant);
    Published published(UUID tenant, String key);
    List<Organization> organizations(UUID tenant, Set<UUID> scope);
    Page query(UUID tenant, Published published, Set<UUID> scope, Query query);
    Detail detail(UUID tenant, String key, UUID id, Set<UUID> scope, Capabilities capabilities);
}
