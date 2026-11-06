package com.mightyoung.softwarefactory.auditops;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import static com.mightyoung.softwarefactory.auditops.TenantAuditQueryContracts.*;
import static org.assertj.core.api.Assertions.*;

class TenantAuditQueryParserTest {
    private final TenantAuditQueryParser parser = new TenantAuditQueryParser();
    private static final String ID = "11111111-1111-1111-1111-111111111111";
    private static final String HASH = "a".repeat(64);

    @Test void defaultsRemainUnresolvedUntilTheAuthorizedServiceCut() {
        var value = parser.query(request("{}"));
        assertThat(value.from()).isNull(); assertThat(value.to()).isNull(); assertThat(value.cursor()).isNull();
        assertThat(value.pageSize()).isEqualTo(20);
        // Deliberately future input: parser has no clock and cannot perform the service's observedAt check.
        var future = parser.query(request("{\"from\":\"9999-01-01T00:00:00Z\",\"to\":\"9999-02-01T00:00:00Z\"}"));
        assertThat(future.to()).isEqualTo(Instant.parse("9999-02-01T00:00:00Z"));
    }

    @Test void parsesTypedFiltersAndNormalizesBothAcceptedUtcRepresentations() {
        var value = parser.query(request("{\"from\":\"2026-11-01T00:00:00.1+00:00\",\"to\":\"2026-11-02T00:00:00.123456Z\","
                + "\"actorId\":\"" + ID + "\",\"resourceType\":\"finding\",\"resourceId\":\"" + ID + "\","
                + "\"action\":\"finding.create\",\"result\":\"DENIED\",\"pageSize\":50,\"cursor\":{"
                + "\"occurredAt\":\"2026-11-01T12:00:00Z\",\"id\":\"" + ID + "\",\"queryFingerprint\":\"" + HASH + "\"}}"));
        assertThat(value.from()).isEqualTo(Instant.parse("2026-11-01T00:00:00.100000Z"));
        assertThat(value.to().getNano()).isEqualTo(123456000);
        assertThat(value.actorId().toString()).isEqualTo(ID); assertThat(value.resourceId()).isEqualTo(value.actorId());
        assertThat(value.result()).isEqualTo(Result.DENIED); assertThat(value.pageSize()).isEqualTo(50);
        assertThat(value.cursor().queryFingerprint()).isEqualTo(HASH);
    }

    @Test void acceptsExactly8192BytesButReadsOnlyThe8193ByteProbeForAnOversizedBody() {
        assertThat(parser.query(request("{}" + " ".repeat(8190))).pageSize()).isEqualTo(20);
        var consumed = new AtomicInteger(); byte[] oversized = ("{}" + " ".repeat(100000)).getBytes(StandardCharsets.UTF_8);
        var stream = new ByteArrayInputStream(oversized);
        var request = new MockHttpServletRequest("POST", "/api/v1/audit-events/query") {
            @Override public ServletInputStream getInputStream() {
                return new ServletInputStream() {
                    @Override public int read() { int value = stream.read(); if (value >= 0) consumed.incrementAndGet(); return value; }
                    @Override public int read(byte[] values, int offset, int length) { int count = stream.read(values, offset, length); if (count > 0) consumed.addAndGet(count); return count; }
                    @Override public boolean isFinished() { return stream.available() == 0; }
                    @Override public boolean isReady() { return true; }
                    @Override public void setReadListener(ReadListener listener) { throw new UnsupportedOperationException(); }
                };
            }
        };
        request.setContentType("application/json");
        fails(request, Code.REQUEST_TOO_LARGE); assertThat(consumed.get()).isEqualTo(8193);
    }

    @ParameterizedTest @MethodSource("invalidJson")
    void rejectsAmbiguousOrNonClosedJson(String body) { fails(request(body), Code.BAD_REQUEST); }
    static Stream<String> invalidJson() {
        return Stream.of("", "[]", "null", "{} {}", "{} true", "{\"pageSize\":20,\"pageSize\":21}",
                "{\"tenantId\":\"" + ID + "\"}", "{\"pageSize\":20.0}", "{\"pageSize\":2e1}", "{\"pageSize\":true}",
                "{\"pageSize\":0}", "{\"pageSize\":51}", "{\"pageSize\":2147483648}", "{\"actorId\":null}",
                "{\"action\":\"bad\\u0000value\"}", "{\"action\":\"\\ud800\"}", "{\"action\":\"\\udc00\"}",
                "{\"from\":\"2026-11-01T00:00:00Z\"}", "{\"resourceId\":\"" + ID + "\"}", "{\"result\":\"UNKNOWN\"}",
                "{\"cursor\":null}", "{\"cursor\":{\"nested\":{\"nested\":{\"nested\":{}}}}}", "{\"pageSize\":20,}", "{/*comment*/}", "{'pageSize':20}");
    }

