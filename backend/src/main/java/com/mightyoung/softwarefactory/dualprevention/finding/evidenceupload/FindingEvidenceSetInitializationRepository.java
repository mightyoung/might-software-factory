package com.mightyoung.softwarefactory.dualprevention.finding.evidenceupload;

import com.mightyoung.softwarefactory.identity.CurrentTenantContext;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import static com.mightyoung.softwarefactory.dualprevention.finding.evidenceupload.FindingEvidenceSetInitializationContracts.*;
import com.mightyoung.softwarefactory.dualprevention.finding.evidenceupload.FindingEvidenceUploadContracts.Notice;

public interface FindingEvidenceSetInitializationRepository {
    <T> T inTenantTransaction(UUID tenantId,Function<Transaction,T> work);
    interface Transaction extends FindingEvidenceUploadReadRepository.Snapshot {
        Optional<Receipt> existing(UUID actorId,UUID idempotencyKey);
        Quota quota(UUID ownerMemberId);
        Receipt initialize(CurrentTenantContext owner,InitializeCommand command,Notice notice,String fingerprint);
    }
}
