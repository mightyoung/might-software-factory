package com.mightyoung.softwarefactory.files;

import static com.mightyoung.softwarefactory.files.FileIngestionContracts.*;

/** Files-owned authenticated checkpoint; neither application permission nor finalization authority. */
public record VerifiedContentCheckpoint(SignedScanProofV1 proof, IngestionContentReceiptV2 receipt,
                                        RecordedAllocation immutableAllocation, RecordedAllocation receiptAllocation) {
    public VerifiedContentCheckpoint {
        required(proof, receipt, immutableAllocation, receiptAllocation);
    }
}
