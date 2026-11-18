package com.mightyoung.softwarefactory.auditops;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import java.util.Map;

/** Own synchronous boundary; composition root sends bytes after late header writers finish. */
public interface TenantAuditHttpTransport {
    void stage(HttpServletRequest request, HttpServletResponse response, int status,
            Map<String,List<String>> headers, int bodyLength, BodyWriter body) throws IOException;
    @FunctionalInterface
    interface BodyWriter { void writeTo(OutputStream output) throws IOException; }
}
