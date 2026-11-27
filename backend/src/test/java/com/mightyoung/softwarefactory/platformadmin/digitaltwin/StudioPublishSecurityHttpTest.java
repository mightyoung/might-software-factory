package com.mightyoung.softwarefactory.platformadmin.digitaltwin;

import com.mightyoung.softwarefactory.identity.AccountSecurityState;
import com.mightyoung.softwarefactory.identity.AccountStatusReadRepository;
import com.mightyoung.softwarefactory.identity.AuthenticationEvents;
import com.mightyoung.softwarefactory.identity.AuthenticationSessionController;
import com.mightyoung.softwarefactory.identity.CapturedHumanRequest;
import com.mightyoung.softwarefactory.identity.LocalPasswordAuthenticationProvider;
import com.mightyoung.softwarefactory.identity.LocalPasswordCredential;
import com.mightyoung.softwarefactory.identity.SecurityConfiguration;
import com.mightyoung.softwarefactory.identity.SessionContextResolver;
import com.mightyoung.softwarefactory.identity.SessionSensitiveRequests;
import com.mightyoung.softwarefactory.identity.TenantLoginContext;
import com.mightyoung.softwarefactory.identity.VerifiedSessionPrincipalTestFixture;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static com.mightyoung.softwarefactory.platformadmin.digitaltwin.StudioPublicationPolicy.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = {AuthenticationSessionController.class, StudioPublishSecurityHttpTest.TestBridge.class})
@Import({SecurityConfiguration.class, SessionContextResolver.class,
        StudioPublishSecurityHttpTest.TestBridge.class, StudioPublishSecurityHttpTest.Dependencies.class})
class StudioPublishSecurityHttpTest {
    private static final String PATH = "/api/v1/admin/studio/screens/floor/publish";
    private static final UUID ACTOR = UUID.randomUUID();
    private static final UUID ACCOUNT = UUID.randomUUID();
    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID MEMBER = UUID.randomUUID();
    private static final String BODY = "{\"snapshotVersion\":1}";
    private static final String DIGEST = "b".repeat(64);

    @Autowired MockMvc mvc;
    @Autowired StudioPublish owner;
    @Autowired AccountStatusReadRepository accounts;

    @BeforeEach void resetDoubles() {
        reset(owner, accounts);
        when(accounts.findState(ACCOUNT)).thenReturn(Optional.of(new AccountSecurityState("ACTIVE", 1)));
    }

    @Test void loginAndMatchingCsrfReachOwnerWithVerifiedIdentityAndReturnReceipt() throws Exception {
        var session = login();
        var token = csrf();
        when(owner.publish(any(), eq(new Target(TENANT, "floor")), eq(0L),
                aryEq(BODY.getBytes(StandardCharsets.UTF_8)))).thenAnswer(call -> {
            var captured = call.<CapturedHumanRequest>getArgument(0).context();
            assertEquals(ACTOR, captured.actorId());
            assertEquals(ACCOUNT, captured.accountId());
            assertEquals(TENANT, captured.tenantId());
            assertEquals(MEMBER, captured.memberId());
            return new Decision(Outcome.APPEND, 1, DIGEST, new Metrics(1, 0, 0, 0, 0));
        });
        mvc.perform(withCsrf(publish().session(session), token).header("X-Tenant-Id", UUID.randomUUID()))
                .andExpect(status().isCreated())
                .andExpect(header().string("Cache-Control", "private, no-store"))
                .andExpect(jsonPath("$.outcome").value("APPEND"))
                .andExpect(jsonPath("$.version").value("1"))
                .andExpect(jsonPath("$.sha256").value(DIGEST));
        verify(owner).publish(any(), eq(new Target(TENANT, "floor")), eq(0L), any());
    }

    @Test void validCsrfWithoutVerifiedSessionDoesNotReachOwner() throws Exception {
        mvc.perform(withCsrf(publish(), csrf()))
                .andExpect(status().isUnauthorized()).andExpect(result -> assertNull(result.getHandler())).andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
        verifyNoInteractions(owner);
    }

