package com.mightyoung.softwarefactory.identity;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/** Resolves a verified session principal and rejects ordinary or anonymous principals. */
@Component
public final class SessionContextResolver {

    public CurrentTenantContext currentTenant() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken
                || !(authentication.getPrincipal() instanceof VerifiedSessionPrincipal principal)) {
            throw new UnauthenticatedSessionException();
        }
        return new CurrentTenantContext(principal.actorId(), principal.tenantId(), principal.memberId());
    }
}
