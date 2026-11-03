package com.mightyoung.softwarefactory.dualprevention.finding.evidenceupload;

import com.mightyoung.softwarefactory.files.UploadProducerReadiness.Capability;
import com.mightyoung.softwarefactory.identity.CurrentTenantContext;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import static com.mightyoung.softwarefactory.dualprevention.finding.evidenceupload.FindingEvidenceUploadContracts.*;

public interface FindingEvidenceUploadRepository {
    /** Owns and commits a new RW/RC transaction; never joins an ambient transaction. */
    <T> T inTenantTransaction(UUID tenantId, Function<Transaction,T> work);
    record InsertResult(Intent intent, boolean inserted) {}
    interface Transaction extends FindingEvidenceUploadReadRepository.Snapshot {
        Optional<Intent> existing(UUID actorId, UUID idempotencyKey);
        Quota quota(UUID ownerMemberId, UUID evidenceSetId);
        InsertResult insert(CurrentTenantContext owner, ReserveCommand command, Notice notice,
                String fingerprint, Capability capability);
    }
}
