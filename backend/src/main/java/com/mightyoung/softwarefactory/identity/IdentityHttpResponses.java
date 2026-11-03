package com.mightyoung.softwarefactory.identity;

import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.UUID;

final class IdentityHttpResponses {

    private IdentityHttpResponses() {
    }

    static void writeError(HttpServletResponse response, int status, String code, String message,
            boolean retryable) throws IOException {
        writeError(response, status, code, message, retryable, UUID.randomUUID());
    }

    static void writeError(HttpServletResponse response, int status, String code, String message,
            boolean retryable, UUID traceId) throws IOException {
        UUID serverTrace = traceId == null ? UUID.randomUUID() : traceId;
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"code\":\"" + code + "\",\"message\":\"" + message
                + "\",\"traceId\":\"" + serverTrace
                + "\",\"retryable\":" + retryable + ",\"fieldErrors\":[]}");
    }

    static void clearSessionCookies(HttpServletResponse response) {
        for (String name : new String[] {"SESSION", "XSRF-TOKEN"}) {
            response.addHeader("Set-Cookie", org.springframework.http.ResponseCookie.from(name, "")
                    .path("/").secure(true).httpOnly(name.equals("SESSION")).sameSite("Lax")
                    .maxAge(0).build().toString());
        }
    }

    /** Capability retry keeps its independently valid CSRF cookie/header. */
    static void clearSessionCookie(HttpServletResponse response) {
        response.addHeader("Set-Cookie", org.springframework.http.ResponseCookie.from("SESSION", "")
                .path("/").secure(true).httpOnly(true).sameSite("Lax").maxAge(0).build().toString());
    }

    static void noContent(HttpServletResponse response) {
        response.setStatus(HttpServletResponse.SC_NO_CONTENT);
    }
}
