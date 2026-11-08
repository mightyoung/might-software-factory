package com.mightyoung.softwarefactory.identity;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.dao.DataAccessException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.TransactionException;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;

/** Outside Spring Session: an uncertain save never retries an already executed capability handler. */
final class IdentityLifecycleSessionPersistenceFilter extends OncePerRequestFilter {
    @Override protected boolean shouldNotFilter(HttpServletRequest request){return !IdentityCapabilityRoutePolicy.matchesLifecycleWrite(request);}
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws IOException,ServletException {
        IdentityCapabilityRoutePolicy.noStore(response);
        try {chain.doFilter(request,response);}
        catch(DataAccessException|TransactionException unavailable) {
            SecurityContextHolder.clearContext();
            if(response.isCommitted())throw unavailable;
            Object trace=request.getAttribute(IdentityLifecycleController.TRACE_ATTRIBUTE);
            LoginSessionPersistenceFailureFilter.lifecycleFailure(response,trace instanceof java.util.UUID,
                    trace instanceof java.util.UUID serverTrace?serverTrace:null);
        }
    }
}
