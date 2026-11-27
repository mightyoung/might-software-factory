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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static com.mightyoung.softwarefactory.platformadmin.digitaltwin.StudioPublicationPolicy.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = {AuthenticationSessionController.class, StudioManagementSecurityHttpTest.TestBridge.class})
@Import({SecurityConfiguration.class, SessionContextResolver.class,
        StudioManagementSecurityHttpTest.TestBridge.class, StudioManagementSecurityHttpTest.Dependencies.class})
class StudioManagementSecurityHttpTest {
    private static final String PUBLISH_PATH = "/api/v1/admin/studio/screens/floor/publish";
    private static final String PLAYER_PATH = "/api/v1/studio/screens/floor/published";
    private static final UUID ACTOR = UUID.randomUUID();
    private static final UUID ACCOUNT = UUID.randomUUID();
    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID MEMBER = UUID.randomUUID();
    private static final String BODY = "{\"snapshotVersion\":1}";
    private static final String DIGEST = "b".repeat(64);

    @Autowired MockMvc mvc;
    @Autowired StudioPublish owner;
    @Autowired AccountStatusReadRepository accounts;
    @Autowired StudioPlayerStorageFixture player;

    @BeforeEach void resetDoubles() throws SQLException {
        reset(owner, accounts);
        when(accounts.findState(ACCOUNT)).thenReturn(Optional.of(new AccountSecurityState("ACTIVE", 1)));
        player.resetStorage();
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

    @Test void playerRequiresLoginAndRejectsOldCredentialBeforeStorage() throws Exception {
        mvc.perform(get(PLAYER_PATH))
                .andExpect(status().isUnauthorized()).andExpect(result -> assertNull(result.getHandler()));
        var session = login();
        when(accounts.findState(ACCOUNT)).thenReturn(Optional.of(new AccountSecurityState("ACTIVE", 2)));
        mvc.perform(get(PLAYER_PATH).session(session))
                .andExpect(status().isUnauthorized()).andExpect(result -> assertNull(result.getHandler()));
        verifyNoInteractions(player.source, player.connection);
    }

    @Test void playerChecksSeparateGrantEvenWithMatchingEtagAfterRevocation() throws Exception {
        var session = login();
        player.granted = true;
        var etag = "\"" + player.snapshot.facts().canonicalSha256() + "\"";
        mvc.perform(get(PLAYER_PATH).session(session).param("version", "7")
                        .header("If-None-Match", etag).header("X-Tenant-Id", UUID.randomUUID()))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", etag))
                .andExpect(header().string("Cache-Control", "private, no-store"))
                .andExpect(content().bytes(player.snapshot.canonicalBytes()));
        verify(player.connection).commit();
        player.granted = false;
        mvc.perform(get(PLAYER_PATH).session(session).param("version", "7").header("If-None-Match", etag))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("ETag"))
                .andExpect(header().string("Cache-Control", "private, no-store"))
                .andExpect(content().string(""));
        verify(player.grant, times(2)).setObject(1, TENANT);
        verify(player.grant, times(2)).setObject(2, MEMBER);
        verify(player.grant, times(2)).setString(3, "floor");
        verify(player.connection, times(1)).prepareStatement(StudioPublishedLookup.PIN_SQL);
        verify(player.connection, never()).prepareStatement(StudioAuthorityQueries.PUBLISH_SQL);
        verifyNoInteractions(owner);
    }

    @Test void playerPinnedMissDoesNotReadCurrentOrReturnItsValidator() throws Exception {
        var session = login();
        player.granted = true;
        mvc.perform(get(PLAYER_PATH).session(session))
                .andExpect(status().isOk()).andExpect(content().bytes(player.snapshot.canonicalBytes()));
        clearInvocations(player.connection);
        mvc.perform(get(PLAYER_PATH).session(session).param("version", "4"))
                .andExpect(status().isNotFound())
                .andExpect(header().doesNotExist("ETag"))
                .andExpect(header().string("Cache-Control", "private, no-store"))
                .andExpect(content().string(""));
        verify(player.pin).setLong(3, 4L);
        verify(player.connection, never()).prepareStatement(StudioPublishedLookup.CURRENT_SQL);
        verify(player.connection).commit();
    }

    @Test void failedPlayerReadCommitCannotReturnBodyOrValidator() throws Exception {
        var session = login();
        player.granted = true;
        doThrow(new SQLException("synthetic commit failure")).when(player.connection).commit();
        mvc.perform(get(PLAYER_PATH).session(session).param("version", "7"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().doesNotExist("ETag"))
                .andExpect(header().string("Cache-Control", "private, no-store"))
                .andExpect(content().string(""));
        verify(player.pin).executeQuery();
        verify(player.connection).commit();
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
        return post(PUBLISH_PATH).contentType("application/json").content(BODY)
                .header(StudioPublishInput.EXPECTED_VERSION, "0");
    }

    /** Only this test context maps URLs. Neither adapter is activated. */
    @RestController
    static class TestBridge {
        private final StudioPublishHttp adapter;
        private final StudioPlayerHttp player;

        TestBridge(SessionSensitiveRequests requests, StudioPublish owner, StudioPlayerStorageFixture storage) {
            adapter = new StudioPublishHttp(requests, owner);
            player = new StudioPlayerHttp(requests, storage.reader);
        }

        @PostMapping(PUBLISH_PATH)
        void post(HttpServletRequest request, HttpServletResponse response) throws IOException {
            adapter.postAfterSecurity("floor", request, response);
        }

        @GetMapping(PLAYER_PATH)
        void getPlayer(HttpServletRequest request, HttpServletResponse response) throws IOException {
            player.get("floor", request, response);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Dependencies {
        @Bean StudioPublish owner() { return mock(StudioPublish.class); }
        @Bean StudioPlayerStorageFixture player() { return new StudioPlayerStorageFixture(TENANT); }
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
