package com.mightyoung.softwarefactory.platformadmin.digitaltwin;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StudioPublishInputTest {
    private static final String EXPECTED = "X-Expected-Published-Version";

    @Test void acceptsFirstPublicationAndPassesAnExactUnparsedBody() throws Exception {
        var bytes = "{\"scene\":\"车间\"}".getBytes(StandardCharsets.UTF_8);
        var request = request("0", bytes);
        request.setContentType("application/json; charset=UTF-8");
        var input = StudioPublishInput.parse(request);
        assertEquals(0, input.expectedVersion());
        assertArrayEquals(bytes, input.bytes());
        input.bytes()[0] = 0;
        assertArrayEquals(bytes, input.bytes());
    }

    @Test void enforcesDeclaredAndStreamedLimitsAndAllowsTheExactCap() throws Exception {
        var atLimit = new byte[256 * 1024];
        assertEquals(atLimit.length, StudioPublishInput.parse(request("1", atLimit)).bytes().length);
        var oversized = new byte[atLimit.length + 1];
        var declared = spy(request("1", oversized));
        assertStatus(413, declared);
        verify(declared, never()).getInputStream();
        var streamed = spy(request("1", oversized));
        doReturn(-1L).when(streamed).getContentLengthLong();
        assertStatus(413, streamed);
        verify(streamed).getInputStream();
    }

    @Test void queryAndAbsentDuplicateOrInvalidVersionAreRejectedBeforeReading() throws Exception {
        var query = spy(request("1", new byte[]{1}));
        query.setQueryString("tenant=another");
        assertStatus(400, query);
        verify(query, never()).getInputStream();
        var duplicate = spy(request("1", new byte[]{1}));
        duplicate.addHeader(EXPECTED, "2");
        assertStatus(400, duplicate);
        verify(duplicate, never()).getInputStream();
        for (String bad : new String[]{null, "01", "-1", "1, 2", "9223372036854775808", " 1"}) {
            var malformed = spy(request(bad, new byte[]{1}));
            assertStatus(400, malformed);
            verify(malformed, never()).getInputStream();
        }
        assertEquals(Long.MAX_VALUE, StudioPublishInput.parse(request(Long.toString(Long.MAX_VALUE), new byte[]{1})).expectedVersion());
    }

    @Test void rejectsWrongContentTypesAndEncodedOrEmptyBodies() {
        var wrongType = request("1", new byte[]{1});
        wrongType.setContentType("text/plain");
        assertStatus(415, wrongType);
        // MockHttpServletRequest normalizes Content-Type as a single value, unlike the raw enumeration.
        var duplicateType = spy(request("1", new byte[]{1}));
        doReturn(java.util.Collections.enumeration(java.util.List.of("application/json", "application/json")))
                .when(duplicateType).getHeaders("Content-Type");
        assertStatus(415, duplicateType);
        var encoded = request("1", new byte[]{1});
        encoded.addHeader("Content-Encoding", "gzip");
        assertStatus(415, encoded);
        assertStatus(400, request("1", new byte[0]));
    }

    private static MockHttpServletRequest request(String expected, byte[] bytes) {
        var request = new MockHttpServletRequest("POST", "/api/v1/admin/studio/screens/floor/publish");
        request.setContentType("application/json");
        request.setContent(bytes);
        if (expected != null) request.addHeader(EXPECTED, expected);
        return request;
    }

    private static void assertStatus(int status, MockHttpServletRequest request) {
        var failure = assertThrows(StudioPublishInput.Rejected.class, () -> StudioPublishInput.parse(request));
        assertEquals(status, failure.status());
    }
}
