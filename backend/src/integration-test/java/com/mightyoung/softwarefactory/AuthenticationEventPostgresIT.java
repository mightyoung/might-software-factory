package com.mightyoung.softwarefactory;

import jakarta.servlet.ServletRequestEvent;
import jakarta.servlet.ServletRequestListener;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.servlet.ServletListenerRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import tools.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Actual HTTPS/PG authentication audit; controlled request-scoped JDBC faults preserve real transaction effects. */
@SpringBootTest(classes = SoftwareFactoryApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(AuthenticationEventPostgresIT.FaultConfiguration.class)
@ActiveProfiles("lan-postgres-compatibility")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class AuthenticationEventPostgresIT {
    private static final String CSRF = "/api/v1/auth/csrf", LOGIN = "/api/v1/auth/login",
            CURRENT = "/api/v1/session/current", PROBE_HEADER = "X-Authentication-Event-Acceptance";
    private static IdentityAcceptanceHttps https;
    @LocalServerPort private int port;
    @Autowired private PasswordEncoder passwords;
    @Autowired private ObjectMapper json;
    @Autowired private Probes probes;
    private BusinessWorkflowPostgresFixture fixture;
    private final List<IdentityAcceptanceHttps.Browser> browsers = new ArrayList<>();
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p)throws Exception {
        var accepted = IdentityAcceptanceTarget.validateInputs(System.getenv());
        if (!("I_CONFIRM_AUTHENTICATION_EVENT_FIXTURE_WRITES:" + accepted.databaseName())
                .equals(System.getenv("LAN_PG_AUTHENTICATION_EVENT_ACCEPTANCE_ACK")))
            throw new IllegalStateException("Authentication event acceptance requires database-specific acknowledgement");
        if (!IdentityLoginPostgresIT.validateRuntimePrivilegeProfile(System.getenv(), accepted.databaseName()).authenticationAudit
                || !("I_CONFIRM_PASSWORD_CHANGE_FIXTURE_WRITES:" + accepted.databaseName())
                    .equals(System.getenv("LAN_PG_PASSWORD_CHANGE_ACCEPTANCE_ACK")))
            throw new IllegalStateException("Authentication audit runtime profile and exact password-change acknowledgement required");
        var target=BusinessWorkflowPostgresFixture.validateTarget();
        if(https==null)https=IdentityAcceptanceHttps.create();
        p.add("spring.datasource.url",target::jdbcUrl);
        p.add("spring.datasource.username",()->System.getenv("LAN_PG_APP_USERNAME"));
        p.add("spring.datasource.password",()->System.getenv("LAN_PG_APP_PASSWORD"));
        p.add("spring.datasource.hikari.maximum-pool-size",()->4);p.add("spring.datasource.hikari.minimum-idle",()->1);
        p.add("server.address",()->"127.0.0.1");p.add("server.ssl.enabled",()->true);
        p.add("server.ssl.key-store",()->https.keyStore().toUri().toString());
        p.add("server.ssl.key-store-password",https::password);p.add("server.ssl.key-store-type",()->"PKCS12");p.add("server.ssl.key-alias",()->"identity-acceptance");
        p.add("spring.session.jdbc.cleanup-cron",()->"-");
        p.add("spring.session.jdbc.initialize-schema",()->"never");p.add("spring.flyway.enabled",()->false);
        p.add("spring.modulith.events.jdbc.schema-initialization.enabled",()->false);
        p.add("flowable.database-schema-update",()->false);p.add("flowable.check-process-definitions",()->false);
        p.add("flowable.async-executor-activate",()->false);p.add("flowable.async-history-executor-activate",()->false);
    }

    @BeforeEach void seed() {
        probes.armed.set(null);
        fixture = new BusinessWorkflowPostgresFixture(passwords);
        fixture.read(db -> {
            String runtime = System.getenv("LAN_PG_APP_USERNAME");
            assertThat(db.queryForObject("select has_table_privilege(?,'public.authentication_event','INSERT')",
                    Boolean.class, runtime)).isTrue();
            for (String privilege : List.of("SELECT", "UPDATE", "DELETE", "TRUNCATE"))
                assertThat(db.queryForObject("select has_table_privilege(?,'public.authentication_event',?)",
                        Boolean.class, runtime, privilege)).as("Runtime authentication event %s", privilege).isFalse();
            return null;
        });
    }
    @AfterEach void close() {
        probes.armed.set(null);
        try { browsers.forEach(IdentityAcceptanceHttps.Browser::close); }
        finally { browsers.clear(); if (fixture != null) fixture.close(); }
        // authentication_event rows and their owned identity ancestors are append-only retained evidence.
    }
    @AfterAll static void closeTls() throws Exception { if (https != null) https.close(); }

    @Test void successfulLoginCommitsExactlyOneAttributedEventBeforePublishingUsableSession() throws Exception {
        var client = browser();
        var probe = new Probe(Mode.OBSERVE);
        long before = allEvents();
        var response = login(client, probe, fixture.reporter.password());
        expect(response, 204);
        assertThat(client.cookie("SESSION")).isPresent();
        expect(client.get(CURRENT), 200);
        assertEvent(probe, "SESSION_PERSISTED", true);
        assertThat(allEvents()).isEqualTo(before + 1);
        assertThat(ownedSessions()).isEqualTo(1);
        // Ordinary reads/CSRF bootstrap do not append additional login outcomes.
        expect(client.get(CSRF), 204);
        assertThat(allEvents()).isEqualTo(before + 1);
    }

    @Test void badCredentialsAppendExactlyOneAnonymousDenialWithoutSubmittedIdentityAttribution() throws Exception {
        var client = browser();
        var probe = new Probe(Mode.OBSERVE);
        long before = allEvents();
        expect(login(client, probe, "incorrect-synthetic-password"), 401);
        assertEvent(probe, "AUTHENTICATION_DENIED", false);
        assertThat(allEvents()).isEqualTo(before + 1);
        assertThat(ownedSessions()).isZero();
        expect(client.get(CURRENT), 401);
    }

    @Test void authenticationDatabaseFailureAppendsOneAnonymousUnavailableEvent() throws Exception {
        var client = browser();
        var probe = new Probe(Mode.AUTHENTICATION_READ_FAILURE);
        long before = allEvents();
        var response = login(client, probe, fixture.reporter.password());
        unavailable(response);
        assertThat(probe.injected.get()).isTrue();
        assertThat(probe.authenticationReadExecuted.get()).isTrue();
        assertEvent(probe, "AUTHENTICATION_UNAVAILABLE", false);
        assertThat(allEvents()).isEqualTo(before + 1);
        assertThat(ownedSessions()).isZero();
        noUsableCookie(client, response);
    }

    @Test void failureAfterActualAuditInsertRollsBackEventAndWithholdsCommittedSessionCookie() throws Exception {
        var client = browser();
        var probe = new Probe(Mode.AUDIT_INSERT_FAILURE);
        long before = allEvents();
        var response = login(client, probe, fixture.reporter.password());
        unavailable(response);
        assertThat(probe.injected.get()).isTrue();
        assertSingleInsert(probe);
        assertThat(events(probe)).isEmpty();
        assertThat(allEvents()).isEqualTo(before);
        assertThat(ownedSessions()).as("Session committed before the independent audit transaction").isEqualTo(1);
        noUsableCookie(client, response);
        // No fallback INSERT is permitted after this failed append.
        assertThat(probe.insertExecutions.get()).isEqualTo(1);
    }

    @Test void lostAuditCommitAcknowledgementRetainsOneEventButPublishesNoUsableSessionCookieOrRetry() throws Exception {
        var client = browser();
        var probe = new Probe(Mode.AUDIT_COMMIT_ACK_LOST);
        long before = allEvents();
        var response = login(client, probe, fixture.reporter.password());
        unavailable(response);
        assertThat(probe.injected.get()).isTrue();
        assertThat(probe.actualAuditCommits.get()).isEqualTo(1);
        assertEvent(probe, "SESSION_PERSISTED", true);
        assertThat(allEvents()).isEqualTo(before + 1);
        assertThat(ownedSessions()).isEqualTo(1);
        noUsableCookie(client, response);
        assertThat(probe.insertExecutions.get()).as("No retry or fallback after unknown commit acknowledgement").isEqualTo(1);
    }

    @Test void malformedCsrfAndRateRejectionsDoNotEnrollOrAppendAnAuthenticationAttempt() throws Exception {
        var client = browser();
        expect(client.get(CSRF), 204);
        var malformed = new Probe(Mode.OBSERVE);
        probes.armed.set(malformed);
        long before = allEvents();
        expect(client.postWithHeaders(LOGIN, "{", client.csrf(), Map.of(PROBE_HEADER, malformed.token)), 400);
        assertNoAppend(malformed, before);
        var csrf = new Probe(Mode.OBSERVE);
        probes.armed.set(csrf);
        expect(client.postWithHeaders(LOGIN, body(fixture.reporter.password()), null,
                Map.of(PROBE_HEADER, csrf.token)), 403);
        assertNoAppend(csrf, before);
        // The five admitted credential failures each append; only the sixth pair-throttled request is excluded.
        for (int i = 0; i < 5; i++) {
            var admitted = new Probe(Mode.OBSERVE);
            expect(login(client, admitted, "incorrect-synthetic-password"), 401);
            assertEvent(admitted, "AUTHENTICATION_DENIED", false);
        }
        assertThat(allEvents()).isEqualTo(before + 5);
        var limited = new Probe(Mode.OBSERVE);
        expect(login(client, limited, "incorrect-synthetic-password"), 429);
        assertNoAppend(limited, before + 5);
        assertThat(ownedSessions()).isZero();
    }

    private IdentityAcceptanceHttps.Browser browser() throws Exception {
        var client = https.browser(port);
        browsers.add(client);
        expect(client.get(CSRF), 204);
        return client;
    }
    private IdentityAcceptanceHttps.Response login(IdentityAcceptanceHttps.Browser client,
                                                   Probe probe, String password) throws Exception {
        probes.armed.set(probe);
        var response=client.postWithHeaders(LOGIN, body(password), client.csrf(), Map.of(PROBE_HEADER, probe.token));
        assertScopeClosed(probe);
        return response;
    }
    private static void assertScopeClosed(Probe probe)throws Exception {
        probe.scopeClosed.get(5,TimeUnit.SECONDS);
        assertThat(probe.scopeEntries.get()).isEqualTo(1);assertThat(probe.scopeExits.get()).isEqualTo(1);
        assertThat(probe.scopeExitThread.get()).isSameAs(probe.scopeThread.get());
    }
    private String body(String password) throws Exception {
        return json.writeValueAsString(Map.of("loginKey", fixture.reporter.loginKey(),
                "password", password, "tenantCode", fixture.tenantCode));
    }
    private long allEvents() {
        // Migrator-owned fixture connection only; the runtime must never SELECT the global event stream.
        return fixture.read(db -> db.queryForObject("select count(*) from public.authentication_event", Long.class));
    }
    private long ownedSessions() {
        return fixture.read(db -> db.queryForObject("select count(*) from public.spring_session where principal_name=?",
                Long.class, fixture.reporter.accountId().toString()));
    }
    private List<Map<String, Object>> events(Probe probe) {
        assertThat(probe.attemptId.get()).as("Bound server-created attempt id from actual INSERT").isNotNull();
        return fixture.read(db -> db.queryForList("select * from public.authentication_event where attempt_id=?",
                probe.attemptId.get()));
    }
    private void assertEvent(Probe probe, String outcome, boolean attributed) {
        assertSingleInsert(probe);
        var rows = events(probe);
        assertThat(rows).hasSize(1);
        var row = rows.getFirst();
        assertThat(row.get("outcome")).isEqualTo(outcome);
        assertThat(row.get("policy_version")).isEqualTo("login-audit-v1");
        assertThat(row.get("occurred_at")).isNotNull();
        if (attributed) {
            assertThat(row.get("account_id")).isEqualTo(fixture.reporter.accountId());
            assertThat(row.get("actor_id")).isEqualTo(fixture.reporter.principalId());
            assertThat(row.get("tenant_id")).isEqualTo(fixture.tenantId);
            assertThat(row.get("member_id")).isEqualTo(fixture.reporter.memberId());
        } else {
            for (String column : List.of("account_id", "actor_id", "tenant_id", "member_id"))
                assertThat(row.get(column)).as("Anonymous event column %s", column).isNull();
        }
        assertThat(row.keySet()).containsExactlyInAnyOrder("attempt_id", "outcome", "account_id", "actor_id",
                "tenant_id", "member_id", "occurred_at", "policy_version");
    }
    private void assertSingleInsert(Probe probe) {
        assertThat(probe.insertExecutions.get()).isEqualTo(1);
        assertThat(probe.auditAutoCommit.get()).isFalse();
        assertThat(probe.auditReadOnly.get()).isFalse();
        assertThat(probe.auditIsolation.get()).isEqualTo(Connection.TRANSACTION_READ_COMMITTED);
    }
    private void assertNoAppend(Probe probe, long expected)throws Exception {
        assertScopeClosed(probe);
        assertThat(probe.insertExecutions.get()).isZero();
        assertThat(probe.attemptId.get()).isNull();
        assertThat(allEvents()).isEqualTo(expected);
    }
    private void unavailable(IdentityAcceptanceHttps.Response response) throws Exception {
        expect(response, 503);
        assertThat(json.readTree(response.body()).path("code").asString()).isEqualTo("DEPENDENCY_UNAVAILABLE");
        assertThat(response.body()).doesNotContain("Controlled audit", "P0001", "08006", "authentication_event",
                fixture.reporter.loginKey(), fixture.reporter.password());
    }
    private void noUsableCookie(IdentityAcceptanceHttps.Browser client,
                                IdentityAcceptanceHttps.Response response) throws Exception {
        assertThat(client.cookie("SESSION")).isEmpty();
        assertThat(response.http().headers().allValues("Set-Cookie").stream()
                .filter(value -> value.startsWith("SESSION=")).toList())
                .allSatisfy(value -> assertThat(value).startsWith("SESSION=;").containsIgnoringCase("max-age=0"));
        expect(client.get(CURRENT), 401);
    }
    private static void expect(IdentityAcceptanceHttps.Response response, int status) {
        assertThat(response.status()).as("Actual HTTPS status").isEqualTo(status);
    }

    enum Mode { OBSERVE, AUTHENTICATION_READ_FAILURE, AUDIT_INSERT_FAILURE, AUDIT_COMMIT_ACK_LOST }
    static final class Probe {
        final Mode mode;
        final String token = UUID.randomUUID().toString();
        final AtomicReference<UUID> attemptId = new AtomicReference<>();
        final AtomicInteger insertExecutions = new AtomicInteger(), actualAuditCommits = new AtomicInteger();
        final AtomicInteger auditIsolation = new AtomicInteger();
        final AtomicInteger scopeEntries=new AtomicInteger(),scopeExits=new AtomicInteger();
        final AtomicReference<Thread> scopeThread=new AtomicReference<>(),scopeExitThread=new AtomicReference<>();
        final CompletableFuture<Void> scopeClosed=new CompletableFuture<>();
        final AtomicBoolean injected = new AtomicBoolean(), authenticationReadExecuted = new AtomicBoolean();
        final AtomicBoolean auditAutoCommit = new AtomicBoolean(true), auditReadOnly = new AtomicBoolean(true);
        Probe(Mode mode) { this.mode = mode; }
        @Override public String toString() { return "AuthenticationAuditProbe[" + mode + "]"; }
    }
    static final class Probes {
        final AtomicReference<Probe> armed = new AtomicReference<>();
        final ThreadLocal<Probe> current = new ThreadLocal<>();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FaultConfiguration {
        @Bean static Probes authenticationEventProbes() { return new Probes(); }
        @Bean static BeanPostProcessor authenticationEventDataSource(Probes probes) {
            return new BeanPostProcessor() {
                @Override public Object postProcessAfterInitialization(Object bean, String name) {
                    return name.equals("dataSource") && bean instanceof DataSource source
                            ? new InstrumentedDataSource(source, probes) : bean;
                }
            };
        }
        @Bean ServletListenerRegistrationBean<ServletRequestListener> authenticationEventProbeScope(Probes probes) {
            // LoginAudit appends only after its inner chain returns. A request lifecycle listener
            // keeps the probe bound through that append without competing for a filter order.
            final String attribute=AuthenticationEventPostgresIT.class.getName()+".requestProbe";
            ServletRequestListener listener=new ServletRequestListener(){
                @Override public void requestInitialized(ServletRequestEvent event){
                    probes.current.remove();
                    var request=(HttpServletRequest)event.getServletRequest();var probe=probes.armed.get();
                    if(probe!=null&&"POST".equals(request.getMethod())&&LOGIN.equals(request.getRequestURI())
                            &&probe.token.equals(request.getHeader(PROBE_HEADER))){
                        probes.current.set(probe);request.setAttribute(attribute,probe);
                        probe.scopeThread.set(Thread.currentThread());probe.scopeEntries.incrementAndGet();
                    }
                }
                @Override public void requestDestroyed(ServletRequestEvent event){
                    probes.current.remove();
                    if(event.getServletRequest().getAttribute(attribute) instanceof Probe probe){
                        probe.scopeExitThread.set(Thread.currentThread());probe.scopeExits.incrementAndGet();
                        probe.scopeClosed.complete(null);
                    }
                }
            };
            return new ServletListenerRegistrationBean<>(listener);
        }
    }

    static final class InstrumentedDataSource extends DelegatingDataSource implements AutoCloseable {
        private final Probes probes;
        InstrumentedDataSource(DataSource source, Probes probes) { super(source); this.probes = probes; }
        @Override public Connection getConnection() throws SQLException { return instrument(super.getConnection()); }
        @Override public Connection getConnection(String user, String password) throws SQLException {
            return instrument(super.getConnection(user, password));
        }
        private Connection instrument(Connection raw) {
            AtomicBoolean auditTransaction = new AtomicBoolean();
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                    (proxy, method, args) -> {
                        try {
                            Probe probe = probes.current.get();
                            if (probe != null && method.getName().equals("prepareStatement")
                                    && args != null && args[0] instanceof String sql) {
                                String normalized = sql.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip();
                                boolean audit = normalized.startsWith("insert into public.authentication_event");
                                boolean authentication = normalized.contains("from public.account a")
                                        && normalized.contains("c.secret_hash");
                                var actual = (PreparedStatement) method.invoke(raw, args);
                                if (audit) auditTransaction.set(true);
                                if (audit || authentication)
                                    return instrumentStatement(actual, raw, probe, audit, authentication);
                                return actual;
                            }
                            if (probe != null && auditTransaction.get() && method.getName().equals("commit")) {
                                Object result = method.invoke(raw, args); // Real commit first; only its acknowledgement is lost.
                                probe.actualAuditCommits.incrementAndGet();
                                if (probe.mode == Mode.AUDIT_COMMIT_ACK_LOST && probe.injected.compareAndSet(false, true))
                                    throw new SQLException("Controlled audit commit acknowledgement loss", "08006");
                                return result;
                            }
                            return method.invoke(raw, args);
                        } catch (InvocationTargetException failure) { throw failure.getCause(); }
                    });
        }
        private PreparedStatement instrumentStatement(PreparedStatement actual, Connection raw, Probe probe,
                                                       boolean audit, boolean authentication) {
            return (PreparedStatement) Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),
                    new Class<?>[]{PreparedStatement.class}, (proxy, method, args) -> {
                        try {
                            if (audit && method.getName().startsWith("set") && args != null && args.length >= 2
                                    && Integer.valueOf(1).equals(args[0]) && args[1] != null) {
                                // Capture only server-created attempt id; never capture login/password/credential parameters.
                                probe.attemptId.compareAndSet(null, UUID.fromString(args[1].toString()));
                            }
                            Object result = method.invoke(actual, args);
                            if (Set.of("execute", "executeUpdate", "executeBatch", "executeQuery",
                                    "executeLargeUpdate", "executeLargeBatch").contains(method.getName())) {
                                if (audit) {
                                    probe.insertExecutions.incrementAndGet();
                                    probe.auditAutoCommit.set(raw.getAutoCommit());
                                    probe.auditReadOnly.set(raw.isReadOnly());
                                    probe.auditIsolation.set(raw.getTransactionIsolation());
                                    if (probe.mode == Mode.AUDIT_INSERT_FAILURE && probe.injected.compareAndSet(false, true))
                                        throw new SQLException("Controlled audit failure after actual INSERT", "P0001");
                                }
                                if (authentication && probe.mode == Mode.AUTHENTICATION_READ_FAILURE
                                        && probe.injected.compareAndSet(false, true)) {
                                    probe.authenticationReadExecuted.set(true);
                                    throw new SQLException("Controlled authentication read dependency failure", "08006");
                                }
                            }
                            return result;
                        } catch (InvocationTargetException failure) { throw failure.getCause(); }
                    });
        }
        @Override public void close() throws Exception {
            if (getTargetDataSource() instanceof AutoCloseable closeable) closeable.close();
        }
    }
}