    @Test void loginAloneDoesNotAllowCookieOnlyOrMismatchedCsrf() throws Exception {
        var session = login();
        var token = csrf();
        mvc.perform(publish().session(session).cookie(token))
                .andExpect(status().isForbidden()).andExpect(result -> assertNull(result.getHandler())).andExpect(jsonPath("$.code").value("ACTION_DENIED"));
        mvc.perform(publish().session(session).cookie(token).header("X-XSRF-TOKEN", csrf().getValue()))
                .andExpect(status().isForbidden()).andExpect(result -> assertNull(result.getHandler())).andExpect(jsonPath("$.code").value("ACTION_DENIED"));
        verifyNoInteractions(owner);
    }

    @Test void expiredSessionCannotReachOwnerWithCsrf() throws Exception {
        var session = login();
        VerifiedSessionPrincipalTestFixture.markAuthenticatedSession(session, Instant.EPOCH);
        mvc.perform(withCsrf(publish().session(session), csrf()))
                .andExpect(status().isUnauthorized()).andExpect(result -> assertNull(result.getHandler()));
        verifyNoInteractions(owner);
    }

    @Test void oldCredentialGenerationCannotReachOwnerWithCsrf() throws Exception {
        var session = login();
        when(accounts.findState(ACCOUNT)).thenReturn(Optional.of(new AccountSecurityState("ACTIVE", 2)));
        mvc.perform(withCsrf(publish().session(session), csrf()))
                .andExpect(status().isUnauthorized()).andExpect(result -> assertNull(result.getHandler())).andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
        verifyNoInteractions(owner);
    }

    private MockHttpSession login() throws Exception {
        var session = (MockHttpSession) mvc.perform(withCsrf(post("/api/v1/auth/login"), csrf())
                .contentType("application/json")
                .content("{\"loginKey\":\"studio-fixture\",\"password\":\"synthetic-pass\",\"tenantCode\":\"north-plant\"}"))
                .andExpect(status().isNoContent()).andReturn().getRequest().getSession(false);
        assertNotNull(session);
        return session;
    }

    private Cookie csrf() throws Exception {
        var token = mvc.perform(get("/api/v1/auth/csrf"))
                .andExpect(status().isNoContent()).andExpect(cookie().exists("XSRF-TOKEN"))
                .andReturn().getResponse().getCookie("XSRF-TOKEN");
        assertNotNull(token);
        return token;
    }

    private static MockHttpServletRequestBuilder withCsrf(MockHttpServletRequestBuilder request, Cookie token) {
        return request.cookie(token).header("X-XSRF-TOKEN", token.getValue());
    }

    private static MockHttpServletRequestBuilder publish() {
        return post(PATH).contentType("application/json").content(BODY)
                .header(StudioPublishInput.EXPECTED_VERSION, "0");
    }

    /** Only this test context maps a URL. The adapter and publication owner remain dormant. */
    @RestController
    static class TestBridge {
        private final StudioPublishHttp adapter;

        TestBridge(SessionSensitiveRequests requests, StudioPublish owner) {
            adapter = new StudioPublishHttp(requests, owner);
        }

        @PostMapping(PATH)
        void post(HttpServletRequest request, HttpServletResponse response) throws IOException {
            adapter.postAfterSecurity("floor", request, response);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Dependencies {
        @Bean StudioPublish owner() { return mock(StudioPublish.class); }
        @Bean AccountStatusReadRepository accounts() { return mock(AccountStatusReadRepository.class); }
        @Bean AuthenticationEvents authenticationEvents() { return event -> {}; }

        @Bean
        AuthenticationProvider localPasswordAuthenticationProvider(PasswordEncoder encoder) {
            var hash = encoder.encode("synthetic-pass");
            var transactions = mock(PlatformTransactionManager.class);
            when(transactions.getTransaction(any())).thenAnswer(call -> new SimpleTransactionStatus());
            return new LocalPasswordAuthenticationProvider(
                    login -> "studio-fixture".equals(login)
                            ? Optional.of(new LocalPasswordCredential(ACTOR, ACCOUNT, "ACTIVE", "ACTIVE", 1, hash))
                            : Optional.empty(),
                    (account, tenant) -> ACCOUNT.equals(account) && "north-plant".equals(tenant)
                            ? Optional.of(new TenantLoginContext(TENANT, MEMBER, "ACTIVE", "ACTIVE", 1, 1))
                            : Optional.empty(),
                    encoder, transactions);
        }
    }
}
