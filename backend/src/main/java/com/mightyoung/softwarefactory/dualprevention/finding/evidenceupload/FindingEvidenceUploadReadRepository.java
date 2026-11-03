package com.mightyoung.softwarefactory.dualprevention.finding.evidenceupload;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import static com.mightyoung.softwarefactory.dualprevention.finding.evidenceupload.FindingEvidenceUploadContracts.*;

public interface FindingEvidenceUploadReadRepository {
    <T> T inTenantReadSnapshot(UUID tenantId, Function<Snapshot,T> work);
    interface Snapshot {
        Optional<Finding> finding(UUID id);
        Optional<Notice> notice(UUID findingId, int roundNo);
        Optional<EvidenceSet> evidenceSet(UUID id);
        Optional<Intent> intent(UUID id);
        OffsetDateTime now();
    }
}
