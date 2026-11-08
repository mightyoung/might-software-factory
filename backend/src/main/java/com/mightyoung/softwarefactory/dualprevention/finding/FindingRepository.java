package com.mightyoung.softwarefactory.dualprevention.finding;

import com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryValidator.ValidatedQuery;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Read-only storage port for the finding ledger. */
public interface FindingRepository {

    PageResult findPage(FindingReadScope scope, ValidatedQuery query);

    Optional<FindingRecord> findVisible(FindingReadScope scope, UUID findingId);

    Optional<FindingDetailContext> findDetailContext(FindingReadScope scope, UUID findingId);

    record PageResult(List<FindingRecord> items, long total) {
        public PageResult {
            items = List.copyOf(items);
            if (total < 0) {
                throw new IllegalArgumentException("A query total cannot be negative");
            }
        }
    }
}
