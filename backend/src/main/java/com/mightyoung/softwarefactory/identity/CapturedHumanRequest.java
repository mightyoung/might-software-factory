package com.mightyoung.softwarefactory.identity;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import java.time.Duration;
import java.time.Instant;

/** Request facts only. SQL authority is separately established in an owner-pinned boundary. */
public final class CapturedHumanRequest {
    private final HttpServletRequest request;
    private final HttpSession session;
    private final Authentication authentication;
    private final VerifiedSessionPrincipal principal;
    private final CurrentTenantContext context;
    private final SessionContextResolver contexts;
    private final Thread thread=Thread.currentThread();
    private final String bearerId;
    private final Instant authenticatedAt,absoluteExpiresAt;
    private boolean closed;
    private CapturedHumanRequest(HttpServletRequest request,SessionContextResolver contexts,Duration timeout) {
        this.request=request;this.contexts=contexts;this.context=contexts.currentTenant();
        this.authentication=SecurityContextHolder.getContext().getAuthentication();
        if(authentication==null||!(authentication.getPrincipal() instanceof VerifiedSessionPrincipal verified))throw SessionSensitiveException.rejected();
        principal=verified;session=request.getSession(false);
        if(session==null||timeout==null||timeout.isZero()||timeout.isNegative())throw SessionSensitiveException.rejected();
        try {
            bearerId=session.getId();
            Object value=session.getAttribute(SessionLifetimePolicy.AUTHENTICATED_AT_ATTRIBUTE);
            if(!(value instanceof Instant instant))throw SessionSensitiveException.rejected();
            authenticatedAt=instant;absoluteExpiresAt=instant.plus(timeout);
        }catch(java.time.DateTimeException|ArithmeticException|IllegalStateException invalid){throw SessionSensitiveException.rejected();}
        requireCurrent();
    }
    static CapturedHumanRequest capture(HttpServletRequest request,SessionContextResolver contexts,Duration timeout) {
        if(request==null)throw SessionSensitiveException.rejected();
        return new CapturedHumanRequest(request,contexts,timeout);
    }
    public CurrentTenantContext context(){requireCurrent();return context;}
    public Instant authenticatedAt(){requireCurrent();return authenticatedAt;}
    public Instant absoluteExpiresAt(){requireCurrent();return absoluteExpiresAt;}
    String bearerId(){requireCurrent();return bearerId;}
    public void requireCurrent() {
        try {
            if(closed||thread!=Thread.currentThread()||request.isAsyncStarted()
                    ||request.getSession(false)!=session||!bearerId.equals(session.getId())
                    ||authentication!=SecurityContextHolder.getContext().getAuthentication()||!authentication.isAuthenticated()
                    ||authentication.getPrincipal()!=principal||!context.equals(contexts.currentTenant())
                    ||!authenticatedAt.equals(session.getAttribute(SessionLifetimePolicy.AUTHENTICATED_AT_ATTRIBUTE)))throw SessionSensitiveException.rejected();
        }catch(IllegalStateException invalid){throw SessionSensitiveException.rejected();}
    }
    void canonicalizeCurrentIndex(){
        requireCurrent();
        session.setAttribute(org.springframework.session.FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME,context.accountId().toString());
        requireCurrent();
    }
    public void close(){closed=true;}
    @Override public String toString(){return "CapturedHumanRequest[REDACTED]";}
}
