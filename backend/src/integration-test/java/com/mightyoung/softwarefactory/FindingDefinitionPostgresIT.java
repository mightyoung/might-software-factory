package com.mightyoung.softwarefactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mightyoung.softwarefactory.applicationregistry.definition.EntityDefinition.Use;
import com.mightyoung.softwarefactory.applicationregistry.navigation.NavigationController;
import com.mightyoung.softwarefactory.applicationregistry.navigation.NavigationService;
import com.mightyoung.softwarefactory.applicationregistry.reference.ReferenceQueryContract;
import com.mightyoung.softwarefactory.authorization.PersistentEntityReadAccessPolicy;
import com.mightyoung.softwarefactory.authorization.PersistentFindingReadAuthorizationRepository;
import com.mightyoung.softwarefactory.dualprevention.FindingDefinitionController;
import com.mightyoung.softwarefactory.dualprevention.FindingDefinitionProvider;
import com.mightyoung.softwarefactory.dualprevention.FindingDefinitionReadRepository;
import com.mightyoung.softwarefactory.dualprevention.FindingDefinitionService;
import com.mightyoung.softwarefactory.dualprevention.FindingDefinitionUnavailableException;
import com.mightyoung.softwarefactory.dualprevention.FindingOrganizationReferenceRequestParser;
import com.mightyoung.softwarefactory.dualprevention.FindingReadAuthorizer;
import com.mightyoung.softwarefactory.dualprevention.JooqFindingDefinitionReadRepository;
import com.mightyoung.softwarefactory.identity.CurrentTenantContext;
import com.mightyoung.softwarefactory.identity.VerifiedSessionPrincipal;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.servlet.Filter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Actual narrow HTTPS definition/reference routes and PostgreSQL authority; no Finding or engine writes. */
@SpringBootTest(classes = FindingDefinitionPostgresIT.DefinitionWebConfiguration.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"lan-postgres-compatibility", "identity-postgres-acceptance"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class FindingDefinitionPostgresIT {
    private static final String DEFINITION = G013ObservedDataSource.DEFINITION_PATH;
    private static final String REFERENCE = G013ObservedDataSource.REFERENCE_PATH;
    private static final String BOOTSTRAP = "/api/v1/session/bootstrap";
    private static final String MENU = "/api/v1/applications/safety/menu";
    private static IdentityAcceptanceHttps https;
    @LocalServerPort private int port;
    @Autowired private PasswordEncoder passwords;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate runtime;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private JdbcIndexedSessionRepository sessions;
    @Autowired private IdentityLoginPostgresIT.SessionTrace trace;
    @Autowired private IdentityLoginPostgresIT.AcceptanceClock clock;
    @Autowired private IdentityLoginPostgresIT.AcceptanceThrottleClock throttle;
    @Autowired private G013ObservedDataSource observed;
    @Autowired private FindingDefinitionReadRepository reads;
    private G013DefinitionPostgresFixture fixture;
    private Set<String> previousSessions;
    private final List<IdentityAcceptanceHttps.Browser> clients = new ArrayList<>();

    @DynamicPropertySource static void properties(DynamicPropertyRegistry properties) throws Exception {
        var target = IdentityAcceptanceTarget.validateInputs(System.getenv());
        for (var entry : Map.of("LAN_PG_NAVIGATION_ACCEPTANCE_ACK", "I_CONFIRM_NAVIGATION_FIXTURE_WRITES:",
                "LAN_PG_AUTHORIZATION_CONCURRENCY_ACK", "I_CONFIRM_AUTHORIZATION_CONCURRENCY_WRITES:").entrySet()) {
            if (!(entry.getValue() + target.databaseName()).equals(System.getenv(entry.getKey())))
                throw new IllegalStateException("Definition acceptance requires exact existing fixture confirmations");
        }
        IdentityAcceptanceTarget.preflightFromEnvironment(System.getenv());
        if (https == null) https = IdentityAcceptanceHttps.create();
        properties.add("spring.datasource.url", target::jdbcUrl);
        properties.add("spring.datasource.username", () -> required("LAN_PG_APP_USERNAME"));
        properties.add("spring.datasource.password", () -> required("LAN_PG_APP_PASSWORD"));
        properties.add("spring.datasource.hikari.maximum-pool-size", () -> 4);
        properties.add("spring.datasource.hikari.minimum-idle", () -> 0);
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

    @BeforeEach void seedOnlyOwnedRows() throws Exception {
        trace.awaitIdle(); clock.reset(); throttle.advance(java.time.Duration.ofMinutes(10));
        observed.clear(); previousSessions = trace.ids();
        assertThat(runtime.queryForObject("select rolsuper or rolbypassrls or rolinherit from pg_roles where rolname=current_user", Boolean.class)).isFalse();
        assertThat(runtime.queryForObject("select has_schema_privilege(current_user,'public','CREATE')", Boolean.class)).isFalse();
        for (String table : List.of("account", "principal", "tenant", "tenant_member", "tenant_application", "org_unit",
                "member_org", "position", "member_position", "user_group", "group_member", "permission", "role",
                "role_permission", "role_grant", "scope_rule", "scope_org")) {
            assertThat(runtime.queryForObject("select has_table_privilege(current_user,?,'SELECT')", Boolean.class,
                    "public.\"" + table + "\"")).as("Existing definition read privilege").isTrue();
        }
        runtime.queryForList("select tenant_id,application_key,enabled,version from public.tenant_application limit 0");
        fixture = G013DefinitionPostgresFixture.create(passwords);
    }

    @AfterEach void cleanupOnlyOwnedRowsAndSessions() throws Exception {
        SecurityContextHolder.clearContext();
        try { for (var client : clients) client.close(); }
        finally {
            clients.clear();
            if (fixture != null) {
                try {
                    trace.awaitIdle();
                    var owned = new HashSet<>(trace.ids()); owned.removeAll(previousSessions);
                    fixture.identity().cleanupSessions(owned);
                } finally { fixture.close(); fixture = null; }
            }
        }
        observed.clear();
    }
    @AfterAll static void closeOwnTls() throws Exception { if (https != null) https.close(); }

    @Test void createOnlyMemberReceivesOnlyCreateProjectionThroughRealPersistedLogin() throws Exception {
        var browser = login();
        assertNavigationProjection(browser, "finding.create");
        JsonNode data = data(browser.get(DEFINITION + "?use=CREATE"), 200);
        assertThat(data.path("fields").size()).isEqualTo(11);
        assertThat(data.path("layout").path("create").size()).isEqualTo(7);
        assertThat(data.path("fields").has("state") || data.path("fields").has("version") || data.path("fields").has("backfill")).isFalse();
        assertThat(data.path("fields").path("grade").path("query").path("enumValues").get(0).asString()).isEqualTo("UNKNOWN");
        expect(browser.get(DEFINITION + "?use=READ"), 403);
        String sessionId = onlyOwnedPersistedSession().getId();
        assertThat(fixture.identity().sessionExists(sessionId)).isTrue();
        assertThat(fixture.identity().storedPrincipalName(sessionId)).isEqualTo(fixture.identity().accountId().toString());
    }

    @Test void readOnlyMemberCannotAcquireCreateDefinitionOrCandidateData() throws Exception {
        fixture.enableReads(); fixture.revokeCreate();
        var browser = login();
        assertNavigationProjection(browser, "finding.read");
        JsonNode read = data(browser.get(DEFINITION + "?use=READ"), 200);
        assertThat(read.path("fields").size()).isEqualTo(22);
        assertThat(read.path("actions").size()).isZero();
        assertThat(read.path("references").size()).isZero();
        assertThat(read.path("layout").path("create").size()).isZero();
        assertThat(read.path("fields").path("description").path("presentation").path("edit").isNull()
                || read.path("fields").path("description").path("presentation").path("edit").isMissingNode()).isTrue();
        expect(browser.get(DEFINITION + "?use=CREATE"), 403);
        var denied = reference(browser, "", null, 2); expect(denied, 403); noCandidateAttempt(denied);
    }

    @Test void anonymousAndNoActionSessionsCannotReadProtectedDefinitionOrOrganizations() throws Exception {
        var anonymous = client(); expect(anonymous.get(DEFINITION + "?use=CREATE"), 401);
        var browser = login(); fixture.identity().revokeGrantA();
        JsonNode empty = data(browser.get(BOOTSTRAP), 200);
        assertThat(empty.path("applications").size()).isZero();
        assertThat(empty.path("permissions").size()).isZero();
        expect(browser.get(MENU), 403);
        expect(browser.get(DEFINITION + "?use=CREATE"), 403);
        var denied = reference(browser, "", null, 2); expect(denied, 403); noCandidateAttempt(denied);
    }

    @Test void semanticEtagIsPrivateCurrentProjectionSpecificAndIndependentOfEnvelopeIds() throws Exception {
        fixture.enableReads(); var browser = login();
        var first = browser.get(DEFINITION + "?use=READ");
        var second = browser.get(DEFINITION + "?use=READ");
        expect(first, 200); expect(second, 200);
        String etag = etag(first);
        assertThat(etag).matches("W/\"[0-9a-f]{64}\"");
        assertThat(etag(second)).isEqualTo(etag);
        assertThat(first.body()).isNotEqualTo(second.body());
        assertThat(etag(browser.get(DEFINITION + "?use=CREATE"))).isNotEqualTo(etag);
        assertThat(first.http().headers().firstValue("Cache-Control").orElseThrow()).contains("private", "no-cache");
        assertThat(first.http().headers().firstValue("Vary").orElseThrow()).contains("Cookie");
    }

    @Test void authorizedWeakListAndWildcardConditionalsReturnBodylessCurrent304() throws Exception {
        fixture.enableReads(); var browser = login();
        String etag = etag(browser.get(DEFINITION + "?use=READ"));
        List<String> validators = List.of(etag, "\"unrelated\", " + etag.substring(2), "*");
        List<String> variants = List.of("weak", "strong-list", "wildcard");
        for (int index = 0; index < validators.size(); index++) {
            var response = browser.getWithHeaders(DEFINITION + "?use=READ", Map.of("If-None-Match", validators.get(index)));
            assertThat(response.status()).as("Conditional validator variant: %s", variants.get(index)).isEqualTo(304);
            expect(response, 304); assertThat(response.body()).isEmpty();
            assertThat(etag(response)).isEqualTo(etag);
            assertThat(response.http().headers().firstValue("Cache-Control").orElseThrow()).contains("private", "no-cache");
            assertThat(response.http().headers().firstValue("Vary").orElseThrow()).contains("Cookie");
        }
    }

    @Test void revokedGrantIsDeniedBeforeOldOrWildcardValidatorCanReturn304() throws Exception {
        fixture.enableReads(); var browser = login();
        String etag = etag(browser.get(DEFINITION + "?use=READ"));
        fixture.identity().revokeGrantA();
        expect(browser.getWithHeaders(DEFINITION + "?use=READ", Map.of("If-None-Match", etag)), 403);
        expect(browser.getWithHeaders(DEFINITION + "?use=READ", Map.of("If-None-Match", "*")), 403);
    }

    enum CurrentMutation {
        CREDENTIAL(401), ACCOUNT(401), PRINCIPAL(403), MEMBERSHIP(403), TENANT(403), ENTITLEMENT(403);
        final int status;
        CurrentMutation(int status) { this.status = status; }
        void apply(IdentityPostgresFixture fixture) {
            switch (this) {
                case CREDENTIAL -> fixture.rotatePasswordAndCredentialVersion();
                case ACCOUNT -> fixture.disableAccount();
                case PRINCIPAL -> fixture.disablePrincipal();
                case MEMBERSHIP -> fixture.revokeMembershipA();
                case TENANT -> fixture.suspendTenantA();
                case ENTITLEMENT -> fixture.setSafetyApplicationA(false);
            }
        }
    }
    @ParameterizedTest @EnumSource(CurrentMutation.class)
    void currentIdentityAndEntitlementRejectionsPrecedeConditionalResponse(CurrentMutation mutation) throws Exception {
        fixture.enableReads(); var browser = login();
        String etag = etag(browser.get(DEFINITION + "?use=READ"));
        mutation.apply(fixture.identity());
        expect(browser.getWithHeaders(DEFINITION + "?use=READ", Map.of("If-None-Match", etag)), mutation.status);
    }

    @Test void enabledApplicationVersionChangesValidatorAndMissingEntitlementDenies() throws Exception {
        var browser = login(); String previous = etag(browser.get(DEFINITION + "?use=CREATE"));
        fixture.identity().setSafetyApplicationA(true);
        var changed = browser.getWithHeaders(DEFINITION + "?use=CREATE", Map.of("If-None-Match", previous));
        expect(changed, 200); assertThat(etag(changed)).isNotEqualTo(previous);
        fixture.identity().removeSafetyApplicationA();
        expect(browser.getWithHeaders(DEFINITION + "?use=CREATE", Map.of("If-None-Match", "*")), 403);
    }

    @Test void finiteProjectionSelectorRejectsMissingDuplicateUnknownAndUndeclaredParameters() throws Exception {
        var browser = login();
        for (String suffix : List.of("", "?use=CREATE&use=READ", "?use=create", "?use=finding.create", "?use=CREATE&permission=finding.create"))
            expect(browser.get(DEFINITION + suffix), 400);
    }

    @Test void referenceRequiresRealCsrfAndHasNoCandidateAttemptOnDenial() throws Exception {
        var browser = login();
        var missing = browser.post(REFERENCE, "{\"query\":\"\"}", null);
        expect(missing, 403); noCandidateAttempt(missing);
        var incorrect = browser.post(REFERENCE, "{\"query\":\"\"}", "incorrect-test-csrf");
        expect(incorrect, 403); noCandidateAttempt(incorrect);
        data(reference(browser, "", null, 2), 200);
    }

    @Test void boundedUuidKeysetPagesHaveNoDuplicatesOrFalseTerminalCursor() throws Exception {
        var browser = login(); List<UUID> all = new ArrayList<>(); UUID after = null;
        for (int page = 0; page < 4; page++) {
            JsonNode result = data(reference(browser, "", after, 2), 200);
            List<UUID> ids = ids(result); assertThat(ids).hasSizeLessThanOrEqualTo(2); all.addAll(ids);
            if (!result.has("nextAfterId")) break;
            after = UUID.fromString(result.path("nextAfterId").asString());
            assertThat(after).isEqualTo(ids.getLast());
            assertThat(page).isLessThan(3);
        }
        assertThat(all).doesNotHaveDuplicates().isEqualTo(fixture.expectedOrganizationIds());
        var last = data(reference(browser, "", all.getLast(), 2), 200);
        assertThat(ids(last)).isEmpty(); assertThat(last.has("nextAfterId")).isFalse();
        var defaultLimit = data(browser.post(REFERENCE, "{\"query\":\"\"}", browser.csrf()), 200);
        assertThat(ids(defaultLimit)).isEqualTo(fixture.expectedOrganizationIds());
    }

    @ParameterizedTest
    @MethodSource("literalQueries")
    void literalSearchDoesNotTreatSqlWildcardCharactersAsPatterns(String query, String name) throws Exception {
        var browser = login();
        assertThat(ids(data(reference(browser, query, null, 20), 200))).containsExactly(fixture.organizationNamed(name));
    }
    static Stream<Arguments> literalQueries() {
        return Stream.of(Arguments.of("%", "Line % literal"), Arguments.of("_", "Line _ literal"),
                Arguments.of("\\", "Line \\ literal"), Arguments.of("设备🧪", "设备🧪线"));
    }

    @Test void candidateScopeAndForceRlsNeverExposeOtherTenantInactiveOrUnscopedRows() throws Exception {
        var browser = login();
        var response = reference(browser, "", null, 50);
        assertThat(ids(data(response, 200))).isEqualTo(fixture.expectedOrganizationIds());
        assertThat(response.body()).doesNotContain(fixture.identity().organizationBId().toString(), "Inactive reference", "Out of scope reference");
        var cursor = data(reference(browser, "", fixture.identity().organizationBId(), 50), 200);
        assertThat(ids(cursor)).isSubsetOf(fixture.expectedOrganizationIds());
        var read = new TransactionTemplate(transactions); read.setReadOnly(true);
        read.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        Integer invisible = read.execute(status -> {
            runtime.queryForObject("select set_config('app.tenant_id',?,true)", String.class, fixture.identity().tenantAId().toString());
            return runtime.queryForObject("select count(*) from public.org_unit where id=?", Integer.class,
                    fixture.identity().organizationBId());
        });
        assertThat(invisible).isZero();
    }

    @Test void queryLengthUsesUnicodeCodePointsAndBodyLimitUsesActualBytes() throws Exception {
        var browser = login();
        expect(reference(browser, "🧪".repeat(100), null, 20), 200);
        var tooLong = reference(browser, "🧪".repeat(101), null, 20); expect(tooLong, 400); noCandidateAttempt(tooLong);
        byte[] valid = ("{\"query\":\"\"}" + " ".repeat(2036)).getBytes(StandardCharsets.UTF_8);
        assertThat(valid).hasSize(2048);
        expect(browser.postRaw(REFERENCE, valid, browser.csrf(), List.of("application/json")), 200);
        var over = browser.postRaw(REFERENCE, new byte[2049], browser.csrf(), List.of("application/json"));
        expect(over, 413); noCandidateAttempt(over);
    }

    @ParameterizedTest @MethodSource("invalidBodies")
    void strictReferenceBodyRejectsOneMalformedContract(String body) throws Exception {
        var browser = login(); var response = browser.post(REFERENCE, body, browser.csrf());
        expect(response, 400); noCandidateAttempt(response);
    }
    static Stream<String> invalidBodies() {
        return Stream.of("{}", "[]", "{\"query\":null}", "{\"query\":\"\",\"query\":\"x\"}",
                "{\"query\":\"\",\"unknown\":true}", "{\"query\":\"\",\"limit\":null}",
                "{\"query\":\"\",\"limit\":\"2\"}", "{\"query\":\"\",\"limit\":0}",
                "{\"query\":\"\",\"limit\":51}", "{\"query\":\"\",\"afterId\":\"1-1-1-1-1\"}",
                "{\"query\":\"\"} {}", "{\"query\":\"\\ud800\"}");
    }

    @ParameterizedTest @MethodSource("invalidWire")
    void strictUtf8AndMediaRejectOneInvalidWireBody(byte[] body, List<String> types, int status) throws Exception {
        var browser = login(); var response = browser.postRaw(REFERENCE, body, browser.csrf(), types);
        expect(response, status); noCandidateAttempt(response);
    }
    static Stream<Arguments> invalidWire() {
        byte[] json = "{\"query\":\"\"}".getBytes(StandardCharsets.UTF_8);
        return Stream.of(Arguments.of(new byte[]{(byte) 0xc3, 0x28}, List.of("application/json"), 400),
                Arguments.of(("\ufeff{\"query\":\"\"}").getBytes(StandardCharsets.UTF_8), List.of("application/json"), 400),
                Arguments.of(json, List.of("text/plain"), 415),
                Arguments.of(json, List.of("application/json;charset=UTF-16"), 415),
                Arguments.of(json, List.of("application/json", "application/json"), 415));
    }

    @Test void undeclaredReferenceQueryParametersAreRejectedBeforeCandidateExecution() throws Exception {
        var browser = login(); var response = browser.post(REFERENCE + "?permission=finding.read", "{\"query\":\"\"}", browser.csrf());
        expect(response, 400); noCandidateAttempt(response);
    }

    @Test void actualAuthorityAndLocalCandidateSqlUseOneOwnerReadOnlySnapshot() throws Exception {
        fixture.enableReads(); var browser = login();
        var read = browser.get(DEFINITION + "?use=READ"); expect(read, 200);
        requireOneOwnerCut(observed.events(read.trace()), false);
        var reference = reference(browser, "", null, 2); expect(reference, 200);
        requireOneOwnerCut(observed.events(reference.trace()), true);
    }

    @Test void committedWriterCannotMixNewCandidateStateWithAnOldAuthorityCut() throws Exception {
        var browser = login(); UUID changed = fixture.organizationNamed("Plain reference line");
        try (var executor = Executors.newSingleThreadExecutor(); var barrier = observed.armCandidate(fixture.identity().tenantAId())) {
            var response = executor.submit(() -> reference(browser, "", null, 50));
            try {
                assertThat(barrier.awaitReached()).as("Actual candidate execution reached the owned boundary").isTrue();
                fixture.revokeAndDeactivate(changed); // returns only after actual writer commit
            } finally { barrier.close(); }
            var oldCut = response.get(15, TimeUnit.SECONDS); expect(oldCut, 200);
            assertThat(ids(data(oldCut, 200))).contains(changed);
            requireOneOwnerCut(observed.events(oldCut.trace()), true);
            var newCut = reference(browser, "", null, 50); expect(newCut, 403); noCandidateAttempt(newCut);
        }
    }

    @Test void everyFollowingPageReauthorizesTheCurrentGrant() throws Exception {
        var browser = login(); JsonNode first = data(reference(browser, "", null, 2), 200);
        UUID cursor = UUID.fromString(first.path("nextAfterId").asString());
        fixture.identity().revokeGrantA();
        var response = reference(browser, "", cursor, 2); expect(response, 403); noCandidateAttempt(response);
        assertCleanConnection();
    }

    @Test void inProcessCopiedAndEscapedAuthorityCannotOutliveTheOwnerSnapshot() throws Exception {
        var browser = login(); trace.awaitIdle();
        var session = onlyOwnedPersistedSession();
        SecurityContext persisted = session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        assertThat(persisted).isNotNull();
        var principal = (VerifiedSessionPrincipal) persisted.getAuthentication().getPrincipal();
        var context = new CurrentTenantContext(principal.actorId(), principal.accountId(), principal.tenantId(), principal.memberId(),
                principal.tenantAuthEpoch(), principal.memberAuthEpoch(), principal.credentialVersion());
        SecurityContextHolder.setContext(persisted);
        var escaped = new AtomicReference<FindingDefinitionReadRepository.Snapshot>();
        try {
            reads.inSnapshot(context, snapshot -> {
                var authority = snapshot.authorize(Use.CREATE);
                var copied = new FindingDefinitionReadRepository.Authority(authority.use(), authority.context(),
                        authority.organizationIds(), authority.applicationVersion());
                assertThatThrownBy(() -> snapshot.organizations(copied, new ReferenceQueryContract.Request("", null, 2)))
                        .isInstanceOf(FindingDefinitionUnavailableException.class);
                escaped.set(snapshot);
                return null;
            });
            assertThatThrownBy(() -> escaped.get().requireActive()).isInstanceOf(FindingDefinitionUnavailableException.class);
        } finally { SecurityContextHolder.clearContext(); }
        assertCleanConnection();
    }

    private static void requireOneOwnerCut(List<G013ObservedDataSource.Event> events, boolean candidates) {
        var actual = events.stream().filter(event -> event.stage() == G013ObservedDataSource.Stage.SUCCESS
                && event.phase() == G013ObservedDataSource.Phase.OWNER_RO_RR).toList();
        assertThat(actual).isNotEmpty();
        var first = actual.getFirst().state();
        assertThat(actual).allSatisfy(event -> {
            assertThat(event.state().ownerRead()).isTrue();
            assertThat(event.state().pid()).isEqualTo(first.pid());
            assertThat(event.state().transactionAt()).isEqualTo(first.transactionAt());
            assertThat(event.state().tenant()).isEqualTo(first.tenant());
        });
        var labels = actual.stream().map(G013ObservedDataSource.Event::label).toList();
        assertThat(labels).contains(G013ObservedDataSource.Label.APPLICATION, G013ObservedDataSource.Label.ACCOUNT,
                G013ObservedDataSource.Label.PRINCIPAL, G013ObservedDataSource.Label.MEMBERSHIP,
                G013ObservedDataSource.Label.DIRECTORY, G013ObservedDataSource.Label.ROLE);
        if (candidates) assertThat(labels).contains(G013ObservedDataSource.Label.CANDIDATE);
        assertThat(events.stream().filter(event -> event.label() == G013ObservedDataSource.Label.ACCOUNT
                && event.phase() == G013ObservedDataSource.Phase.PREFILTER_OR_CONTEXT)).isNotEmpty();
    }
    private void noCandidateAttempt(IdentityAcceptanceHttps.Response response) {
        assertThat(observed.events(response.trace()).stream().filter(event -> event.label() == G013ObservedDataSource.Label.CANDIDATE
                && event.stage() == G013ObservedDataSource.Stage.ATTEMPT)).isEmpty();
    }
    private IdentityAcceptanceHttps.Browser client() {
        var browser = https.browser(port); clients.add(browser); return browser;
    }
    private IdentityAcceptanceHttps.Browser login() throws Exception {
        var browser = client(); expect(browser.get("/api/v1/auth/csrf"), 204);
        expect(browser.post("/api/v1/auth/login", json.writeValueAsString(Map.of("loginKey", fixture.identity().loginKey(),
                "password", fixture.identity().password(), "tenantCode", fixture.identity().tenantACode())), browser.csrf()), 204);
        expect(browser.get("/api/v1/session/current"), 200); trace.awaitIdle(); return browser;
    }
    private IdentityAcceptanceHttps.Response reference(IdentityAcceptanceHttps.Browser browser, String query, UUID after, int limit) throws Exception {
        var body = new java.util.LinkedHashMap<String, Object>(); body.put("query", query); body.put("limit", limit);
        if (after != null) body.put("afterId", after.toString());
        return browser.post(REFERENCE, json.writeValueAsString(body), browser.csrf());
    }
    private void expect(IdentityAcceptanceHttps.Response response, int status) throws Exception {
        assertThat(response.status()).as("Owned definition/reference HTTPS status").isEqualTo(status); trace.awaitIdle();
    }
    private void assertNavigationProjection(IdentityAcceptanceHttps.Browser browser, String permission) throws Exception {
        JsonNode bootstrap = data(browser.get(BOOTSTRAP), 200);
        assertThat(bootstrap.path("identity").path("tenantId").asString()).isEqualTo(fixture.identity().tenantAId().toString());
        assertThat(bootstrap.path("applications").size()).isEqualTo(1);
        assertThat(bootstrap.path("applications").get(0).path("id").asString()).isEqualTo("safety");
        assertThat(bootstrap.path("applications").get(0).path("entryPath").asString()).isEqualTo("safety/findings");
        assertThat(bootstrap.path("permissions").size()).isEqualTo(1);
        assertThat(bootstrap.path("permissions").get(0).asString()).isEqualTo(permission);
        JsonNode menu = data(browser.get(MENU), 200);
        assertThat(menu.path("applicationId").asString()).isEqualTo("safety");
        assertThat(menu.path("permissions").size()).isEqualTo(1);
        assertThat(menu.path("permissions").get(0).asString()).isEqualTo(permission);
        assertThat(menu.path("nodes").size()).isEqualTo(1);
        assertThat(menu.path("nodes").get(0).path("permission").asString()).isEqualTo(permission);
        assertThat(menu.path("nodes").get(0).path("path").asString()).isEqualTo("safety/findings");
    }
    private JsonNode data(IdentityAcceptanceHttps.Response response, int status) throws Exception {
        expect(response, status); return json.readTree(response.body()).path("data");
    }
    private static List<UUID> ids(JsonNode data) {
        var result = new ArrayList<UUID>();
        data.path("items").forEach(item -> result.add(UUID.fromString(item.path("id").asString())));
        return List.copyOf(result);
    }
    private String etag(IdentityAcceptanceHttps.Response response) throws Exception {
        if (response.status() != 304) expect(response, 200);
        return response.http().headers().firstValue("ETag").orElseThrow();
    }
    private void assertCleanConnection() {
        assertThat(runtime.queryForObject("select nullif(current_setting('app.tenant_id',true),'') is null", Boolean.class)).isTrue();
    }
    private org.springframework.session.Session onlyOwnedPersistedSession() {
        var owned = sessions.findByPrincipalName(fixture.identity().accountId().toString());
        assertThat(owned).as("Actual persisted canonical account index").hasSize(1);
        return owned.values().iterator().next();
    }
    private static String required(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) throw new IllegalStateException("Missing guarded acceptance input");
        return value;
    }

    @Configuration(proxyBeanMethods = false)
    @TestComponent
    @Import({IdentityLoginPostgresIT.IdentityWebConfiguration.class, NavigationController.class, NavigationService.class,
            PersistentFindingReadAuthorizationRepository.class,
            PersistentEntityReadAccessPolicy.class, FindingReadAuthorizer.class, FindingDefinitionProvider.class,
            FindingDefinitionController.class, FindingDefinitionService.class, FindingOrganizationReferenceRequestParser.class,
            JooqFindingDefinitionReadRepository.class})
    static class DefinitionWebConfiguration {
        @Bean @Primary G013ObservedDataSource dataSource(Environment environment) {
            var config = new HikariConfig();
            config.setJdbcUrl(environment.getRequiredProperty("spring.datasource.url"));
            config.setUsername(environment.getRequiredProperty("spring.datasource.username"));
            config.setPassword(environment.getRequiredProperty("spring.datasource.password"));
            config.setMaximumPoolSize(4); config.setMinimumIdle(0);
            config.setPoolName("g013-runtime-observed");
            return new G013ObservedDataSource(new HikariDataSource(config));
        }
        @Bean FilterRegistrationBean<Filter> definitionSqlRequestObservation(G013ObservedDataSource source) {
            var registration = new FilterRegistrationBean<>(source.requestScope());
            registration.setOrder(-102); // after JDBC Session, before existing trace (-101) and Security (-100)
            registration.setDispatcherTypes(jakarta.servlet.DispatcherType.REQUEST);
            return registration;
        }
    }
}
