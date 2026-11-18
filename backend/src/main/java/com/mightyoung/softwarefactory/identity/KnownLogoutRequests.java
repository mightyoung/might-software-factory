package com.mightyoung.softwarefactory.identity;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import org.springframework.session.web.http.CookieHttpSessionIdResolver;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.DefaultCookieSerializer;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/** Trusted cookie bootstrap bundle; no session-authority activation. Configure once, then share its instances. */
public final class KnownLogoutRequests {
    private final ObservedCookieSerializer serializer;
    private final CookieHttpSessionIdResolver resolver;

    public KnownLogoutRequests(Consumer<DefaultCookieSerializer> configure) {
        serializer = new ObservedCookieSerializer();
        serializer.setCookieName("SESSION");
        Objects.requireNonNull(configure).accept(serializer);
        serializer.seal();
        resolver = new CookieHttpSessionIdResolver();
        resolver.setCookieSerializer(serializer);
    }

    /** The actual Spring Session filter must receive this same instance during trusted bootstrap. */
    public CookieHttpSessionIdResolver sessionIdResolver() { return resolver; }

    /** Stable sealed serializer capability for the same trusted filter assembly. */
    public CookieSerializer cookieSerializer() { return serializer; }

    public KnownLineageLogoutCapture capture(HttpServletRequest request) {
        if (request == null) throw SessionSensitiveException.unavailable();
        return new KnownLineageLogoutCapture(request, this);
    }

    List<String> resolve(HttpServletRequest request) {
        Attempt attempt = serializer.begin(request);
        try {
            List<String> selected = List.copyOf(resolver.resolveSessionIds(request));
            if (attempt.calls != 1 || attempt.observation == null
                    || !selected.equals(attempt.observation.selected())
                    || attempt.observation.matchingCookies() != selected.size()
                    || selected.size() > 1) throw SessionSensitiveException.unavailable();
            for (String id : selected) requireId(id);
            return selected;
        } catch (RuntimeException failure) {
            throw SessionSensitiveException.unavailable();
        } finally {
            serializer.end(attempt);
        }
    }

    static void requireId(String id) {
        if (id == null || id.isEmpty() || id.length() > 256
                || id.codePoints().anyMatch(c -> c < 33 || c > 126)) throw SessionSensitiveException.unavailable();
    }

    private record Observation(int matchingCookies, List<String> selected) { }
    private static final class Attempt {
        final HttpServletRequest request;
        int calls;
        Observation observation;
        Attempt(HttpServletRequest request) { this.request = request; }
    }

    private static final class ObservedCookieSerializer extends DefaultCookieSerializer {
        private final ThreadLocal<Attempt> attempts = new ThreadLocal<>();
        private String cookieName = "SESSION";
        private boolean sealed;

        synchronized void seal() { sealed = true; }
        private void requireConfigurable() { if (sealed) throw new IllegalStateException("Cookie configuration is sealed"); }
        Attempt begin(HttpServletRequest request) {
            if (!sealed || attempts.get() != null) throw SessionSensitiveException.unavailable();
            Attempt attempt = new Attempt(request);
            attempts.set(attempt);
            return attempt;
        }
        void end(Attempt attempt) {
            Attempt current = attempts.get();
            attempts.remove();
            if (current != attempt) throw SessionSensitiveException.unavailable();
        }

        @Override public List<String> readCookieValues(HttpServletRequest request) {
            Attempt attempt = attempts.get();
            if (attempt != null && (attempt.request != request || ++attempt.calls != 1))
                throw SessionSensitiveException.unavailable();
            Cookie[] input = request.getCookies();
            Cookie[] snapshot = input == null ? null : Arrays.stream(input)
                    .map(cookie -> (Cookie) Objects.requireNonNull(cookie).clone()).toArray(Cookie[]::new);
            int matching = snapshot == null ? 0 : (int) Arrays.stream(snapshot)
                    .filter(cookie -> cookieName.equals(cookie.getName())).count();
            HttpServletRequest frozen = new HttpServletRequestWrapper(request) {
                @Override public Cookie[] getCookies() {
                    return snapshot == null ? null : Arrays.stream(snapshot)
                            .map(cookie -> (Cookie) cookie.clone()).toArray(Cookie[]::new);
                }
            };
            List<String> selected = List.copyOf(super.readCookieValues(frozen));
            if (attempt != null) attempt.observation = new Observation(matching, selected);
            return selected;
        }

        @Override public synchronized void setCookieName(String value) { requireConfigurable(); super.setCookieName(value); cookieName = value; }
        @Override public synchronized void setUseBase64Encoding(boolean value) { requireConfigurable(); super.setUseBase64Encoding(value); }
        @Override public synchronized void setJvmRoute(String value) { requireConfigurable(); super.setJvmRoute(value); }
        @Override public synchronized void setUseSecureCookie(boolean value) { requireConfigurable(); super.setUseSecureCookie(value); }
        @Override public synchronized void setUseHttpOnlyCookie(boolean value) { requireConfigurable(); super.setUseHttpOnlyCookie(value); }
        @Override public synchronized void setCookiePath(String value) { requireConfigurable(); super.setCookiePath(value); }
        @Override public synchronized void setCookieMaxAge(int value) { requireConfigurable(); super.setCookieMaxAge(value); }
        @Override public synchronized void setDomainName(String value) { requireConfigurable(); super.setDomainName(value); }
        @Override public synchronized void setDomainNamePattern(String value) { requireConfigurable(); super.setDomainNamePattern(value); }
        @Override public synchronized void setRememberMeRequestAttribute(String value) { requireConfigurable(); super.setRememberMeRequestAttribute(value); }
        @Override public synchronized void setSameSite(String value) { requireConfigurable(); super.setSameSite(value); }
        @Override public synchronized void setPartitioned(boolean value) { requireConfigurable(); super.setPartitioned(value); }
    }

    @Override public String toString() { return "KnownLogoutRequests[REDACTED]"; }
}
