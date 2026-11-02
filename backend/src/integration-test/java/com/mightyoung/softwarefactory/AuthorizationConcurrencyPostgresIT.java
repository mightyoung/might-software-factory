package com.mightyoung.softwarefactory;

import com.mightyoung.softwarefactory.authorization.AuthorizationWriteGuard;
import com.mightyoung.softwarefactory.authorization.PostgresAuthorizationWriteGuard;
import com.mightyoung.softwarefactory.dualprevention.finding.workflow.FindingWorkflowAccessPolicy;
import com.mightyoung.softwarefactory.dualprevention.finding.workflow.PersistentFindingWorkflowAccessPolicy;
import com.mightyoung.softwarefactory.identity.CurrentTenantContext;
import com.mightyoung.softwarefactory.identity.VerifiedSessionPrincipal;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContext;
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
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/** Real HTTPS-produced session + production central policy and two managed PostgreSQL transactions.
 * Proves authorization linearization, without creating immutable finding/audit/evidence records.
 */
@SpringBootTest(classes = {IdentityLoginPostgresIT.IdentityWebConfiguration.class,
        AuthorizationConcurrencyPostgresIT.GuardConfiguration.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"lan-postgres-compatibility", "identity-postgres-acceptance"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class AuthorizationConcurrencyPostgresIT {
    private static IdentityAcceptanceHttps https;
    @LocalServerPort private int port;
    @Autowired private PasswordEncoder passwords;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate runtime;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private AuthorizationWriteGuard commandGuard;
    @Autowired private FindingWorkflowAccessPolicy policy;
    @Autowired private JdbcIndexedSessionRepository sessions;
    @Autowired private IdentityLoginPostgresIT.AcceptanceClock clock;
    @Autowired private IdentityLoginPostgresIT.AcceptanceThrottleClock throttleClock;
    @Autowired private IdentityLoginPostgresIT.SessionTrace trace;
    private IdentityPostgresFixture fixture;
    private IdentityAcceptanceHttps.Browser browser;
    private HikariDataSource migrationSource;
    private JdbcTemplate migration;
    private DataSourceTransactionManager migrationTransactions;
    private AuthorizationWriteGuard securityGuard;
    private ExecutorService executor;
    private CurrentTenantContext context;
    private Set<String> sessionIds = Set.of();
    private UUID roleId;
    private UUID grantId;
    private UUID scopeId;
    private final UUID candidateAccount = UUID.randomUUID();
    private final UUID candidateActor = UUID.randomUUID();
    private final UUID candidateMember = UUID.randomUUID();
    private final UUID candidateGrant = UUID.randomUUID();
    private boolean candidateSeeded;

    @DynamicPropertySource static void properties(DynamicPropertyRegistry properties) throws Exception {
        var accepted = IdentityAcceptanceTarget.validateInputs(System.getenv());
        String expected = "I_CONFIRM_AUTHORIZATION_CONCURRENCY_WRITES:" + accepted.databaseName();
        if (!expected.equals(System.getenv("LAN_PG_AUTHORIZATION_CONCURRENCY_ACK"))) {
            throw new IllegalStateException("Authorization concurrency acceptance requires exact database-specific write confirmation");
        }
        var target = IdentityAcceptanceTarget.preflightFromEnvironment(System.getenv());
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

    @BeforeEach void seedAndAuthenticateOverRealHttps() throws Exception {
        trace.awaitIdle();
        clock.reset();
        throttleClock.advance(Duration.ofMinutes(10));
        fixture = IdentityPostgresFixture.create(passwords);
        fixture.setSafetyApplicationA(true);
        var target = IdentityAcceptanceTarget.preflightFromEnvironment(System.getenv());
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(target.jdbcUrl());
        config.setUsername(required("LAN_PG_USERNAME"));
        config.setPassword(required("LAN_PG_PASSWORD"));
        config.setMaximumPoolSize(2);
        config.setMinimumIdle(0);
        config.setPoolName("authorization-security-writer");
        migrationSource = new HikariDataSource(config);
        migration = new JdbcTemplate(migrationSource);
        migrationTransactions = new DataSourceTransactionManager(migrationSource);
        securityGuard = new PostgresAuthorizationWriteGuard(migrationSource);
        executor = Executors.newSingleThreadExecutor();
        securityTransaction().executeWithoutResult(status -> {
            tenant(migration);
            var row = migration.queryForMap("""
                    select g.id, g.role_id, p.scope_rule_id from public.role_grant g
                    join public.role_permission p on p.tenant_id=g.tenant_id and p.role_id=g.role_id
                    join public.permission permission on permission.id=p.permission_id
                    where g.tenant_id=? and g.member_id=? and permission.code='finding.create'
                    """, fixture.tenantAId(), fixture.memberAId());
            grantId = (UUID) row.get("id");
            roleId = (UUID) row.get("role_id");
            scopeId = (UUID) row.get("scope_rule_id");
        });
        browser = https.browser(port);
        assertThat(browser.get("/api/v1/auth/csrf").status()).isEqualTo(204);
        assertThat(browser.post("/api/v1/auth/login", json.writeValueAsString(Map.of(
                "loginKey", fixture.loginKey(), "password", fixture.password(), "tenantCode", fixture.tenantACode())),
                browser.csrf()).status()).isEqualTo(204);
        assertThat(browser.get("/api/v1/session/current").status()).isEqualTo(200);
        trace.awaitIdle();
        context = readContextFromActualLoginSession();
        assertThat(context.actorId()).isEqualTo(fixture.actorId());
        assertThat(context.tenantId()).isEqualTo(fixture.tenantAId());
        assertThat(commandTransaction().<Boolean>execute(status -> { tenant(runtime); commandGuard.forFindingCommand(context.tenantId()); return allowed(false); }))
                .isTrue();
    }

    /** Reads the persisted production login output; never constructs or injects an Authentication/principal. */
    private CurrentTenantContext readContextFromActualLoginSession() {
        var owned = sessions.findByPrincipalName(fixture.accountId().toString());
        assertThat(owned).hasSize(1);
        sessionIds = new HashSet<>(owned.keySet());
        org.springframework.session.Session storedSession = owned.values().iterator().next();
        Object stored = storedSession.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        assertThat(stored).isInstanceOf(SecurityContext.class);
        Object authenticated = ((SecurityContext) stored).getAuthentication().getPrincipal();
        assertThat(authenticated).isInstanceOf(VerifiedSessionPrincipal.class);
        VerifiedSessionPrincipal principal = (VerifiedSessionPrincipal) authenticated;
        return new CurrentTenantContext(principal.actorId(), principal.accountId(), principal.tenantId(),
                principal.memberId(), principal.tenantAuthEpoch(), principal.memberAuthEpoch(), principal.credentialVersion());
    }

    @ParameterizedTest @EnumSource(Change.class)
    void securityChangeCommitsFirstAndWaitingCommandUsesFreshIdentityOrGrantSnapshot(Change change) throws Exception {
        prepare(change);
        CompletableFuture<Integer> waiterPid = new CompletableFuture<>();
        CompletableFuture<Future<Boolean>> pending = new CompletableFuture<>();
        securityTransaction().executeWithoutResult(status -> {
            tenant(migration);
            lockSecurity(change);
            mutate(change);
            int holderPid = pid(migration);
            Future<Boolean> waiting = executor.submit(() -> commandTransaction().execute(command -> {
                tenant(runtime);
                assertThat(allowed(change.candidate())).isEqualTo(change.beforeAllowed());
                waiterPid.complete(pid(runtime));
                commandGuard.forFindingCommand(context.tenantId());
                return allowed(change.candidate());
            }));
            pending.complete(waiting);
            awaitActualWait(holderPid, awaitPid(waiterPid), waiting);
        });
        assertThat(pending.get(1, TimeUnit.SECONDS).get(7, TimeUnit.SECONDS)).isEqualTo(change.afterAllowed());
    }

    @ParameterizedTest @EnumSource(Change.class)
    void commandCommitsFirstAndSecurityWriterWaitsUntilItsAuthorizationDecisionCompletes(Change change) throws Exception {
        prepare(change);
        CompletableFuture<Integer> waiterPid = new CompletableFuture<>();
        CompletableFuture<Future<?>> pending = new CompletableFuture<>();
        commandTransaction().executeWithoutResult(command -> {
            tenant(runtime);
            commandGuard.forFindingCommand(context.tenantId());
            assertThat(allowed(change.candidate())).isEqualTo(change.beforeAllowed());
            int holderPid = pid(runtime);
            Future<?> waiting = executor.submit(() -> securityTransaction().executeWithoutResult(status -> {
                tenant(migration);
                waiterPid.complete(pid(migration));
                lockSecurity(change);
                mutate(change);
            }));
            pending.complete(waiting);
            awaitActualWait(holderPid, awaitPid(waiterPid), waiting);
            assertThat(allowed(change.candidate())).isEqualTo(change.beforeAllowed());
        });
        pending.get(1, TimeUnit.SECONDS).get(7, TimeUnit.SECONDS);
        assertThat(commandTransaction().<Boolean>execute(status -> {
            tenant(runtime); commandGuard.forFindingCommand(context.tenantId()); return allowed(change.candidate());
        })).isEqualTo(change.afterAllowed());
    }

    private void prepare(Change change) {
        if (change.candidate()) seedCandidate();
        if (change == Change.GRANT_INSERT_PHANTOM) securityTransaction().executeWithoutResult(status -> {
            tenant(migration); securityGuard.forTenantSecurityChange(List.of(context.tenantId())); deleteActorGrant();
        });
        assertThat(commandTransaction().<Boolean>execute(status -> {
            tenant(runtime); commandGuard.forFindingCommand(context.tenantId()); return allowed(change.candidate());
        })).isEqualTo(change.beforeAllowed());
    }

    private boolean allowed(boolean candidate) {
        Throwable failure = catchThrowable(() -> {
            policy.requireAction(context, "finding.create", fixture.organizationAId());
            if (candidate) policy.requireAssignableMember(context, candidateMember, fixture.organizationAId(), "finding.create");
        });
        if (failure == null) return true;
        assertThat(failure).isInstanceOfAny(AccessDeniedException.class,
                com.mightyoung.softwarefactory.dualprevention.FindingNotVisibleException.class);
        return false;
    }

    private void lockSecurity(Change change) {
        if (change.global()) securityGuard.forGlobalSecurityChange();
        else securityGuard.forTenantSecurityChange(List.of(context.tenantId()));
    }

    private void mutate(Change change) {
        int changed = switch (change) {
            case ACCOUNT_DISABLED -> migration.update("update public.account set status='DISABLED' where id=?", fixture.accountId());
            case PRINCIPAL_DISABLED -> migration.update("update public.principal set status='DISABLED' where id=? and account_id=?", fixture.actorId(), fixture.accountId());
            case CREDENTIAL_VERSION -> migration.update("update public.account set credential_version=credential_version+1 where id=?", fixture.accountId());
            case TENANT_SUSPENDED -> migration.update("update public.tenant set status='SUSPENDED',auth_epoch=auth_epoch+1 where id=?", context.tenantId());
            case MEMBER_DISABLED -> migration.update("update public.tenant_member set status='DISABLED',auth_epoch=auth_epoch+1 where tenant_id=? and id=?", context.tenantId(), fixture.memberAId());
            case CANDIDATE_ACCOUNT_DISABLED -> migration.update("update public.account set status='DISABLED' where id=?", candidateAccount);
            case CANDIDATE_PRINCIPAL_DISABLED -> migration.update("update public.principal set status='DISABLED' where id=? and account_id=?", candidateActor, candidateAccount);
            case GRANT_DELETE_PHANTOM -> deleteActorGrant();
            case GRANT_INSERT_PHANTOM -> insertGrant(grantId, fixture.memberAId());
            case SCOPE_ORG_REMOVED -> migration.update("delete from public.scope_org where tenant_id=? and scope_rule_id=? and org_id=?", context.tenantId(), scopeId, fixture.organizationAId());
            case ORGANIZATION_INACTIVE -> migration.update("update public.org_unit set active=false where tenant_id=? and id=?", context.tenantId(), fixture.organizationAId());
        };
        assertThat(changed).isEqualTo(1);
    }

    private int deleteActorGrant() {
        return migration.update("delete from public.role_grant where tenant_id=? and id=?", context.tenantId(), grantId);
    }

    private int insertGrant(UUID id, UUID member) {
        return migration.update("""
                insert into public.role_grant(tenant_id,id,role_id,subject_kind,member_id,
                    source_type,source_id,granted_by_member_id,valid_from)
                values (?,?,?,'MEMBER',?,'P0_IDENTITY_ACCEPTANCE',?,?,current_timestamp-interval '1 day')
                """, context.tenantId(), id, roleId, member, id, fixture.memberAId());
    }

    private void seedCandidate() {
        securityTransaction().executeWithoutResult(status -> {
            tenant(migration);
            securityGuard.forGlobalSecurityChange();
            migration.update("insert into public.account(id,login_key,status,credential_version) values (?,?,'ACTIVE',1)",candidateAccount,"candidate-"+candidateAccount);
            migration.update("insert into public.principal(id,kind,account_id,status) values (?,'HUMAN',?,'ACTIVE')",candidateActor,candidateAccount);
            migration.update("insert into public.tenant_member(tenant_id,id,account_id,status,display_name) values (?,?,?,'ACTIVE','Synthetic candidate')",context.tenantId(),candidateMember,candidateAccount);
            migration.update("insert into public.member_org(tenant_id,member_id,org_id,kind,valid_from) values (?,?,?,'PRIMARY',current_timestamp-interval '1 day')",context.tenantId(),candidateMember,fixture.organizationAId());
            assertThat(insertGrant(candidateGrant,candidateMember)).isEqualTo(1);
        });
        candidateSeeded = true;
    }

    private void awaitActualWait(int holderPid, int waiterPid, Future<?> waiting) {
        long deadline = System.nanoTime()+Duration.ofSeconds(3).toNanos();
        while (System.nanoTime()<deadline) {
            if (Boolean.TRUE.equals(runtime.queryForObject("""
                    select ?=any(pg_blocking_pids(?)) and exists (
                      select 1 from pg_locks where pid=? and locktype='advisory' and not granted)
                    """,Boolean.class,holderPid,waiterPid,waiterPid))) {
                assertThat(waiting.isDone()).isFalse(); return;
            }
            assertThat(waiting.isDone()).as("writer/command completed before actual advisory wait").isFalse();
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
        }
        throw new AssertionError("No matching PostgreSQL advisory wait observed");
    }

    private static int awaitPid(CompletableFuture<Integer> pid) {
        try { return pid.get(3,TimeUnit.SECONDS); }
        catch (Exception failure) { throw new IllegalStateException("Waiter did not publish backend pid",failure); }
    }
    private static int pid(JdbcTemplate jdbc) { return jdbc.queryForObject("select pg_backend_pid()",Integer.class); }
    private void tenant(JdbcTemplate jdbc) { jdbc.queryForObject("select set_config('app.tenant_id',?,true)",String.class,fixture.tenantAId().toString()); }
    private TransactionTemplate commandTransaction() { return template(transactions); }
    private TransactionTemplate securityTransaction() { return template(migrationTransactions); }
    private static TransactionTemplate template(PlatformTransactionManager manager) {
        var template=new TransactionTemplate(manager);
        template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED); template.setTimeout(15); return template;
    }

    @AfterEach void cleanupOnlyOwnedMutableIdentityRows() throws Exception {
        try {
            if (executor!=null) { executor.shutdownNow(); assertThat(executor.awaitTermination(10,TimeUnit.SECONDS)).isTrue(); }
        } finally {
            try {
                if (browser!=null) browser.close();
                trace.awaitIdle();
                if (fixture!=null) {
                    Set<String> owned = new HashSet<>(sessionIds);
                    owned.addAll(sessions.findByPrincipalName(fixture.accountId().toString()).keySet());
                    fixture.cleanupSessions(owned);
                }
                if (candidateSeeded) securityTransaction().executeWithoutResult(status -> {
                    tenant(migration); securityGuard.forGlobalSecurityChange();
                    migration.update("delete from public.role_grant where tenant_id=? and id=?",context.tenantId(),candidateGrant);
                    migration.update("delete from public.member_org where tenant_id=? and member_id=? and org_id=?",context.tenantId(),candidateMember,fixture.organizationAId());
                    migration.update("delete from public.tenant_member where tenant_id=? and id=? and account_id=?",context.tenantId(),candidateMember,candidateAccount);
                    migration.update("delete from public.principal where id=? and account_id=?",candidateActor,candidateAccount);
                    migration.update("delete from public.account where id=?",candidateAccount);
                });
            } finally {
                try { if (fixture!=null) fixture.close(); }
                finally { if (migrationSource!=null) migrationSource.close(); }
            }
        }
    }
    @AfterAll static void closeTls() throws Exception { if (https!=null) https.close(); }
    private static String required(String name) { String value=System.getenv(name); if(value==null||value.isBlank())throw new IllegalStateException("Missing "+name);return value; }
    enum Change {
        ACCOUNT_DISABLED, PRINCIPAL_DISABLED, CREDENTIAL_VERSION, TENANT_SUSPENDED, MEMBER_DISABLED,
        CANDIDATE_ACCOUNT_DISABLED, CANDIDATE_PRINCIPAL_DISABLED, GRANT_DELETE_PHANTOM,
        GRANT_INSERT_PHANTOM, SCOPE_ORG_REMOVED, ORGANIZATION_INACTIVE;
        boolean global() { return this==ACCOUNT_DISABLED||this==PRINCIPAL_DISABLED||this==CREDENTIAL_VERSION||candidate(); }
        boolean candidate() { return this==CANDIDATE_ACCOUNT_DISABLED||this==CANDIDATE_PRINCIPAL_DISABLED; }
        boolean beforeAllowed() { return this!=GRANT_INSERT_PHANTOM; }
        boolean afterAllowed() { return this==GRANT_INSERT_PHANTOM; }
    }
    @Configuration(proxyBeanMethods=false)
    @Import({PostgresAuthorizationWriteGuard.class,PersistentFindingWorkflowAccessPolicy.class})
    static class GuardConfiguration { }
}
