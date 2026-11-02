package com.mightyoung.softwarefactory.identity;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.authentication.session.CompositeSessionAuthenticationStrategy;
import org.springframework.security.web.csrf.CsrfAuthenticationStrategy;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.authentication.AbstractAuthenticationProcessingFilter;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

import java.util.List;

/** JSON login filter; the session principal is saved only after provider validation succeeds. */
final class LocalPasswordAuthenticationFilter extends AbstractAuthenticationProcessingFilter {

    LocalPasswordAuthenticationFilter(
            AuthenticationProvider provider,
            LoginRequestAuthenticationConverter converter,
            SecurityContextRepository securityContextRepository,
            CsrfTokenRepository csrfTokenRepository,
            SessionLifetimePolicy sessionLifetime) {
        super(PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/api/v1/auth/login"),
                new ProviderManager(provider));
        setAuthenticationConverter(converter);
        setSessionAuthenticationStrategy(new CompositeSessionAuthenticationStrategy(List.of(
                new ChangeSessionIdAuthenticationStrategy(),
                new CsrfAuthenticationStrategy(csrfTokenRepository))));
        setSecurityContextRepository(securityContextRepository);
        setAuthenticationSuccessHandler((request, response, authentication) -> {
            sessionLifetime.markAuthenticated(request.getSession(true));
            IdentityHttpResponses.noContent(response);
        });
        setAuthenticationFailureHandler((request, response, exception) -> {
            if (exception instanceof InvalidLoginRequestException) {
                IdentityHttpResponses.writeError(response, HttpServletResponse.SC_BAD_REQUEST,
                        "INVALID_QUERY", "The login request is invalid", false);
            } else if (exception instanceof AuthenticationServiceException) {
                IdentityHttpResponses.writeError(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                        "DEPENDENCY_UNAVAILABLE", "Authentication could not be completed", true);
            } else {
                IdentityHttpResponses.writeError(response, HttpServletResponse.SC_UNAUTHORIZED,
                        "AUTH_REQUIRED", "Invalid credentials or account context", false);
            }
        });
    }
}
