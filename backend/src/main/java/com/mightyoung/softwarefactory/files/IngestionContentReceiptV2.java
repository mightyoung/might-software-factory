package com.mightyoung.softwarefactory.files;

import java.time.Instant;
import java.util.UUID;
import static com.mightyoung.softwarefactory.files.FileIngestionContracts.*;

/** Separate SCANNED_CONTENT_V2 receipt; never changes or impersonates a legacy CONTENT receipt. */
public record IngestionContentReceiptV2(UUID receiptId, UUID tenantId, UUID intentId, UUID fileId,
        UUID sourceAttemptId, UUID scanAttemptId, UUID immutableAllocationId, UUID scanProofId,
        String immutableStorageVersion, String immutableObservationSha256, String sourceQuarantineStorageVersion,
        String scanProofPayloadSha256, String sha256, String detectedMediaType, String generationSha256,
        String policyVersion, String verifierId, String keyId, long sizeBytes,
        Instant issuedAt, Instant notBefore, Instant expiresAt, byte[] canonicalPayload, byte[] signature) {
    public IngestionContentReceiptV2 {
        required(receiptId,tenantId,intentId,fileId,sourceAttemptId,scanAttemptId,immutableAllocationId,scanProofId);
        if(sourceAttemptId.equals(scanAttemptId))throw invalid();
        token(immutableStorageVersion);hash(immutableObservationSha256);token(sourceQuarantineStorageVersion);
        hash(scanProofPayloadSha256);hash(sha256);media(detectedMediaType);hash(generationSha256);
        ident(policyVersion);ident(verifierId);ident(keyId);contentSize(sizeBytes);times(issuedAt,notBefore,expiresAt);
        issuedAt=instant(issuedAt);notBefore=instant(notBefore);expiresAt=instant(expiresAt);times(issuedAt,notBefore,expiresAt);
        canonicalPayload=bytes(canonicalPayload,MAX_CANONICAL_BYTES);signature=FileIngestionContracts.signature(signature);
    }
    @Override public byte[] canonicalPayload(){return canonicalPayload.clone();}
    @Override public byte[] signature(){return signature.clone();}
    @Override public String toString(){return "IngestionContentReceiptV2[receiptId="+receiptId+"]";}
}
