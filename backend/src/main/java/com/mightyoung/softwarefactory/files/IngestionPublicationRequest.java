package com.mightyoung.softwarefactory.files;

import java.util.UUID;
import static com.mightyoung.softwarefactory.files.FileIngestionContracts.*;

/** Business-authorized publication context, consumed only inside the caller's guarded transaction. */
public record IngestionPublicationRequest(UUID tenantId, UUID intentId, VerifiedCheckpoint checkpoint,
        UUID findingId, int roundNo, UUID evidenceSetId, UUID evidenceItemId, UUID fileLinkId,
        UUID ownerMemberId, UUID ownerActorId, UUID publicationActorId, long technicalVersion,
        long findingVersion, long evidenceRevision) {
    public IngestionPublicationRequest {
        required(tenantId,intentId,checkpoint,findingId,evidenceSetId,evidenceItemId,fileLinkId,
                ownerMemberId,ownerActorId,publicationActorId);
        if(roundNo<1)throw invalid();nonnegative(technicalVersion);nonnegative(findingVersion);nonnegative(evidenceRevision);
    }
}
