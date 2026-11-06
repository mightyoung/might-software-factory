package com.mightyoung.softwarefactory;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.catalina.Context;
import org.apache.catalina.startup.Tomcat;
import org.apache.catalina.util.ServerInfo;
import org.apache.coyote.http11.AbstractHttp11Protocol;
import org.apache.tomcat.util.descriptor.web.FilterDef;
import org.apache.tomcat.util.descriptor.web.FilterMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/** Real Tomcat/socket component proof. No authentication, session persistence, MVC or database acceptance. */
class RiskScenarioHttpBoundaryContainerTest {
    private static final String DETAIL = "/api/v1/entities/risk_scenario/fixture";
    private static final String DEFINITION = "/api/v1/entities/risk_scenario/definition";
    private static final String DATE = "Mon, 02 Nov 2026 00:00:00 GMT";
    private static final String COOKIE = "fixture=value; Path=/; Secure; SameSite=Lax";
    private static final byte[] BODY = {0, 1, 2, 13, 10, (byte) 255};
    private static final Map<String, List<String>> JSON = Map.of("Content-Type", List.of("application/json"), "Cache-Control", List.of("no-store"));
    @TempDir Path directory;

    @Test void defaultConnectorCapIncludesActualWireHeadersAndPreservesRepeatedCookiesAndBinaryBody() throws Exception {
        try (var fixture = new Fixture(directory)) {
            assertThat(ServerInfo.getServerInfo()).contains("Tomcat/11.0.27");
            assertThat(fixture.cap).isEqualTo(8192);
            Wire response = fixture.exchange("GET", DETAIL);
            assertThat(response.status()).isEqualTo(200);
            assertThat(response.headerBytes()).isLessThanOrEqualTo(fixture.cap);
            assertThat(response.values("Set-Cookie")).containsExactly(COOKIE, COOKIE);
            assertThat(response.values("X-Frame-Options")).containsExactly("DENY");
            assertThat(response.values("Date")).containsExactly(DATE);
            assertThat(response.values("Content-Length")).containsExactly("6");
            assertThat(response.values("Content-Language")).isEmpty();
            assertThat(response.body()).containsExactly(BODY);
            assertThat(fixture.bodyWrites.get()).isEqualTo(1);
            assertThat(fixture.pristineEntries.get()).isEqualTo(1);
            assertThat(response.serverClosed()).isTrue();
        }
    }

    @Test void headAndNotModifiedReachTheServletButNeverInvokeTheOwnedBodyWriter() throws Exception {
        try (var fixture = new Fixture(directory)) {
            Wire head = fixture.exchange("HEAD", DETAIL);
            assertThat(head.status()).isEqualTo(200);
            assertThat(head.values("Content-Length")).containsExactly("6");
            assertThat(head.body()).isEmpty();
            assertThat(head.headerBytes()).isLessThanOrEqualTo(fixture.cap);
            assertThat(head.serverClosed()).isTrue();
            Wire notModified = fixture.exchange("GET", DEFINITION);
            assertThat(notModified.status()).isEqualTo(304);
            assertThat(notModified.values("Content-Length")).isEmpty();
            assertThat(notModified.values("Transfer-Encoding")).isEmpty();
            assertThat(notModified.body()).isEmpty();
            assertThat(notModified.headerBytes()).isLessThanOrEqualTo(fixture.cap);
            assertThat(notModified.serverClosed()).isTrue();
            assertThat(fixture.handlerCalls.get()).isEqualTo(2);
            assertThat(fixture.pristineEntries.get()).isEqualTo(2);
            assertThat(fixture.bodyWrites.get()).isZero();
        }
    }

    @Test void aNearCapResponseFitsTheRealDefaultConnectorWithAnExplicitFixtureReserve() throws Exception {
        try (var fixture = new Fixture(directory)) {
            Wire response = fixture.exchange("GET", "/api/v1/entities/risk_scenario/budget");
            assertThat(response.status()).isEqualTo(200);
            assertThat(response.headerBytes()).isGreaterThan(fixture.cap - 512).isLessThanOrEqualTo(fixture.cap);
            assertThat(response.values("X-Pad")).singleElement().satisfies(value -> assertThat(value).hasSize(fixture.paddingLength));
            assertThat(response.values("Set-Cookie")).containsExactly(COOKIE, COOKIE);
            assertThat(response.body()).containsExactly(BODY);
            assertThat(response.serverClosed()).isTrue();
        }
    }

    private static final class Fixture implements AutoCloseable {
        final Tomcat tomcat = new Tomcat();
        final int cap;
        final int reserve;
        final int paddingLength;
        final AtomicInteger bodyWrites = new AtomicInteger();
        final AtomicInteger handlerCalls = new AtomicInteger();
        final AtomicInteger pristineEntries = new AtomicInteger();

