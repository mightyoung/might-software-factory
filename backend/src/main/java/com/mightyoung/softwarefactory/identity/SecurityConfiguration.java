package com.mightyoung.softwarefactory.identity;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.boot.session.autoconfigure.DefaultCookieSerializerCustomizer;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;
import java.util.Map;
import java.util.function.Supplier;
import java.time.Clock;
import java.time.Duration;

/**
 * Session and CSRF boundary for the management API. Local password login installs only the
 * server-verified {@link VerifiedSessionPrincipal}; Spring's generated development user is disabled.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
public class SecurityConfiguration {

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            CookieCsrfTokenRepository csrfTokenRepository,
            SecurityContextRepository securityContextRepository,
            LocalPasswordAuthenticationFilter loginFilter,
            SessionLifetimePolicy sessionLifetime)
            throws Exception {
        return http
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfTokenRepository)
                        .requireCsrfProtectionMatcher(request -> switch (request.getMethod()) {
                            case "GET", "HEAD", "TRACE", "OPTIONS" -> false;
                            default -> true;
                        })
                        .csrfTokenRequestHandler(new EagerCookieCsrfTokenRequestHandler()))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/api/v1/auth/csrf", "/api/v1/auth/login").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, exception) -> IdentityHttpResponses.writeError(
                                response, HttpServletResponse.SC_UNAUTHORIZED, "AUTH_REQUIRED",
                                "A verified session is required", false))
                        .accessDeniedHandler((request, response, exception) -> IdentityHttpResponses.writeError(
                                response, HttpServletResponse.SC_FORBIDDEN, "ACTION_DENIED",
                                "The request is not allowed", false)))
                .requestCache(cache -> cache.disable())
                .securityContext(context -> context.securityContextRepository(securityContextRepository))
                .sessionManagement(session -> session.sessionFixation(fixation -> fixation.changeSessionId()))
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout
                        .logoutUrl("/api/v1/auth/logout")
                        .clearAuthentication(true)
                        .invalidateHttpSession(true)
                        .deleteCookies("SESSION", "XSRF-TOKEN")
                        .logoutSuccessHandler((request, response, authentication) ->
                                IdentityHttpResponses.noContent(response)))
                .addFilterAt(loginFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(new AbsoluteSessionLifetimeFilter(sessionLifetime), SecurityContextHolderFilter.class)
                .build();
    }

    @Bean
    SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    LoginRequestAuthenticationConverter loginRequestAuthenticationConverter(ObjectMapper objectMapper) {
        return new LoginRequestAuthenticationConverter(objectMapper);
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new DelegatingPasswordEncoder("bcrypt", Map.of("bcrypt", new BCryptPasswordEncoder(12)));
    }

    @Bean
    SessionLifetimePolicy sessionLifetimePolicy(ObjectProvider<Clock> clocks,
            @Value("${software-factory.security.session.absolute-timeout:12h}") Duration absoluteTimeout) {
        return new SessionLifetimePolicy(clocks.getIfAvailable(Clock::systemUTC), absoluteTimeout);
    }

    @Bean
    LocalPasswordAuthenticationFilter localPasswordAuthenticationFilter(
            @Qualifier("localPasswordAuthenticationProvider") AuthenticationProvider provider,
            LoginRequestAuthenticationConverter converter,
            SecurityContextRepository securityContextRepository,
            CookieCsrfTokenRepository csrfTokenRepository,
            SessionLifetimePolicy sessionLifetime) {
        return new LocalPasswordAuthenticationFilter(provider, converter, securityContextRepository,
                csrfTokenRepository, sessionLifetime);
    }

    @Bean
    FilterRegistrationBean<LocalPasswordAuthenticationFilter> disableServletRegistration(
            LocalPasswordAuthenticationFilter loginFilter) {
        FilterRegistrationBean<LocalPasswordAuthenticationFilter> registration = new FilterRegistrationBean<>(loginFilter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    CookieCsrfTokenRepository csrfTokenRepository() {
        CookieCsrfTokenRepository repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        repository.setCookiePath("/");
        repository.setCookieCustomizer(cookie -> cookie.secure(true).sameSite("Lax"));
        return repository;
    }

    /** Suppresses Spring Boot's generated in-memory development user. */
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

    private static final class EagerCookieCsrfTokenRequestHandler extends CsrfTokenRequestAttributeHandler {
        @Override
        public void handle(jakarta.servlet.http.HttpServletRequest request,
                HttpServletResponse response, Supplier<CsrfToken> deferredCsrfToken) {
            CsrfToken token = deferredCsrfToken.get();
            super.handle(request, response, () -> token);
        }
    }
}
