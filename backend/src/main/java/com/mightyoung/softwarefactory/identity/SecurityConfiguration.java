package com.mightyoung.softwarefactory.identity;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.boot.session.autoconfigure.DefaultCookieSerializerCustomizer;

import java.io.IOException;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Session and CSRF boundary for the management API. This slice intentionally has no login provider:
 * an authenticated request must already carry a server-issued SecurityContext whose principal is
 * {@link VerifiedSessionPrincipal}. Spring's generated development user is disabled.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
public class SecurityConfiguration {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, CookieCsrfTokenRepository csrfTokenRepository)
            throws Exception {
        return http
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfTokenRepository)
                        .csrfTokenRequestHandler(new EagerCookieCsrfTokenRequestHandler()))
                .authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated())
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, exception) -> writeError(
                                response, HttpServletResponse.SC_UNAUTHORIZED, "AUTH_REQUIRED",
                                "A verified session is required"))
                        .accessDeniedHandler((request, response, exception) -> writeError(
                                response, HttpServletResponse.SC_FORBIDDEN, "ACTION_DENIED",
                                "The request is not allowed")))
                .requestCache(cache -> cache.disable())
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable())
                .build();
    }

    @Bean
    CookieCsrfTokenRepository csrfTokenRepository() {
        CookieCsrfTokenRepository repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        repository.setCookiePath("/");
        repository.setCookieCustomizer(cookie -> cookie.secure(true).sameSite("Lax"));
        return repository;
    }

    /** Suppresses Spring Boot's generated in-memory development user; no password login is wired. */
    @Bean
    UserDetailsService noInteractiveLoginConfigured() {
        return username -> {
            throw new UsernameNotFoundException("No interactive login provider is configured");
        };
    }

    @Bean
    DefaultCookieSerializerCustomizer sessionCookiePolicy() {
        return serializer -> {
            serializer.setCookieName("SESSION");
            serializer.setCookiePath("/");
            serializer.setUseHttpOnlyCookie(true);
            serializer.setUseSecureCookie(true);
            serializer.setSameSite("Lax");
        };
    }

    private static void writeError(HttpServletResponse response, int status, String code, String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        String id = UUID.randomUUID().toString();
        response.getWriter().write("{\"code\":\"" + code + "\",\"message\":\"" + message
                + "\",\"traceId\":\"" + id + "\",\"retryable\":false,\"fieldErrors\":[]}");
    }

    private static final class EagerCookieCsrfTokenRequestHandler extends CsrfTokenRequestAttributeHandler {
        @Override
        public void handle(jakarta.servlet.http.HttpServletRequest request,
                HttpServletResponse response, Supplier<CsrfToken> deferredCsrfToken) {
            CsrfToken token = deferredCsrfToken.get();
            super.handle(request, response, () -> token);
        }
    }
}
