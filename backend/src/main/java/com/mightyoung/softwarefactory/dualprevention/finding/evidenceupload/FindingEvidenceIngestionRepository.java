package com.mightyoung.softwarefactory.dualprevention.finding.evidenceupload;

import com.mightyoung.softwarefactory.files.FileIngestionContracts.TechnicalFence;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import static com.mightyoung.softwarefactory.dualprevention.finding.evidenceupload.FindingEvidenceUploadContracts.*;

/** Business-owned technical state. Every callback is one ordered, guarded physical RC transaction. */
public interface FindingEvidenceIngestionRepository {
    <T> T inIntentTransaction(UUID tenantId, UUID intentId, Function<Transaction,T> work);
    List<UUID> due(UUID tenantId, UUID afterIntentId, int limit);

    interface Transaction extends FindingEvidenceUploadReadRepository.Snapshot {
        TechnicalIntent current();
        void assertOpen();
        TechnicalIntent compareAndSet(TechnicalIntent expected, Change change, UUID auditActor, String policyVersion);
        default TechnicalIntent finalizeEvidence(TechnicalIntent expected,
                com.mightyoung.softwarefactory.files.VerifiedContentCheckpoint verified,
                UUID evidenceItemId, UUID fileLinkId, UUID auditActor) { throw conflict(); }
    }
    enum Phase {
        INTENT_CREATED, RECEIVING, QUARANTINED, SCANNING, VERIFIED_PENDING_FINALIZATION,
        RETRY_WAIT, FAILED, REJECTED, CANCELLED, EXPIRED, FINALIZED;
        public boolean active(){return this==RECEIVING||this==SCANNING;}
        public boolean terminal(){return this==FAILED||this==REJECTED||this==CANCELLED||this==EXPIRED||this==FINALIZED;}
    }
    record TechnicalIntent(UUID tenantId, Intent declaration, String mediaType, long sizeBytes,
            long expectedFindingVersion, long expectedEvidenceRevision, String producerVersion, String policyVersion,
            Phase phase, long technicalVersion, long leaseEpoch, int attemptNo, UUID attemptId,
            UUID reservedScanAttemptId, UUID leaseToken, Instant leaseUntil, Instant heartbeatAt, Instant nextAttemptAt,
            UUID quarantineAllocationId, UUID immutableAllocationId, UUID scanProofId, UUID contentReceiptId,
            String scannerGeneration, String lastErrorCode, boolean cancelRequested, Instant updatedAt, UUID currentExecutionId) {
        public TechnicalFence fence(){
            if(!phase.active()||attemptId==null||leaseToken==null)throw conflict();
            return new TechnicalFence(tenantId,declaration.id(),attemptId,leaseToken,technicalVersion,leaseEpoch);
        }
        @Override public String toString(){return "TechnicalIntent[phase="+phase+",version="+technicalVersion+"]";}
    }
    record Change(Phase phase, long leaseEpoch, int attemptNo, UUID attemptId, UUID reservedScanAttemptId,
            UUID leaseToken, Instant leaseUntil, Instant heartbeatAt, Instant nextAttemptAt,
            UUID quarantineAllocationId, UUID immutableAllocationId, UUID scanProofId, UUID contentReceiptId,
            String scannerGeneration, String lastErrorCode, boolean cancelRequested, UUID currentExecutionId) {
        public static Change unchanged(TechnicalIntent i){return new Change(i.phase(),i.leaseEpoch(),i.attemptNo(),
                i.attemptId(),i.reservedScanAttemptId(),i.leaseToken(),i.leaseUntil(),i.heartbeatAt(),i.nextAttemptAt(),
                i.quarantineAllocationId(),i.immutableAllocationId(),i.scanProofId(),i.contentReceiptId(),
                i.scannerGeneration(),i.lastErrorCode(),i.cancelRequested(),i.currentExecutionId());}
    }
    static FindingEvidenceUploadException conflict(){return new FindingEvidenceUploadException(FindingEvidenceUploadException.Reason.CONFLICT);}
}
