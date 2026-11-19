package com.mightyoung.softwarefactory.files;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jooq.Record;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Synthetic signed fixtures verify storage projection integrity, not scanner acceptance. */
class PersistedIngestionCheckpointReaderTest {
    final IngestionProofCodecTest fixture=new IngestionProofCodecTest();
    final PersistedIngestionCheckpointReader reader=new PersistedIngestionCheckpointReader(fixture.codec);

    @Test void everyPersistedProofHeaderMustMatchAuthenticatedPayload() {
        var p=fixture.proof();var fields=new LinkedHashMap<String,Object>();
        put(fields,"protocol","SCAN_PROOF_V1","id",p.proofId(),"tenant_id",p.tenantId(),"intent_id",p.intentId(),
                "file_id",p.fileId(),"source_attempt_id",p.sourceAttemptId(),"scan_attempt_id",p.scanAttemptId(),
                "quarantine_allocation_id",p.quarantineAllocationId(),"execution_id",p.executionId(),
                "descriptor_sha256",p.descriptorSha256(),"kernel_result_sha256",p.kernelResultSha256(),"process_state","REAPED",
                "generation_id",p.generationId(),"generation_sha256",p.generationSha256(),
                "quarantine_observation_sha256",p.quarantineObservationSha256(),"sha256",p.sha256(),
                "canonical_payload_sha256",IngestionProofCodec.sha256(p.canonicalPayload()),"size_bytes",p.sizeBytes(),
                "detected_media_type",p.detectedMediaType(),"storage_version",p.quarantineStorageVersion(),"key_id",p.keyId(),
                "started_at",p.startedAt(),"completed_at",p.completedAt(),"issued_at",p.issuedAt(),"not_before",p.notBefore(),"expires_at",p.expiresAt());
        fields.put("canonical_payload",p.canonicalPayload());fields.put("signature",p.signature());
        assertThat(reader.scan(row(fields))).usingRecursiveComparison().isEqualTo(p);
        for(String header:fields.keySet()) {
            var altered=new LinkedHashMap<>(fields);altered.put(header,null);
            assertThatThrownBy(()->reader.scan(row(altered))).as(header).isInstanceOf(FileEvidenceTrustUnavailableException.class);
        }
    }

    @Test void everyPersistedReceiptHeaderMustMatchAuthenticatedPayload() {
        var p=fixture.proof();var r=fixture.receipt(p);var fields=new LinkedHashMap<String,Object>();
        put(fields,"protocol","SCANNED_CONTENT_V2","id",r.receiptId(),"tenant_id",r.tenantId(),"intent_id",r.intentId(),
                "file_id",r.fileId(),"source_attempt_id",r.sourceAttemptId(),"scan_attempt_id",r.scanAttemptId(),
                "immutable_allocation_id",r.immutableAllocationId(),"scan_proof_id",r.scanProofId(),
                "scan_proof_payload_sha256",r.scanProofPayloadSha256(),"immutable_observation_sha256",r.immutableObservationSha256(),
                "canonical_payload_sha256",IngestionProofCodec.sha256(r.canonicalPayload()),"sha256",r.sha256(),
                "size_bytes",r.sizeBytes(),"detected_media_type",r.detectedMediaType(),"storage_version",r.immutableStorageVersion(),
                "key_id",r.keyId(),"issued_at",r.issuedAt(),"not_before",r.notBefore(),"expires_at",r.expiresAt());
        fields.put("canonical_payload",r.canonicalPayload());fields.put("signature",r.signature());
        assertThat(reader.receipt(row(fields),p)).usingRecursiveComparison().isEqualTo(r);
        for(String header:fields.keySet()) {
            var altered=new LinkedHashMap<>(fields);altered.put(header,null);
            assertThatThrownBy(()->reader.receipt(row(altered),p)).as(header).isInstanceOf(FileEvidenceTrustUnavailableException.class);
        }
    }

    private static void put(Map<String,Object> map,Object... values) {
        for(int i=0;i<values.length;i+=2)map.put((String)values[i],values[i+1] instanceof Instant t?t.atOffset(ZoneOffset.UTC):values[i+1]);
    }
    private static Record row(Map<String,Object> values) {
        Record row=mock(Record.class);
        when(row.get(anyString(),any(Class.class))).thenAnswer(call->values.get(call.getArgument(0,String.class)));
        return row;
    }
}
