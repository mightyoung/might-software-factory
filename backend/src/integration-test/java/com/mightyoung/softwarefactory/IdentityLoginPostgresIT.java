package com.mightyoung.softwarefactory;

import com.mightyoung.softwarefactory.authorization.JooqRoleScopeReadRepository;
import com.mightyoung.softwarefactory.authorization.PersistentFindingWorkflowAuthorizationRepository;
import com.mightyoung.softwarefactory.directory.JooqMemberDirectoryReadRepository;
import com.mightyoung.softwarefactory.dualprevention.FindingApiExceptionHandler;
import com.mightyoung.softwarefactory.dualprevention.JooqReportableOrganizationReadRepository;
import com.mightyoung.softwarefactory.dualprevention.ReportableOrganizationController;
import com.mightyoung.softwarefactory.dualprevention.ReportableOrganizationService;
import com.mightyoung.softwarefactory.identity.AuthenticationSessionController;
import com.mightyoung.softwarefactory.identity.AccountPasswordCredentialReadRepository;
import com.mightyoung.softwarefactory.identity.CurrentSessionController;
import com.mightyoung.softwarefactory.identity.CurrentSessionService;
import com.mightyoung.softwarefactory.identity.JooqAccountPasswordCredentialReadRepository;
import com.mightyoung.softwarefactory.identity.JooqAccountStatusReadRepository;
import com.mightyoung.softwarefactory.identity.JooqCurrentSessionReadRepository;
import com.mightyoung.softwarefactory.identity.JooqPrincipalStatusReadRepository;
import com.mightyoung.softwarefactory.identity.LocalPasswordAuthenticationProvider;
import com.mightyoung.softwarefactory.identity.LoginAttemptLimiter;
import com.mightyoung.softwarefactory.identity.SecurityConfiguration;
import com.mightyoung.softwarefactory.identity.SessionContextResolver;
import com.mightyoung.softwarefactory.identity.TenantLoginContextResolver;
import com.mightyoung.softwarefactory.tenancy.JooqTenantLoginContextResolver;
import com.mightyoung.softwarefactory.tenancy.JooqTenantApplicationReadRepository;
import com.mightyoung.softwarefactory.tenancy.JooqTenantMembershipReadRepository;
import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataAccessException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/** Real HTTPS, production authentication/JDBC repositories, real JDBC Session and FORCE RLS. */
@SpringBootTest(classes = IdentityLoginPostgresIT.IdentityWebConfiguration.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"lan-postgres-compatibility", "identity-postgres-acceptance"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class IdentityLoginPostgresIT {

    private static final String CSRF = "/api/v1/auth/csrf";
    private static final String LOGIN = "/api/v1/auth/login";
    private static final String LOGOUT = "/api/v1/auth/logout";
    private static final String CURRENT = "/api/v1/session/current";
    private static final String ORGANIZATIONS = "/api/v1/organizations/reportable";
    private static final List<String> IDENTITY_TABLES = List.of(
            "account", "principal", "credential", "tenant", "tenant_application", "tenant_member", "org_unit", "member_org",
            "position", "member_position", "user_group", "group_member", "permission", "role", "scope_rule",
            "role_permission", "role_grant", "scope_org");
    private static IdentityAcceptanceHttps https;

    @LocalServerPort private int port;
    @Autowired private PasswordEncoder passwords;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate runtimeJdbc;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private JdbcIndexedSessionRepository indexedSessions;
    @Autowired private SessionTrace sessions;
    @Autowired private AcceptanceClock clock;
    @Autowired private AcceptanceThrottleClock throttleClock;
    private IdentityPostgresFixture fixture;
    private Set<String> previousSessions;
    private final List<IdentityAcceptanceHttps.Browser> browsers = new ArrayList<>();
    private int backendPid;

    enum RuntimePrivilegeProfile {
        IDENTITY_ONLY(false,false,false), BUSINESS_WORKFLOW(true,false,false), BUSINESS_PASSWORD_CHANGE(true,true,false),
        BUSINESS_AUTHENTICATION_AUDIT(true,true,true);
        final boolean business;
        final boolean passwordChange;
        final boolean authenticationAudit;
        RuntimePrivilegeProfile(boolean business,boolean passwordChange,boolean authenticationAudit) {
            this.business=business;this.passwordChange=passwordChange;this.authenticationAudit=authenticationAudit;
        }
        boolean permitsUpdate(String table,String column) {
            return passwordChange && ((table.equals("account") && column.equals("credential_version"))
                    || (table.equals("credential") && Set.of("secret_hash","changed_at").contains(column)));
        }
    }

    /** New writer scope requires both exact ACKs; either old scope remains identity-read-only. */
    static RuntimePrivilegeProfile validateRuntimePrivilegeProfile(Map<String,String> environment,String database) {
        String profile=environment.getOrDefault("LAN_PG_RUNTIME_PRIVILEGE_PROFILE","identity-only");
        if("identity-only".equals(profile)) return RuntimePrivilegeProfile.IDENTITY_ONLY;
        if(!Set.of("business-workflow","business-password-change","business-authentication-audit").contains(profile)
                || !("I_CONFIRM_BUSINESS_WORKFLOW_FIXTURE_WRITES:"+database).equals(environment.get("LAN_PG_BUSINESS_ACCEPTANCE_ACK"))) {
            throw new IllegalStateException("Runtime privilege profile requires an explicit database-specific business acknowledgement");
        }
        if("business-password-change".equals(profile) || "business-authentication-audit".equals(profile)) {
            if(!("I_CONFIRM_PASSWORD_CHANGE_FIXTURE_WRITES:"+database).equals(environment.get("LAN_PG_PASSWORD_CHANGE_ACCEPTANCE_ACK")))
                throw new IllegalStateException("Password-change privilege profile requires an explicit database-specific password-change acknowledgement");
            if("business-authentication-audit".equals(profile)) {
                if(!("I_CONFIRM_AUTHENTICATION_EVENT_FIXTURE_WRITES:"+database).equals(environment.get("LAN_PG_AUTHENTICATION_EVENT_ACCEPTANCE_ACK")))
                    throw new IllegalStateException("Authentication-event privilege profile requires an explicit database-specific authentication-event acknowledgement");
                return RuntimePrivilegeProfile.BUSINESS_AUTHENTICATION_AUDIT;
            }
            return RuntimePrivilegeProfile.BUSINESS_PASSWORD_CHANGE;
        }
        return RuntimePrivilegeProfile.BUSINESS_WORKFLOW;
    }
    private static RuntimePrivilegeProfile validateRuntimePrivilegeProfile(String database) {
        return validateRuntimePrivilegeProfile(System.getenv(),database);
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry properties) throws Exception {
        var accepted = IdentityAcceptanceTarget.validateInputs(System.getenv());
        validateRuntimePrivilegeProfile(accepted.databaseName());
        var target = IdentityAcceptanceTarget.preflightFromEnvironment(System.getenv());
        if (https == null) https = IdentityAcceptanceHttps.create();
        properties.add("spring.session.jdbc.cleanup-cron", () -> "-");
        properties.add("spring.datasource.url", target::jdbcUrl);
        properties.add("spring.datasource.username", () -> System.getenv("LAN_PG_APP_USERNAME"));
        properties.add("spring.datasource.password", () -> System.getenv("LAN_PG_APP_PASSWORD"));
        properties.add("spring.datasource.hikari.maximum-pool-size", () -> 1);
        properties.add("spring.datasource.hikari.minimum-idle", () -> 1);
        properties.add("server.address", () -> "127.0.0.1");
        properties.add("server.ssl.enabled", () -> true);
        properties.add("server.ssl.key-store", () -> https.keyStore().toUri().toString());
        properties.add("server.ssl.key-store-password", https::password);
        properties.add("server.ssl.key-store-type", () -> "PKCS12");
        properties.add("server.ssl.key-alias", () -> "identity-acceptance");
        properties.add("flowable.process.enabled", () -> false);
        properties.add("flowable.idm.enabled", () -> false);
        properties.add("flowable.eventregistry.enabled", () -> false);
        properties.add("spring.modulith.events.externalization.enabled", () -> false);
    }

    @BeforeEach
    void seedOwnedIdentityAndCheckRuntimeRole() throws Exception {
        sessions.awaitIdle();
        clock.reset();
        // Reset by monotonic advancement, never by replacing or clearing production limiter state.
        throttleClock.advance(Duration.ofMinutes(10));
        previousSessions = sessions.ids();
        fixture = IdentityPostgresFixture.create(passwords);
        assertThat(runtimeJdbc.queryForObject("select current_user = session_user and current_user = ?",
                Boolean.class, System.getenv("LAN_PG_APP_USERNAME"))).isTrue();
        assertThat(runtimeJdbc.queryForObject("""
                select rolsuper or rolbypassrls or rolinherit
                from pg_roles where rolname = current_user
                """, Boolean.class)).isFalse();
        assertThat(runtimeJdbc.queryForObject("select has_schema_privilege(current_user, 'public', 'CREATE')",
                Boolean.class)).isFalse();
        backendPid = runtimeJdbc.queryForObject("select pg_backend_pid()", Integer.class);
        assertCleanConnection();
    }

    @AfterEach
    void cleanupOwnedSessionsAndIdentity() throws Exception {
        try {
            for (var browser : browsers) browser.close();
        } finally {
            browsers.clear();
            if (fixture != null) {
                try {
                    sessions.awaitIdle();
                    Set<String> owned = new HashSet<>(sessions.ids());
                    owned.removeAll(previousSessions);
                    fixture.cleanupSessions(owned);
                } finally {
                    fixture.close();
                    fixture = null;
                }
            }
        }
    }

    @AfterAll
    static void removeOwnTlsMaterial() throws Exception {
        if (https != null) https.close();
    }

    @Test
    void realCsrfLoginAndTwoTenantSessionsRemainIsolatedOnTheSameRuntimeConnection() throws Exception {
        var tenantA = browser();
        var tenantB = browser();
        var nonPostLogin = tenantA.getWithJsonBody(LOGIN, loginBody(fixture.password(), fixture.tenantACode()));
        assertThat(nonPostLogin.status()).isIn(401, 404, 405);
        assertThat(tenantA.cookie("SESSION")).isEmpty();
        expect(tenantA.get(CURRENT), 401);
        bootstrap(tenantA);
        expect(tenantA.post(LOGIN, loginBody(fixture.password(), fixture.tenantACode()), null), 403);
        expect(tenantA.post(LOGIN, loginBody(fixture.password(), fixture.tenantACode()), "wrong-test-token"), 403);
        expect(tenantA.post(LOGIN, loginBody("incorrect-synthetic-password", fixture.tenantACode()), tenantA.csrf()), 401);
        expect(tenantA.post(LOGIN, loginBody(fixture.password(), fixture.tenantCCode()), tenantA.csrf()), 401);
        assertCleanConnection();

        var loginA = login(tenantA, fixture.password(), fixture.tenantACode());
        String sessionA = sessions.idFor(loginA.trace());
        assertThat(fixture.sessionExists(sessionA)).isTrue();
        assertThat(fixture.storedCredentialVersion(sessionA)).isEqualTo(1L);
        assertSessionCookie(loginA, tenantA);
        assertCurrent(tenantA, fixture.tenantAId().toString(), fixture.memberAId().toString());
        assertOrganizations(tenantA, fixture.organizationAId().toString(), fixture.organizationBId().toString());
        assertCleanConnection();

        var loginB = login(tenantB, fixture.password(), fixture.tenantBCode());
        String sessionB = sessions.idFor(loginB.trace());
        assertThat(sessionB).isNotEqualTo(sessionA);
        assertCurrent(tenantB, fixture.tenantBId().toString(), fixture.memberBId().toString());
        assertOrganizations(tenantB, fixture.organizationBId().toString(), fixture.organizationAId().toString());
        // A's server-side context is unchanged by B's login on the same one-connection pool.
        assertCurrent(tenantA, fixture.tenantAId().toString(), fixture.memberAId().toString());
        assertCleanConnection();

        logout(tenantA);
        assertThat(fixture.sessionExists(sessionA)).isFalse();
        expect(tenantA.get(CURRENT), 401);
        assertCurrent(tenantB, fixture.tenantBId().toString(), fixture.memberBId().toString());
    }

    @Test
    void reauthenticationRotatesTheRealJdbcSessionAndOldCookieCannotAuthenticate() throws Exception {
        var client = browser();
        var first = login(client, fixture.password(), fixture.tenantACode());
        String previousId = sessions.idFor(first.trace());
        String previousCookie = client.cookie("SESSION").orElseThrow().getValue();
        var oldCookieClient = client.copyTo(port);
        browsers.add(oldCookieClient);
        var second = login(client, fixture.password(), fixture.tenantBCode());
        String currentId = sessions.idFor(second.trace());
        assertThat(currentId.equals(previousId)).as("The persistent session ID rotates").isFalse();
        assertThat(client.cookie("SESSION").orElseThrow().getValue().equals(previousCookie))
                .as("The actual browser cookie rotates").isFalse();
        assertThat(fixture.sessionExists(previousId)).isFalse();
        assertThat(fixture.sessionExists(currentId)).isTrue();
        expect(oldCookieClient.get(CURRENT), 401);
        assertCurrent(client, fixture.tenantBId().toString(), fixture.memberBId().toString());
        assertIndexedSessions(fixture, currentId);
        var alternateEntry = browser();
        String basic = java.util.Base64.getEncoder().encodeToString(
                (fixture.loginKey() + ":" + fixture.password()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        expect(alternateEntry.getWithHeaders(CURRENT, Map.of("Authorization", "Basic " + basic)), 401);
        assertThat(alternateEntry.cookie("SESSION").isPresent()).isFalse();
        assertCleanConnection();
    }

    @Test
    void accountIndexGroupsTenantSessionsAndKeepsOtherAccountsIsolatedThroughLogout() throws Exception {
        var firstTenant = browser();
        var secondTenant = browser();
        String firstSession = sessions.idFor(login(firstTenant, fixture.password(), fixture.tenantACode()).trace());
        String secondSession = sessions.idFor(login(secondTenant, fixture.password(), fixture.tenantBCode()).trace());
        assertThat(fixture.storedPrincipalName(firstSession)).isEqualTo(fixture.accountId().toString());
        assertThat(fixture.storedPrincipalName(secondSession)).isEqualTo(fixture.accountId().toString());
        assertIndexedSessions(fixture, firstSession, secondSession);

        Set<String> beforeOtherAccount = sessions.ids();
        try (var other = IdentityPostgresFixture.create(passwords)) {
            var otherClient = browser();
            try {
                bootstrap(otherClient);
                var otherLogin = otherClient.post(LOGIN,
                        loginBody(other.loginKey(), other.password(), other.tenantACode()), otherClient.csrf());
                expect(otherLogin, 204);
                String otherSession = sessions.idFor(otherLogin.trace());
                assertThat(other.storedPrincipalName(otherSession)).isEqualTo(other.accountId().toString());
                assertIndexedSessions(fixture, firstSession, secondSession);
                assertIndexedSessions(other, otherSession);
                for (var owner : List.of(fixture, other)) {
                    for (String alias : List.of(owner.loginKey(), owner.actorId().toString(),
                            owner.memberAId().toString(), owner.memberBId().toString(),
                            owner.tenantAId().toString(), owner.tenantBId().toString())) {
                        assertNoSessionsIndexedBy(alias);
                    }
                }
                assertNoSessionsIndexedBy("VerifiedSessionPrincipal");

                logout(firstTenant);
                assertThat(fixture.sessionExists(firstSession)).isFalse();
                assertIndexedSessions(fixture, secondSession);
                assertIndexedSessions(other, otherSession);

                logout(secondTenant);
                assertThat(fixture.sessionExists(secondSession)).isFalse();
                assertIndexedSessions(fixture);
                assertIndexedSessions(other, otherSession);
                assertCurrent(otherClient, other, other.tenantAId().toString(), other.memberAId().toString());
                assertOrganizations(otherClient, other.organizationAId().toString(), fixture.organizationAId().toString());
                assertCleanConnection();
            } finally {
                try {
                    otherClient.close();
                } finally {
                    browsers.remove(otherClient);
                    sessions.awaitIdle();
                    Set<String> owned = new HashSet<>(sessions.ids());
                    owned.removeAll(beforeOtherAccount);
                    other.cleanupSessions(owned);
                }
            }
        }
    }

    @Test
    void aLoadedLegacyIndexRepairsItselfButAnIndexedExpiredSessionStillCannotAuthenticate() throws Exception {
        var client = browser();
        String session = sessions.idFor(login(client, fixture.password(), fixture.tenantACode()).trace());
        Set<String> beforeOtherAccount = sessions.ids();
        try (var other = IdentityPostgresFixture.create(passwords)) {
            var otherClient = browser();
            try {
                bootstrap(otherClient);
                var otherLogin = otherClient.post(LOGIN,
                        loginBody(other.loginKey(), other.password(), other.tenantACode()), otherClient.csrf());
                expect(otherLogin, 204);
                String otherSession = sessions.idFor(otherLogin.trace());
                assertIndexedSessions(other, otherSession);

                fixture.replaceStoredPrincipalName(session, "VerifiedSessionPrincipal");
                assertThat(fixture.storedPrincipalName(session)).isEqualTo("VerifiedSessionPrincipal");
                assertIndexedSessions(fixture);
                assertIndexedSessions(other, otherSession);
                assertCurrent(client, fixture.tenantAId().toString(), fixture.memberAId().toString());
                assertThat(fixture.storedPrincipalName(session)).isEqualTo(fixture.accountId().toString());
                assertIndexedSessions(fixture, session);
                assertIndexedSessions(other, otherSession);
                assertNoSessionsIndexedBy("VerifiedSessionPrincipal");

                fixture.expireStoredSession(session);
                // The repository's principal index is a lookup, not an authorization or expiry gate.
                assertIndexedSessions(fixture, session);
                assertIndexedSessions(other, otherSession);
                expect(client.get(CURRENT), 401);
                assertThat(fixture.sessionExists(session)).isFalse();
                assertIndexedSessions(fixture);
                assertIndexedSessions(other, otherSession);
                assertCurrent(otherClient, other, other.tenantAId().toString(), other.memberAId().toString());
                assertCleanConnection();
            } finally {
                try {
                    otherClient.close();
                } finally {
                    browsers.remove(otherClient);
                    sessions.awaitIdle();
                    Set<String> owned = new HashSet<>(sessions.ids());
                    owned.removeAll(beforeOtherAccount);
                    other.cleanupSessions(owned);
                }
            }
        }
    }

    @Test
    void absoluteLifetimeSurvivesJdbcReloadDoesNotSlideAndExpiresOnlyTheOlderLogin() throws Exception {
        var tenantA = browser();
        var tenantB = browser();
        Instant started = clock.instant();
        String sessionA = sessions.idFor(login(tenantA, fixture.password(), fixture.tenantACode()).trace());
        assertThat(fixture.storedAuthenticatedAt(sessionA)).isEqualTo(started);

        clock.advance(Duration.ofHours(1));
        String sessionB = sessions.idFor(login(tenantB, fixture.password(), fixture.tenantBCode()).trace());
        assertThat(fixture.storedAuthenticatedAt(sessionB)).isEqualTo(started.plus(Duration.ofHours(1)));
        assertCurrent(tenantA, fixture.tenantAId().toString(), fixture.memberAId().toString());
        assertThat(fixture.storedAuthenticatedAt(sessionA)).isEqualTo(started);

        clock.advance(Duration.ofHours(11).minusNanos(1));
        assertCurrent(tenantA, fixture.tenantAId().toString(), fixture.memberAId().toString());
        assertOrganizations(tenantA, fixture.organizationAId().toString(), fixture.organizationBId().toString());
        assertThat(fixture.storedAuthenticatedAt(sessionA)).isEqualTo(started);
        assertThat(fixture.sessionExists(sessionA)).isTrue();

        clock.advance(Duration.ofNanos(1));
        expect(tenantA.get(CURRENT), 401);
        assertThat(fixture.sessionExists(sessionA)).isFalse();
        expect(tenantA.get(ORGANIZATIONS), 401);
        assertCurrent(tenantB, fixture.tenantBId().toString(), fixture.memberBId().toString());
        assertOrganizations(tenantB, fixture.organizationBId().toString(), fixture.organizationAId().toString());
        assertThat(fixture.sessionExists(sessionB)).isTrue();

        bootstrap(tenantA);
        String freshSession = sessions.idFor(login(tenantA, fixture.password(), fixture.tenantACode()).trace());
        assertThat(freshSession).isNotEqualTo(sessionA);
        assertThat(fixture.storedAuthenticatedAt(freshSession)).isEqualTo(clock.instant());
        assertCurrent(tenantA, fixture.tenantAId().toString(), fixture.memberAId().toString());
        assertOrganizations(tenantA, fixture.organizationAId().toString(), fixture.organizationBId().toString());
        assertCleanConnection();
    }

    @ParameterizedTest(name = "Invalid persisted authentication time: {0}")
    @EnumSource(InvalidAuthenticationTime.class)
    void invalidPersistedAuthenticationTimeCannotBeUpgradedIntoAFreshSession(
            InvalidAuthenticationTime invalid) throws Exception {
        var client = browser();
        String originalSession = sessions.idFor(login(client, fixture.password(), fixture.tenantACode()).trace());
        assertThat(fixture.storedAuthenticatedAt(originalSession)).isEqualTo(clock.instant());
        switch (invalid) {
            case MISSING -> fixture.removeStoredAuthenticationTime(originalSession);
            case WRONG_TYPE -> fixture.replaceStoredAuthenticationTime(originalSession, "synthetic-invalid-time");
            case FUTURE -> fixture.replaceStoredAuthenticationTime(originalSession, clock.instant().plusSeconds(1));
        }

        expect(client.get(CURRENT), 401);
        assertThat(fixture.sessionExists(originalSession)).isFalse();
        expect(client.get(ORGANIZATIONS), 401);
        bootstrap(client);
        String freshSession = sessions.idFor(login(client, fixture.password(), fixture.tenantACode()).trace());
        assertThat(freshSession).isNotEqualTo(originalSession);
        assertThat(fixture.storedAuthenticatedAt(freshSession)).isEqualTo(clock.instant());
        assertCurrent(client, fixture.tenantAId().toString(), fixture.memberAId().toString());
        assertCleanConnection();
    }

    private enum InvalidAuthenticationTime { MISSING, WRONG_TYPE, FUTURE }

    @Test
    void repeatedBadCredentialsCoolDownOnlyTheSourceAndNormalizedLoginAndRecoverAtTheDeadline() throws Exception {
        var tenantB = browser();
        String existingSession = sessions.idFor(login(tenantB, fixture.password(), fixture.tenantBCode()).trace());
        var client = browser();
        bootstrap(client);
        for (int attempt = 0; attempt < 5; attempt++) {
            assertGenericAuthenticationFailure(client.post(LOGIN,
                    loginBody("incorrect-synthetic-password", fixture.tenantACode()), client.csrf()));
        }

        var blocked = client.post(LOGIN, loginBody(fixture.password(), fixture.tenantACode()), client.csrf());
        assertThat(assertRateLimited(blocked)).isEqualTo(30L);
        assertThat(client.cookie("SESSION")).isEmpty();
        // A login failure/cooldown must not mutate the account or revoke an already valid session.
        assertThat(runtimeJdbc.queryForObject("select status from public.account where id = ?",
                String.class, fixture.accountId())).isEqualTo("ACTIVE");
        assertThat(runtimeJdbc.queryForObject("select credential_version from public.account where id = ?",
                Long.class, fixture.accountId())).isEqualTo(1L);
        assertCurrent(tenantB, fixture.tenantBId().toString(), fixture.memberBId().toString());
        assertOrganizations(tenantB, fixture.organizationBId().toString(), fixture.organizationAId().toString());
        assertThat(fixture.sessionExists(existingSession)).isTrue();

        StringBuilder equivalentLogin = new StringBuilder();
        for (char character : fixture.loginKey().toUpperCase(Locale.ROOT).toCharArray()) {
            equivalentLogin.append((char) (character + 0xfee0)); // Full-width ASCII folds under NFKC.
        }
        var changedSelection = client.postWithHeaders(LOGIN,
                loginBody(equivalentLogin.toString(), fixture.password(), fixture.tenantBCode()), client.csrf(),
                Map.of("X-Forwarded-For", "198.51.100.7", "Forwarded", "for=198.51.100.7;proto=https"));
        assertThat(assertRateLimited(changedSelection)).isEqualTo(30L);
        // A different login key on the same actual source still reaches generic authentication.
        assertGenericAuthenticationFailure(client.post(LOGIN,
                loginBody("unregistered-" + fixture.loginKey(), fixture.password(), fixture.tenantACode()),
                client.csrf()));

        throttleClock.advance(Duration.ofSeconds(10));
        var afterTenSeconds = client.postWithHeaders(LOGIN,
                loginBody(fixture.password(), fixture.tenantACode()), client.csrf(),
                Map.of("X-Forwarded-For", "198.51.100.8", "X-Real-IP", "198.51.100.8"));
        assertThat(assertRateLimited(afterTenSeconds)).isEqualTo(20L);
        throttleClock.advance(Duration.ofSeconds(19));
        assertThat(assertRateLimited(client.post(LOGIN,
                loginBody(fixture.password(), fixture.tenantACode()), client.csrf()))).isEqualTo(1L);
        throttleClock.advance(Duration.ofSeconds(1));

        String freshSession = sessions.idFor(login(client, fixture.password(), fixture.tenantACode()).trace());
        assertThat(fixture.sessionExists(freshSession)).isTrue();
        assertThat(fixture.storedCredentialVersion(freshSession)).isEqualTo(1L);
        assertThat(fixture.storedAuthenticatedAt(freshSession)).isEqualTo(clock.instant());
        assertCurrent(client, fixture.tenantAId().toString(), fixture.memberAId().toString());
        assertOrganizations(client, fixture.organizationAId().toString(), fixture.organizationBId().toString());
        assertCurrent(tenantB, fixture.tenantBId().toString(), fixture.memberBId().toString());
        assertCleanConnection();
    }

    @Test
    void realCredentialRotationRejectsOldSessionsAndFreshLoginPersistsTheNewGeneration() throws Exception {
        var client = browser();
        var firstLogin = login(client, fixture.password(), fixture.tenantACode());
        String originalId = sessions.idFor(firstLogin.trace());
        assertThat(fixture.storedCredentialVersion(originalId)).isEqualTo(1L);
        fixture.rotatePasswordAndCredentialVersion();
        expect(client.get(CURRENT), 401);
        expect(client.get(ORGANIZATIONS), 401);
        bootstrap(client);
        expect(client.post(LOGIN, loginBody(fixture.password(), fixture.tenantACode()), client.csrf()), 401);
        var newLogin = login(client, fixture.newPassword(), fixture.tenantACode());
        String replacementId = sessions.idFor(newLogin.trace());
        assertThat(replacementId).isNotEqualTo(originalId);
        assertThat(fixture.sessionExists(originalId)).isFalse();
        assertThat(fixture.storedCredentialVersion(replacementId)).isEqualTo(2L);
        assertCurrent(client, fixture.tenantAId().toString(), fixture.memberAId().toString());
        assertOrganizations(client, fixture.organizationAId().toString(), fixture.organizationBId().toString());
        assertCleanConnection();
        logout(client);
        assertThat(fixture.sessionExists(replacementId)).isFalse();
    }

    @Test
    void membershipAndRoleRevocationAreRecheckedWithoutChangingAnotherTenantSession() throws Exception {
        var tenantA = browser();
        var tenantB = browser();
        login(tenantA, fixture.password(), fixture.tenantACode());
        login(tenantB, fixture.password(), fixture.tenantBCode());
        fixture.revokeGrantA();
        expect(tenantA.get(CURRENT), 200);
        var organizations = tenantA.get(ORGANIZATIONS);
        expect(organizations, 200);
        assertThat(json.readTree(organizations.body()).path("data").size()).isZero();
        assertOrganizations(tenantB, fixture.organizationBId().toString(), fixture.organizationAId().toString());

        fixture.revokeMembershipA();
        expect(tenantA.get(CURRENT), 403);
        expect(tenantA.get(ORGANIZATIONS), 403);
        bootstrap(tenantA);
        expect(tenantA.post(LOGIN, loginBody(fixture.password(), fixture.tenantACode()), tenantA.csrf()), 401);
        fixture.restoreMembershipA();
        // Reactivating a member cannot resurrect the old epoch captured in A's session.
        expect(tenantA.get(CURRENT), 403);
        login(tenantA, fixture.password(), fixture.tenantACode());
        assertCurrent(tenantA, fixture.tenantAId().toString(), fixture.memberAId().toString());
        assertCurrent(tenantB, fixture.tenantBId().toString(), fixture.memberBId().toString());
        assertCleanConnection();
    }

    @ParameterizedTest(name = "Identity state revoked: {0}")
    @EnumSource(RevokedIdentityState.class)
    void inactiveIdentityRejectsExistingSessionsAndNewLoginWithoutLeakingItsState(
            RevokedIdentityState state) throws Exception {
        var tenantA = browser();
        var tenantB = browser();
        login(tenantA, fixture.password(), fixture.tenantACode());
        login(tenantB, fixture.password(), fixture.tenantBCode());

        state.revoke(fixture);
        int protectedStatus = switch (state) {
            case ACCOUNT_LOCKED, ACCOUNT_DISABLED -> 401;
            case PRINCIPAL_DISABLED, TENANT_SUSPENDED -> 403;
        };
        expect(tenantA.get(CURRENT), protectedStatus);
        expect(tenantA.get(ORGANIZATIONS), protectedStatus);
        bootstrap(tenantA);
        assertGenericAuthenticationFailure(tenantA.post(
                LOGIN, loginBody(fixture.password(), fixture.tenantACode()), tenantA.csrf()));
        if (state == RevokedIdentityState.TENANT_SUSPENDED) {
            assertCurrent(tenantB, fixture.tenantBId().toString(), fixture.memberBId().toString());
            assertOrganizations(tenantB, fixture.organizationBId().toString(), fixture.organizationAId().toString());
        } else {
            expect(tenantB.get(CURRENT), protectedStatus);
            expect(tenantB.get(ORGANIZATIONS), protectedStatus);
            bootstrap(tenantB);
            assertGenericAuthenticationFailure(tenantB.post(
                    LOGIN, loginBody(fixture.password(), fixture.tenantBCode()), tenantB.csrf()));
        }
        assertCleanConnection();
    }

    @Test
    void runtimeIdentityReadsAndAdditionalConsumersMatchTheConfirmedPrivilegeProfile() {
        assertThat(runtimeJdbc.queryForObject("select current_setting('server_version_num')::int",
                Integer.class) / 10_000).isEqualTo(18);
        RuntimePrivilegeProfile privilegeProfile=validateRuntimePrivilegeProfile(runtimeJdbc.queryForObject("select current_database()",String.class));
        assertThat(runtimeJdbc.queryForObject("select has_database_privilege(current_user,current_database(),'CREATE,TEMP')",Boolean.class))
                .as("No runtime database CREATE/TEMP in any profile").isFalse();
        for (String table : IDENTITY_TABLES) {
            String qualified = "public." + table;
            assertThat(runtimeJdbc.queryForObject(
                    "select has_table_privilege(current_user, cast(? as regclass), 'SELECT')",
                    Boolean.class, qualified)).as("SELECT on %s", table).isTrue();
            assertThat(runtimeJdbc.queryForObject("""
                    select pg_get_userbyid(relowner) = current_user
                    from pg_class where oid = cast(? as regclass)
                    """, Boolean.class, qualified)).as("Runtime must not own %s", table).isFalse();
            // PostgreSQL18 includes MAINTAIN; comma-separated privilege checks mean ANY privilege.
            assertThat(runtimeJdbc.queryForObject("""
                    select has_table_privilege(current_user, cast(? as regclass),
                        'INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER,MAINTAIN')
                    """, Boolean.class, qualified)).as("No table mutation/maintenance on %s", table).isFalse();
            for(String column:runtimeJdbc.queryForList("select attname from pg_attribute where attrelid=cast(? as regclass) and attnum>0 and not attisdropped",String.class,qualified)) {
                for(String operation:List.of("SELECT","INSERT","UPDATE","REFERENCES")) {
                    boolean expected=operation.equals("SELECT") || (operation.equals("UPDATE") && privilegeProfile.permitsUpdate(table,column));
                    assertThat(runtimeJdbc.queryForObject("select has_column_privilege(current_user,cast(? as regclass),?,?)",Boolean.class,qualified,column,operation))
                            .as("Effective %s on %s.%s in %s",operation,table,column,privilegeProfile).isEqualTo(expected);
                }
            }
            if (!Set.of("account", "principal", "credential", "permission").contains(table)) {
                assertThat(runtimeJdbc.queryForObject("""
                        select relrowsecurity and relforcerowsecurity
                        from pg_class where oid = cast(? as regclass)
                        """, Boolean.class, qualified)).as("Enabled and forced RLS on %s", table).isTrue();
            }
        }
        for (var tenantId : List.of(fixture.tenantAId(), fixture.tenantBId(), fixture.tenantCId())) {
            assertThat(runtimeJdbc.queryForObject("select count(*) from public.tenant where id = ?",
                    Integer.class, tenantId)).as("No tenant visible without a context").isZero();
        }
        for (var organizationId : List.of(fixture.organizationAId(), fixture.organizationBId())) {
            assertThat(runtimeJdbc.queryForObject("select count(*) from public.org_unit where id = ?",
                    Integer.class, organizationId)).as("No organization visible without a context").isZero();
        }
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            assertThat(runtimeJdbc.queryForObject("select set_config('app.tenant_id', ?, true)",
                    String.class, fixture.tenantAId().toString())).isEqualTo(fixture.tenantAId().toString());
            assertThat(runtimeJdbc.queryForObject("select count(*) from public.tenant where id = ?",
                    Integer.class, fixture.tenantAId())).isEqualTo(1);
            for (var tenantId : List.of(fixture.tenantBId(), fixture.tenantCId())) {
                assertThat(runtimeJdbc.queryForObject("select count(*) from public.tenant where id = ?",
                        Integer.class, tenantId)).as("Tenant A context hides other tenants").isZero();
            }
            assertThat(runtimeJdbc.queryForObject("select count(*) from public.org_unit where id = ?",
                    Integer.class, fixture.organizationAId())).isEqualTo(1);
            assertThat(runtimeJdbc.queryForObject("select count(*) from public.org_unit where id = ?",
                    Integer.class, fixture.organizationBId())).as("Tenant A context hides B's organization").isZero();
        });
        assertCleanConnection();
        for (String table : List.of("spring_session", "spring_session_attributes")) {
            for (String operation : List.of("SELECT", "INSERT", "UPDATE", "DELETE")) {
                assertThat(runtimeJdbc.queryForObject(
                        "select has_table_privilege(current_user, cast(? as regclass), ?)",
                        Boolean.class, "public." + table, operation))
                        .as("Session %s on %s", operation, table).isTrue();
            }
            assertThat(runtimeJdbc.queryForObject("""
                    select has_table_privilege(current_user, cast(? as regclass),
                        'TRUNCATE,REFERENCES,TRIGGER,MAINTAIN')
                    """, Boolean.class, "public." + table)).as("Only required Session operations on %s", table).isFalse();
        }
        if (privilegeProfile.business) {
            assertThat(runtimeJdbc.queryForObject("select to_regclass('public.p0_finding_projection') is null", Boolean.class))
                    .as("Business target is independent of the P0 probe schema").isTrue();
            for (String operation : List.of("SELECT", "INSERT", "UPDATE", "DELETE")) {
                assertThat(runtimeJdbc.queryForObject("select has_table_privilege(current_user, 'public.event_publication', ?)",
                        Boolean.class, operation)).as("Actual publication registry %s", operation).isTrue();
            }
            assertThat(runtimeJdbc.queryForObject("""
                    select count(*) from pg_class where relnamespace = 'public'::regnamespace and relkind = 'r'
                      and left(relname, 4) in ('act_', 'flw_')
                      and has_table_privilege(current_user, oid, 'SELECT')
                    """, Integer.class)).as("Fixed Flowable8 schema read access").isEqualTo(45);
            assertThat(runtimeJdbc.queryForObject("""
                    select count(*) from pg_class where relnamespace = 'public'::regnamespace and relkind = 'r'
                      and left(relname, 4) in ('act_', 'flw_')
                      and (pg_get_userbyid(relowner) = current_user or
                           has_table_privilege(current_user, oid, 'TRUNCATE,REFERENCES,TRIGGER,MAINTAIN'))
                    """, Integer.class)).as("No engine ownership or maintenance").isZero();
            assertThat(runtimeJdbc.queryForObject("""
                    select has_table_privilege(current_user, 'public.audit_event', 'SELECT')
                       and has_table_privilege(current_user, 'public.audit_event', 'INSERT')
                       and not has_table_privilege(current_user, 'public.audit_event', 'UPDATE,DELETE,TRUNCATE')
                       and not has_schema_privilege(current_user, 'public', 'CREATE')
                       and not has_database_privilege(current_user, current_database(), 'TEMP')
                    """, Boolean.class)).as("Append-only audit and no DDL/TEMP").isTrue();
        } else {
            for (String table : List.of("p0_finding_projection", "event_publication")) {
                assertThat(runtimeJdbc.queryForObject(
                        "select has_table_privilege(current_user, cast(? as regclass), 'SELECT')",
                        Boolean.class, "public." + table)).as("No unrelated table read on %s", table).isFalse();
            }
            assertThat(runtimeJdbc.queryForObject("""
                    select count(*) from pg_class
                    where relnamespace = 'public'::regnamespace and relkind = 'r'
                      and left(relname, 4) in ('act_', 'flw_')
                    """, Integer.class)).as("The negative Flowable privilege check must not be vacuous").isPositive();
            assertThat(runtimeJdbc.queryForObject("""
                    select count(*) from pg_class
                    where relnamespace = 'public'::regnamespace and relkind = 'r'
                      and left(relname, 4) in ('act_', 'flw_')
                      and has_table_privilege(current_user, oid, 'SELECT')
                    """, Integer.class)).as("No Flowable table read privileges").isZero();
        }

        if(privilegeProfile.passwordChange) {
            assertThat(runtimeJdbc.queryForObject("select to_regclass('public.security_event') is not null",Boolean.class)).isTrue();
            assertThat(runtimeJdbc.queryForObject("select relrowsecurity and relforcerowsecurity and pg_get_userbyid(relowner)<>current_user from pg_class where oid='public.security_event'::regclass",Boolean.class)).isTrue();
            for(String operation:List.of("SELECT","INSERT","UPDATE","DELETE","TRUNCATE","REFERENCES","TRIGGER","MAINTAIN")) {
                assertThat(runtimeJdbc.queryForObject("select has_table_privilege(current_user,'public.security_event',?)",Boolean.class,operation))
                        .as("Exact security-event table %s",operation).isEqualTo(operation.equals("INSERT"));
            }
            for(String column:runtimeJdbc.queryForList("select attname from pg_attribute where attrelid='public.security_event'::regclass and attnum>0 and not attisdropped",String.class)) {
                for(String operation:List.of("SELECT","INSERT","UPDATE","REFERENCES")) {
                    assertThat(runtimeJdbc.queryForObject("select has_column_privilege(current_user,'public.security_event',?,?)",Boolean.class,column,operation))
                            .as("Exact security-event column %s %s",column,operation).isEqualTo(operation.equals("INSERT"));
                }
            }
        } else if(Boolean.TRUE.equals(runtimeJdbc.queryForObject("select to_regclass('public.security_event') is not null",Boolean.class))) {
            assertThat(runtimeJdbc.queryForObject("select has_table_privilege(current_user,'public.security_event','SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER,MAINTAIN') or has_any_column_privilege(current_user,'public.security_event','SELECT,INSERT,UPDATE,REFERENCES')",Boolean.class))
                    .as("Old profiles have no security_event consumer").isFalse();
        }

        if(privilegeProfile.authenticationAudit) {
            assertThat(runtimeJdbc.queryForObject("select to_regclass('public.authentication_event') is not null",Boolean.class)).isTrue();
            assertThat(runtimeJdbc.queryForObject("select not relrowsecurity and not relforcerowsecurity and pg_get_userbyid(relowner)<>current_user from pg_class where oid='public.authentication_event'::regclass",Boolean.class)).isTrue();
            for(String operation:List.of("SELECT","INSERT","UPDATE","DELETE","TRUNCATE","REFERENCES","TRIGGER","MAINTAIN")) {
                assertThat(runtimeJdbc.queryForObject("select has_table_privilege(current_user,'public.authentication_event',?)",Boolean.class,operation))
                        .as("Exact authentication-event table %s",operation).isEqualTo(operation.equals("INSERT"));
            }
            for(String column:runtimeJdbc.queryForList("select attname from pg_attribute where attrelid='public.authentication_event'::regclass and attnum>0 and not attisdropped",String.class)) {
                for(String operation:List.of("SELECT","INSERT","UPDATE","REFERENCES")) {
                    assertThat(runtimeJdbc.queryForObject("select has_column_privilege(current_user,'public.authentication_event',?,?)",Boolean.class,column,operation))
                            .as("Exact authentication-event column %s %s",column,operation).isEqualTo(operation.equals("INSERT"));
                }
            }
        } else if(Boolean.TRUE.equals(runtimeJdbc.queryForObject("select to_regclass('public.authentication_event') is not null",Boolean.class))) {
            assertThat(runtimeJdbc.queryForObject("select has_table_privilege(current_user,'public.authentication_event','SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER,MAINTAIN') or has_any_column_privilege(current_user,'public.authentication_event','SELECT,INSERT,UPDATE,REFERENCES')",Boolean.class))
                    .as("Old profiles have no authentication_event grant").isFalse();
        }

        String forbiddenTable = "identity_forbidden_" + java.util.UUID.randomUUID().toString().replace("-", "");
        Throwable ddlDenied = catchThrowable(() -> runtimeJdbc.execute(
                "create table public." + forbiddenTable + " (id integer primary key)"));
        assertThat(ddlDenied).isInstanceOf(DataAccessException.class);
        assertThat(((DataAccessException) ddlDenied).getMostSpecificCause())
                .isInstanceOfSatisfying(SQLException.class,
                        sql -> assertThat(sql.getSQLState()).isEqualTo("42501"));
        assertThat(runtimeJdbc.queryForObject("select to_regclass(?) is null", Boolean.class,
                "public." + forbiddenTable)).as("Rejected DDL must leave no object").isTrue();

        String originalStatus = runtimeJdbc.queryForObject(
                "select status from public.account where id = ?", String.class, fixture.accountId());
        Throwable denied = catchThrowable(() -> runtimeJdbc.update(
                "update public.account set status = 'DISABLED' where id = ?", fixture.accountId()));
        assertThat(denied).isInstanceOf(DataAccessException.class);
        assertThat(((DataAccessException) denied).getMostSpecificCause())
                .isInstanceOfSatisfying(SQLException.class,
                        sql -> assertThat(sql.getSQLState()).isEqualTo("42501"));
        assertThat(runtimeJdbc.queryForObject("select status from public.account where id = ?",
                String.class, fixture.accountId())).isEqualTo(originalStatus).isEqualTo("ACTIVE");
        assertCleanConnection();
    }

    private void assertGenericAuthenticationFailure(IdentityAcceptanceHttps.Response response) throws Exception {
        expect(response, 401);
        JsonNode error = json.readTree(response.body());
        assertThat(error.path("code").asString()).isEqualTo("AUTH_REQUIRED");
        assertThat(error.path("message").asString()).isEqualTo("Invalid credentials or account context");
        assertThat(response.body()).doesNotContain(fixture.password(), fixture.newPassword(),
                "LOCKED", "DISABLED", "SUSPENDED", "secret_hash", "secretHash");
    }

    private long assertRateLimited(IdentityAcceptanceHttps.Response response) throws Exception {
        expect(response, 429);
        JsonNode error = json.readTree(response.body());
        assertThat(error.path("code").asString()).isEqualTo("RATE_LIMITED");
        assertThat(error.path("message").asString()).isEqualTo("Too many login attempts; try again later");
        assertThat(error.path("retryable").asBoolean()).isTrue();
        assertThat(error.path("fieldErrors").isArray()).isTrue();
        assertThat(error.path("fieldErrors").size()).isZero();
        assertThat(error.path("traceId").asString()).isNotBlank();
        assertThat(response.body()).doesNotContain(fixture.loginKey(), fixture.password(), fixture.newPassword(),
                fixture.tenantACode(), fixture.tenantBCode(), "198.51.100.", "127.0.0.1", "secret_hash");
        String retryAfter = response.http().headers().firstValue("Retry-After").orElseThrow();
        assertThat(retryAfter).matches("[1-9][0-9]*");
        return Long.parseLong(retryAfter);
    }

    private enum RevokedIdentityState {
        ACCOUNT_LOCKED,
        ACCOUNT_DISABLED,
        PRINCIPAL_DISABLED,
        TENANT_SUSPENDED;

        void revoke(IdentityPostgresFixture fixture) {
            switch (this) {
                case ACCOUNT_LOCKED -> fixture.lockAccount();
                case ACCOUNT_DISABLED -> fixture.disableAccount();
                case PRINCIPAL_DISABLED -> fixture.disablePrincipal();
                case TENANT_SUSPENDED -> fixture.suspendTenantA();
            }
        }
    }

    private IdentityAcceptanceHttps.Browser browser() {
        var browser = https.browser(port);
        browsers.add(browser);
        return browser;
    }

    private void bootstrap(IdentityAcceptanceHttps.Browser client) throws Exception {
        expect(client.get(CSRF), 204);
        var cookie = client.cookie("XSRF-TOKEN").orElseThrow();
        assertThat(cookie.getSecure()).isTrue();
        assertThat(cookie.isHttpOnly()).isFalse();
    }

    private IdentityAcceptanceHttps.Response login(IdentityAcceptanceHttps.Browser client,
                                                   String password, String tenantCode) throws Exception {
        bootstrap(client);
        var response = client.post(LOGIN, loginBody(password, tenantCode), client.csrf());
        expect(response, 204);
        return response;
    }

    private String loginBody(String password, String tenantCode) {
        return loginBody(fixture.loginKey(), password, tenantCode);
    }

    private String loginBody(String loginKey, String password, String tenantCode) {
        return json.writeValueAsString(java.util.Map.of(
                "loginKey", loginKey, "password", password, "tenantCode", tenantCode));
    }

    private void logout(IdentityAcceptanceHttps.Browser client) throws Exception {
        bootstrap(client);
        expect(client.post(LOGOUT, null, client.csrf()), 204);
    }

    private void assertCurrent(IdentityAcceptanceHttps.Browser client, String tenantId, String memberId)
            throws Exception {
        assertCurrent(client, fixture, tenantId, memberId);
    }

    private void assertCurrent(IdentityAcceptanceHttps.Browser client, IdentityPostgresFixture owner,
                               String tenantId, String memberId) throws Exception {
        var response = client.get(CURRENT);
        expect(response, 200);
        JsonNode current = json.readTree(response.body()).path("data");
        assertThat(current.path("actorId").asString()).isEqualTo(owner.actorId().toString());
        assertThat(current.path("accountId").asString()).isEqualTo(owner.accountId().toString());
        assertThat(current.path("tenantId").asString()).isEqualTo(tenantId);
        assertThat(current.path("memberId").asString()).isEqualTo(memberId);
        assertThat(current.has("secretHash")).isFalse();
        assertThat(current.has("credentialVersion")).isFalse();
    }

    private void assertIndexedSessions(IdentityPostgresFixture owner, String... expectedIds) throws Exception {
        sessions.awaitIdle();
        String accountName = owner.accountId().toString();
        // Inspect opaque IDs before repository reload, so unrelated serialized sessions are never read.
        assertThat(runtimeJdbc.queryForList(
                "select session_id from public.spring_session where principal_name = ?", String.class, accountName))
                .containsExactlyInAnyOrder(expectedIds);
        for (String id : expectedIds) owner.storedCredentialVersion(id);
        assertThat(indexedSessions.findByPrincipalName(accountName).keySet()).containsExactlyInAnyOrder(expectedIds);
    }

    private void assertNoSessionsIndexedBy(String name) {
        assertThat(runtimeJdbc.queryForObject(
                "select count(*) from public.spring_session where principal_name = ?", Integer.class, name)).isZero();
        assertThat(indexedSessions.findByPrincipalName(name)).isEmpty();
    }

    private void assertOrganizations(IdentityAcceptanceHttps.Browser client, String expected, String forbidden)
            throws Exception {
        var response = client.get(ORGANIZATIONS);
        expect(response, 200);
        JsonNode organizations = json.readTree(response.body()).path("data");
        assertThat(organizations.isArray()).isTrue();
        assertThat(organizations.size()).isEqualTo(1);
        assertThat(organizations.get(0).path("id").asString()).isEqualTo(expected);
        assertThat(response.body()).doesNotContain(forbidden);
    }

    private void assertCleanConnection() {
        assertThat(runtimeJdbc.queryForObject("select pg_backend_pid()", Integer.class)).isEqualTo(backendPid);
        assertThat(runtimeJdbc.queryForObject("""
                select nullif(current_setting('app.tenant_id', true), '') is null
                   and nullif(current_setting('app.login_tenant_code', true), '') is null
                """, Boolean.class)).isTrue();
    }

    private static void assertSessionCookie(IdentityAcceptanceHttps.Response login,
                                            IdentityAcceptanceHttps.Browser client) {
        var cookie = client.cookie("SESSION").orElseThrow();
        assertThat(cookie.getSecure()).isTrue();
        assertThat(cookie.isHttpOnly()).isTrue();
        assertThat(cookie.getPath()).isEqualTo("/");
        assertThat(login.http().headers().allValues("Set-Cookie").stream()
                .filter(value -> value.startsWith("SESSION="))
                .anyMatch(value -> value.toLowerCase(java.util.Locale.ROOT).contains("samesite=lax"))).isTrue();
    }

    private void expect(IdentityAcceptanceHttps.Response response, int status) throws Exception {
        assertThat(response.status()).as("HTTP acceptance response status").isEqualTo(status);
        sessions.awaitIdle();
    }

    /** Observation only: no principal injection, authentication replacement or session fabrication. */
    static final class SessionTrace {
        private final ConcurrentHashMap<String, String> requests = new ConcurrentHashMap<>();
        private final AtomicInteger inFlight = new AtomicInteger();
        Set<String> ids() { return Set.copyOf(requests.values()); }
        String idFor(String requestId) throws Exception {
            awaitIdle();
            String id = requests.get(requestId);
            assertThat(id).as("The real servlet filter must observe the saved session").isNotNull();
            return id;
        }

        void awaitIdle() throws InterruptedException {
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (inFlight.get() != 0 && System.nanoTime() < deadline) Thread.sleep(10);
            assertThat(inFlight.get()).as("Tracked servlet requests must finish before session inspection/cleanup")
                    .isZero();
        }
    }

    /** Advances only the application's lifetime policy; JDBC Session retains its real idle clock. */
    static final class AcceptanceClock extends Clock {
        private static final Instant START = Instant.parse("2026-11-02T00:00:00Z");
        private final AtomicReference<Instant> now;
        private final ZoneId zone;

        AcceptanceClock() { this(new AtomicReference<>(START), ZoneOffset.UTC); }
        private AcceptanceClock(AtomicReference<Instant> now, ZoneId zone) {
            this.now = now;
            this.zone = zone;
        }
        void reset() { now.set(START); }
        void advance(Duration duration) { now.updateAndGet(instant -> instant.plus(duration)); }
        @Override public ZoneId getZone() { return zone; }
        @Override public Clock withZone(ZoneId zone) { return new AcceptanceClock(now, zone); }
        @Override public Instant instant() { return now.get(); }
    }

    /** Limiter time is independent of authentication age and advances only monotonically. */
    static final class AcceptanceThrottleClock {
        private final AtomicLong nanos = new AtomicLong();
        long nanoTime() { return nanos.get(); }
        void advance(Duration duration) { nanos.addAndGet(duration.toNanos()); }
    }

    @Configuration(proxyBeanMethods = false)
    @TestComponent // Excluded from the main application's scan, but still an explicit primary config.
    @EnableAutoConfiguration(excludeName = {
            "org.springframework.modulith.events.config.EventPublicationAutoConfiguration",
            "org.springframework.modulith.events.jdbc.JdbcEventPublicationAutoConfiguration"})
    @Import({SecurityConfiguration.class,
            com.mightyoung.softwarefactory.auditops.JooqAuthenticationEvents.class,
            JooqAccountPasswordCredentialReadRepository.class, JooqTenantLoginContextResolver.class,
            JooqTenantApplicationReadRepository.class,
            SessionContextResolver.class, CurrentSessionService.class, JooqCurrentSessionReadRepository.class,
            CurrentSessionController.class, AuthenticationSessionController.class,
            JooqAccountStatusReadRepository.class, JooqPrincipalStatusReadRepository.class,
            JooqTenantMembershipReadRepository.class, JooqMemberDirectoryReadRepository.class,
            JooqRoleScopeReadRepository.class, PersistentFindingWorkflowAuthorizationRepository.class,
            ReportableOrganizationService.class, JooqReportableOrganizationReadRepository.class,
            ReportableOrganizationController.class, FindingApiExceptionHandler.class})
    static class IdentityWebConfiguration {
        @Bean SessionTrace sessionTrace() { return new SessionTrace(); }
        @Bean @Primary AcceptanceClock acceptanceClock() { return new AcceptanceClock(); }
        @Bean AcceptanceThrottleClock acceptanceThrottleClock() { return new AcceptanceThrottleClock(); }
        @Bean @Primary LoginAttemptLimiter acceptanceLoginAttemptLimiter(AcceptanceThrottleClock clock) {
            return new LoginAttemptLimiter(LoginAttemptLimiter.Config.defaults(), clock::nanoTime);
        }

        // SecurityConfiguration selects this production provider by its component-scan bean name.
        @Bean
        LocalPasswordAuthenticationProvider localPasswordAuthenticationProvider(
                AccountPasswordCredentialReadRepository credentials, TenantLoginContextResolver tenants,
                PasswordEncoder encoder, PlatformTransactionManager transactions) {
            return new LocalPasswordAuthenticationProvider(credentials, tenants, encoder, transactions);
        }

        @Bean
        FilterRegistrationBean<Filter> observeOnlyOwnSessions(SessionTrace trace) {
            Filter filter = (request, response, chain) -> {
                trace.inFlight.incrementAndGet();
                try {
                    chain.doFilter(request, response);
                } finally {
                    try {
                        var http = (HttpServletRequest) request;
                        String requestId = http.getHeader(IdentityAcceptanceHttps.TRACE_HEADER);
                        if (requestId != null) {
                            try {
                                HttpSession session = http.getSession(false);
                                if (session != null) trace.requests.put(requestId, session.getId());
                            } catch (IllegalStateException invalidatedSession) {
                                // Logout may already have invalidated the observed session.
                            }
                        }
                    } finally {
                        trace.inFlight.decrementAndGet();
                    }
                }
            };
            var registration = new FilterRegistrationBean<>(filter);
            registration.setOrder(-101); // After Spring Session, immediately before Spring Security.
            return registration;
        }
    }
}
