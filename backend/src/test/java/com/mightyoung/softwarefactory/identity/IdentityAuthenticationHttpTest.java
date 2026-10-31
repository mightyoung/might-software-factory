package com.mightyoung.softwarefactory.identity;

import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.http.ResponseEntity;
import com.mightyoung.softwarefactory.authorization.EntityReadAccessPolicy;
import com.mightyoung.softwarefactory.authorization.EntityReadGrant;
import com.mightyoung.softwarefactory.authorization.FindingReadAuthorizationSnapshot;
import com.mightyoung.softwarefactory.authorization.PersistentEntityReadAccessPolicy;
import com.mightyoung.softwarefactory.authorization.PersistentEntityReadAuthorizationRepository;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionOperations;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {AuthenticationSessionController.class, CurrentSessionController.class})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Import({SecurityConfiguration.class, SessionContextResolver.class,
        CurrentSessionService.class,
        com.mightyoung.softwarefactory.dualprevention.FindingApiExceptionHandler.class,
        IdentityAuthenticationHttpTest.TestEndpoints.class,
        IdentityAuthenticationHttpTest.TestDependencies.class})
class IdentityAuthenticationHttpTest {

    private static final String CSRF_PATH = "/api/v1/auth/csrf";
    private static final String LOGIN_PATH = "/api/v1/auth/login";
    private static final String LOGOUT_PATH = "/api/v1/auth/logout";
    private static final String CONTEXT_PATH = "/api/v1/test/auth-context";
    private static final String FINDING_READ_PATH = "/api/v1/test/finding-read";
    private static final String CURRENT_SESSION_PATH = "/api/v1/session/current";
    private static final UUID ACTOR_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID ACCOUNT_ID = UUID.fromString("11000000-0000-0000-0000-000000000001");
    private static final UUID TENANT_ID = UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final UUID MEMBER_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final String CSRF_TOKEN = "synthetic-csrf-token";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private AuthorizationSnapshotState authorizationSnapshotState;

    @Autowired
    private CurrentSessionReadState currentSessionReadState;

    @BeforeEach
    void resetAuthorizationSnapshot() {
        authorizationSnapshotState.snapshot.set(snapshot("ACTIVE", "HUMAN", ACTOR_ID, ACCOUNT_ID));
        currentSessionReadState.current.set(Optional.of(syntheticCurrentSession()));
    }

    @Test
    void csrfBootstrapIsPublicAndIssuesTheDoubleSubmitCookie() throws Exception {
        mvc.perform(get(CSRF_PATH))
                .andExpect(status().isNoContent())
                .andExpect(cookie().exists("XSRF-TOKEN"));
    }

    @Test
    void currentSessionEndpointReturnsTheVerifiedCurrentMembership() throws Exception {
        MockHttpSession session = authenticatedSession();

        mvc.perform(get(CURRENT_SESSION_PATH).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.actorId").value(ACTOR_ID.toString()))
                .andExpect(jsonPath("$.data.accountId").value(ACCOUNT_ID.toString()))
                .andExpect(jsonPath("$.data.tenantId").value(TENANT_ID.toString()))
                .andExpect(jsonPath("$.data.tenantCode").value("north-plant"))
                .andExpect(jsonPath("$.data.tenantName").value("North plant"))
                .andExpect(jsonPath("$.data.memberId").value(MEMBER_ID.toString()))
                .andExpect(jsonPath("$.data.displayName").value("Synthetic Operator"));
    }

    @Test
    void currentSessionEndpointUses401ForMissingSessionAnd403ForRevokedMembership() throws Exception {
        mvc.perform(get(CURRENT_SESSION_PATH))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));

