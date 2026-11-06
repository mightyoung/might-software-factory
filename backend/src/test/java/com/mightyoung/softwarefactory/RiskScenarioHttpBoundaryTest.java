package com.mightyoung.softwarefactory;

import com.mightyoung.softwarefactory.identity.IdentityRequestIds;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import static org.assertj.core.api.Assertions.*;

/** Mechanical mock-servlet proof only; not an assembled security/session filter or container test. */
class RiskScenarioHttpBoundaryTest {
    private static final String DETAIL = "/api/v1/entities/risk_scenario/id";
    private static final Map<String, List<String>> JSON = Map.of("Content-Type", List.of("application/json"), "Cache-Control", List.of("no-store"));

    @Test void waitsForLateHeadersAndKeepsTheOwnedBodyWriterWithoutAnEarlyCopy() throws Exception {
        var boundary = new RiskScenarioHttpBoundary(0); var request = request("GET", DETAIL); var target = new MockHttpServletResponse();
        var calls = new AtomicInteger();
        boundary.doFilter(request, target, (req, res) -> {
            var response = (HttpServletResponse) res;
            boundary.stage(new HttpServletRequestWrapper((HttpServletRequest) req), new HttpServletResponseWrapper(response), 200, JSON, 3,
                    output -> { calls.incrementAndGet(); output.write(new byte[]{1, 2, 3}); });
            assertThat(calls.get()).isZero(); assertThat(target.getContentAsByteArray()).isEmpty();
            response.addHeader("Set-Cookie", "SESSION=one; Secure");
            response.addHeader("Set-Cookie", "SESSION=one; Secure");
            response.addHeader("Set-Cookie", "XSRF-TOKEN=two; Secure");
            response.setHeader("X-Frame-Options", "DENY");
        });
        assertThat(calls.get()).isEqualTo(1); assertThat(target.getContentAsByteArray()).containsExactly((byte) 1, (byte) 2, (byte) 3);
        assertThat(target.getHeaders("Set-Cookie")).containsExactly("SESSION=one; Secure", "SESSION=one; Secure", "XSRF-TOKEN=two; Secure");
        assertThat(target.getHeader("X-Frame-Options")).isEqualTo("DENY");
        assertThat(target.getHeader("Content-Length")).isEqualTo("3");
        assertThat(target.getHeader("Content-Language")).isNull();
    }

    @ParameterizedTest @ValueSource(strings = {"/api/v1/entities/risk_scenario/id", "/api/v1/entities/risk_scenario/schema", "/api/v1/entities/risk_scenario/definition",
            "/api/v1/risk-scenarios/id/history", "/api/v1/risk-scenarios/id/history/1/hazards/hazard"})
    void implicitHeadRunsTheHandlerButDoesNotInvokeItsBodyWriter(String path) throws Exception {
        var boundary = new RiskScenarioHttpBoundary(0); var target = new MockHttpServletResponse(); var calls = new AtomicInteger();
        boundary.doFilter(request("HEAD", path), target, (req, res) -> boundary.stage((HttpServletRequest) req, (HttpServletResponse) res,
                200, JSON, 3, output -> calls.incrementAndGet()));
        assertThat(calls.get()).isZero(); assertThat(target.getContentAsByteArray()).isEmpty();
        assertThat(target.getHeader("Content-Length")).isEqualTo("3");
    }

    @ParameterizedTest @ValueSource(strings = {"/api/v1/entities/risk_scenario/query", "/api/v1/risk-scenarios/eligible-hazards"})
    void bothPostReadsAreCaptured(String path) throws Exception {
        var boundary = new RiskScenarioHttpBoundary(0); var target = new MockHttpServletResponse();
        boundary.doFilter(request("POST", path), target, (req, res) -> boundary.stage((HttpServletRequest) req, (HttpServletResponse) res,
                200, JSON, 1, output -> output.write(7)));
        assertThat(target.getContentAsByteArray()).containsExactly((byte) 7);
    }

    @Test void doesNotEnableWritesOrOtherMethodsAndPaths() throws Exception {
        var boundary = new RiskScenarioHttpBoundary(0);
        for (var pair : List.of(new String[]{"POST", "/api/v1/risk-scenarios"}, new String[]{"POST", "/api/v1/risk-scenarios/id/update"},
                new String[]{"HEAD", "/api/v1/risk-scenarios/eligible-hazards"}, new String[]{"GET", DETAIL + "/extra"})) {
            var request = request(pair[0], pair[1]); var response = new MockHttpServletResponse();
            boundary.doFilter(request, response, (req, res) -> { assertThat(req).isSameAs(request); assertThat(res).isSameAs(response); });
            assertThat(IdentityRequestIds.existing(request)).isEmpty();
        }
    }

