package com.mightyoung.softwarefactory.identity;

import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.GrantedAuthority;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = AuthenticationSessionController.class)
@Import({SecurityConfiguration.class, SessionContextResolver.class,
        IdentityAuthenticationHttpTest.TestEndpoints.class,
        IdentityAuthenticationHttpTest.TestDependencies.class})
class IdentityAuthenticationHttpTest {

    private static final String CSRF_PATH = "/api/v1/auth/csrf";
    private static final String LOGIN_PATH = "/api/v1/auth/login";
    private static final String LOGOUT_PATH = "/api/v1/auth/logout";
    private static final String CONTEXT_PATH = "/api/v1/test/auth-context";
    private static final UUID ACTOR_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID ACCOUNT_ID = UUID.fromString("11000000-0000-0000-0000-000000000001");
    private static final UUID TENANT_ID = UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final UUID MEMBER_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final String CSRF_TOKEN = "synthetic-csrf-token";

    @Autowired
    private MockMvc mvc;

    @Test
    void csrfBootstrapIsPublicAndIssuesTheDoubleSubmitCookie() throws Exception {
        mvc.perform(get(CSRF_PATH))
                .andExpect(status().isNoContent())
                .andExpect(cookie().exists("XSRF-TOKEN"));
    }

    @Test
    void loginRotatesAnExistingSessionAndStoresOnlyTheVerifiedPrincipal() throws Exception {
        TrackingMockHttpSession oldSession = new TrackingMockHttpSession();
        String oldSessionId = oldSession.getId();

        MvcResult login = mvc.perform(post(LOGIN_PATH)
                        .session(oldSession)
                        .with(request -> {
                            assertThat(request.getSession(false)).isSameAs(oldSession);
                            request.setRequestedSessionIdValid(true);
                            return request;
                        })
                        .with(validCsrf())
                        .contentType("application/json")
                        .content("{\"loginKey\":\"operator-1\",\"password\":\"synthetic-pass\",\"tenantCode\":\"north-plant\"}"))
                .andExpect(status().isNoContent())
                .andExpect(cookie().maxAge("XSRF-TOKEN", 0))
                .andReturn();

        HttpSession session = login.getRequest().getSession(false);
        assertThat(session).isNotNull();
        assertThat(oldSession.changeSessionIdCalls).isEqualTo(1);
        assertThat(session.getId()).isNotEqualTo(oldSessionId);

        mvc.perform(get(CONTEXT_PATH).session((MockHttpSession) session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.actorId").value(ACTOR_ID.toString()))
                .andExpect(jsonPath("$.accountId").value(ACCOUNT_ID.toString()))
                .andExpect(jsonPath("$.tenantId").value(TENANT_ID.toString()))
                .andExpect(jsonPath("$.memberId").value(MEMBER_ID.toString()));
    }

    @Test
    void loginRequiresCsrfBeforeCredentialAuthentication() throws Exception {
        mvc.perform(post(LOGIN_PATH)
                        .contentType("application/json")
                        .content("{\"loginKey\":\"operator-1\",\"password\":\"synthetic-pass\",\"tenantCode\":\"north-plant\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACTION_DENIED"));
    }

    @Test
    void invalidPasswordReturnsGenericFailureWithoutSavingAnAuthenticatedSession() throws Exception {
        MvcResult result = mvc.perform(post(LOGIN_PATH)
                        .with(validCsrf())
                        .contentType("application/json")
                        .content("{\"loginKey\":\"operator-1\",\"password\":\"wrong-pass\",\"tenantCode\":\"north-plant\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"))
                .andExpect(jsonPath("$.message").value("Invalid credentials or account context"))
                .andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain("private account detail", "wrong-pass");
        assertThat(result.getRequest().getSession(false)).isNull();
    }

    @Test
    void logoutRequiresCsrfInvalidatesTheSessionAndClearsTheSecurityContext() throws Exception {
        MockHttpSession session = authenticatedSession();

        mvc.perform(post(LOGOUT_PATH).session(session).with(validCsrf()))
                .andExpect(status().isNoContent())
                .andExpect(cookie().maxAge("SESSION", 0));

        mvc.perform(get(CONTEXT_PATH).session(session))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
    }

    private static MockHttpSession authenticatedSession() {
        MockHttpSession session = new MockHttpSession();
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                VerifiedSessionPrincipalTestFixture.create(
                        ACTOR_ID, ACCOUNT_ID, TENANT_ID, MEMBER_ID, 17L, 23L), null, java.util.List.of()));
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
        return session;
    }

    private static RequestPostProcessor validCsrf() {
        return request -> {
            request.setCookies(new jakarta.servlet.http.Cookie("XSRF-TOKEN", CSRF_TOKEN));
            request.addHeader("X-XSRF-TOKEN", CSRF_TOKEN);
            return request;
        };
    }

    private static final class TrackingMockHttpSession extends MockHttpSession {
        private int changeSessionIdCalls;

        @Override
        public String changeSessionId() {
            changeSessionIdCalls++;
            return super.changeSessionId();
        }
    }

    @RestController
    static class TestEndpoints {
        private final SessionContextResolver resolver;

        TestEndpoints(SessionContextResolver resolver) {
            this.resolver = resolver;
        }

        @GetMapping(CONTEXT_PATH)
        Map<String, String> context() {
            CurrentTenantContext current = resolver.currentTenant();
            Map<String, String> result = new LinkedHashMap<>();
            result.put("actorId", current.actorId().toString());
            result.put("accountId", current.accountId().toString());
            result.put("tenantId", current.tenantId().toString());
            result.put("memberId", current.memberId().toString());
            return result;
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestDependencies {
        @Bean
        AuthenticationProvider localPasswordAuthenticationProvider() {
            return new AuthenticationProvider() {
                @Override
                public Authentication authenticate(Authentication authentication) throws AuthenticationException {
                    char[] credentials = (char[]) authentication.getCredentials();
                    try {
                        if (Arrays.equals(credentials, "wrong-pass".toCharArray())) {
                            throw new org.springframework.security.authentication.BadCredentialsException(
                                    "private account detail for operator-1");
                        }
                    } finally {
                        Arrays.fill(credentials, '\0');
                    }
                    return UsernamePasswordAuthenticationToken.authenticated(
                            VerifiedSessionPrincipalTestFixture.create(
                                    ACTOR_ID, ACCOUNT_ID, TENANT_ID, MEMBER_ID, 17L, 23L),
                            null, java.util.List.<GrantedAuthority>of());
                }

                @Override
                public boolean supports(Class<?> authentication) {
                    return LocalPasswordAuthenticationToken.class.isAssignableFrom(authentication);
                }
            };
        }
    }
}
