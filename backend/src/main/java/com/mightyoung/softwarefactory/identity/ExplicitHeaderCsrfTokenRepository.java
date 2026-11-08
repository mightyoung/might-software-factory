package com.mightyoung.softwarefactory.identity;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.http.ResponseCookie;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;

/** Keeps the standard CSRF repository semantics while making its fixed cookie visible to outer header boundaries. */
final class ExplicitHeaderCsrfTokenRepository implements CsrfTokenRepository {
    private static final String FAILURE = "CSRF cookie boundary is unavailable";
    private static final Set<String> ATTRIBUTES = Set.of("path", "max-age", "secure", "samesite");
    private final CookieCsrfTokenRepository delegate;

    ExplicitHeaderCsrfTokenRepository() {
        delegate = CookieCsrfTokenRepository.withHttpOnlyFalse();
        delegate.setCookiePath("/");
        delegate.setCookieCustomizer(cookie -> cookie.secure(true).sameSite("Lax"));
    }
    @Override public CsrfToken generateToken(HttpServletRequest request) { return delegate.generateToken(request); }
    @Override public CsrfToken loadToken(HttpServletRequest request) { return delegate.loadToken(request); }
    @Override public void saveToken(CsrfToken token, HttpServletRequest request, HttpServletResponse response) {
        delegate.saveToken(token, request, new HttpServletResponseWrapper(response) {
            @Override public void addCookie(Cookie cookie) { response.addHeader("Set-Cookie", header(cookie)); }
        });
    }
    // Keep CsrfTokenRepository's default loadDeferredToken: its deferred save must call this adapter.

    static String header(Cookie cookie) {
        if (cookie == null || !"XSRF-TOKEN".equals(cookie.getName()) || !"/".equals(cookie.getPath())
                || !cookie.getSecure() || cookie.isHttpOnly() || cookie.getDomain() != null
                || !"Lax".equals(cookie.getAttribute("SameSite"))
                || !cookie.getAttributes().keySet().stream().map(key -> key.toLowerCase(Locale.ROOT)).collect(Collectors.toSet()).equals(ATTRIBUTES)
                || !"".equals(cookie.getAttribute("Secure"))
                || !Integer.toString(cookie.getMaxAge()).equals(cookie.getAttribute("Max-Age"))) throw rejected();
        String value = cookie.getValue(); int age = cookie.getMaxAge();
        boolean session = age == -1 && value != null && value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
        boolean deletion = age == 0 && "".equals(value);
        if (!session && !deletion) throw rejected();
        return ResponseCookie.from("XSRF-TOKEN", value).path("/").secure(true).httpOnly(false).sameSite("Lax")
                .maxAge(age).build().toString();
    }
    private static IllegalStateException rejected() { return new IllegalStateException(FAILURE); }
}