    @Test void notModifiedHasNoBodyOrContentLength() throws Exception {
        var boundary = new RiskScenarioHttpBoundary(0); var response = new MockHttpServletResponse();
        boundary.doFilter(request("GET", DETAIL), response, (req, res) -> boundary.stage((HttpServletRequest) req, (HttpServletResponse) res,
                304, Map.of("ETag", List.of("\"fixed\""), "Vary", List.of("Cookie")), 0, output -> { throw new AssertionError("304 writer invoked"); }));
        assertThat(response.getStatus()).isEqualTo(304); assertThat(response.getHeader("Content-Length")).isNull();
        assertThat(response.getContentAsByteArray()).isEmpty();
    }

    @Test void assignsIdsBeforeAnEarlyIdentityErrorAndBuffersItUntilLateHeadersFinish() throws Exception {
        String deletionCookie = "SESSION=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT";
        var boundary = new RiskScenarioHttpBoundary(0); var request = request("GET", DETAIL); var target = new MockHttpServletResponse();
        var ids = new AtomicReference<IdentityRequestIds.Ids>();
        boundary.doFilter(request, target, (req, res) -> {
            ids.set(IdentityRequestIds.existing((HttpServletRequest) req).orElseThrow());
            var response = (HttpServletResponse) res; response.setStatus(401); response.setContentType("application/json"); response.setCharacterEncoding("UTF-8");
            response.getWriter().write("{\"traceId\":\"" + ids.get().traceId() + "\"}");
            assertThat(target.getContentAsByteArray()).isEmpty(); response.addHeader("Set-Cookie", deletionCookie);
        });
        assertThat(IdentityRequestIds.get(request)).isSameAs(ids.get()); assertThat(target.getStatus()).isEqualTo(401);
        assertThat(target.getContentAsString()).contains(ids.get().traceId().toString());
        assertThat(target.getHeaders("Set-Cookie")).containsExactly(deletionCookie);
    }

    @Test void repeatedStageCannotBeRecoveredEvenWhenItsExceptionIsSwallowed() {
        var boundary = new RiskScenarioHttpBoundary(0); var target = new MockHttpServletResponse(); var calls = new AtomicInteger();
        assertThatThrownBy(() -> boundary.doFilter(request("GET", DETAIL), target, (req, res) -> {
            boundary.stage((HttpServletRequest) req, (HttpServletResponse) res, 200, JSON, 1, out -> { calls.incrementAndGet(); out.write(1); });
            assertThatThrownBy(() -> boundary.stage((HttpServletRequest) req, (HttpServletResponse) res, 200, JSON, 1, out -> out.write(1))).isInstanceOf(IOException.class);
        })).isInstanceOf(ServletException.class);
        assertThat(calls.get()).isZero(); assertThat(target.getContentAsByteArray()).isEmpty();
    }

    @Test void crossThreadStagePermanentlyPoisonsTheOriginalInvocation() {
        var boundary = new RiskScenarioHttpBoundary(0); var target = new MockHttpServletResponse(); var failure = new AtomicReference<Throwable>();
        assertThatThrownBy(() -> boundary.doFilter(request("GET", DETAIL), target, (req, res) -> {
            var thread = new Thread(() -> { try { boundary.stage((HttpServletRequest) req, (HttpServletResponse) res, 200, JSON, 0, out -> {}); }
                catch (Throwable rejected) { failure.set(rejected); } });
            thread.start(); try { thread.join(); } catch (InterruptedException interrupted) { throw new ServletException(interrupted); }
            assertThat(failure.get()).isInstanceOf(IOException.class);
            assertThatThrownBy(() -> boundary.stage((HttpServletRequest) req, (HttpServletResponse) res, 200, JSON, 0, out -> {})).isInstanceOf(IOException.class);
        })).isInstanceOf(ServletException.class);
        assertThat(target.getContentAsByteArray()).isEmpty();
    }

