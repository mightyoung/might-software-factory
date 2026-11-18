package com.mightyoung.softwarefactory.files;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Objects;
import org.jooq.Record;
import static com.mightyoung.softwarefactory.files.FileIngestionContracts.*;

/** Validates SQL index/header columns against the authenticated payload, never trusting either alone. */
final class PersistedIngestionCheckpointReader {
    private final IngestionSignedCheckpointReader signed;
    PersistedIngestionCheckpointReader(IngestionProofCodec codec) { signed=new IngestionSignedCheckpointReader(codec); }

    SignedScanProofV1 scan(Record row) {
        var p=signed.scan(row.get("canonical_payload",byte[].class),row.get("signature",byte[].class));
        match(row,"protocol","SCAN_PROOF_V1","id",p.proofId(),"tenant_id",p.tenantId(),"intent_id",p.intentId(),
                "file_id",p.fileId(),"source_attempt_id",p.sourceAttemptId(),"scan_attempt_id",p.scanAttemptId(),
                "quarantine_allocation_id",p.quarantineAllocationId(),"execution_id",p.executionId(),
                "descriptor_sha256",p.descriptorSha256(),"kernel_result_sha256",p.kernelResultSha256(),"process_state","REAPED",
                "generation_id",p.generationId(),"generation_sha256",p.generationSha256(),
                "quarantine_observation_sha256",p.quarantineObservationSha256(),"sha256",p.sha256(),
                "canonical_payload_sha256",IngestionProofCodec.sha256(p.canonicalPayload()),"size_bytes",p.sizeBytes(),
                "detected_media_type",p.detectedMediaType(),"storage_version",p.quarantineStorageVersion(),"key_id",p.keyId(),
                "started_at",p.startedAt(),"completed_at",p.completedAt(),"issued_at",p.issuedAt(),
                "not_before",p.notBefore(),"expires_at",p.expiresAt());
        return p;
    }

    IngestionContentReceiptV2 receipt(Record row,SignedScanProofV1 proof) {
        var r=signed.receipt(row.get("canonical_payload",byte[].class),row.get("signature",byte[].class),proof);
        match(row,"protocol","SCANNED_CONTENT_V2","id",r.receiptId(),"tenant_id",r.tenantId(),"intent_id",r.intentId(),
                "file_id",r.fileId(),"source_attempt_id",r.sourceAttemptId(),"scan_attempt_id",r.scanAttemptId(),
                "immutable_allocation_id",r.immutableAllocationId(),"scan_proof_id",r.scanProofId(),
                "scan_proof_payload_sha256",r.scanProofPayloadSha256(),"immutable_observation_sha256",r.immutableObservationSha256(),
                "canonical_payload_sha256",IngestionProofCodec.sha256(r.canonicalPayload()),"sha256",r.sha256(),
                "size_bytes",r.sizeBytes(),"detected_media_type",r.detectedMediaType(),"storage_version",r.immutableStorageVersion(),
                "key_id",r.keyId(),"issued_at",r.issuedAt(),"not_before",r.notBefore(),"expires_at",r.expiresAt());
        return r;
    }

    private static void match(Record row,Object... pairs) {
        for(int i=0;i<pairs.length;i+=2) {
            String name=(String)pairs[i];Object expected=pairs[i+1];
            Object actual=expected instanceof Instant?row.get(name,OffsetDateTime.class):row.get(name,expected.getClass());
            if(actual instanceof OffsetDateTime time)actual=time.toInstant();
            if(!Objects.equals(expected,actual))throw new FileEvidenceTrustUnavailableException();
        }
    }
}
