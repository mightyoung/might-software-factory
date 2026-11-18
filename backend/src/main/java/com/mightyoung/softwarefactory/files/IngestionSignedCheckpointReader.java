package com.mightyoung.softwarefactory.files;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import static com.mightyoung.softwarefactory.files.FileIngestionContracts.*;

/** Decodes the signed wire format, then authenticates every reconstructed projection. */
final class IngestionSignedCheckpointReader {
    private final IngestionProofCodec codec;

    IngestionSignedCheckpointReader(IngestionProofCodec codec) { this.codec = codec; }

    SignedScanProofV1 scan(byte[] payload, byte[] signature) {
        if(signature==null||signature.length!=32)throw new FileEvidenceTrustUnavailableException();
        try (var in = new Fields(payload)) {
            in.expect("SCAN_PROOF_V1"); in.expect("CLEAN");
            var proof = new SignedScanProofV1(in.id(), in.id(), in.id(), in.id(), in.id(), in.id(),
                    in.id(), in.id(), in.id(), in.text(), in.text(), in.text(), in.text(), in.text(),
                    in.text(), in.text(), in.text(), in.text(), in.text(), in.text(), in.text(),
                    in.number(), in.time(), in.time(), in.time(), in.time(), in.time(), payload, signature);
            in.end();
            codec.verifyScan(proof);
            return proof;
        } catch (IOException | IllegalArgumentException | java.time.DateTimeException exception) {
            throw new FileEvidenceTrustUnavailableException();
        }
    }

    IngestionContentReceiptV2 receipt(byte[] payload, byte[] signature, SignedScanProofV1 proof) {
        if(signature==null||signature.length!=32||proof==null)throw new FileEvidenceTrustUnavailableException();
        try (var in = new Fields(payload)) {
            in.expect("SCANNED_CONTENT_V2");
            var receipt = new IngestionContentReceiptV2(in.id(), in.id(), in.id(), in.id(), in.id(), in.id(),
                    in.id(), in.id(), in.text(), in.text(), in.text(), in.text(), in.text(), in.text(),
                    in.text(), in.text(), in.text(), in.text(), in.number(), in.time(), in.time(), in.time(),
                    payload, signature);
            in.end();
            // Historical reads deliberately do not require the original freshness window to remain open.
            codec.verifyReceipt(receipt, proof);
            return receipt;
        } catch (IOException | IllegalArgumentException | java.time.DateTimeException exception) {
            throw new FileEvidenceTrustUnavailableException();
        }
    }

    static byte[] receiptFileBytes(IngestionContentReceiptV2 receipt) {
        try {
            var bytes=new ByteArrayOutputStream();
            try(var out=new DataOutputStream(bytes)) {
                out.writeUTF("SCANNED_CONTENT_V2");byte[] payload=receipt.canonicalPayload();
                out.writeInt(payload.length);out.write(payload);out.write(receipt.signature());
            }
            return bytes.toByteArray();
        } catch(IOException impossible) { throw new IllegalStateException(impossible); }
    }

    private static final class Fields implements AutoCloseable {
        private final DataInputStream in;
        Fields(byte[] payload) {
            if (payload == null || payload.length == 0 || payload.length > MAX_CANONICAL_BYTES) throw invalid();
            in = new DataInputStream(new ByteArrayInputStream(payload));
        }
        void tag(int expected) throws IOException { if (in.readUnsignedByte() != expected) throw invalid(); }
        String text() throws IOException {
            tag(1);
            int size = in.readInt();
            if (size < 1 || size > in.available()) throw invalid();
            return new String(in.readNBytes(size), StandardCharsets.UTF_8);
        }
        void expect(String value) throws IOException { if (!value.equals(text())) throw invalid(); }
        UUID id() throws IOException { tag(2); return new UUID(in.readLong(), in.readLong()); }
        long number() throws IOException { tag(3); return in.readLong(); }
        Instant time() throws IOException { tag(4); return Instant.ofEpochSecond(in.readLong(), in.readInt()); }
        void end() throws IOException { if (in.available() != 0) throw invalid(); }
        @Override public void close() throws IOException { in.close(); }
    }
}
