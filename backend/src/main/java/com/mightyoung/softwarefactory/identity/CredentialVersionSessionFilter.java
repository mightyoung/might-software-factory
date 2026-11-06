package com.mightyoung.softwarefactory.identity;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.Set;

/** A pre-handler generation check; guard-time authorization must still revalidate inside writes. */
final class CredentialVersionSessionFilter extends OncePerRequestFilter {
    private static final Set<String> PUBLIC_AUTH=Set.of("/api/v1/auth/csrf","/api/v1/auth/login","/api/v1/auth/logout");
    private final AccountStatusReadRepository accounts;
    CredentialVersionSessionFilter(AccountStatusReadRepository accounts){this.accounts=accounts;}
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        String path=IdentityCapabilityRoutePolicy.path(request);
        return PUBLIC_AUTH.contains(path);
    }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws IOException,ServletException {
        boolean capability=IdentityCapabilityRoutePolicy.matches(request);
        if(capability)IdentityCapabilityRoutePolicy.noStore(response);
        org.springframework.security.core.Authentication authentication;
        try {authentication=SecurityContextHolder.getContext().getAuthentication();}
        catch(RuntimeException unavailable) {
            if(!capability)throw unavailable;
            SecurityContextHolder.clearContext();
            IdentityHttpResponses.writeError(request,response,503,"DEPENDENCY_UNAVAILABLE","Session validation is temporarily unavailable",true);return;
        }
        if(authentication!=null&&authentication.isAuthenticated()&&authentication.getPrincipal() instanceof VerifiedSessionPrincipal principal) {
            AccountSecurityState state;
            try { state=accounts.findState(principal.accountId()).orElse(null); }
            catch(RuntimeException unavailable) {
                IdentityHttpResponses.writeError(request,response,503,"DEPENDENCY_UNAVAILABLE","Session validation is temporarily unavailable",true);return;
            }
            if(state==null||!"ACTIVE".equals(state.status())||state.credentialVersion()!=principal.credentialVersion()) {
                SecurityContextHolder.clearContext();
                try { var session=request.getSession(false);if(session!=null)session.invalidate(); }
                catch(RuntimeException unavailable) {
                    if(capability) {
                        IdentityHttpResponses.writeError(request,response,503,"DEPENDENCY_UNAVAILABLE","Session validation is temporarily unavailable",true);return;
                    }
                    /* A stale generation must fail even if cleanup is unavailable. */
                }
                if(capability) {
                    IdentityHttpResponses.clearSessionCookie(response);
                    request.setAttribute(IdentityCapabilityRoutePolicy.ANONYMOUS_CLASSIFICATION,
                            IdentityCapabilityRoutePolicy.AnonymousClassification.STALE_CREDENTIAL);
                    chain.doFilter(request,response);return;
                }
                IdentityHttpResponses.clearSessionCookies(response);
                IdentityHttpResponses.writeError(request,response,401,"AUTH_REQUIRED","A current verified session is required",false);return;
            }
        }
        chain.doFilter(request,response);
    }
}