    @Test void wrongResponseOwnershipAndReentryBothFailClosed() {
        var boundary = new RiskScenarioHttpBoundary(0); var target = new MockHttpServletResponse();
        assertThatThrownBy(() -> boundary.doFilter(request("GET", DETAIL), target, (req, res) -> {
            assertThatThrownBy(() -> boundary.stage((HttpServletRequest) req, target, 200, JSON, 0, out -> {})).isInstanceOf(IOException.class);
        })).isInstanceOf(ServletException.class);
        var second = new RiskScenarioHttpBoundary(0);
        assertThatThrownBy(() -> second.doFilter(request("GET", DETAIL), new MockHttpServletResponse(), (req, res) -> {
            assertThatThrownBy(() -> second.doFilter(req, res, (r, s) -> {})).isInstanceOf(ServletException.class);
        })).isInstanceOf(ServletException.class);
    }

    @Test void asyncStartAndNonRequestDispatchAreRejected() {
        var boundary = new RiskScenarioHttpBoundary(0); var target = new MockHttpServletResponse();
        assertThatThrownBy(() -> boundary.doFilter(request("GET", DETAIL), target, (req, res) -> {
            assertThat(req.isAsyncSupported()).isFalse(); assertThatThrownBy(req::startAsync).isInstanceOf(IllegalStateException.class);
        })).isInstanceOf(ServletException.class);
        var async = request("GET", DETAIL); async.setDispatcherType(DispatcherType.ASYNC);
        assertThatThrownBy(() -> boundary.doFilter(async, new MockHttpServletResponse(), (req, res) -> fail("async chain invoked"))).isInstanceOf(ServletException.class);
    }

    @Test void lateFailureCannotReplaceAnAlreadyStagedSensitiveRead() {
        var boundary = new RiskScenarioHttpBoundary(0); var target = new MockHttpServletResponse(); var calls = new AtomicInteger();
        assertThatThrownBy(() -> boundary.doFilter(request("GET", DETAIL), target, (req, res) -> {
            boundary.stage((HttpServletRequest) req, (HttpServletResponse) res, 200, JSON, 1, out -> { calls.incrementAndGet(); out.write(1); });
            assertThatThrownBy(() -> ((HttpServletResponse) res).setStatus(503)).isInstanceOf(IllegalStateException.class);
        })).isInstanceOf(ServletException.class);
        assertThat(calls.get()).isZero(); assertThat(target.getContentAsByteArray()).isEmpty();
    }

    @Test void finalReserveFailureNeverInvokesTheBodyOrDropsHeadersToFit() throws Exception {
        var boundary = new RiskScenarioHttpBoundary(1); var target = new MockHttpServletResponse(); var calls = new AtomicInteger();
        assertThatThrownBy(() -> boundary.doFilter(request("GET", DETAIL), target, (req, res) -> {
            ((HttpServletResponse) res).setLocale(Locale.US);
            boundary.stage((HttpServletRequest) req, (HttpServletResponse) res, 304, Map.of(), 0, out -> calls.incrementAndGet());
            ((HttpServletResponse) res).setHeader("X", "a".repeat(16251));
        })).isInstanceOf(IOException.class);
        assertThat(calls.get()).isZero(); assertThat(target.getContentAsByteArray()).isEmpty();
        var exact = new RiskScenarioHttpBoundary(0); var admitted = new MockHttpServletResponse();
        exact.doFilter(request("GET", DETAIL), admitted, (req, res) -> {
            ((HttpServletResponse) res).setLocale(Locale.US);
            exact.stage((HttpServletRequest) req, (HttpServletResponse) res, 304, Map.of(), 0, out -> {});
            ((HttpServletResponse) res).setHeader("X", "a".repeat(16251));
        });
        assertThat(admitted.getStatus()).isEqualTo(304); assertThat(admitted.getHeader("X")).hasSize(16251);
    }

    @ParameterizedTest @ValueSource(strings = {"line\r\nbreak", "é", "\u0000"})
    void maliciousLateHeadersPoisonTheResponse(String value) {
        var boundary = new RiskScenarioHttpBoundary(0); var target = new MockHttpServletResponse();
        assertThatThrownBy(() -> boundary.doFilter(request("GET", DETAIL), target, (req, res) -> {
            boundary.stage((HttpServletRequest) req, (HttpServletResponse) res, 200, JSON, 0, out -> {});
            assertThatThrownBy(() -> ((HttpServletResponse) res).addHeader("Set-Cookie", value)).isInstanceOf(IllegalStateException.class);
        })).isInstanceOf(ServletException.class);
        assertThat(target.getContentAsByteArray()).isEmpty();
    }

