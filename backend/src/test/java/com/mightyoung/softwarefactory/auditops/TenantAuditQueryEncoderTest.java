package com.mightyoung.softwarefactory.auditops;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;
import static com.mightyoung.softwarefactory.auditops.TenantAuditQueryContracts.*;
import static org.assertj.core.api.Assertions.*;

class TenantAuditQueryEncoderTest {
    private static final JsonMapper JSON = JsonMapper.builder().disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS).build();
    private static final Instant FROM = Instant.parse("2026-11-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-11-02T00:00:00Z");
    private static final UUID REQUEST = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID TRACE = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Test void completeEmptyWireRetainsDistinctMetadataAndOmitsTheNullCursor() throws Exception {
        var response = new Response(new Data(List.of(), new Window(FROM, TO), false, null), new Meta(REQUEST, TRACE));
        var encoded = TenantAuditQueryEncoder.encode(response); byte[] bytes = bytes(encoded);
        var root = JSON.readTree(bytes);
        assertThat(root.get("data").get("items").size()).isZero();
        assertThat(root.get("data").get("hasMore").booleanValue()).isFalse(); assertThat(root.get("data").has("nextCursor")).isFalse();
        assertThat(root.get("data").get("window").get("from").stringValue()).isEqualTo(FROM.toString());
        assertThat(root.get("meta").get("requestId").stringValue()).isEqualTo(REQUEST.toString());
        assertThat(root.get("meta").get("traceId").stringValue()).isEqualTo(TRACE.toString());
        assertThat(root.get("meta").get("schemaVersion").isString()).isTrue(); assertThat(root.get("meta").get("schemaVersion").stringValue()).isEqualTo("1");
        assertThat(encoded.size()).isEqualTo(bytes.length);
        assertThat(root).isEqualTo(JSON.readTree(JSON.writeValueAsBytes(response)));
    }

    @Test void completeItemsAndLastCursorArePreservedWithoutDroppingNullableItemFields() throws Exception {
        Item item = new Item(REQUEST, TRACE, null, "finding.create", "finding", null, Result.SUCCESS, null, "v1", FROM);
        var response = new Response(new Data(List.of(item), new Window(FROM, TO), true,
                new Cursor(FROM, REQUEST, "a".repeat(64))), new Meta(REQUEST, TRACE));
        var root = JSON.readTree(bytes(TenantAuditQueryEncoder.encode(response)));
        var data = root.get("data"); var row = data.get("items").get(0);
        assertThat(row.get("delegatorId").isNull()).isTrue(); assertThat(row.get("resourceId").isNull()).isTrue(); assertThat(row.get("traceId").isNull()).isTrue();
        assertThat(data.get("hasMore").booleanValue()).isTrue();
        assertThat(data.get("nextCursor").get("id").stringValue()).isEqualTo(REQUEST.toString());
        assertThat(data.get("nextCursor").get("occurredAt").stringValue()).isEqualTo(FROM.toString());
        assertThat(root).isEqualTo(JSON.readTree(JSON.writeValueAsBytes(response)));
        var keys = new HashSet<String>(); row.properties().forEach(entry -> keys.add(entry.getKey()));
        assertThat(keys).containsExactlyInAnyOrder("id", "actorId", "delegatorId", "action", "resourceType", "resourceId", "result", "traceId", "policyVersion", "occurredAt");
    }

    @Test void allFiftyMaximumLegalMetadataItemsRemainCompleteAndFitTheDeclaredByteGuards() throws Exception {
        Response response = maximumPage(); var encoded = TenantAuditQueryEncoder.encode(response); byte[] bytes = bytes(encoded);
        assertThat(bytes.length).isLessThanOrEqualTo(RESPONSE_BYTES);
        var root = JSON.readTree(bytes); assertThat(root.get("data").get("items").size()).isEqualTo(50);
        assertThat(root).isEqualTo(JSON.readTree(JSON.writeValueAsBytes(response)));
        for (var item : response.data().items()) assertThat(JSON.writeValueAsBytes(item).length).isLessThanOrEqualTo(ITEM_BYTES);
        // Public contracts currently make the legal page smaller than 128KiB; exact ceiling proof is below.
    }

    @Test void aHostileSinkCannotRetainOrModifyTheOwnedBufferOrLaterWrites() throws Exception {
        var encoded = TenantAuditQueryEncoder.encode(maximumPage()); byte[] expected = bytes(encoded); var captured = new ByteArrayOutputStream();
        var largest = new AtomicInteger(); assertThat(expected.length).isGreaterThan(8192);
        encoded.writeTo(new OutputStream() {
            @Override public void write(int value) { captured.write(value); }
            @Override public void write(byte[] values, int offset, int length) {
                largest.accumulateAndGet(values.length, Math::max); captured.write(values, offset, length); Arrays.fill(values, (byte) 0);
            }
        });
        assertThat(largest.get()).isLessThanOrEqualTo(8192); assertThat(captured.toByteArray()).containsExactly(expected);
        assertThat(bytes(encoded)).containsExactly(expected);
    }

    @Test void outputDoesNotFlushOrCloseTheCallersStream() throws Exception {
        var flushed = new AtomicInteger(); var closed = new AtomicInteger();
        var destination = new ByteArrayOutputStream() {
            @Override public void flush() { flushed.incrementAndGet(); }
            @Override public void close() { closed.incrementAndGet(); }
        };
        TenantAuditQueryEncoder.encode(maximumPage()).writeTo(destination);
        assertThat(flushed.get()).isZero(); assertThat(closed.get()).isZero();
        destination.write(1); assertThat(destination.size()).isPositive();
    }

    @Test void externalSinkFailuresPropagateUnchangedWithoutRetryOrReencoding() {
        var encoded = TenantAuditQueryEncoder.encode(maximumPage());
        var attempts = new AtomicInteger(); IOException io = new IOException("synthetic sink failure");
        assertThatThrownBy(() -> encoded.writeTo(new OutputStream() {
            @Override public void write(int value) throws IOException { attempts.incrementAndGet(); throw io; }
            @Override public void write(byte[] values, int offset, int length) throws IOException { attempts.incrementAndGet(); throw io; }
        })).isSameAs(io);
        assertThat(attempts.get()).isEqualTo(1);
        RuntimeException runtime = new IllegalStateException("synthetic sink runtime"); attempts.set(0);
        assertThatThrownBy(() -> encoded.writeTo(new OutputStream() {
            @Override public void write(int value) { attempts.incrementAndGet(); throw runtime; }
            @Override public void write(byte[] values, int offset, int length) { attempts.incrementAndGet(); throw runtime; }
        })).isSameAs(runtime);
        assertThat(attempts.get()).isEqualTo(1);
    }

    @Test void privatePrimitiveEnforcesExactItemAndCompleteByteCeilingsWithoutPartialSuccess() throws Exception {
        for (int maximum : List.of(ITEM_BYTES, RESPONSE_BYTES)) {
            var exact = new TenantAuditQueryEncoder.LimitedOutput(maximum); exact.write(new byte[maximum], 0, maximum);
            var sealed = exact.seal(); assertThat(sealed.size()).isEqualTo(maximum);
            assertThatThrownBy(() -> exact.write(1)).isInstanceOf(IOException.class);
            assertThat(bytes(sealed)).hasSize(maximum);
            var overflow = new TenantAuditQueryEncoder.LimitedOutput(maximum); overflow.write(new byte[maximum], 0, maximum);
            assertThatThrownBy(() -> overflow.write(1)).isInstanceOf(IOException.class);
            assertThatThrownBy(overflow::seal).isExactlyInstanceOf(TenantAuditQueryEncoder.EncodingFailure.class)
                    .hasMessage("Audit query response cannot be encoded");
        }
    }

    @Test void encodingFailureIsFixedAndSeparateFromParserRejection() {
        assertThatThrownBy(() -> TenantAuditQueryEncoder.encode(null)).isExactlyInstanceOf(TenantAuditQueryEncoder.EncodingFailure.class)
                .isNotInstanceOf(Failure.class).hasMessage("Audit query response cannot be encoded");
        assertThatThrownBy(() -> new TenantAuditQueryEncoder.LimitedOutput(RESPONSE_BYTES + 1)).isExactlyInstanceOf(TenantAuditQueryEncoder.EncodingFailure.class);
    }

    @Test void caughtNullAndInvalidRangesPermanentlyInvalidateTheInternalSink() {
        for (int mode : List.of(0, 1, 2)) {
            var output = new TenantAuditQueryEncoder.LimitedOutput(ITEM_BYTES);
            int fault = mode;
            assertThatThrownBy(() -> {
                if (fault == 0) output.write(null, 0, 0);
                else if (fault == 1) output.write(new byte[1], -1, 1);
                else output.write(new byte[1], 0, 2);
            }).isExactlyInstanceOf(IOException.class).hasMessage("Audit query encoding boundary exceeded");
            assertThatThrownBy(() -> output.write(1)).isExactlyInstanceOf(IOException.class);
            assertThatThrownBy(output::seal).isExactlyInstanceOf(TenantAuditQueryEncoder.EncodingFailure.class);
        }
    }

    @Test void caughtNullWholeArrayWriteCannotBypassPermanentInvalidation() {
        var output = new TenantAuditQueryEncoder.LimitedOutput(ITEM_BYTES);
        assertThatThrownBy(() -> output.write((byte[]) null)).isExactlyInstanceOf(IOException.class)
                .hasMessage("Audit query encoding boundary exceeded");
        assertThatThrownBy(() -> output.write(new byte[]{1})).isExactlyInstanceOf(IOException.class);
        assertThatThrownBy(output::seal).isExactlyInstanceOf(TenantAuditQueryEncoder.EncodingFailure.class);
    }

    private static byte[] bytes(TenantAuditQueryEncoder.EncodedResponse encoded) throws IOException {
        var output = new ByteArrayOutputStream(); encoded.writeTo(output); return output.toByteArray();
    }
    private static Response maximumPage() {
        var items = new ArrayList<Item>();
        for (int i = 50; i > 0; i--) items.add(new Item(new UUID(0, i), REQUEST, TRACE, "a".repeat(128), "r".repeat(64),
                TRACE, Result.SUCCESS, TRACE, "p".repeat(64), FROM.plusSeconds(i).plusNanos(123456000)));
        Item last = items.getLast();
        return new Response(new Data(items, new Window(FROM, TO), true, new Cursor(last.occurredAt(), last.id(), "f".repeat(64))), new Meta(REQUEST, TRACE));
    }
}
