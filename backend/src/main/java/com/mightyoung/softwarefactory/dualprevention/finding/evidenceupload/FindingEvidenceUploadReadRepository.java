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
        /** 该成员在这一轮最近创建且尚未封存的证据集。 */
        Optional<EvidenceSet> openSetOf(UUID findingId, int roundNo, UUID ownerMemberId);
        /** 证据集下的上传意图，最早的在前；最多 100 条（证据集条目上限）。 */
        java.util.List<Intent> intentsOf(UUID evidenceSetId);
        OffsetDateTime now();
    }
}
