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
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof VerifiedSessionPrincipal) {
            HttpSession session = request.getSession(false);
            if (lifetime.isExpired(session)) {
                try {
                    if (session != null) session.invalidate();
                } catch (IllegalStateException alreadyInvalidated) {
                    // Concurrent invalidation still requires this request to lose its authentication.
                }
                SecurityContextHolder.clearContext();
            }
        }
        chain.doFilter(request, response);
    }
}
