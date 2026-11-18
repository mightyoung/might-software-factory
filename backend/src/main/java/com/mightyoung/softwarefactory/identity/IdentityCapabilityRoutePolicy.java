package com.mightyoung.softwarefactory.identity;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Set;

/** Exact server-owned capability boundary, shared by authorization and session validation. */
final class IdentityCapabilityRoutePolicy {
    static final String ANONYMOUS_CLASSIFICATION = IdentityCapabilityRoutePolicy.class.getName()+".anonymous";
    enum AnonymousClassification { STALE_CREDENTIAL, ABSOLUTE_LIFETIME_EXPIRED }
    private static final Set<String> PATHS=Set.of(
            "/api/v1/auth/password-reset-requests",
            "/api/v1/auth/password-resets/consume",
            "/api/v1/auth/invitations/consume");
    private IdentityCapabilityRoutePolicy() { }
    static boolean matches(HttpServletRequest request) {
        return "POST".equals(request.getMethod()) && PATHS.contains(path(request));
    }
    static boolean matchesLifecycleWrite(HttpServletRequest request) {
        if(!"POST".equals(request.getMethod()))return false;
        String path=path(request);
        return PATHS.contains(path)||"/api/v1/account-invitations".equals(path)
                ||path.matches("/api/v1/account-invitations/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/(revoke|reissue)");
    }
    static String path(HttpServletRequest request) {
        String uri=request.getRequestURI(), context=request.getContextPath();
        return uri.startsWith(context)?uri.substring(context.length()):uri;
    }
    static void noStore(HttpServletResponse response) {
        response.setHeader("Cache-Control","no-store");
        response.setHeader("Pragma","no-cache");
    }
}
