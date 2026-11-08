package com.mightyoung.softwarefactory.identity;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Server request facts only; neither freshness nor cookie possession is ownership. */
public final class KnownLineageLogoutCapture implements AutoCloseable {
    private final HttpServletRequest request;
    private final KnownLogoutRequests requests;
    private final List<String> selectedIds;
    private final HttpSession session;
    private final Authentication authentication;
    private final Object principal;
    private final String bearerId, requestedId;
    private final boolean authenticated;
    private final Thread thread = Thread.currentThread();
    private boolean closed;

    KnownLineageLogoutCapture(HttpServletRequest request, KnownLogoutRequests requests) {
        this.request = Objects.requireNonNull(request);
        this.requests = Objects.requireNonNull(requests);
        try {
            if (request.isAsyncStarted()) throw SessionSensitiveException.unavailable();
            session = request.getSession(false);
            authentication = SecurityContextHolder.getContext().getAuthentication();
            principal = authentication == null ? null : authentication.getPrincipal();
            authenticated = authentication != null && authentication.isAuthenticated();
            requestedId = request.getRequestedSessionId();
            selectedIds = requests.resolve(request);
            String selected = selectedIds.isEmpty() ? null : selectedIds.getFirst();
            if (!Objects.equals(requestedId, selected)
                    || (selected != null && session != null && !selected.equals(session.getId())))
                throw SessionSensitiveException.unavailable();
            bearerId = session != null ? session.getId() : selected;
            if (bearerId != null) KnownLogoutRequests.requireId(bearerId);
            requireUnchanged();
        } catch (RuntimeException failure) { throw SessionSensitiveException.unavailable(); }
    }

    public void requireUnchanged() {
        try {
            if (closed || thread != Thread.currentThread() || request.isAsyncStarted()
                    || request.getSession(false) != session
                    || (session != null && !Objects.equals(bearerId, session.getId()))
                    || !Objects.equals(requestedId, request.getRequestedSessionId())
                    || !selectedIds.equals(requests.resolve(request))
                    || authentication != SecurityContextHolder.getContext().getAuthentication()
                    || (authentication != null && (authentication.getPrincipal() != principal
                    || authentication.isAuthenticated() != authenticated))) throw SessionSensitiveException.unavailable();
        } catch (RuntimeException failure) { throw SessionSensitiveException.unavailable(); }
    }
    String bearerId() { requireUnchanged(); return bearerId; }
    UUID claimedAccount() {
        requireUnchanged();
        if (principal instanceof VerifiedSessionPrincipal verified) {
            if (verified.accountId() == null) throw SessionSensitiveException.unavailable();
            return verified.accountId();
        }
        return null;
    }
    UUID claimedActor() { requireUnchanged(); return principal instanceof VerifiedSessionPrincipal verified ? verified.actorId() : null; }
    @Override public void close() { closed = true; }
    @Override public String toString() { return "KnownLineageLogoutCapture[REDACTED]"; }
}
