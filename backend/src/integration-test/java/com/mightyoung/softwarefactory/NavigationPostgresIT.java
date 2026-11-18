package com.mightyoung.softwarefactory;

import com.mightyoung.softwarefactory.applicationregistry.navigation.NavigationController;
import com.mightyoung.softwarefactory.applicationregistry.navigation.NavigationService;
import com.mightyoung.softwarefactory.authorization.PersistentEntityReadAccessPolicy;
import com.mightyoung.softwarefactory.authorization.PersistentEffectivePermissions;
import com.mightyoung.softwarefactory.authorization.PersistentFindingReadAuthorizationRepository;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

/** Real HTTPS authentication + JDBC entitlement/scope projection. No browser UI or workflow-command acceptance. */
@SpringBootTest(classes = NavigationPostgresIT.NavigationWebConfiguration.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"lan-postgres-compatibility", "identity-postgres-acceptance"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class NavigationPostgresIT {
    private static final String BOOTSTRAP = "/api/v1/session/bootstrap";
    private static final String MENU = "/api/v1/applications/safety/menu";
    private static final String ORGANIZATIONS = "/api/v1/organizations/reportable";
    private static IdentityAcceptanceHttps https;
    @LocalServerPort private int port;
    @Autowired private PasswordEncoder passwords;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate runtime;
    @Autowired private IdentityLoginPostgresIT.SessionTrace sessions;
    @Autowired private IdentityLoginPostgresIT.AcceptanceClock clock;
    @Autowired private IdentityLoginPostgresIT.AcceptanceThrottleClock throttle;
    private IdentityPostgresFixture fixture;
    private Set<String> previousSessions;
    private final List<IdentityAcceptanceHttps.Browser> clients = new ArrayList<>();

    @DynamicPropertySource static void properties(DynamicPropertyRegistry properties) throws Exception {
        var target = IdentityAcceptanceTarget.validateInputs(System.getenv());
        if (!("I_CONFIRM_NAVIGATION_FIXTURE_WRITES:" + target.databaseName())
                .equals(System.getenv("LAN_PG_NAVIGATION_ACCEPTANCE_ACK"))) {
            throw new IllegalStateException("Navigation acceptance requires database-specific fixture-write confirmation");
        }
        IdentityAcceptanceTarget.preflightFromEnvironment(System.getenv());
        if (https == null) https = IdentityAcceptanceHttps.create();
        properties.add("spring.datasource.url",target::jdbcUrl);
        properties.add("spring.datasource.username",() -> System.getenv("LAN_PG_APP_USERNAME"));
        properties.add("spring.datasource.password",() -> System.getenv("LAN_PG_APP_PASSWORD"));
        properties.add("spring.datasource.hikari.maximum-pool-size",() -> 1);
        properties.add("spring.datasource.hikari.minimum-idle",() -> 1);
        properties.add("server.address",() -> "127.0.0.1");
        properties.add("server.ssl.enabled",() -> true);
        properties.add("server.ssl.key-store",() -> https.keyStore().toUri().toString());
        properties.add("server.ssl.key-store-password",https::password);
        properties.add("server.ssl.key-store-type",() -> "PKCS12");
        properties.add("server.ssl.key-alias",() -> "identity-acceptance");
        properties.add("flowable.process.enabled",() -> false);
        properties.add("flowable.idm.enabled",() -> false);
        properties.add("flowable.eventregistry.enabled",() -> false);
        properties.add("spring.modulith.events.externalization.enabled",() -> false);
    }

    @BeforeEach void seed() throws Exception {
        sessions.awaitIdle(); clock.reset(); throttle.advance(Duration.ofMinutes(10));
        previousSessions = sessions.ids();
        // Fail before fixture writes on a target without V11 or the runtime SELECT grant.
        assertThat(runtime.queryForObject("""
                select relrowsecurity and relforcerowsecurity
                from pg_class where oid = 'public.tenant_application'::regclass
                """,Boolean.class)).isTrue();
        // Read the required V11 shape without granting the runtime access to Flyway history.
        runtime.queryForList("select tenant_id, application_key, enabled, version from public.tenant_application limit 0");
        assertThat(runtime.queryForObject("select has_table_privilege(current_user, 'public.tenant_application', 'SELECT')",Boolean.class)).isTrue();
        assertThat(runtime.queryForObject("select rolsuper or rolbypassrls or rolinherit from pg_roles where rolname = current_user",Boolean.class)).isFalse();
        fixture = IdentityPostgresFixture.create(passwords);
        fixture.enableNavigationReads();
    }

    @AfterEach void cleanup() throws Exception {
        try { for (var client : clients) client.close(); }
        finally {
            clients.clear();
            if (fixture != null) try {
                sessions.awaitIdle();
                Set<String> owned = new HashSet<>(sessions.ids()); owned.removeAll(previousSessions);
                fixture.cleanupSessions(owned);
            } finally { fixture.close(); fixture = null; }
        }
    }
    @AfterAll static void removeTls() throws Exception { if (https != null) https.close(); }

    @Test void twoTenantEntitlementsControlMinimalBootstrapAndOnDemandMenu() throws Exception {
        var anonymous = client(); expect(anonymous.get(BOOTSTRAP),401); expect(anonymous.get(MENU),401);
        fixture.setSafetyApplicationB(false);
        var a = login(fixture.tenantACode()); var b = login(fixture.tenantBCode());
        JsonNode allowed = data(a.get(BOOTSTRAP),200);
        assertThat(allowed.path("identity").path("tenantId").asString()).isEqualTo(fixture.tenantAId().toString());
        assertThat(allowed.path("applications").size()).isEqualTo(1);
        assertThat(allowed.path("applications").get(0).path("id").asString()).isEqualTo("safety");
        assertThat(allowed.path("applications").get(0).path("entryPath").asString()).isEqualTo("safety/findings");
        assertThat(allowed.path("permissions").toString()).contains("finding.read","finding.create");
        assertThat(allowed.path("schemaVersion").asInt()).isEqualTo(1);
        assertThat(allowed.has("nodes") || allowed.has("menus") || allowed.has("organizations") || allowed.has("dictionaries")).isFalse();
        assertThat(allowed.toString()).doesNotContain("credentialVersion","secretHash","secret_hash",fixture.organizationAId().toString());
        JsonNode denied = data(b.get(BOOTSTRAP),200);
        assertThat(denied.path("identity").path("tenantId").asString()).isEqualTo(fixture.tenantBId().toString());
        assertThat(denied.path("applications").size()).isZero(); assertThat(denied.path("permissions").size()).isZero();
        expect(b.get(MENU),403); expect(b.get(ORGANIZATIONS),403);
        JsonNode menu = data(a.get(MENU),200);
        assertThat(menu.path("applicationId").asString()).isEqualTo("safety");
        assertThat(menu.path("nodes").size()).isEqualTo(1);
        assertThat(menu.path("nodes").get(0).path("permission").asString()).isEqualTo("finding.read");
        expect(a.get("/api/v1/applications/equipment/menu"),403);
        assertCleanTenantContext();
    }

    @Test void entitlementDisableMissingAndEmptyScopeAreFreshAndFailClosed() throws Exception {
        var a = login(fixture.tenantACode());
        assertThat(data(a.get(BOOTSTRAP),200).path("authorization").path("applicationVersion").asLong()).isEqualTo(1);
        fixture.setSafetyApplicationA(false);
        JsonNode disabled = data(a.get(BOOTSTRAP),200);
        assertThat(disabled.path("applications").size()).isZero();
        assertThat(disabled.path("authorization").path("applicationVersion").asLong()).isEqualTo(2);
        expect(a.get(MENU),403); expect(a.get(ORGANIZATIONS),403);
        fixture.removeSafetyApplicationA();
        assertThat(data(a.get(BOOTSTRAP),200).path("applications").size()).isZero();
        expect(a.get(MENU),403); expect(a.get(ORGANIZATIONS),403);
        fixture.setSafetyApplicationA(true);
        assertThat(data(a.get(BOOTSTRAP),200).path("applications").size()).isEqualTo(1);
        fixture.revokeGrantA();
        JsonNode noScope = data(a.get(BOOTSTRAP),200);
        assertThat(noScope.path("applications").size()).isZero(); assertThat(noScope.path("permissions").size()).isZero();
        expect(a.get(MENU),403); assertCleanTenantContext();
    }

    @Test void revokedCreatePermissionDisappearsWithoutRemovingReadMenuOrExposingOrganizations() throws Exception {
        var a = login(fixture.tenantACode());
        assertThat(data(a.get(BOOTSTRAP),200).path("permissions").toString()).contains("finding.create");
        assertThat(data(a.get(ORGANIZATIONS),200).size()).isEqualTo(1);
        fixture.revokeCreatePermissionA();
        JsonNode after = data(a.get(BOOTSTRAP),200);
        assertThat(after.path("permissions").size()).isEqualTo(1);
        assertThat(after.path("permissions").get(0).asString()).isEqualTo("finding.read");
        data(a.get(MENU),200);
        // Existing selector contract denies data with 200 []; this does not prove workflow-command rejection.
        assertThat(data(a.get(ORGANIZATIONS),200).size()).isZero();
        assertCleanTenantContext();
    }

    private IdentityAcceptanceHttps.Browser client() {
        var client = https.browser(port); clients.add(client); return client;
    }
    private IdentityAcceptanceHttps.Browser login(String tenantCode) throws Exception {
        var client = client(); expect(client.get("/api/v1/auth/csrf"),204);
        expect(client.post("/api/v1/auth/login",json.writeValueAsString(Map.of("loginKey",fixture.loginKey(),
                "password",fixture.password(),"tenantCode",tenantCode)),client.csrf()),204);
        return client;
    }
    private void expect(IdentityAcceptanceHttps.Response response,int status) throws Exception {
        assertThat(response.status()).as("Navigation HTTPS status").isEqualTo(status); sessions.awaitIdle();
    }
    private JsonNode data(IdentityAcceptanceHttps.Response response,int status) throws Exception {
        expect(response,status); return json.readTree(response.body()).path("data");
    }
    private void assertCleanTenantContext() {
        assertThat(runtime.queryForObject("select nullif(current_setting('app.tenant_id',true),'') is null",Boolean.class)).isTrue();
    }

    @Configuration(proxyBeanMethods = false)
    @TestComponent
    @Import({IdentityLoginPostgresIT.IdentityWebConfiguration.class, NavigationController.class,
            NavigationService.class, PersistentEntityReadAccessPolicy.class, PersistentFindingReadAuthorizationRepository.class,
            PersistentEffectivePermissions.class})
    static class NavigationWebConfiguration { }
}
