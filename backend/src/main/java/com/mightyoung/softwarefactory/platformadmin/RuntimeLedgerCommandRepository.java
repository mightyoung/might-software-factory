package com.mightyoung.softwarefactory.platformadmin;

import java.util.UUID;
import static com.mightyoung.softwarefactory.platformadmin.RuntimeLedgerCommandModels.*;

public interface RuntimeLedgerCommandRepository {
    LedgerConfigurationModels.Snapshot published(UUID tenant, String key);
    LedgerConfigurationModels.Definition definition(UUID tenant, String key, long version);
    RecordView lock(UUID tenant, String key, UUID id);
    RecordView replay(UUID tenant, UUID member, String key, UUID request, String operation, Object payload);
    RecordView create(UUID tenant, UUID member, String key, CreateRequest request);
    RecordView update(UUID tenant, UUID member, String key, UUID id, UpdateRequest request);
    RecordView delete(UUID tenant, UUID member, String key, UUID id, DeleteRequest request);
}
