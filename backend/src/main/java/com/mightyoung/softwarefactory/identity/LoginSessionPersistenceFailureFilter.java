package com.mightyoung.softwarefactory.identity;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpMethod;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.transaction.TransactionException;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;

/** Outside Spring Session: a failed save must not become a successful login response. */
final class LoginSessionPersistenceFailureFilter extends OncePerRequestFilter {
    private final PathPatternRequestMatcher login = PathPatternRequestMatcher.withDefaults()
            .matcher(HttpMethod.POST, "/api/v1/auth/login");

    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        return !login.matches(request);
    }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        try {
            chain.doFilter(request, response);
        } catch (DataAccessException | TransactionException unavailable) {
            var attempt = AuthenticationAttempt.current(request);
            if (attempt != null) attempt.sessionFailure();
            SecurityContextHolder.clearContext();
            if (response.isCommitted()) throw unavailable;
            unavailable(response);
        }
    }

    static void unavailable(HttpServletResponse response) throws IOException {
        // Servlet implementations may retain a special Content-Length after setHeader(null).
        // Reset the uncommitted response, then restore unrelated/security headers.
        var retainedHeaders = new java.util.LinkedHashMap<String, java.util.List<String>>();
        for (String name : response.getHeaderNames()) {
            if (!name.equalsIgnoreCase("Content-Length") && !name.equalsIgnoreCase("Content-Type")
                    && !name.equalsIgnoreCase("Content-Encoding") && !name.equalsIgnoreCase("Set-Cookie")) {
                retainedHeaders.put(name, java.util.List.copyOf(response.getHeaders(name)));
            }
        }
        // A prior internal save may have emitted a cookie before a later failure. Do not expose it.
        var remainingCookies = new ArrayList<String>();
        for (String cookie : response.getHeaders("Set-Cookie")) {
            int separator = cookie.indexOf('=');
            String name = separator < 0 ? cookie : cookie.substring(0, separator).strip();
            if (!name.equals("SESSION") && !name.equals("XSRF-TOKEN")) remainingCookies.add(cookie);
        }
        response.reset();
        retainedHeaders.forEach((name, values) -> values.forEach(value -> response.addHeader(name, value)));
        remainingCookies.forEach(cookie -> response.addHeader("Set-Cookie", cookie));
        IdentityHttpResponses.clearSessionCookies(response);
        // No native getSession(), save retry or rollback claim: commit outcome may be unknown.
        IdentityHttpResponses.writeError(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                "DEPENDENCY_UNAVAILABLE", "Authentication could not be completed", true);
    }
}
