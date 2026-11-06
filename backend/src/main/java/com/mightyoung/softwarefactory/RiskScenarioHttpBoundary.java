package com.mightyoung.softwarefactory;

import com.mightyoung.softwarefactory.dualprevention.RiskScenarioHttpTransport;
import com.mightyoung.softwarefactory.identity.IdentityRequestIds;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Supplier;
import java.util.function.IntSupplier;
import org.springframework.web.filter.OncePerRequestFilter;

/** Outer synchronous read boundary. Registration and a reviewed container reserve are owned by configuration. */
final class RiskScenarioHttpBoundary extends OncePerRequestFilter implements RiskScenarioHttpTransport {
    private static final String ATTRIBUTE = RiskScenarioHttpBoundary.class.getName() + ".lifetime";
    private static final String FAILURE = "Risk HTTP boundary rejected the response";
    private static final int ERROR_BYTES = 8192;
    private static final int MAX_BODY_BYTES = 1024 * 1024;
    private static final Set<String> SENDFILE_ATTRIBUTES = Set.of("org.apache.tomcat.sendfile.filename",
            "org.apache.tomcat.sendfile.start", "org.apache.tomcat.sendfile.end");
    private static final Set<String> FIXED_HEADERS = Set.of("content-type", "cache-control", "etag", "vary");
    private static final DateTimeFormatter HTTP_DATE = DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US).withZone(ZoneOffset.UTC);
    private final IntSupplier containerReserve;
    private final ThreadLocal<Lifetime> current = new ThreadLocal<>();

    RiskScenarioHttpBoundary(int containerReserveBytes) { this(constantReserve(containerReserveBytes)); }
    RiskScenarioHttpBoundary(IntSupplier containerReserve) { this.containerReserve = Objects.requireNonNull(containerReserve); }
    private static IntSupplier constantReserve(int reserve) {
        if (reserve < 0 || reserve > RiskScenarioHeaderBudget.MAX_BYTES) throw new IllegalArgumentException(FAILURE);
        return () -> reserve;
    }
    private int reserve() throws IOException {
        try {
            int reserve = containerReserve.getAsInt();
            if (reserve < 0 || reserve > RiskScenarioHeaderBudget.MAX_BYTES) throw new IllegalArgumentException();
            return reserve;
        } catch (RuntimeException failure) { throw new IOException(FAILURE); }
    }

    @Override protected boolean shouldNotFilter(HttpServletRequest request) throws ServletException {
        if (!matches(request)) return true;
        Object previous = request.getAttribute(ATTRIBUTE);
        if (previous != null) {
            if (previous instanceof Lifetime lifetime) lifetime.invalid = true;
            throw new ServletException(FAILURE);
        }
        return false;
    }
    @Override protected boolean shouldNotFilterAsyncDispatch() { return false; }
    @Override protected boolean shouldNotFilterErrorDispatch() { return false; }

    private static boolean matches(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String context = request.getContextPath();
        if (!uri.startsWith(context)) return false;
        String path = uri.substring(context.length());
        if ("POST".equals(request.getMethod())) return path.equals("/api/v1/entities/risk_scenario/query")
                || path.equals("/api/v1/risk-scenarios/eligible-hazards");
        if (!"GET".equals(request.getMethod()) && !"HEAD".equals(request.getMethod())) return false;
        return path.equals("/api/v1/entities/risk_scenario/schema")
                || path.equals("/api/v1/entities/risk_scenario/definition")
                || path.matches("/api/v1/entities/risk_scenario/[^/]+")
                || path.matches("/api/v1/risk-scenarios/[^/]+/history")
                || path.matches("/api/v1/risk-scenarios/[^/]+/history/[^/]+/hazards/[^/]+");
    }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getDispatcherType() != DispatcherType.REQUEST || request.isAsyncStarted() || current.get() != null || response.isCommitted() || response.getStatus() != 200
                || response.getContentType() != null || !response.getHeaderNames().isEmpty() || response.getTrailerFields() != null
                || SENDFILE_ATTRIBUTES.stream().anyMatch(name -> request.getAttribute(name) != null)) {
            Lifetime active = current.get(); if (active != null) active.invalid = true;
            throw new ServletException(FAILURE);
        }
        Lifetime lifetime = new Lifetime(request, response, reserve());
        request.setAttribute(ATTRIBUTE, lifetime);
        current.set(lifetime);
        try {
            IdentityRequestIds.get(request);
            lifetime.response = new CapturedResponse(lifetime, response);
            lifetime.request = new SynchronousRequest(lifetime, request);
            chain.doFilter(lifetime.request, lifetime.response);
            System.err.println("RISK_HEADER_DIAGNOSTIC captured=" + lifetime.response.getHeaderNames()
                    + "; native=" + response.getHeaderNames()
                    + "; csrf=" + (request.getAttribute("org.springframework.security.web.csrf.CsrfToken") != null)
                    + "; deferredCsrf=" + (request.getAttribute("org.springframework.security.web.csrf.DeferredCsrfToken") != null));
            lifetime.check();
            lifetime.response.release();
            System.err.println("RISK_HEADER_DIAGNOSTIC released=" + response.getHeaderNames());
        } catch (IOException | ServletException failure) {
            lifetime.invalid = true;
            throw failure;
        } catch (RuntimeException failure) {
            lifetime.invalid = true;
            throw new ServletException(FAILURE);
        } finally {
            lifetime.closed = true;
            current.remove();
        }
    }

    @Override public void stage(HttpServletRequest request, HttpServletResponse response, int status,
            Map<String, List<String>> headers, int bodyLength, BodyWriter body) throws IOException {
        Lifetime candidate = null;
        try {
            Object attribute = request == null ? null : request.getAttribute(ATTRIBUTE);
            if (attribute instanceof Lifetime found && found.owner() == this) candidate = found;
            if (candidate == null || current.get() != candidate) throw new IllegalStateException(FAILURE);
            candidate.check();
            if (!containsRequest(request, candidate.request) || !containsResponse(response, candidate.response)
                    || candidate.staged || candidate.response.bodySize != 0 || candidate.response.writer != null
                    || candidate.response.stream != null || bodyLength < 0 || bodyLength > MAX_BODY_BYTES
                    || body == null || status < 200 || status > 599 || status == 204
                    || status == 304 && bodyLength != 0 || headers == null) throw new IllegalStateException(FAILURE);
            Map<String, List<String>> fixed = new LinkedHashMap<>();
            for (var entry : headers.entrySet()) {
                if (entry.getKey() == null) throw new IllegalStateException(FAILURE);
                RiskScenarioHeaderBudget.bytes(status, Collections.singletonMap(entry.getKey(), List.of("")));
                String key = entry.getKey().toLowerCase(Locale.ROOT);
                if (!FIXED_HEADERS.contains(key) || fixed.containsKey(key) || entry.getValue() == null)
                    throw new IllegalStateException(FAILURE);
                if (entry.getValue().isEmpty()) throw new IllegalStateException(FAILURE);
                List<String> selected = new ArrayList<>(); fixed.put(key, selected);
                for (String value : entry.getValue()) { selected.add(value); RiskScenarioHeaderBudget.bytes(status, fixed); }
            }
            RiskScenarioHeaderBudget.bytes(status, fixed);
            candidate.staged = true;
            candidate.status = status;
            candidate.bodyLength = bodyLength;
            candidate.body = body;
            candidate.response.status = status;
            for (var entry : fixed.entrySet()) candidate.response.replace(entry.getKey(), entry.getValue());
        } catch (RuntimeException failure) {
            if (candidate != null) candidate.invalid = true;
            Lifetime local = current.get(); if (local != null) local.invalid = true;
            throw new IOException(FAILURE);
        }
    }

    private static boolean containsRequest(HttpServletRequest actual, HttpServletRequest expected) {
        ServletRequest cursor = actual;
        for (int depth = 0; depth < 64 && cursor != null; depth++) {
            if (cursor == expected) return true;
            cursor = cursor instanceof ServletRequestWrapper wrapper ? wrapper.getRequest() : null;
        }
        return false;
    }
    private static boolean containsResponse(HttpServletResponse actual, HttpServletResponse expected) {
        ServletResponse cursor = actual;
        for (int depth = 0; depth < 64 && cursor != null; depth++) {
            if (cursor == expected) return true;
            cursor = cursor instanceof ServletResponseWrapper wrapper ? wrapper.getResponse() : null;
        }
        return false;
    }

    private final class Lifetime {
        final Thread thread = Thread.currentThread();
        final HttpServletRequest original;
        final HttpServletResponse target;
        final int admittedReserve;
        volatile boolean invalid;
        volatile boolean closed;
        boolean staged;
        boolean releasing;
        int status;
        int bodyLength;
        BodyWriter body;
        SynchronousRequest request;
        CapturedResponse response;
        Lifetime(HttpServletRequest original, HttpServletResponse target, int reserve) { this.original = original; this.target = target; this.admittedReserve = reserve; }
        RiskScenarioHttpBoundary owner() { return RiskScenarioHttpBoundary.this; }
        void check() {
            if (invalid || closed || thread != Thread.currentThread() || original.getAttribute(ATTRIBUTE) != this
                    || original.isAsyncStarted() || original.getDispatcherType() != DispatcherType.REQUEST
                    || SENDFILE_ATTRIBUTES.stream().anyMatch(name -> original.getAttribute(name) != null)) fail();
        }
        void fail() { invalid = true; throw new IllegalStateException(FAILURE); }
    }

    private final class SynchronousRequest extends HttpServletRequestWrapper {
        private final Lifetime lifetime;
        SynchronousRequest(Lifetime lifetime, HttpServletRequest request) { super(request); this.lifetime = lifetime; }
        @Override public boolean isAsyncSupported() { lifetime.check(); return false; }
        @Override public Object getAttribute(String name) { lifetime.check(); return super.getAttribute(name); }
        @Override public void setAttribute(String name, Object value) { lifetime.check(); if (name == null || ATTRIBUTE.equals(name) || SENDFILE_ATTRIBUTES.contains(name)) lifetime.fail(); super.setAttribute(name, value); }
        @Override public void removeAttribute(String name) { lifetime.check(); if (ATTRIBUTE.equals(name)) lifetime.fail(); super.removeAttribute(name); }
        @Override public HttpSession getSession(boolean create) { lifetime.check(); return super.getSession(create); }
        @Override public HttpSession getSession() { lifetime.check(); return super.getSession(); }
        @Override public ServletInputStream getInputStream() throws IOException { lifetime.check(); return super.getInputStream(); }
        @Override public BufferedReader getReader() throws IOException { lifetime.check(); return super.getReader(); }
        @Override public <T extends HttpUpgradeHandler> T upgrade(Class<T> handlerClass) throws IOException, ServletException { lifetime.fail(); return null; }
        @Override public AsyncContext startAsync() { lifetime.fail(); return null; }
        @Override public AsyncContext startAsync(ServletRequest request, ServletResponse response) { lifetime.fail(); return null; }
        @Override public void setRequest(ServletRequest request) { if (lifetime != null) lifetime.fail(); super.setRequest(request); }
    }

    private final class CapturedResponse extends HttpServletResponseWrapper {
        final Lifetime lifetime;
        final Map<String, List<String>> headers = new LinkedHashMap<>();
        final byte[] errors = new byte[ERROR_BYTES];
        int bodySize;
        int status;
        Charset charset;
        Locale locale;
        ServletOutputStream stream;
        PrintWriter writer;
        boolean streamSelected;
        boolean writerSelected;
        CapturedResponse(Lifetime lifetime, HttpServletResponse target) {
            super(target); this.lifetime = lifetime; status = target.getStatus();
            charset = Charset.forName(target.getCharacterEncoding()); locale = target.getLocale();
        }
        void open() { lifetime.check(); if (lifetime.releasing) lifetime.fail(); }
        String key(String name) {
            try { RiskScenarioHeaderBudget.bytes(200, Collections.singletonMap(name, List.of(""))); }
            catch (RuntimeException failure) { lifetime.fail(); }
            return name.toLowerCase(Locale.ROOT);
        }
        void replace(String name, List<String> values) {
            open(); String key = key(name); prohibited(key);
            if (values == null) headers.remove(key); else headers.put(key, new ArrayList<>(values));
            validate();
        }
        void prohibited(String key) { if (key.equals("trailer") || key.equals("transfer-encoding")) lifetime.fail(); }
        void validate() {
            try { RiskScenarioHeaderBudget.bytes(status, headers); }
            catch (RuntimeException failure) { lifetime.fail(); }
        }
        @Override public int getStatus() { return status; }
        @Override public void setStatus(int value) { open(); if (lifetime.staged) lifetime.fail(); status = value; validate(); }
        @Override public void setHeader(String name, String value) {
            if (name != null && name.equalsIgnoreCase("Content-Type")) setContentType(value);
            else replace(name, value == null ? null : List.of(value));
        }
        @Override public void addHeader(String name, String value) {
            open(); String key = key(name); prohibited(key); if (value == null) { replace(name, null); return; }
            headers.computeIfAbsent(key, ignored -> new ArrayList<>()).add(value); validate();
        }
        @Override public String getHeader(String name) { List<String> values = headers.get(key(name)); return values == null ? null : values.getFirst(); }
        @Override public Collection<String> getHeaders(String name) { return List.copyOf(headers.getOrDefault(key(name), List.of())); }
        @Override public Collection<String> getHeaderNames() { return List.copyOf(headers.keySet()); }
        @Override public boolean containsHeader(String name) { return headers.containsKey(key(name)); }
        @Override public void setIntHeader(String name, int value) { setHeader(name, Integer.toString(value)); }
        @Override public void addIntHeader(String name, int value) { addHeader(name, Integer.toString(value)); }
        @Override public void setDateHeader(String name, long value) { setHeader(name, HTTP_DATE.format(Instant.ofEpochMilli(value))); }
        @Override public void addDateHeader(String name, long value) { addHeader(name, HTTP_DATE.format(Instant.ofEpochMilli(value))); }
        @Override public void setContentLength(int value) { setContentLengthLong(value); }
        @Override public void setContentLengthLong(long value) { setHeader("Content-Length", Long.toString(value)); }
        @Override public String getContentType() { return getHeader("Content-Type"); }
        @Override public void setContentType(String value) {
            replace("Content-Type", value == null ? null : List.of(value));
            if (value != null && !writerSelected) {
                try { Charset selected = org.springframework.http.MediaType.parseMediaType(value).getCharset(); if (selected != null) charset = selected; }
                catch (RuntimeException failure) { lifetime.fail(); }
            }
        }
        @Override public String getCharacterEncoding() { return charset.name(); }
        @Override public void setCharacterEncoding(String value) {
            open(); if (writerSelected || value == null) return;
            if (value.length() > 128) lifetime.fail();
            try { charset = Charset.forName(value); } catch (RuntimeException failure) { lifetime.fail(); }
            String type = getContentType();
            if (type != null) {
                var media = org.springframework.http.MediaType.parseMediaType(type);
                var parameters = new LinkedHashMap<>(media.getParameters());
                parameters.put("charset", charset.name());
                setHeader("Content-Type", new org.springframework.http.MediaType(media.getType(), media.getSubtype(), parameters).toString());
            }
        }
        @Override public void setCharacterEncoding(Charset value) { setCharacterEncoding(value == null ? null : value.name()); }
        @Override public Locale getLocale() { return locale; }
        @Override public void setLocale(Locale value) { open(); language(value); locale = value; }
        void language(Locale value) {
            if (value == null) lifetime.fail();
            String tag = value.toLanguageTag();
            if (tag.length() > 128 || !tag.matches("[A-Za-z]{2,8}(?:-[A-Za-z0-9]{1,8})*")) lifetime.fail();
            setHeader("Content-Language", tag);
        }
        @Override public boolean isCommitted() { return lifetime.releasing; }
        // Session's reviewed resolver writes exact Set-Cookie fields through addHeader. Native CookieProcessor
        // serialization has no reviewed byte-equivalent seam here, so this separate path stays closed.
        @Override public void addCookie(Cookie cookie) { lifetime.fail(); }
        @Override public ServletOutputStream getOutputStream() {
            open(); if (lifetime.staged || writerSelected) lifetime.fail(); streamSelected = true;
            if (stream == null) stream = errorStream(); return stream;
        }
        ServletOutputStream errorStream() {
            return new ServletOutputStream() {
                @Override public void write(int value) { open(); if (lifetime.staged || status < 400 || bodySize == ERROR_BYTES) lifetime.fail(); errors[bodySize++] = (byte) value; }
                @Override public void write(byte[] values, int offset, int length) {
                    open();
                    try { Objects.checkFromIndexSize(offset, length, values.length); } catch (RuntimeException invalid) { lifetime.fail(); }
                    if (lifetime.staged || status < 400 || length > ERROR_BYTES - bodySize) lifetime.fail();
                    System.arraycopy(values, offset, errors, bodySize, length); bodySize += length;
                }
                @Override public boolean isReady() { return !lifetime.closed && !lifetime.invalid; }
                @Override public void setWriteListener(WriteListener listener) { lifetime.fail(); }
                @Override public void flush() { open(); }
                @Override public void close() { open(); }
            };
        }
        @Override public PrintWriter getWriter() {
            open(); if (lifetime.staged || streamSelected) lifetime.fail(); writerSelected = true;
            if (writer == null) writer = new PrintWriter(new OutputStreamWriter(errorStream(), charset)); return writer;
        }
        @Override public void flushBuffer() { open(); if (writer != null) writer.flush(); }
        @Override public void resetBuffer() { open(); if (lifetime.staged) lifetime.fail(); if (writer != null) writer.flush(); bodySize = 0; headers.remove("content-length"); }
        @Override public void reset() { resetBuffer(); headers.clear(); status = 200; }
        @Override public void sendError(int value) { sendError(value, null); }
        @Override public void sendError(int value, String message) { resetBuffer(); setStatus(value); }
        @Override public void sendRedirect(String location) { lifetime.fail(); }
        @Override public void sendRedirect(String location, boolean clear) { lifetime.fail(); }
        @Override public void sendRedirect(String location, int status, boolean clear) { lifetime.fail(); }
        @Override public void sendRedirect(String location, int status) { lifetime.fail(); }
        @Override public void setTrailerFields(Supplier<Map<String, String>> supplier) { lifetime.fail(); }
        @Override public Supplier<Map<String, String>> getTrailerFields() { lifetime.fail(); return null; }
        @Override public void setResponse(ServletResponse response) { if (lifetime != null) lifetime.fail(); super.setResponse(response); }

        void release() throws IOException {
            open(); if (writer != null) writer.flush();
            if (!lifetime.staged && status < 400) lifetime.fail();
            int length = lifetime.staged ? lifetime.bodyLength : bodySize;
            if (status == 304) {
                if (length != 0 || headers.containsKey("content-length")) lifetime.fail();
            } else {
                List<String> existing = headers.get("content-length");
                if (existing != null && !existing.equals(List.of(Integer.toString(length)))) lifetime.fail();
                headers.put("content-length", List.of(Integer.toString(length)));
            }
            if (!headers.containsKey("date")) setDateHeader("Date", System.currentTimeMillis());
            int actualReserve = reserve();
            if (actualReserve != lifetime.admittedReserve) { lifetime.invalid = true; throw new IOException(FAILURE); }
            try { RiskScenarioHeaderBudget.requireFits(status, headers, actualReserve); }
            catch (RuntimeException rejected) { lifetime.invalid = true; throw new IOException(FAILURE); }
            lifetime.check(); if (lifetime.target.isCommitted()) lifetime.fail();
            lifetime.releasing = true; // Never retry after any target write, including IOException.
            HttpServletResponse target = lifetime.target;
            target.reset(); target.setStatus(status);
            headers.forEach((name, values) -> { target.setHeader(name, values.getFirst()); for (int i = 1; i < values.size(); i++) target.addHeader(name, values.get(i)); });
            if (status != 304 && !"HEAD".equals(lifetime.original.getMethod())) {
                if (lifetime.staged) {
                    ExactOutput output = new ExactOutput(lifetime, target.getOutputStream(), length);
                    lifetime.body.writeTo(output); output.finish();
                } else target.getOutputStream().write(errors, 0, bodySize);
            }
        }
    }

    private static final class ExactOutput extends OutputStream {
        final Lifetime lifetime;
        final OutputStream target;
        final int expected;
        int written;
        boolean finished;
        ExactOutput(Lifetime lifetime, OutputStream target, int expected) { this.lifetime = lifetime; this.target = target; this.expected = expected; }
        void ensure(int amount) throws IOException {
            try { lifetime.check(); } catch (RuntimeException failure) { throw new IOException(FAILURE); }
            if (finished || lifetime.invalid || lifetime.closed || lifetime.thread != Thread.currentThread() || amount > expected - written) {
                lifetime.invalid = true; throw new IOException(FAILURE);
            }
        }
        @Override public void write(int value) throws IOException {
            ensure(1); try { target.write(value); written++; } catch (IOException | RuntimeException | Error failure) { lifetime.invalid = true; throw failure; }
        }
        @Override public void write(byte[] values, int offset, int length) throws IOException {
            try { Objects.checkFromIndexSize(offset, length, values.length); } catch (RuntimeException invalid) { lifetime.invalid = true; throw new IOException(FAILURE); }
            ensure(length);
            try { target.write(values, offset, length); written += length; } catch (IOException | RuntimeException | Error failure) { lifetime.invalid = true; throw failure; }
        }
        @Override public void flush() throws IOException { ensure(0); }
        @Override public void close() throws IOException { ensure(0); }
        void finish() throws IOException { ensure(0); if (written != expected) { lifetime.invalid = true; throw new IOException(FAILURE); } finished = true; }
    }
}
