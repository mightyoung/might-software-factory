package com.mightyoung.softwarefactory.identity;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** Removes expired authentication before CSRF, authorization or any application handler runs. */
final class AbsoluteSessionLifetimeFilter extends OncePerRequestFilter {

    private final SessionLifetimePolicy lifetime;

    AbsoluteSessionLifetimeFilter(SessionLifetimePolicy lifetime) {
        this.lifetime = lifetime;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        boolean capability=IdentityCapabilityRoutePolicy.matches(request);
        if(capability)IdentityCapabilityRoutePolicy.noStore(response);
        Authentication authentication;
        try {authentication=SecurityContextHolder.getContext().getAuthentication();}
        catch(RuntimeException unavailable) {
            if(!capability)throw unavailable;
            SecurityContextHolder.clearContext();
            IdentityHttpResponses.writeError(request,response,503,"DEPENDENCY_UNAVAILABLE","Session validation is temporarily unavailable",true);return;
        }
        if (authentication != null && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof VerifiedSessionPrincipal) {
            HttpSession session;
            boolean expired;
            try {session=request.getSession(false);expired=lifetime.isExpired(session);}
            catch(RuntimeException unavailable) {
                if(!capability)throw unavailable;
                SecurityContextHolder.clearContext();
                IdentityHttpResponses.writeError(request,response,503,"DEPENDENCY_UNAVAILABLE","Session validation is temporarily unavailable",true);return;
            }
            if (expired) {
                try {
                    if (session != null) session.invalidate();
                } catch (RuntimeException unavailable) {
                    if(capability) {
                        SecurityContextHolder.clearContext();
                        IdentityHttpResponses.writeError(request,response,503,"DEPENDENCY_UNAVAILABLE","Session validation is temporarily unavailable",true);return;
                    }
                    if(!(unavailable instanceof IllegalStateException))throw unavailable;
                    // Concurrent invalidation still requires this request to lose its authentication.
                }
                SecurityContextHolder.clearContext();
                if(capability) {
                    IdentityHttpResponses.clearSessionCookie(response);
                    request.setAttribute(IdentityCapabilityRoutePolicy.ANONYMOUS_CLASSIFICATION,
                            IdentityCapabilityRoutePolicy.AnonymousClassification.ABSOLUTE_LIFETIME_EXPIRED);
                }
            }
        }
        chain.doFilter(request, response);
    }
}