        Fixture(Path directory) throws Exception {
            Path documentRoot = Files.createDirectories(directory.resolve("document-root"));
            tomcat.setBaseDir(directory.resolve("tomcat").toString());
            tomcat.setHostname("127.0.0.1");
            var connector = tomcat.getConnector();
            connector.setPort(0);
            var protocol = (AbstractHttp11Protocol<?>) connector.getProtocolHandler();
            protocol.setAddress(InetAddress.getByName("127.0.0.1"));
            cap = protocol.getMaxHttpResponseHeaderSize();
            assertThat(protocol.getMaxHttpHeaderSize()).isEqualTo(8192);
            // Component fixture only: leave 256 bytes for container-generated fields such as Connection.
            // This derives from the actual connector cap; it is not the production configuration profile.
            reserve = RiskScenarioHeaderBudget.MAX_BYTES - cap + 256;
            var planned = new LinkedHashMap<String, List<String>>(JSON);
            planned.put("Date", List.of(DATE));
            planned.put("Content-Length", List.of(Integer.toString(BODY.length)));
            planned.put("Set-Cookie", List.of(COOKIE, COOKIE));
            planned.put("X-Frame-Options", List.of("DENY"));
            paddingLength = (int) (RiskScenarioHeaderBudget.MAX_BYTES - reserve - RiskScenarioHeaderBudget.bytes(200, planned) - 9);
            assertThat(paddingLength).isPositive(); // X-Pad plus colon/SP/CRLF consumes 9 bytes.
            Context context = tomcat.addContext("", documentRoot.toString());
            context.setParentClassLoader(RiskScenarioHttpBoundaryContainerTest.class.getClassLoader());
            var boundary = new RiskScenarioHttpBoundary(() -> reserve);
            // This component installs the boundary before any fixture writer. Servlet getters do not
            // establish that arbitrary prior native code left no hidden body or explicit default locale.
            filter(context, "pristine-entry-fixture", (request, response, chain) -> {
                var http = (HttpServletResponse) response;
                assertThat(http.getHeaderNames()).isEmpty(); assertThat(http.getStatus()).isEqualTo(200);
                assertThat(http.getContentType()).isNull(); assertThat(http.getTrailerFields()).isNull();
                pristineEntries.incrementAndGet(); chain.doFilter(request, response);
            });
            filter(context, "risk-boundary", boundary);
            filter(context, "late-fixture-fields", (request, response, chain) -> {
                try { chain.doFilter(request, response); }
                finally {
                    var http = (HttpServletResponse) response;
                    http.addHeader("Set-Cookie", COOKIE);
                    http.addHeader("Set-Cookie", COOKIE);
                    http.setHeader("X-Frame-Options", "DENY");
                    if (((HttpServletRequest) request).getRequestURI().endsWith("/budget")) http.setHeader("X-Pad", "a".repeat(paddingLength));
                }
            });
            Tomcat.addServlet(context, "risk-read-fixture", new HttpServlet() {
                // Explicitly dispatch HEAD to the same stage call. This isolates transport mechanics;
                // it does not stand in for Spring MVC's implicit HEAD mapping or its authorization.
                @Override protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException {
                    handlerCalls.incrementAndGet();
                    response.setDateHeader("Date", Instant.parse("2026-11-02T00:00:00Z").toEpochMilli());
                    if (request.getRequestURI().equals(DEFINITION)) {
                        boundary.stage(request, response, 304, Map.of("ETag", List.of("\"fixture\"")), 0, output -> bodyWrites.incrementAndGet());
                    } else {
                        boundary.stage(request, response, 200, JSON, BODY.length, output -> { bodyWrites.incrementAndGet(); output.write(BODY); });
                    }
                }
            });
            context.addServletMappingDecoded("/", "risk-read-fixture");
            try { tomcat.start(); }
            catch (Exception failure) { try { tomcat.destroy(); } catch (Exception cleanup) { failure.addSuppressed(cleanup); } throw failure; }
        }

        Wire exchange(String method, String path) throws IOException {
            try (var socket = new Socket("127.0.0.1", tomcat.getConnector().getLocalPort())) {
                socket.setSoTimeout(5000);
                socket.getOutputStream().write((method + " " + path + " HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                socket.getOutputStream().flush();
                var bytes = new ByteArrayOutputStream(); var chunk = new byte[4096]; boolean serverClosed = false;
                while (true) {
                    int count = socket.getInputStream().read(chunk);
                    if (count == -1) { serverClosed = true; break; }
                    if (bytes.size() + count > 2 * 1024 * 1024) throw new IOException("Fixture response exceeded its read bound");
                    bytes.write(chunk, 0, count);
                }
                byte[] raw = bytes.toByteArray(); int delimiter = -1;
                for (int i = 0; i + 3 < raw.length; i++) if (raw[i] == 13 && raw[i + 1] == 10 && raw[i + 2] == 13 && raw[i + 3] == 10) { delimiter = i; break; }
                if (delimiter < 0) throw new IOException("Fixture response did not contain complete HTTP headers");
                String headers = new String(raw, 0, delimiter, StandardCharsets.ISO_8859_1);
                String[] lines = headers.split("\r\n");
                int status = Integer.parseInt(lines[0].split(" ")[1]);
                return new Wire(status, delimiter + 4, List.of(lines), java.util.Arrays.copyOfRange(raw, delimiter + 4, raw.length), serverClosed);
            }
        }

        @Override public void close() throws Exception { try { tomcat.stop(); } finally { tomcat.destroy(); } }
    }

    private static void filter(Context context, String name, Filter filter) {
        var definition = new FilterDef(); definition.setFilterName(name); definition.setFilterClass(filter.getClass().getName()); definition.setFilter(filter); definition.setAsyncSupported("false");
        context.addFilterDef(definition);
        var mapping = new FilterMap(); mapping.setFilterName(name); mapping.addURLPattern("/*"); mapping.setDispatcher("REQUEST");
        context.addFilterMap(mapping);
    }

    private record Wire(int status, int headerBytes, List<String> lines, byte[] body, boolean serverClosed) {
        List<String> values(String name) {
            var values = new ArrayList<String>();
            for (int i = 1; i < lines.size(); i++) {
                String line = lines.get(i); int colon = line.indexOf(':');
                if (colon > 0 && line.substring(0, colon).equalsIgnoreCase(name)) values.add(line.substring(colon + 1).strip());
            }
            return values;
        }
    }
}
