package com.mightyoung.softwarefactory.files;

import java.util.UUID;
import java.util.function.Function;
import static com.mightyoung.softwarefactory.files.FileIngestionContracts.*;

/** Files-owned SQL, joining exactly the caller's already guarded, ordered application transaction. */
public interface FileIngestionLedger {
    <T> T inCallerTransaction(UUID tenantId, Function<FileIngestionTxContext,T> work);
    RecordedAllocation reserve(FileIngestionTxContext tx, AllocationRequest request);
    RecordedAllocation load(FileIngestionTxContext tx, UUID allocationId);
    RecordedAllocation loadForAttempt(FileIngestionTxContext tx, UUID intentId, UUID attemptId, AllocationKind kind);
    RetainedAllocationPage retainedForIntent(FileIngestionTxContext tx, UUID intentId, UUID afterAllocationId, int limit);
    java.util.Optional<DisposalClaim> currentDisposalClaim(FileIngestionTxContext tx, UUID allocationId);
    PhysicalCharges charges(FileIngestionTxContext tx, UUID ownerMemberId);
    RecordedAllocation recordQuarantine(FileIngestionTxContext tx, TechnicalFence fence, QuarantinedObject source);
    RecordedAllocation recordObservation(FileIngestionTxContext tx, TechnicalFence fence, DurableObservation observation);
    RecordedExecution recordExecution(FileIngestionTxContext tx, ExecutionDescriptor descriptor);
    java.util.Optional<RecordedExecution> executionForAttempt(FileIngestionTxContext tx, UUID intentId, UUID scanAttemptId);
    RecordedAllocation recordRecoveryObservation(FileIngestionTxContext tx, RecoveryBinding binding, RecoveryObservation observation);
    UUID recordGeneration(FileIngestionTxContext tx, AcceptedGeneration generation);
    VerifiedCheckpoint recordVerified(FileIngestionTxContext tx, TechnicalFence fence, QuarantinedObject source,
                                      ImmutableObjectAndReceipt verified, AcceptedGeneration generation, ScanOutcome outcome);
    DisposalClaim claimDisposal(FileIngestionTxContext tx, DisposalEligibility eligibility,
                                UUID allocationId, String expectedObservationSha256);
    RecordedAllocation confirmDisposal(FileIngestionTxContext tx, DisposalClaim claim, DisposalObservation observation, long expectedTechnicalVersion);
}
