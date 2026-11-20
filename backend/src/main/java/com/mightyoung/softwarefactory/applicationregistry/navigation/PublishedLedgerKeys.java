package com.mightyoung.softwarefactory.applicationregistry.navigation;

import java.util.List;
import java.util.UUID;

/** Published business ledger keys from the current tenant's navigation snapshot. */
public interface PublishedLedgerKeys {
    List<String> publishedKeys(UUID tenantId);
}
