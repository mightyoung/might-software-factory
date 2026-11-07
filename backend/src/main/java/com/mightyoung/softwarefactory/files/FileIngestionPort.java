package com.mightyoung.softwarefactory.files;

import java.io.InputStream;
import static com.mightyoung.softwarefactory.files.FileIngestionContracts.*;

/** External I/O boundary; implementations reject ambient transactions before any effect. */
public interface FileIngestionPort {
    enum RejectionReason { INVALID_LENGTH, UNSUPPORTED_MEDIA, MALFORMED_CONTENT, SCANNER_REJECTED }
    final class ContentRejected extends RuntimeException {
        private final RejectionReason reason;
        public ContentRejected(RejectionReason reason){super("File content was rejected",null,false,false);this.reason=java.util.Objects.requireNonNull(reason);}
        public RejectionReason reason(){return reason;}
    }
    final class QuarantineNotReusable extends RuntimeException {
        public QuarantineNotReusable(){super("The original quarantined object is not reusable",null,false,false);}
    }
    default ProducerResources currentResources(){throw new FileEvidenceTrustUnavailableException();}
    default boolean isCurrentGeneration(AcceptedGeneration generation){return false;}
    QuarantinedObject receive(ReceiveAllocation allocation, InputStream input, Deadline deadline);
    PreparedSlotLease prepareSlot(ScanPlan plan, Deadline deadline);
    RecoveryObservation observeRecovery(RecordedAllocation allocation, java.util.Optional<RecordedExecution> execution,
                                        RecoveryBinding binding, Deadline deadline);
    ScanOutcome scan(QuarantinedObject source, ScanAllocation allocation,
                         AcceptedGeneration generation, TechnicalFence fence, RecordedExecution execution, PreparedSlotLease lease, Deadline deadline);
    ImmutableObjectAndReceipt publishImmutable(QuarantinedObject source, ScanOutcome outcome,
                                               CopyAllocation copy, ReceiptAllocation receipt, Deadline deadline);
    DisposalObservation dispose(DisposalClaim claim, RecoveryBinding postClaim, Deadline deadline);
}