        currentSessionReadState.current.set(Optional.empty());
        mvc.perform(get(CURRENT_SESSION_PATH).session(authenticatedSession()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACTION_DENIED"));
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
    @Order(2)
    void disabledPrincipalRevokesFindingReadFromAnAlreadyIssuedSession() throws Exception {
        MockHttpSession session = loginAndReturnSession();

        mvc.perform(get(FINDING_READ_PATH).session(session))
                .andExpect(status().isOk());

        authorizationSnapshotState.snapshot.set(snapshot("DISABLED", "HUMAN", ACTOR_ID, ACCOUNT_ID));

        mvc.perform(get(FINDING_READ_PATH).session(session))
                .andExpect(status().isForbidden());
    }

    @Test
    @Order(1)
    void principalAccountMismatchRevokesFindingReadFromAnAlreadyIssuedSession() throws Exception {
        MockHttpSession session = loginAndReturnSession();

        authorizationSnapshotState.snapshot.set(snapshot("ACTIVE", "HUMAN", ACTOR_ID,
                UUID.fromString("11000000-0000-0000-0000-000000000099")));

        mvc.perform(get(FINDING_READ_PATH).session(session))
                .andExpect(status().isForbidden());
    }

    private MockHttpSession loginAndReturnSession() throws Exception {
        MvcResult login = mvc.perform(post(LOGIN_PATH)
                        .with(validCsrf())
                        .contentType("application/json")
                        .content("{\"loginKey\":\"operator-1\",\"password\":\"synthetic-pass\",\"tenantCode\":\"north-plant\"}"))
                .andExpect(status().isNoContent())
                .andReturn();
        return (MockHttpSession) login.getRequest().getSession(false);
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
    void malformedJsonReturnsGenericBadRequestWithoutParserDetails() throws Exception {
        assertLoginBodyRejected("{malformed");
    }

    @Test
    void wrongJsonFieldTypesReturnGenericBadRequestWithoutParserDetails() throws Exception {
        assertLoginBodyRejected("{\"loginKey\":[],\"password\":\"synthetic-pass\",\"tenantCode\":\"north-plant\"}");
        assertLoginBodyRejected("{\"loginKey\":\"operator-1\",\"password\":{},\"tenantCode\":\"north-plant\"}");
    }

    private void assertLoginBodyRejected(String body) throws Exception {
        MvcResult result = mvc.perform(post(LOGIN_PATH)
                        .with(validCsrf())
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_QUERY"))
                .andExpect(jsonPath("$.message").value("The login request is invalid"))
                .andReturn();
        assertThat(result.getResponse().getContentAsString())
                .doesNotContain("MismatchedInput", "JacksonException", "line", "column", "synthetic-pass");
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

    @Test
    void logoutWithoutAnActiveSessionIsIdempotentButStillRequiresCsrf() throws Exception {
        mvc.perform(post(LOGOUT_PATH))
                .andExpect(status().isForbidden());

        mvc.perform(post(LOGOUT_PATH).with(validCsrf()))
                .andExpect(status().isNoContent())
                .andExpect(cookie().maxAge("SESSION", 0));
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
        private final EntityReadAccessPolicy readAccessPolicy;

        TestEndpoints(SessionContextResolver resolver, EntityReadAccessPolicy readAccessPolicy) {
            this.resolver = resolver;
            this.readAccessPolicy = readAccessPolicy;
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

        @GetMapping(FINDING_READ_PATH)
        ResponseEntity<Map<String, Object>> findingRead() {
            CurrentTenantContext current = resolver.currentTenant();
            Optional<EntityReadGrant> grant = readAccessPolicy.resolveEntityReadGrant(current, "finding");
            return grant.<ResponseEntity<Map<String, Object>>>map(value -> ResponseEntity.ok(
                    Map.of("tenantId", value.tenantId().toString(), "organizations", value.organizationIds())))
                    .orElseGet(() -> ResponseEntity.status(403).body(Map.of("code", "ACTION_DENIED")));
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestDependencies {
        @Bean
        CurrentSessionReadState currentSessionReadState() {
            return new CurrentSessionReadState();
        }

        @Bean
        CurrentSessionReadRepository currentSessionReadRepository(CurrentSessionReadState state) {
            return context -> state.current.get();
        }

        /** Exercise the production provider/filter wiring against in-memory synthetic identity rows. */
        @Bean
        AuthenticationProvider localPasswordAuthenticationProvider(PasswordEncoder passwordEncoder) {
            AccountPasswordCredentialReadRepository credentials = loginKey ->
                    "operator-1".equals(loginKey)
                            ? Optional.of(new LocalPasswordCredential(ACTOR_ID, ACCOUNT_ID, "ACTIVE", "ACTIVE",
                                    passwordEncoder.encode("synthetic-pass")))
                            : Optional.empty();
            TenantLoginContextResolver tenantContexts = (accountId, tenantCode) ->
                    ACCOUNT_ID.equals(accountId) && "north-plant".equals(tenantCode)
                            ? Optional.of(new TenantLoginContext(TENANT_ID, MEMBER_ID,
                                    "ACTIVE", "ACTIVE", 17L, 23L))
                            : Optional.empty();
            TransactionOperations transactions = new TransactionOperations() {
                @Override
                public <T> T execute(org.springframework.transaction.support.TransactionCallback<T> action) {
                    return action.doInTransaction(new SimpleTransactionStatus());
                }
            };
            return new LocalPasswordAuthenticationProvider(credentials, tenantContexts, passwordEncoder, transactions);
        }

        @Bean
        AuthorizationSnapshotState authorizationSnapshotState() {
            return new AuthorizationSnapshotState();
        }

        @Bean
        PersistentEntityReadAuthorizationRepository syntheticAuthorizationRepository(
                AuthorizationSnapshotState state) {
            return (context, permissionCode) -> Optional.of(state.snapshot.get());
        }

        @Bean
        EntityReadAccessPolicy entityReadAccessPolicy(PersistentEntityReadAuthorizationRepository repository) {
            return new PersistentEntityReadAccessPolicy(repository);
        }
    }

    static final class AuthorizationSnapshotState {
        private final AtomicReference<FindingReadAuthorizationSnapshot> snapshot =
                new AtomicReference<>(snapshot("ACTIVE", "HUMAN", ACTOR_ID, ACCOUNT_ID));
    }

    static final class CurrentSessionReadState {
        private final AtomicReference<Optional<CurrentSessionDetails>> current =
                new AtomicReference<>(Optional.of(syntheticCurrentSession()));
    }

    private static CurrentSessionDetails syntheticCurrentSession() {
        return new CurrentSessionDetails(
                ACTOR_ID, ACCOUNT_ID, TENANT_ID, "north-plant", "North plant", MEMBER_ID, "Synthetic Operator");
    }

    private static FindingReadAuthorizationSnapshot snapshot(
            String principalStatus, String principalKind, UUID principalId, UUID principalAccountId) {
        return new FindingReadAuthorizationSnapshot(
                principalId, principalAccountId, principalKind, principalStatus,
                "ACTIVE", "ACTIVE", "ACTIVE", 17L, 23L, Set.of(
                        UUID.fromString("31000000-0000-0000-0000-000000000001")));
    }
}