    @ParameterizedTest @ValueSource(strings = {"2026-11-01T00:00:00.1234567Z", "2026-11-01T00:00:00-00:00", "2026-11-01T01:00:00+01:00",
            "2026-11-01T00:00:00z", "2026-11-01T00:00Z", "2026-02-29T00:00:00Z", "0000-11-01T00:00:00Z", "2026-11-01T00:00:60Z"})
    void rejectsNonUtcOrNonMicrosecondDateRepresentations(String value) {
        fails(request("{\"from\":\"" + value + "\",\"to\":\"2026-11-02T00:00:00Z\"}"), Code.BAD_REQUEST);
    }

    @Test void enforcesStaticWindowAndCursorBoundsWithoutTreatingTheFingerprintAsAuthority() {
        fails(request("{\"from\":\"2026-10-01T00:00:00Z\",\"to\":\"2026-11-02T00:00:00Z\"}"), Code.BAD_REQUEST);
        fails(request("{\"from\":\"2026-11-02T00:00:00Z\",\"to\":\"2026-11-02T00:00:00Z\"}"), Code.BAD_REQUEST);
        String cursor = "\"cursor\":{\"occurredAt\":\"2026-11-02T00:00:00Z\",\"id\":\"" + ID + "\",\"queryFingerprint\":\"" + HASH + "\"}";
        fails(request("{" + cursor + "}"), Code.BAD_REQUEST);
        fails(request("{\"from\":\"2026-11-01T00:00:00Z\",\"to\":\"2026-11-02T00:00:00Z\"," + cursor + "}"), Code.BAD_REQUEST);
        fails(request("{\"from\":\"2026-11-01T00:00:00Z\",\"to\":\"2026-11-03T00:00:00Z\"," + cursor.replace(HASH, "A".repeat(64)) + "}"), Code.BAD_REQUEST);
        fails(request("{\"from\":\"2026-11-01T00:00:00Z\",\"to\":\"2026-11-03T00:00:00Z\"," + cursor.replace("\"queryFingerprint\"", "\"unknown\"") + "}"), Code.BAD_REQUEST);
    }

    @ParameterizedTest @ValueSource(strings = {"1-1-1-1-1", "FFFFFFFF-FFFF-FFFF-FFFF-FFFFFFFFFFFF", "11111111-1111-1111-1111-111111111111 ", "urn:uuid:11111111-1111-1111-1111-111111111111"})
    void refusesNonCanonicalUuid(String value) { fails(request("{\"actorId\":\"" + value + "\"}"), Code.BAD_REQUEST); }

    @ParameterizedTest @ValueSource(strings = {"text/plain", "application/json;charset=ISO-8859-1", "application/json;foo=bar", "application/json;charset=UTF-8;charset=UTF-8"})
    void refusesOtherMediaAndParameters(String type) { var request = request("{}"); request.setContentType(type); fails(request, Code.UNSUPPORTED_MEDIA_TYPE); }

    @Test void rejectsDuplicateMediaAnyEncodingQueryStringAndInvalidUtf8() {
        var ordinary = request("{}");
        var duplicate = new HttpServletRequestWrapper(ordinary) {
            @Override public java.util.Enumeration<String> getHeaders(String name) {
                return name.equalsIgnoreCase("Content-Type") ? Collections.enumeration(List.of("application/json", "application/json")) : super.getHeaders(name);
            }
        };
        fails(duplicate, Code.UNSUPPORTED_MEDIA_TYPE);
        var encoded = request("{}"); encoded.addHeader("Content-Encoding", "identity"); fails(encoded, Code.UNSUPPORTED_MEDIA_TYPE);
        var query = request("{}"); query.setQueryString(""); fails(query, Code.BAD_REQUEST);
        var badUtf8 = request("{}"); badUtf8.setContent(new byte[]{(byte) 0xc3, (byte) 0x28}); fails(badUtf8, Code.BAD_REQUEST);
        fails(request("\uFEFF{}"), Code.BAD_REQUEST);
        var missing = request("{}"); missing.removeHeader("Content-Type"); fails(missing, Code.UNSUPPORTED_MEDIA_TYPE);
    }

    @ParameterizedTest @ValueSource(strings = {"application/json", "application/json;charset=UTF-8", "Application/JSON; charset=\"UTF-8\""})
    void acceptsOnlyDeclaredJsonUtf8Forms(String type) { var request = request("{}"); request.setContentType(type); assertThat(parser.query(request).pageSize()).isEqualTo(20); }

    private static MockHttpServletRequest request(String body) {
        var request = new MockHttpServletRequest("POST", "/api/v1/audit-events/query"); request.setContentType("application/json");
        request.setContent(body.getBytes(StandardCharsets.UTF_8)); return request;
    }
    private void fails(HttpServletRequest request, Code code) {
        assertThatThrownBy(() -> parser.query(request)).isExactlyInstanceOf(Failure.class).satisfies(error -> {
            assertThat(((Failure) error).code()).isEqualTo(code); assertThat(((Failure) error).status()).isEqualTo(code.status());
            assertThat(error).hasMessage("Audit query request was rejected");
        });
    }
}
