package com.mightyoung.softwarefactory.identity;

import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.UUID;

final class IdentityHttpResponses {

    private IdentityHttpResponses() {
    }

    static void writeError(HttpServletResponse response, int status, String code, String message,
            boolean retryable) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"code\":\"" + code + "\",\"message\":\"" + message
                + "\",\"traceId\":\"" + UUID.randomUUID()
                + "\",\"retryable\":" + retryable + ",\"fieldErrors\":[]}");
    }

    static void noContent(HttpServletResponse response) {
        response.setStatus(HttpServletResponse.SC_NO_CONTENT);
    }
}
