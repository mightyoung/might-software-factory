package com.mightyoung.softwarefactory.identity;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class BufferedLoginResponseTest {
    @Test void allStatusHeaderCookieAndBodyMutationsStayLocalUntilRelease() throws Exception {
        var target = new MockHttpServletResponse();
        target.addHeader("X-Frame-Options", "DENY");
        var response = new BufferedLoginResponse(target, 16384);
        response.setStatus(201);
        response.setContentType("text/plain");
        response.setCharacterEncoding(StandardCharsets.UTF_8);
        response.setContentLengthLong(7);
        response.setLocale(Locale.CANADA);
        response.setHeader("X-Test", "first");
        response.addHeader("x-test", "second");
        response.setIntHeader("X-Number", 3);
        response.addIntHeader("x-number", 4);
        response.setDateHeader("Last-Modified", 0);
        response.addDateHeader("last-modified", 1000);
        Cookie cookie = new Cookie("SESSION", "private-session");
        cookie.setPath("/"); cookie.setSecure(true); cookie.setHttpOnly(true); cookie.setAttribute("SameSite", "Lax");
        response.addCookie(cookie);
        response.getWriter().write("success");
        response.getWriter().flush();
        response.flushBuffer();
        assertThat(target.getStatus()).isEqualTo(200);
        assertThat(target.getHeaderNames()).containsExactly("X-Frame-Options");
        assertThat(target.getContentAsByteArray()).isEmpty();
        assertThat(target.isCommitted()).isFalse();
        assertThat(response.isCommitted()).isFalse();
        assertThat(response.getHeaders("X-TEST")).containsExactly("first", "second");
        response.release();
        assertThat(target.getStatus()).isEqualTo(201);
        assertThat(target.getContentAsString()).isEqualTo("success");
        assertThat(target.getHeaders("X-Test")).containsExactly("first", "second");
        assertThat(target.getHeaders("X-Number")).containsExactly("3", "4");
        assertThat(target.getHeaders("Last-Modified")).containsExactly("Thu, 01 Jan 1970 00:00:00 GMT", "Thu, 01 Jan 1970 00:00:01 GMT");
        assertThat(target.getHeader("Set-Cookie")).contains("SESSION=private-session", "HttpOnly", "Secure", "SameSite=Lax", "Path=/");
        assertThat(target.getHeader("X-Frame-Options")).isEqualTo("DENY");
        assertThat(target.isCommitted()).isTrue();
        assertThat(response.isCommitted()).isTrue();
    }

    @Test void utf8WriterHandlesSplitSurrogatesAndLocalCloseWithoutPublishing() throws Exception {
        var target = new MockHttpServletResponse();
        var response = new BufferedLoginResponse(target, 128);
        response.setCharacterEncoding("UTF-8");
        var writer = response.getWriter();
        writer.write("登录"); writer.write('\ud83d'); writer.flush(); writer.write('\ude00'); writer.close();
        assertThat(target.getContentAsByteArray()).isEmpty();
        assertThat(response.isCommitted()).isFalse();
        response.prepare();
        response.prepare();
        response.release();
        assertThat(target.getContentAsByteArray()).isEqualTo("登录😀".getBytes(StandardCharsets.UTF_8));
    }

    @Test void writerCharsetIsFrozenUntilFullReset() throws Exception {
        var target = new MockHttpServletResponse();
        var response = new BufferedLoginResponse(target, 256);
        response.setContentType("text/plain;charset=UTF-8");
        response.getWriter().write("旧");
        response.setCharacterEncoding("UTF-16");
        response.setContentType("text/plain;charset=UTF-16");
        assertThat(response.getCharacterEncoding()).isEqualTo("UTF-8");
        response.resetBuffer();
        assertThat(response.getCharacterEncoding()).isEqualTo("UTF-8");
        response.getWriter().write("新");
        response.release();
        assertThat(target.getContentAsByteArray()).isEqualTo("新".getBytes(StandardCharsets.UTF_8));
        assertThat(target.getContentType()).contains("charset=UTF-8");
    }

    @Test void localErrorAndRedirectDoNotDispatchOrCommitAndCanBeReplaced() throws Exception {
        var target = spy(new MockHttpServletResponse());
        var response = new BufferedLoginResponse(target, 512);
        response.getWriter().write("stale success");
        response.flushBuffer();
        response.sendError(503, "private diagnostic");
        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.isCommitted()).isFalse();
        response.sendRedirect("/retry", 307, true);
        assertThat(response.getStatus()).isEqualTo(307);
        assertThat(response.getHeader("location")).isEqualTo("/retry");
        assertThat(target.getStatus()).isEqualTo(200);
        assertThat(target.getContentAsByteArray()).isEmpty();
        response.release();
        assertThat(target.getStatus()).isEqualTo(307);
        assertThat(target.getHeader("Location")).isEqualTo("/retry");
        assertThat(target.getContentAsByteArray()).isEmpty();
        verify(target, never()).sendError(anyInt(), anyString());
        verify(target, never()).sendRedirect(anyString(), anyInt(), anyBoolean());
    }

    @Test void resetClearsStaleBodyLengthAndWriterSelectionWithoutNativeReset() throws Exception {
        var target = new MockHttpServletResponse();
        var response = new BufferedLoginResponse(target, 256);
        response.setStatus(201);
        response.setContentLength(99);
        response.setContentType("text/plain;charset=UTF-8");
        var staleWriter = response.getWriter();
        staleWriter.write("old");
        response.reset();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getHeaderNames()).isEmpty();
        assertThat(response.getCharacterEncoding()).isEqualTo("ISO-8859-1");
        assertThatThrownBy(() -> staleWriter.write("stale")).isInstanceOf(IllegalStateException.class);
        response.getOutputStream().write(new byte[] {1, 2});
        response.release();
        assertThat(target.getContentLength()).isZero();
        assertThat(target.getContentAsByteArray()).containsExactly(1, 2);
    }

    @Test void resetBufferDropsStaleContentLengthAndRetainsSecurityHeaders() throws Exception {
        var target = new MockHttpServletResponse();
        var response = new BufferedLoginResponse(target, 256);
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setContentLength(99);
        response.getOutputStream().write(new byte[] {1, 2, 3});
        response.resetBuffer();
        response.getOutputStream().write(4);
        response.release();
        assertThat(target.getHeader("Content-Length")).isNull();
        assertThat(target.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(target.getContentAsByteArray()).containsExactly(4);
    }

    @Test void releaseResetsNativeZeroContentLengthBeforePublishingReplacementFailure() throws Exception {
        var target = spy(new MockHttpServletResponse());
        target.setContentLength(0);
        var response = new BufferedLoginResponse(target, 16384);
        response.reset();
        response.setHeader("X-Content-Type-Options", "nosniff");
        IdentityHttpResponses.writeError(response, 503, "AUTHENTICATION_UNAVAILABLE", "Try again", true);
        response.prepare();
        assertThat(target.getHeader("Content-Length")).isEqualTo("0");
        assertThat(target.getContentAsByteArray()).isEmpty();
        response.release();
        verify(target).reset();
        assertThat(target.getStatus()).isEqualTo(503);
        assertThat(target.getHeader("Content-Length")).isNull();
        assertThat(target.getContentType()).contains("application/json", "charset=UTF-8");
        assertThat(target.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(target.getContentAsString()).contains("AUTHENTICATION_UNAVAILABLE", "Try again");
    }

    @Test void discardsOnlyAuthenticationCookiesAndPreservesOtherCookiesAndSecurityHeaders() throws Exception {
        var target = new MockHttpServletResponse();
        target.addHeader("Set-Cookie", "SESSION=previous; Path=/");
        target.addHeader("Strict-Transport-Security", "max-age=31536000");
        var response = new BufferedLoginResponse(target, 1024);
        response.addHeader("set-cookie", "XSRF-TOKEN=token; Path=/");
        response.addHeader("Set-Cookie", "preferences=compact; Path=/");
        response.discardAuthenticationCookies();
        assertThat(target.getHeader("Set-Cookie")).isEqualTo("SESSION=previous; Path=/");
        response.release();
        assertThat(target.getHeaders("Set-Cookie")).hasSize(3).anySatisfy(value -> assertThat(value).isEqualTo("preferences=compact; Path=/"))
                .anySatisfy(value -> assertThat(value).startsWith("SESSION=;").contains("Max-Age=0"))
                .anySatisfy(value -> assertThat(value).startsWith("XSRF-TOKEN=;").contains("Max-Age=0"));
        assertThat(target.getHeaders("Set-Cookie")).noneMatch(value -> value.contains("previous") || value.contains("=token"));
        assertThat(target.getHeader("Strict-Transport-Security")).isEqualTo("max-age=31536000");
    }

    @Test void combinedHeaderAndBodyLimitRejectsBeforeAnythingSpills() throws Exception {
        var target = new MockHttpServletResponse();
        var response = new BufferedLoginResponse(target, 32);
        response.setHeader("X", "123"); // Eight bytes including name and separators.
        response.getOutputStream().write(new byte[24]);
        assertThatThrownBy(() -> response.getOutputStream().write(1)).isInstanceOf(BufferedLoginResponse.ResponseLimitExceeded.class);
        assertThatThrownBy(() -> response.addHeader("Y", "a")).isInstanceOf(BufferedLoginResponse.ResponseLimitExceeded.class);
        assertThat(target.getContentAsByteArray()).isEmpty();
        assertThat(target.getHeaderNames()).isEmpty();
        assertThat(target.isCommitted()).isFalse();
    }

    @Test void headerReplacementUsesReplacementBudgetAndOverflowKeepsPreviousValue() throws Exception {
        var target = new MockHttpServletResponse();
        var response = new BufferedLoginResponse(target, 32);
        response.setHeader("X-Value", "1234567890");
        response.setHeader("x-value", "abcdefghij");
        assertThat(response.getHeaders("X-VALUE")).containsExactly("abcdefghij");
        assertThatThrownBy(() -> response.setHeader("X-Value", "a".repeat(33)))
                .isInstanceOf(BufferedLoginResponse.ResponseLimitExceeded.class);
        assertThat(response.getHeader("X-Value")).isEqualTo("abcdefghij");
        response.release();
        assertThat(target.getHeaders("x-value")).containsExactly("abcdefghij");
    }

    @Test void writerLimitCountsEncodedBytesAndRejectsLargeWritesWithoutSpill() throws Exception {
        var target = new MockHttpServletResponse();
        var response = new BufferedLoginResponse(target, 8);
        response.setCharacterEncoding("UTF-8");
        assertThatThrownBy(() -> response.getWriter().write("界界界"))
                .isInstanceOf(BufferedLoginResponse.ResponseLimitExceeded.class);
        assertThat(target.getContentAsByteArray()).isEmpty();
        assertThat(target.isCommitted()).isFalse();
    }

    @Test void prepareDetectsPendingSurrogateOverflowBeforeAuditAndAllowsResetToFailure() throws Exception {
        var target = new MockHttpServletResponse();
        var response = new BufferedLoginResponse(target, 128);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("x".repeat(128));
        response.getWriter().write('\ud83d');
        assertThatThrownBy(response::prepare).isInstanceOf(BufferedLoginResponse.ResponseLimitExceeded.class);
        assertThat(target.getHeaderNames()).isEmpty();
        assertThat(target.getContentAsByteArray()).isEmpty();
        assertThat(target.isCommitted()).isFalse();
        assertThat(response.isCommitted()).isFalse();
        response.reset();
        response.setStatus(503);
        response.setContentType("text/plain;charset=UTF-8");
        response.getWriter().write("unavailable");
        response.prepare();
        response.release();
        assertThat(target.getStatus()).isEqualTo(503);
        assertThat(target.getContentAsString()).isEqualTo("unavailable");
    }

    @Test void capturesExistingHeadersWithinTheSameLimitAndRejectsOversizedCookies() {
        var target = new MockHttpServletResponse();
        target.setHeader("X-Existing", "a".repeat(30));
        assertThatThrownBy(() -> new BufferedLoginResponse(target, 32))
                .isInstanceOf(BufferedLoginResponse.ResponseLimitExceeded.class);
        var empty = new MockHttpServletResponse();
        var response = new BufferedLoginResponse(empty, 32);
        assertThatThrownBy(() -> response.addCookie(new Cookie("SESSION", "x".repeat(100))))
                .isInstanceOf(BufferedLoginResponse.ResponseLimitExceeded.class);
        assertThat(response.getHeaderNames()).isEmpty();
        assertThat(empty.getHeaderNames()).isEmpty();
    }

    @Test void writerAndStreamAreMutuallyExclusiveAndStreamCloseIsLocal() throws Exception {
        var target = new MockHttpServletResponse();
        var response = new BufferedLoginResponse(target, 64);
        var stream = response.getOutputStream();
        stream.write(1); stream.flush(); stream.close();
        assertThatThrownBy(response::getWriter).isInstanceOf(IllegalStateException.class);
        assertThat(response.isCommitted()).isFalse();
        assertThat(target.getContentAsByteArray()).isEmpty();
        response.reset();
        response.getWriter();
        assertThatThrownBy(response::getOutputStream).isInstanceOf(IllegalStateException.class);
    }

    @Test void releaseFailureAllowsOnlyOneNativePublicationAttempt() throws Exception {
        HttpServletResponse target = mock(HttpServletResponse.class);
        when(target.getCharacterEncoding()).thenReturn("UTF-8");
        when(target.getLocale()).thenReturn(Locale.US);
        when(target.getHeaderNames()).thenReturn(List.of());
        var writes = new AtomicInteger();
        ServletOutputStream failing = new ServletOutputStream() {
            @Override public void write(int value) throws IOException { writes.incrementAndGet(); throw new IOException("lost transport"); }
            @Override public boolean isReady() { return true; }
            @Override public void setWriteListener(WriteListener listener) { }
        };
        when(target.getOutputStream()).thenReturn(failing);
        var response = new BufferedLoginResponse(target, 64);
        response.getOutputStream().write(1);
        assertThatIOException().isThrownBy(response::release);
        assertThatThrownBy(response::release).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(response::reset).isInstanceOf(IllegalStateException.class);
        assertThat(response.isCommitted()).isTrue();
        assertThat(writes).hasValue(1);
        verify(target).getOutputStream();
        verify(target, never()).flushBuffer();
    }

    @Test void asyncAndTrailersAreRejectedWithoutReachingTheNativeResponse() throws Exception {
        var target = new MockHttpServletResponse();
        var response = new BufferedLoginResponse(target, 64);
        assertThatThrownBy(() -> response.getOutputStream().setWriteListener(mock(WriteListener.class)))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> response.setTrailerFields(() -> Map.of("X", "v")))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(response::getTrailerFields).isInstanceOf(UnsupportedOperationException.class);
        assertThat(target.isCommitted()).isFalse();
    }
}
