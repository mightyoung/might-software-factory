package com.mightyoung.softwarefactory.identity;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/** Holds a small login response until the caller confirms its authentication event. */
final class BufferedLoginResponse extends HttpServletResponseWrapper {
    private static final int MAX_BYTES = 16 * 1024;
    private static final DateTimeFormatter HTTP_DATE = DateTimeFormatter
            .ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US).withZone(ZoneOffset.UTC);
    private final byte[] body;
    private final Map<String, List<String>> headers = new LinkedHashMap<>();
    private int bodySize;
    private int headerSize;
    private int status;
    private int generation;
    private boolean releaseAttempted;
    private Charset charset;
    private Locale locale;
    private ServletOutputStream stream;
    private PrintWriter writer;
    private BodyWriter bodyWriter;

    BufferedLoginResponse(HttpServletResponse target, int maxBytes) {
        super(target);
        if (maxBytes < 1 || maxBytes > MAX_BYTES) throw new IllegalArgumentException("Invalid login response limit");
        body = new byte[maxBytes];
        status = target.getStatus();
        charset = Charset.forName(target.getCharacterEncoding());
        locale = target.getLocale();
        for (String name : target.getHeaderNames()) {
            for (String value : target.getHeaders(name)) addHeader(name, value);
        }
    }

    @Override public int getStatus() { return status; }
    @Override public void setStatus(int value) { requireOpen(); status = value; }
    @Override public boolean isCommitted() { return releaseAttempted; }
    @Override public String getCharacterEncoding() { return charset.name(); }
    @Override public String getContentType() { return getHeader("Content-Type"); }
    @Override public Locale getLocale() { return locale; }
    @Override public void setLocale(Locale value) { requireOpen(); locale = Objects.requireNonNull(value); }
    @Override public String encodeURL(String url) { return url; }
    @Override public String encodeRedirectURL(String url) { return url; }

    @Override public void setCharacterEncoding(String value) {
        requireOpen();
        if (writer != null || value == null) return;
        Charset next = Charset.forName(value);
        String type = getContentType();
        if (type != null) replaceHeader("Content-Type", withoutCharset(type) + ";charset=" + next.name(), false);
        charset = next;
    }
    @Override public void setCharacterEncoding(Charset value) { setCharacterEncoding(value == null ? null : value.name()); }

    @Override public void setContentType(String value) {
        requireOpen();
        if (value == null) { replaceHeader("Content-Type", null, false); return; }
        if (utf8Size(value) > body.length) throw new ResponseLimitExceeded();
        String[] parts = value.split(";");
        Charset next = charset;
        boolean specifiedCharset = false;
        for (int i = 1; i < parts.length; i++) {
            String part = parts[i].strip();
            if (part.toLowerCase(Locale.ROOT).startsWith("charset=")) {
                specifiedCharset = true;
                if (writer == null) next = Charset.forName(part.substring(8).replace("\"", "").strip());
            }
        }
        String normalized = specifiedCharset ? withoutCharset(value) + ";charset=" + next.name() : value;
        replaceHeader("Content-Type", normalized, false);
        charset = next;
    }

    private static String withoutCharset(String type) {
        StringBuilder result = new StringBuilder();
        for (String part : type.split(";")) {
            if (!part.strip().toLowerCase(Locale.ROOT).startsWith("charset=")) {
                if (!result.isEmpty()) result.append(';');
                result.append(part);
            }
        }
        return result.toString();
    }

    @Override public void setContentLength(int value) { setContentLengthLong(value); }
    @Override public void setContentLengthLong(long value) { setHeader("Content-Length", Long.toString(value)); }
    @Override public boolean containsHeader(String name) { return headers.containsKey(key(name)); }
    @Override public String getHeader(String name) {
        List<String> values = headers.get(key(name));
        return values == null || values.isEmpty() ? null : values.getFirst();
    }
    @Override public Collection<String> getHeaders(String name) {
        List<String> values = headers.get(key(name));
        return values == null ? List.of() : List.copyOf(values);
    }
    @Override public Collection<String> getHeaderNames() { return List.copyOf(headers.keySet()); }
    @Override public void setHeader(String name, String value) {
        if (name.equalsIgnoreCase("Content-Type")) setContentType(value);
        else replaceHeader(name, value, false);
    }
    @Override public void addHeader(String name, String value) { replaceHeader(name, value, true); }
    @Override public void setIntHeader(String name, int value) { setHeader(name, Integer.toString(value)); }
    @Override public void addIntHeader(String name, int value) { addHeader(name, Integer.toString(value)); }
    @Override public void setDateHeader(String name, long value) { setHeader(name, HTTP_DATE.format(Instant.ofEpochMilli(value))); }
    @Override public void addDateHeader(String name, long value) { addHeader(name, HTTP_DATE.format(Instant.ofEpochMilli(value))); }

    private void replaceHeader(String name, String value, boolean append) {
        requireOpen();
        String key = key(name);
        if (value == null) append = false;
        List<String> previous = headers.getOrDefault(key, List.of());
        int removed = append ? 0 : previous.stream().mapToInt(item -> headerBytes(key, item)).sum();
        int added = value == null ? 0 : headerBytes(key, value);
        if ((long) bodySize + headerSize - removed + added > body.length) throw new ResponseLimitExceeded();
        if (!append || value == null) headers.remove(key);
        if (value != null) headers.computeIfAbsent(key, ignored -> new ArrayList<>()).add(value);
        headerSize += added - removed;
    }

    @Override public void addCookie(Cookie cookie) {
        Objects.requireNonNull(cookie);
        // Bound each component before constructing the serialized cookie.
        long size = (long) utf8Size(cookie.getName()) + 1 + utf8Size(Objects.toString(cookie.getValue(), ""));
        for (var attribute : cookie.getAttributes().entrySet()) {
            size += 2L + utf8Size(attribute.getKey());
            if (!attribute.getValue().isEmpty()) size += 1L + utf8Size(attribute.getValue());
        }
        requireFits(size + headerBytes("set-cookie", ""));
        StringBuilder value = new StringBuilder((int) size).append(cookie.getName()).append('=')
                .append(Objects.toString(cookie.getValue(), ""));
        cookie.getAttributes().forEach((name, attribute) -> {
            value.append("; ").append(name);
            if (!attribute.isEmpty()) value.append('=').append(attribute);
        });
        addHeader("Set-Cookie", value.toString());
    }

    void discardAuthenticationCookies() {
        requireOpen();
        List<String> retained = new ArrayList<>(getHeaders("Set-Cookie"));
        retained.removeIf(value -> {
            int equals = value.indexOf('=');
            String name = equals < 0 ? value.strip() : value.substring(0, equals).strip();
            return name.equals("SESSION") || name.equals("XSRF-TOKEN");
        });
        setHeader("Set-Cookie", null);
        retained.forEach(value -> addHeader("Set-Cookie", value));
        IdentityHttpResponses.clearSessionCookies(this);
    }

    @Override public ServletOutputStream getOutputStream() {
        requireOpen();
        if (writer != null) throw new IllegalStateException("Writer already selected");
        if (stream == null) {
            int selectedGeneration = generation;
            stream = new ServletOutputStream() {
                @Override public void write(int value) { checkGeneration(selectedGeneration); requireFits(1); body[bodySize++] = (byte) value; }
                @Override public void write(byte[] values, int offset, int length) {
                    Objects.checkFromIndexSize(offset, length, values.length);
                    checkGeneration(selectedGeneration); requireFits(length);
                    System.arraycopy(values, offset, body, bodySize, length); bodySize += length;
                }
                @Override public boolean isReady() { return !releaseAttempted; }
                @Override public void setWriteListener(WriteListener listener) { throw new UnsupportedOperationException("Async login response unsupported"); }
                @Override public void flush() { checkGeneration(selectedGeneration); }
                @Override public void close() { checkGeneration(selectedGeneration); }
            };
        }
        return stream;
    }

    @Override public PrintWriter getWriter() {
        requireOpen();
        if (stream != null) throw new IllegalStateException("Output stream already selected");
        if (writer == null) {
            bodyWriter = new BodyWriter();
            writer = new PrintWriter(bodyWriter);
        }
        return writer;
    }

    private final class BodyWriter extends Writer {
        private final int selectedGeneration = generation;
        private final CharsetEncoder encoder = charset.newEncoder().onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);
        private char pending;
        private boolean finished;
        @Override public void write(int value) { encode(CharBuffer.wrap(new char[] {(char) value})); }
        @Override public void write(char[] values, int offset, int length) { encode(CharBuffer.wrap(values, offset, length)); }
        @Override public void write(String value, int offset, int length) { encode(CharBuffer.wrap(value, offset, offset + length)); }
        private void encode(CharBuffer input) {
            checkGeneration(selectedGeneration);
            if (finished) throw new IllegalStateException("Writer closed");
            while (pending != 0 && input.hasRemaining()) {
                char first = input.get();
                CharBuffer pair = CharBuffer.wrap(new char[] {pending, first});
                pending = 0;
                encodeChunk(pair, false);
                if (pair.hasRemaining()) pending = pair.get();
            }
            encodeChunk(input, false);
            if (input.hasRemaining()) pending = input.get();
        }
        private void encodeChunk(CharBuffer input, boolean end) {
            ByteBuffer output = ByteBuffer.wrap(body);
            output.position(bodySize).limit(body.length - headerSize);
            var result = encoder.encode(input, output, end);
            bodySize = output.position();
            if (result.isOverflow() || result.isError()) throw new ResponseLimitExceeded();
        }
        private void finish() {
            if (finished) return;
            encodeChunk(pending == 0 ? CharBuffer.allocate(0) : CharBuffer.wrap(new char[] {pending}), true);
            ByteBuffer output = ByteBuffer.wrap(body);
            output.position(bodySize).limit(body.length - headerSize);
            var result = encoder.flush(output);
            bodySize = output.position();
            if (result.isOverflow() || result.isError()) throw new ResponseLimitExceeded();
            pending = 0;
            finished = true;
        }
        private void clear() { encoder.reset(); pending = 0; finished = false; }
        @Override public void flush() { checkGeneration(selectedGeneration); }
        @Override public void close() { checkGeneration(selectedGeneration); finish(); }
    }

    @Override public void flushBuffer() { requireOpen(); if (writer != null) writer.flush(); }
    @Override public void resetBuffer() {
        requireOpen(); bodySize = 0;
        if (bodyWriter != null) bodyWriter.clear();
        setHeader("Content-Length", null);
    }
    @Override public void reset() {
        requireOpen(); bodySize = 0; headerSize = 0; headers.clear(); status = SC_OK;
        charset = StandardCharsets.ISO_8859_1; generation++; stream = null; writer = null; bodyWriter = null;
    }
    @Override public int getBufferSize() { return body.length; }
    @Override public void setBufferSize(int size) {
        requireOpen();
        if (bodySize != 0 || size > body.length || size < 0) throw new IllegalStateException("Fixed bounded login response buffer");
    }
    @Override public void sendError(int value) { sendError(value, null); }
    @Override public void sendError(int value, String message) { resetBuffer(); setStatus(value); }
    @Override public void sendRedirect(String location) { sendRedirect(location, SC_FOUND, true); }
    @Override public void sendRedirect(String location, boolean clearBuffer) { sendRedirect(location, SC_FOUND, clearBuffer); }
    @Override public void sendRedirect(String location, int value) { sendRedirect(location, value, true); }
    @Override public void sendRedirect(String location, int value, boolean clearBuffer) {
        requireOpen(); if (clearBuffer) resetBuffer(); setHeader("Location", location); setStatus(value);
    }
    @Override public void setTrailerFields(Supplier<Map<String, String>> supplier) { throw new UnsupportedOperationException("Login trailers unsupported"); }
    @Override public Supplier<Map<String, String>> getTrailerFields() { throw new UnsupportedOperationException("Login trailers unsupported"); }

    /** Complete local encoding before the caller classifies and records the response. */
    void prepare() {
        requireOpen();
        if (bodyWriter != null) bodyWriter.finish();
    }

    void release() throws IOException {
        requireOpen();
        // Mark the attempt before the first operation that can fail; publication must never be retried.
        releaseAttempted = true;
        if (bodyWriter != null) bodyWriter.finish();
        HttpServletResponse target = (HttpServletResponse) getResponse();
        target.reset();
        target.setStatus(status);
        target.setCharacterEncoding(charset.name());
        target.setLocale(locale);
        headers.forEach((name, values) -> {
            target.setHeader(name, values.getFirst());
            for (int i = 1; i < values.size(); i++) target.addHeader(name, values.get(i));
        });
        target.getOutputStream().write(body, 0, bodySize);
        target.flushBuffer();
    }

    private void requireOpen() { if (releaseAttempted) throw new IllegalStateException("Login response release already attempted"); }
    private void checkGeneration(int selected) { requireOpen(); if (selected != generation) throw new IllegalStateException("Response was reset"); }
    private void requireFits(long added) { if ((long) bodySize + headerSize + added > body.length) throw new ResponseLimitExceeded(); }
    private static String key(String name) {
        utf8Size(Objects.requireNonNull(name));
        return name.toLowerCase(Locale.ROOT);
    }
    private static int headerBytes(String name, String value) {
        long bytes = (long) utf8Size(name) + utf8Size(value) + 4;
        if (bytes > MAX_BYTES) throw new ResponseLimitExceeded();
        return (int) bytes;
    }
    private static int utf8Size(String value) {
        int size = 0;
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            size += character < 0x80 ? 1 : character < 0x800 ? 2 : 3;
            if (Character.isHighSurrogate(character) && i + 1 < value.length() && Character.isLowSurrogate(value.charAt(i + 1))) { size++; i++; }
            if (size > MAX_BYTES) throw new ResponseLimitExceeded();
        }
        return size;
    }

    static final class ResponseLimitExceeded extends IllegalStateException {
        ResponseLimitExceeded() { super("Login response exceeds its bounded buffer"); }
    }
}
