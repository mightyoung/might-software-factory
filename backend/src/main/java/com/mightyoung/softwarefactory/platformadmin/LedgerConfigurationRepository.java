package com.mightyoung.softwarefactory.platformadmin;

import java.util.List;
import java.util.UUID;
import static com.mightyoung.softwarefactory.platformadmin.LedgerConfigurationModels.*;

public interface LedgerConfigurationRepository {
    List<Item> list(UUID tenant);
    List<Snapshot> versions(UUID tenant, String key);
    Snapshot save(UUID tenant, UUID actor, String key, SaveRequest request);
    Snapshot restore(UUID tenant, UUID actor, String key, RestoreRequest request);
    Snapshot publish(UUID tenant, UUID actor, String key, PublishRequest request);
}
