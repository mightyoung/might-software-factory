package com.mightyoung.softwarefactory.platformadmin.digitaltwin;

import jakarta.servlet.http.HttpServletRequest;

import java.io.IOException;
import java.util.Collections;
import java.util.regex.Pattern;

import static jakarta.servlet.http.HttpServletResponse.*;

/** Bounded dormant transport input. The eventual handler must be behind the existing CSRF/session chain. */
record StudioPublishInput(long expectedVersion, byte[] bytes) {
    static final String EXPECTED_VERSION = "X-Expected-Published-Version";
    private static final Pattern CANONICAL_VERSION = Pattern.compile("0|[1-9][0-9]{0,18}");
    private static final Pattern JSON = Pattern.compile(
            "application/json(?:[ \\t]*;[ \\t]*charset[ \\t]*=[ \\t]*(?:UTF-8|\"UTF-8\"))?[ \\t]*",
            Pattern.CASE_INSENSITIVE);

    StudioPublishInput {
        bytes = bytes.clone();
    }

    @Override public byte[] bytes() { return bytes.clone(); }

    static StudioPublishInput parse(HttpServletRequest request) {
        if (request.getQueryString() != null) throw new Rejected(SC_BAD_REQUEST);
        var versions = Collections.list(request.getHeaders(EXPECTED_VERSION));
        if (versions.size() != 1 || !CANONICAL_VERSION.matcher(versions.getFirst()).matches()) {
            throw new Rejected(SC_BAD_REQUEST);
        }
        long expected;
        try {
            expected = Long.parseLong(versions.getFirst());
        } catch (NumberFormatException invalid) {
            throw new Rejected(SC_BAD_REQUEST);
        }
        var contentTypes = Collections.list(request.getHeaders("Content-Type"));
        if (contentTypes.size() != 1 || !JSON.matcher(contentTypes.getFirst()).matches()
                || request.getHeader("Content-Encoding") != null) {
            throw new Rejected(SC_UNSUPPORTED_MEDIA_TYPE);
        }
        int maximum = StudioDraftDecoder.MAX_BYTES;
        if (request.getContentLengthLong() > maximum) throw new Rejected(SC_REQUEST_ENTITY_TOO_LARGE);
        try {
            var bytes = request.getInputStream().readNBytes(maximum + 1);
            if (bytes.length > maximum) throw new Rejected(SC_REQUEST_ENTITY_TOO_LARGE);
            if (bytes.length == 0) throw new Rejected(SC_BAD_REQUEST);
            return new StudioPublishInput(expected, bytes);
        } catch (IOException invalid) {
            throw new Rejected(SC_BAD_REQUEST);
        }
    }

    static final class Rejected extends RuntimeException {
        private final int status;
        Rejected(int status) { this.status = status; }
        int status() { return status; }
    }
}
