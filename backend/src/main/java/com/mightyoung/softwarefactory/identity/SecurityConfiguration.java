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
import org.springframework.security.web.csrf.CsrfTokenRepository;
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
            CsrfTokenRepository csrfTokenRepository,
            SecurityContextRepository securityContextRepository,
            LocalPasswordAuthenticationFilter loginFilter,
            SessionLifetimePolicy sessionLifetime,
            AccountStatusReadRepository accountStatusRepository)
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
                        .requestMatchers(IdentityCapabilityRoutePolicy::matches).permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, exception) -> IdentityHttpResponses.writeError(
                                request, response, HttpServletResponse.SC_UNAUTHORIZED, "AUTH_REQUIRED",
                                "A verified session is required", false))
                        .accessDeniedHandler((request, response, exception) -> IdentityHttpResponses.writeError(
                                request, response, HttpServletResponse.SC_FORBIDDEN, "ACTION_DENIED",
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
                .addFilterAfter(new CredentialVersionSessionFilter(accountStatusRepository), AbsoluteSessionLifetimeFilter.class)
                .build();
    }

    @Bean
    FilterRegistrationBean<LoginAuditFilter> loginAuditBoundary(
            org.springframework.core.env.Environment environment, AuthenticationEvents events,
            ObjectProvider<Clock> clocks,
            @Value("${software-factory.security.login-audit.max-concurrent:8}") int maximumConcurrent) {
        int sessionOrder = environment.getProperty("spring.session.servlet.filter-order", Integer.class,
                org.springframework.session.web.http.SessionRepositoryFilter.DEFAULT_ORDER);
        if (sessionOrder < Integer.MIN_VALUE + 2) {
            throw new IllegalStateException("Session filter order must allow both login response boundaries");
        }
        var registration = new FilterRegistrationBean<>(new LoginAuditFilter(events,
                clocks.getIfAvailable(Clock::systemUTC), maximumConcurrent));
        registration.setOrder(sessionOrder - 2);
        registration.setDispatcherTypes(jakarta.servlet.DispatcherType.REQUEST);
        // Other routes retain their async support; only the wrapped login request forbids it.
        registration.setAsyncSupported(true);
        return registration;
    }

    @Bean
    FilterRegistrationBean<LoginSessionPersistenceFailureFilter> loginSessionPersistenceFailureBoundary(
            org.springframework.core.env.Environment environment) {
        int sessionOrder = environment.getProperty("spring.session.servlet.filter-order", Integer.class,
                org.springframework.session.web.http.SessionRepositoryFilter.DEFAULT_ORDER);
        if (sessionOrder == Integer.MIN_VALUE) {
            throw new IllegalStateException("Session filter order must allow an outer login persistence boundary");
        }
        var registration = new FilterRegistrationBean<>(new LoginSessionPersistenceFailureFilter());
        registration.setOrder(sessionOrder - 1);
        registration.setDispatcherTypes(jakarta.servlet.DispatcherType.REQUEST);
        return registration;
    }

    @Bean
    FilterRegistrationBean<IdentityLifecycleSessionPersistenceFilter> lifecycleSessionPersistenceFailureBoundary(
            org.springframework.core.env.Environment environment) {
        int sessionOrder=environment.getProperty("spring.session.servlet.filter-order",Integer.class,
                org.springframework.session.web.http.SessionRepositoryFilter.DEFAULT_ORDER);
        if(sessionOrder==Integer.MIN_VALUE)throw new IllegalStateException("Session filter order must allow a capability persistence boundary");
        var registration=new FilterRegistrationBean<>(new IdentityLifecycleSessionPersistenceFilter());
        registration.setOrder(sessionOrder-1);
        registration.setDispatcherTypes(jakarta.servlet.DispatcherType.REQUEST);
        return registration;
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
    SessionSensitiveRequests sessionSensitiveRequests(SessionContextResolver contexts,
            @Value("${software-factory.security.session.absolute-timeout:12h}") Duration absoluteTimeout) {
        return new SessionSensitiveRequests(contexts,absoluteTimeout);
    }

    @Bean
    SessionSensitiveKeyProvider sessionSensitiveKeyProvider(ObjectProvider<SessionSensitiveKeys> material) {
        return () -> {
            var available=material.stream().limit(2).toList();
            if(available.size()!=1)throw SessionSensitiveException.unavailable();
            return available.getFirst();
        };
    }

    @Bean
    LoginAttemptLimiter loginAttemptLimiter(
            @Value("${software-factory.security.login-throttle.global-attempts:600}") int globalAttempts,
            @Value("${software-factory.security.login-throttle.source-attempts:60}") int sourceAttempts,
            @Value("${software-factory.security.login-throttle.attempt-window:60s}") Duration attemptWindow,
            @Value("${software-factory.security.login-throttle.failure-threshold:5}") int failureThreshold,
            @Value("${software-factory.security.login-throttle.failure-window:5m}") Duration failureWindow,
            @Value("${software-factory.security.login-throttle.cooldown:30s}") Duration cooldown,
            @Value("${software-factory.security.login-throttle.max-concurrent:8}") int maxConcurrent,
            @Value("${software-factory.security.login-throttle.max-sources:4096}") int maxSources,
            @Value("${software-factory.security.login-throttle.max-pairs:8192}") int maxPairs) {
        return new LoginAttemptLimiter(new LoginAttemptLimiter.Config(globalAttempts, sourceAttempts, attemptWindow,
                failureThreshold, failureWindow, cooldown, maxConcurrent, maxSources, maxPairs), System::nanoTime);
    }

    @Bean
    LocalPasswordAuthenticationFilter localPasswordAuthenticationFilter(
            @Qualifier("localPasswordAuthenticationProvider") AuthenticationProvider provider,
            LoginRequestAuthenticationConverter converter,
            SecurityContextRepository securityContextRepository,
            CsrfTokenRepository csrfTokenRepository,
            SessionLifetimePolicy sessionLifetime,
            LoginAttemptLimiter attempts) {
        return new LocalPasswordAuthenticationFilter(provider, converter, securityContextRepository,
                csrfTokenRepository, sessionLifetime, attempts);
    }

    @Bean
    FilterRegistrationBean<LocalPasswordAuthenticationFilter> disableServletRegistration(
            LocalPasswordAuthenticationFilter loginFilter) {
        FilterRegistrationBean<LocalPasswordAuthenticationFilter> registration = new FilterRegistrationBean<>(loginFilter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    CsrfTokenRepository csrfTokenRepository() {
        return new ExplicitHeaderCsrfTokenRepository();
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