    @Test void rejectsDirectSuccessAndOversizedErrors() {
        for (int status : List.of(200, 401)) {
            var boundary = new RiskScenarioHttpBoundary(0); var target = new MockHttpServletResponse();
            assertThatThrownBy(() -> boundary.doFilter(request("GET", DETAIL), target, (req, res) -> {
                var response = (HttpServletResponse) res; response.setStatus(status);
                response.getOutputStream().write(new byte[status == 200 ? 1 : 8193]);
            })).isInstanceOf(ServletException.class);
            assertThat(target.getContentAsByteArray()).isEmpty();
        }
    }

    @ParameterizedTest @ValueSource(ints = {0, 2})
    void stagedWriterMustWriteItsExactLength(int actual) {
        var boundary = new RiskScenarioHttpBoundary(0); var target = new MockHttpServletResponse();
        assertThatThrownBy(() -> boundary.doFilter(request("GET", DETAIL), target, (req, res) ->
                boundary.stage((HttpServletRequest) req, (HttpServletResponse) res, 200, JSON, 1, out -> out.write(new byte[actual]))))
                .isInstanceOf(IOException.class);
        assertThat(target.getStatus()).isEqualTo(200);
    }

    @Test void writerIOExceptionIsPropagatedOnceWithoutChangingStatusOrRetrying() {
        var boundary = new RiskScenarioHttpBoundary(0); var target = new MockHttpServletResponse(); var calls = new AtomicInteger();
        IOException original = new IOException("synthetic output failure");
        assertThatThrownBy(() -> boundary.doFilter(request("GET", DETAIL), target, (req, res) ->
                boundary.stage((HttpServletRequest) req, (HttpServletResponse) res, 200, JSON, 1, out -> { calls.incrementAndGet(); throw original; })))
                .isSameAs(original);
        assertThat(calls.get()).isEqualTo(1); assertThat(target.getStatus()).isEqualTo(200);
        assertThat(target.getContentAsByteArray()).isEmpty();
    }

    @Test void reserveMustBeReadyAtEntryAndUnchangedAtRelease() {
        var calls = new AtomicInteger();
        var unknown = new RiskScenarioHttpBoundary(() -> { throw new IllegalStateException("not ready"); });
        assertThatThrownBy(() -> unknown.doFilter(request("GET", DETAIL), new MockHttpServletResponse(), (req, res) -> calls.incrementAndGet()))
                .isInstanceOf(IOException.class).hasMessage("Risk HTTP boundary rejected the response");
        assertThat(calls.get()).isZero();
        var values = new AtomicInteger(); var changed = new RiskScenarioHttpBoundary(values::getAndIncrement);
        var target = new MockHttpServletResponse();
        assertThatThrownBy(() -> changed.doFilter(request("GET", DETAIL), target, (req, res) -> changed.stage(
                (HttpServletRequest) req, (HttpServletResponse) res, 200, JSON, 0, out -> calls.incrementAndGet())))
                .isInstanceOf(IOException.class);
        assertThat(calls.get()).isZero(); assertThat(target.getContentAsByteArray()).isEmpty();
    }

    @Test void explicitlyCapturesDateAndLocaleButRejectsUnreviewedNativeCookieSerialization() throws Exception {
        var boundary = new RiskScenarioHttpBoundary(0); var target = new MockHttpServletResponse();
        boundary.doFilter(request("GET", DETAIL), target, (req, res) -> {
            ((HttpServletResponse) res).setLocale(Locale.FRANCE);
            boundary.stage((HttpServletRequest) req, (HttpServletResponse) res, 200, JSON, 0, out -> {});
        });
        assertThat(target.getHeader("Date")).matches("[A-Z][a-z]{2}, [0-9]{2} [A-Z][a-z]{2} [0-9]{4} [0-9]{2}:[0-9]{2}:[0-9]{2} GMT");
        assertThat(target.getHeader("Content-Language")).isEqualTo("fr-FR");
        var closed = new RiskScenarioHttpBoundary(0); var denied = new MockHttpServletResponse();
        assertThatThrownBy(() -> closed.doFilter(request("GET", DETAIL), denied, (req, res) -> {
            closed.stage((HttpServletRequest) req, (HttpServletResponse) res, 200, JSON, 0, out -> {});
            assertThatThrownBy(() -> ((HttpServletResponse) res).addCookie(new Cookie("SESSION", "value"))).isInstanceOf(IllegalStateException.class);
        })).isInstanceOf(ServletException.class);
        assertThat(denied.getContentAsByteArray()).isEmpty();
    }

