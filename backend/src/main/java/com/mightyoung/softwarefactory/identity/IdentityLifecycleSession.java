package com.mightyoung.softwarefactory.identity;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Captures request authority, not a reusable or serializable cross-tenant credential. */
@Component
public final class IdentityLifecycleSession {
    private final SessionContextResolver contexts;
    private final CurrentSessionReadRepository sessions;
    private final SessionLifetimePolicy lifetime;
    private final ObjectProvider<HttpServletRequest> requests;
    public IdentityLifecycleSession(SessionContextResolver contexts,CurrentSessionReadRepository sessions,
                                    SessionLifetimePolicy lifetime,ObjectProvider<HttpServletRequest> requests) {
        this.contexts=contexts;this.sessions=sessions;this.lifetime=lifetime;this.requests=requests;
    }
    public Captured capture() {
        CurrentTenantContext context=contexts.currentTenant();
        HttpServletRequest request=requests.getIfAvailable();
        if(request==null)throw new UnauthenticatedSessionException();
        HttpSession session=request.getSession(false);
        Authentication authentication=SecurityContextHolder.getContext().getAuthentication();
        var captured=new Captured(context,authentication,session,request,contexts,sessions,lifetime);
        captured.requireRequestCurrent();
        return captured;
    }
    public static final class Captured {
        private final CurrentTenantContext context;
        private final Authentication authentication;
        private final HttpSession session;
        private final HttpServletRequest request;
        private final SessionContextResolver contexts;
        private final CurrentSessionReadRepository sessions;
        private final SessionLifetimePolicy lifetime;
        private final AtomicBoolean entered=new AtomicBoolean();
        private boolean sourceReadClosed;
        private String verifiedDisplayName;
        private Captured(CurrentTenantContext context,Authentication authentication,HttpSession session,
                         HttpServletRequest request,SessionContextResolver contexts,CurrentSessionReadRepository sessions,
                         SessionLifetimePolicy lifetime) {
            this.context=context;this.authentication=authentication;this.session=session;this.request=request;
            this.contexts=contexts;this.sessions=sessions;this.lifetime=lifetime;
        }
        public CurrentTenantContext context(){return context;}
        public void beginSourceValidation() {
            if(!entered.compareAndSet(false,true))throw new UnauthenticatedSessionException();
            requireRequestCurrent();
            var current=sessions.findCurrent(context).filter(value->context.actorId().equals(value.actorId())
                    &&context.accountId().equals(value.accountId())&&context.memberId().equals(value.memberId())
                    &&context.tenantId().equals(value.tenantId())).orElseThrow(UnauthenticatedSessionException::new);
            if(sourceReadClosed)throw new UnauthenticatedSessionException();
            verifiedDisplayName=current.displayName();
            requireRequestCurrent();
        }
        /** Called exactly once before the lifecycle writer switches the transaction's tenant. */
        public void closeSourcePhase(){if(!entered.get()||sourceReadClosed)throw new UnauthenticatedSessionException();sourceReadClosed=true;}
        public String verifiedDisplayName(){if(!sourceReadClosed||verifiedDisplayName==null)throw new UnauthenticatedSessionException();return verifiedDisplayName;}
        /** No database/session repository call: it must not silently reset target-tenant RLS. */
        public void requireRequestCurrent() {
            if(authentication==null||!authentication.isAuthenticated()
                    ||SecurityContextHolder.getContext().getAuthentication()!=authentication
                    ||!context.equals(contexts.currentTenant())||request.getSession(false)!=session
                    ||lifetime.isExpired(session))throw new UnauthenticatedSessionException();
        }
        @Override public String toString(){return "CapturedLifecycleSession[REDACTED]";}
    }
}
