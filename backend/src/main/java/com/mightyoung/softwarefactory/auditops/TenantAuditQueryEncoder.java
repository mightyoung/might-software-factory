package com.mightyoung.softwarefactory.auditops;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;
import static com.mightyoung.softwarefactory.auditops.TenantAuditQueryContracts.*;

/** Encodes immutable typed values once; transport consumes the owned result without re-encoding DTOs. */
final class TenantAuditQueryEncoder {
    private static final JsonMapper JSON = JsonMapper.builder().disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS).build();
    private TenantAuditQueryEncoder() { }

    static EncodedResponse encode(Response response) {
        if (response == null) throw new EncodingFailure();
        var output = new LimitedOutput(RESPONSE_BYTES);
        try {
            output.literal("{\"data\":{\"items\":[");
            boolean first = true;
            for (Item item : response.data().items()) {
                var encodedItem = new LimitedOutput(ITEM_BYTES);
                JSON.writeValue(encodedItem, item);
                if (!first) output.literal(","); first = false;
                encodedItem.seal().appendTo(output);
            }
            output.literal("],\"window\":"); JSON.writeValue(output, response.data().window());
            output.literal(response.data().hasMore() ? ",\"hasMore\":true" : ",\"hasMore\":false");
            if (response.data().nextCursor() != null) {
                output.literal(",\"nextCursor\":"); JSON.writeValue(output, response.data().nextCursor());
            }
            output.literal("},\"meta\":"); JSON.writeValue(output, response.meta()); output.literal("}");
            return output.seal();
        } catch (IOException | tools.jackson.core.JacksonException failure) { throw new EncodingFailure(); }
    }

    static final class EncodingFailure extends IllegalStateException {
        EncodingFailure() { super("Audit query response cannot be encoded"); }
    }
    static final class EncodedResponse {
        private final byte[] bytes;
        private final int length;
        private EncodedResponse(byte[] owned, int length) { this.bytes = owned; this.length = length; }
        int size() { return length; }
        private void appendTo(LimitedOutput target) throws IOException { target.write(bytes, 0, length); }
        void writeTo(OutputStream destination) throws IOException {
            Objects.requireNonNull(destination);
            byte[] scratch = new byte[Math.min(8192, length)];
            for (int offset = 0; offset < length;) {
                int amount = Math.min(scratch.length, length - offset);
                System.arraycopy(bytes, offset, scratch, 0, amount); destination.write(scratch, 0, amount); offset += amount;
            }
        }
    }
    /** Module-private mechanical budget primitive; production limits are fixed above, never supplied by a request. */
    static final class LimitedOutput extends OutputStream {
        private final byte[] bytes;
        private int size;
        private boolean sealed;
        private boolean failed;
        LimitedOutput(int maximum) {
            if (maximum < 1 || maximum > RESPONSE_BYTES) throw new EncodingFailure(); bytes = new byte[maximum];
        }
        @Override public void write(int value) throws IOException { writable(1); bytes[size++] = (byte) value; }
        @Override public void write(byte[] value) throws IOException { write(value, 0, value == null ? 0 : value.length); }
        @Override public void write(byte[] value, int offset, int length) throws IOException {
            try { Objects.checkFromIndexSize(offset, length, value.length); }
            catch (RuntimeException invalid) { failed = true; throw new IOException("Audit query encoding boundary exceeded"); }
            writable(length);
            System.arraycopy(value, offset, bytes, size, length); size += length;
        }
        void literal(String fixed) throws IOException { byte[] value = fixed.getBytes(StandardCharsets.US_ASCII); write(value, 0, value.length); }
        private void writable(int length) throws IOException {
            if (sealed || failed || length > bytes.length - size) { failed = true; throw new IOException("Audit query encoding boundary exceeded"); }
        }
        EncodedResponse seal() {
            if (sealed || failed) throw new EncodingFailure(); sealed = true; return new EncodedResponse(bytes, size);
        }
    }
}