    @Test void aCaughtTargetIOExceptionStillForbidsAnotherWriteAndSuccessfulRelease() {
        var attempts = new AtomicInteger();
        var target = new MockHttpServletResponse() {
            @Override public ServletOutputStream getOutputStream() {
                return new ServletOutputStream() {
                    @Override public void write(int value) throws IOException { attempts.incrementAndGet(); throw new IOException("synthetic sink failure"); }
                    @Override public boolean isReady() { return true; }
                    @Override public void setWriteListener(WriteListener listener) { throw new UnsupportedOperationException(); }
                };
            }
        };
        var boundary = new RiskScenarioHttpBoundary(0);
        assertThatThrownBy(() -> boundary.doFilter(request("GET", DETAIL), target, (req, res) -> boundary.stage(
                (HttpServletRequest) req, (HttpServletResponse) res, 200, JSON, 1, out -> {
                    try { out.write(1); } catch (IOException ignored) { }
                    assertThatThrownBy(() -> out.write(1)).isInstanceOf(IOException.class);
                }))).isInstanceOf(IOException.class);
        assertThat(attempts.get()).isEqualTo(1); assertThat(target.getStatus()).isEqualTo(200);
    }

    @ParameterizedTest @ValueSource(strings = {"Trailer", "tRaNsFeR-EnCoDiNg"})
    void protocolHeaderRejectionCannotBeCaughtToReleaseAStagedBody(String name) {
        for (boolean append : List.of(false, true)) {
            var boundary = new RiskScenarioHttpBoundary(0); var target = new MockHttpServletResponse(); var calls = new AtomicInteger();
            assertThatThrownBy(() -> boundary.doFilter(request("GET", DETAIL), target, (req, res) -> {
                boundary.stage((HttpServletRequest) req, (HttpServletResponse) res, 200, JSON, 1, out -> { calls.incrementAndGet(); out.write(1); });
                assertThatThrownBy(() -> { if (append) ((HttpServletResponse) res).addHeader(name, "chunked");
                    else ((HttpServletResponse) res).setHeader(name, "chunked"); }).isInstanceOf(IllegalStateException.class);
            })).isInstanceOf(ServletException.class);
            assertThat(calls.get()).isZero(); assertThat(target.getContentAsByteArray()).isEmpty();
        }
    }

    @ParameterizedTest @ValueSource(strings = {"org.apache.tomcat.sendfile.filename", "org.apache.tomcat.sendfile.start", "org.apache.tomcat.sendfile.end"})
    void sendfileRejectionPermanentlyInvalidatesTheStage(String attribute) {
        var boundary = new RiskScenarioHttpBoundary(0); var original = request("GET", DETAIL); var target = new MockHttpServletResponse(); var calls = new AtomicInteger();
        assertThatThrownBy(() -> boundary.doFilter(original, target, (req, res) -> {
            boundary.stage((HttpServletRequest) req, (HttpServletResponse) res, 200, JSON, 1, out -> { calls.incrementAndGet(); out.write(1); });
            assertThatThrownBy(() -> req.setAttribute(attribute, "synthetic")).isInstanceOf(IllegalStateException.class);
        })).isInstanceOf(ServletException.class);
        assertThat(original.getAttribute(attribute)).isNull(); assertThat(calls.get()).isZero(); assertThat(target.getContentAsByteArray()).isEmpty();
        var preexisting = request("GET", DETAIL); preexisting.setAttribute(attribute, "already present");
        assertThatThrownBy(() -> boundary.doFilter(preexisting, new MockHttpServletResponse(), (req, res) -> fail("sendfile chain invoked")))
                .isInstanceOf(ServletException.class);
    }

    @Test void upgradeAndTrailerSuppliersCannotEscapeAfterTheirRejectionIsCaught() {
        for (int operation : List.of(0, 1, 2)) {
            var boundary = new RiskScenarioHttpBoundary(0); var target = new MockHttpServletResponse(); var calls = new AtomicInteger();
            assertThatThrownBy(() -> boundary.doFilter(request("GET", DETAIL), target, (req, res) -> {
                boundary.stage((HttpServletRequest) req, (HttpServletResponse) res, 200, JSON, 1, out -> { calls.incrementAndGet(); out.write(1); });
                assertThatThrownBy(() -> {
                    if (operation == 0) ((HttpServletRequest) req).upgrade(NeverUpgrade.class);
                    else if (operation == 1) ((HttpServletResponse) res).setTrailerFields(() -> { fail("trailer supplier invoked"); return Map.of(); });
                    else ((HttpServletResponse) res).getTrailerFields();
                }).isInstanceOf(IllegalStateException.class);
            })).isInstanceOf(ServletException.class);
            assertThat(calls.get()).isZero(); assertThat(target.getContentAsByteArray()).isEmpty();
        }
    }

    public static final class NeverUpgrade implements HttpUpgradeHandler {
        @Override public void init(WebConnection connection) { fail("native upgrade handler initialized"); }
        @Override public void destroy() { }
    }

    @Test void rejectsExistingDuplicateNativeHeadersInsteadOfAttemptingToCopyThem() {
        var target = new MockHttpServletResponse();
        target.addHeader("Set-Cookie", "SESSION=duplicate"); target.addHeader("Set-Cookie", "SESSION=duplicate");
        assertPristineEntryRejected(target);
        assertThat(target.getHeaders("Set-Cookie")).containsExactly("SESSION=duplicate", "SESSION=duplicate");
    }

    @Test void rejectsAnExistingTrailerSupplierAndNonDefaultNativeMetadata() {
        var trailers = new MockHttpServletResponse() {
            @Override public java.util.function.Supplier<Map<String, String>> getTrailerFields() { return () -> { fail("native trailer supplier invoked"); return Map.of(); }; }
        };
        assertPristineEntryRejected(trailers);
        var status = new MockHttpServletResponse(); status.setStatus(201); assertPristineEntryRejected(status);
        var contentType = new MockHttpServletResponse(); contentType.setContentType("application/json"); assertPristineEntryRejected(contentType);
    }

    private static void assertPristineEntryRejected(MockHttpServletResponse target) {
        var boundary = new RiskScenarioHttpBoundary(0); var calls = new AtomicInteger();
        assertThatThrownBy(() -> boundary.doFilter(request("GET", DETAIL), target, (req, res) -> {
            calls.incrementAndGet(); boundary.stage((HttpServletRequest) req, (HttpServletResponse) res, 200, JSON, 0, out -> calls.incrementAndGet());
        })).isInstanceOf(ServletException.class);
        assertThat(calls.get()).isZero(); assertThat(target.getContentAsByteArray()).isEmpty();
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void caughtTargetRuntimeFailurePermanentlyInvalidatesBothWriteEntrypoints(boolean bulk) {
        var attempts = new AtomicInteger();
        var target = new MockHttpServletResponse() {
            @Override public ServletOutputStream getOutputStream() {
                return new ServletOutputStream() {
                    @Override public void write(int value) { attempts.incrementAndGet(); throw new IllegalStateException("synthetic sink failure"); }
                    @Override public void write(byte[] values, int offset, int length) { attempts.incrementAndGet(); throw new IllegalStateException("synthetic sink failure"); }
                    @Override public boolean isReady() { return true; }
                    @Override public void setWriteListener(WriteListener listener) { throw new UnsupportedOperationException(); }
                };
            }
        };
        var boundary = new RiskScenarioHttpBoundary(0);
        assertThatThrownBy(() -> boundary.doFilter(request("GET", DETAIL), target, (req, res) -> boundary.stage(
                (HttpServletRequest) req, (HttpServletResponse) res, 200, JSON, 1, out -> {
                    try { if (bulk) out.write(new byte[]{1}); else out.write(1); } catch (IllegalStateException ignored) { }
                    assertThatThrownBy(() -> { if (bulk) out.write(new byte[]{1}); else out.write(1); }).isInstanceOf(IOException.class);
                }))).isInstanceOf(IOException.class);
        assertThat(attempts.get()).isEqualTo(1); assertThat(target.getStatus()).isEqualTo(200);
    }

    private static MockHttpServletRequest request(String method, String path) { return new MockHttpServletRequest(method, path); }
}
