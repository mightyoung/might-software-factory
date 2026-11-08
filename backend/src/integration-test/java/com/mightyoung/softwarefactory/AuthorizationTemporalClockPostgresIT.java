package com.mightyoung.softwarefactory;

import com.mightyoung.softwarefactory.authorization.AuthorizationWriteGuard;
import com.mightyoung.softwarefactory.authorization.PostgresAuthorizationWriteGuard;
import com.mightyoung.softwarefactory.dualprevention.finding.workflow.FindingWorkflowAccessPolicy;
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

import org.jooq.DSLContext;
import org.jooq.ExecuteContext;
import org.jooq.ExecuteListener;
import org.jooq.ExecuteListenerProvider;
import org.jooq.impl.DefaultExecuteListenerProvider;
import com.mightyoung.softwarefactory.authorization.FindingWorkflowAuthorizationRepository;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import org.junit.jupiter.api.Test;
import org.springframework.core.serializer.support.SerializingConverter;

/** Real HTTPS session and production temporal policy only; no Finding/Flowable domain write.
 * A private owned Spring Session attribute is the optional downstream PG commit marker.
 * This class is opt-in under the existing exact authorization-concurrency write acknowledgments.
 */
@SpringBootTest(classes = {IdentityLoginPostgresIT.IdentityWebConfiguration.class,
        AuthorizationConcurrencyPostgresIT.GuardConfiguration.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"lan-postgres-compatibility", "identity-postgres-acceptance"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class AuthorizationTemporalClockPostgresIT {
    private static IdentityAcceptanceHttps https;
    @LocalServerPort private int port;
    @Autowired private PasswordEncoder passwords;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate runtime;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private AuthorizationWriteGuard commandGuard;
    @Autowired private FindingWorkflowAccessPolicy policy;
    @Autowired private FindingWorkflowAuthorizationRepository authorization;
    @Autowired private JdbcIndexedSessionRepository sessions;
    @Autowired private DSLContext dsl;
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
    private Set<String> sessionIds=Set.of();
    private UUID grantId,roleId,scopeId;
    private final UUID inheritedOrg=UUID.randomUUID(),positionId=UUID.randomUUID(),groupId=UUID.randomUUID();
    private boolean extraDirectory;
    private final String marker="p0-clock-"+UUID.randomUUID();
    private final SqlObservation observer=new SqlObservation();
    private ExecuteListenerProvider[] originalListeners;

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
        assertThat(runtime.queryForObject("select has_table_privilege(current_user,'public.spring_session_attributes','SELECT') and has_table_privilege(current_user,'public.spring_session_attributes','INSERT')",Boolean.class))
                .as("Existing Session privileges only; never grant a marker privilege").isTrue();
        context = readContextFromActualLoginSession();
        assertThat(context.actorId()).isEqualTo(fixture.actorId());
        assertThat(context.tenantId()).isEqualTo(fixture.tenantAId());
        assertThat(commandTransaction().<Boolean>execute(status -> { tenant(runtime); commandGuard.forFindingCommand(context.tenantId()); return evaluate(false, null).allowed(); }))
                .isTrue();
        originalListeners=dsl.configuration().executeListenerProviders();
        var listeners=new ArrayList<ExecuteListenerProvider>(Arrays.asList(originalListeners));
        listeners.add(new DefaultExecuteListenerProvider(observer));
        dsl.configuration().set(listeners.toArray(ExecuteListenerProvider[]::new));
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


    @ParameterizedTest @EnumSource(TemporalCase.class)
    void aRealGuardWaitCrossesTheSingleRelevantValidityBoundary(TemporalCase scenario) throws Exception {
        var unchanged=scopedCounts();
        OffsetDateTime cutoff=prepare(scenario); // Period change is committed before the holder starts.
        CompletableFuture<Integer> waiterPid=new CompletableFuture<>();
        CompletableFuture<Future<Decision>> pending=new CompletableFuture<>();
        var before=new java.util.concurrent.atomic.AtomicReference<Decision>();
        var began=new java.util.concurrent.atomic.AtomicReference<OffsetDateTime>();
        var acquired=new java.util.concurrent.atomic.AtomicReference<OffsetDateTime>();
        var holder=new java.util.concurrent.atomic.AtomicInteger();
        securityTransaction().executeWithoutResult(status->{
            tenant(migration);securityGuard.forTenantSecurityChange(List.of(context.tenantId()));
            // This holder changes no role/status/epoch/period row: wall-clock passage is the only change.
            holder.set(pid(migration));
            Future<Decision> waiting=executor.submit(()->commandTransaction().execute(command->{
                tenant(runtime);began.set(transactionTime(runtime));
                before.set(evaluate(scenario.candidate,null));
                assertThat(before.get().allowed()).isEqualTo(!scenario.start);
                waiterPid.complete(pid(runtime));
                commandGuard.forFindingCommand(context.tenantId());
                acquired.set(now(runtime));
                return evaluateAndMaybeMark(scenario.candidate,null);
            }));
            pending.complete(waiting);
            awaitActualWait(holder.get(),awaitPid(waiterPid),waiting);
            awaitDatabaseTime(migration,cutoff);
        });
        Decision after=pending.get(1,TimeUnit.SECONDS).get(7,TimeUnit.SECONDS);
        assertThat(began.get()).isBefore(cutoff);
        assertThat(acquired.get()).isAfterOrEqualTo(cutoff);
        assertThat(after.allowed()).isEqualTo(scenario.start);
        before.get().assertBound(waiterPid.get(),"read committed");
        after.assertBound(waiterPid.get(),"read committed");
        assertThat(after.time()).isAfterOrEqualTo(acquired.get());
        assertThat(markerCount()).isEqualTo(scenario.start?1:0);
        assertThat(scopedCounts()).isEqualTo(unchanged);
        evidence(scenario.name(),holder.get(),waiterPid.get(),began.get(),cutoff,acquired.get(),after);
    }

    @Test void nonOverlappingInheritedPeriodsNeverCreateAuthorityAcrossSeparateQueries() {
        var unchanged=scopedCounts();
        var cutoff=prepare(TemporalCase.ORG_MEMBERSHIP_END);
        securityTransaction().executeWithoutResult(status->{
            tenant(migration);securityGuard.forTenantSecurityChange(List.of(context.tenantId()));
            assertThat(migration.update("update public.role_grant set valid_from=? where tenant_id=? and id=?",cutoff,context.tenantId(),grantId)).isEqualTo(1);
        });
        // Both half-open periods are already committed. No writer changes rows during this evaluation.
        Decision result=commandTransaction().execute(status->{
            tenant(runtime);commandGuard.forFindingCommand(context.tenantId());
            return evaluateAndMaybeMark(false,cutoff);
        });
        assertThat(result.allowed()).isFalse();assertThat(markerCount()).isZero();
        assertThat(scopedCounts()).isEqualTo(unchanged);
        result.assertBound(result.entries.getFirst().pid,"read committed");
        assertThat(result.time()).isBefore(cutoff);
        assertThat(result.entries.stream().filter(e->e.label.equals("ROLE")).findFirst().orElseThrow().before)
                .isAfterOrEqualTo(cutoff);
        evidence("NON_OVERLAPPING_PERIODS",0,result.entries.getFirst().pid,null,cutoff,null,result);
    }

    @Test void standaloneRepeatableReadKeepsItsOriginalTimeWhileANewSnapshotAndRcSeeExpiry() {
        var cutoff=prepare(TemporalCase.MEMBER_GRANT_END);
        var rr=template(transactions);rr.setReadOnly(true);rr.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        rr.executeWithoutResult(status->{
            tenant(runtime);int backend=pid(runtime);
            Decision first=evaluate(false,null);assertThat(first.allowed()).isTrue();first.assertBound(backend,"repeatable read");
            awaitDatabaseTime(runtime,cutoff);
            Decision retained=evaluate(false,null);assertThat(retained.allowed()).isTrue();retained.assertBound(backend,"repeatable read");
            assertThat(retained.time()).isEqualTo(first.time());
        });
        try(var capture=observer.capture(null)) {
            assertThat(authorization.loadActionSnapshot(context,"finding.create","create").orElseThrow().organizationIds()).isEmpty();
            var fresh=new Decision(false,capture.copy());fresh.assertBound(fresh.entries.getFirst().pid,"repeatable read");
            assertThat(fresh.time()).isAfterOrEqualTo(cutoff);
        }
        assertThat(commandTransaction().<Boolean>execute(status->{tenant(runtime);commandGuard.forFindingCommand(context.tenantId());return evaluate(false,null).allowed();})).isFalse();
        assertThat(markerCount()).isZero();
    }

    @Test void securityWriterWaitsForAnAlreadyGuardedDecisionThenSetsAnActualCutoff() throws Exception {
        prepareSubject("MEMBER");
        CompletableFuture<Integer> writerPid=new CompletableFuture<>();
        CompletableFuture<Future<?>> pending=new CompletableFuture<>();
        commandTransaction().executeWithoutResult(status->{
            tenant(runtime);commandGuard.forFindingCommand(context.tenantId());
            Decision before=evaluateAndMaybeMark(false,null);assertThat(before.allowed()).isTrue();before.assertBound(pid(runtime),"read committed");
            int holder=pid(runtime);
            Future<?> waiting=executor.submit(()->securityTransaction().executeWithoutResult(writer->{
                tenant(migration);writerPid.complete(pid(migration));
                securityGuard.forTenantSecurityChange(List.of(context.tenantId()));
                assertThat(migration.update("update public.role_grant set valid_to=clock_timestamp() where tenant_id=? and id=?",context.tenantId(),grantId)).isEqualTo(1);
            }));
            pending.complete(waiting);awaitActualWait(holder,awaitPid(writerPid),waiting);
            assertThat(evaluate(false,null).allowed()).isTrue();
        });
        pending.get(1,TimeUnit.SECONDS).get(7,TimeUnit.SECONDS);
        assertThat(markerCount()).isEqualTo(1);
        assertThat(commandTransaction().<Boolean>execute(status->{tenant(runtime);commandGuard.forFindingCommand(context.tenantId());return evaluate(false,null).allowed();})).isFalse();
    }

    @Test void securityWriterCommitsAnActualCutoffBeforeAnAlreadyStartedCommandGetsItsGuard() throws Exception {
        prepareSubject("MEMBER");
        var unchanged=scopedCounts();
        CompletableFuture<Integer> waiterPid=new CompletableFuture<>();
        CompletableFuture<Future<Decision>> pending=new CompletableFuture<>();
        var before=new java.util.concurrent.atomic.AtomicReference<Decision>();
        var began=new java.util.concurrent.atomic.AtomicReference<OffsetDateTime>();
        var acquired=new java.util.concurrent.atomic.AtomicReference<OffsetDateTime>();
        var cutoff=new java.util.concurrent.atomic.AtomicReference<OffsetDateTime>();
        var holder=new java.util.concurrent.atomic.AtomicInteger();
        securityTransaction().executeWithoutResult(status->{
            tenant(migration);securityGuard.forTenantSecurityChange(List.of(context.tenantId()));
            holder.set(pid(migration));
            Future<Decision> waiting=executor.submit(()->commandTransaction().execute(command->{
                tenant(runtime);began.set(transactionTime(runtime));
                before.set(evaluate(false,null));assertThat(before.get().allowed()).isTrue();
                waiterPid.complete(pid(runtime));
                commandGuard.forFindingCommand(context.tenantId());
                acquired.set(now(runtime));
                return evaluateAndMaybeMark(false,null);
            }));
            pending.complete(waiting);
            awaitActualWait(holder.get(),awaitPid(waiterPid),waiting);
            // Only the validity boundary changes, after a real waiting command has read its old hint.
            cutoff.set(migration.queryForObject("update public.role_grant set valid_to=clock_timestamp() where tenant_id=? and id=? returning valid_to",
                    (rs,row)->rs.getObject(1,OffsetDateTime.class),context.tenantId(),grantId));
        }); // Security writer commits before the waiting command can obtain the shared guard.
        Decision after=pending.get(1,TimeUnit.SECONDS).get(7,TimeUnit.SECONDS);
        assertThat(began.get()).isBefore(cutoff.get());
        before.get().assertBound(waiterPid.get(),"read committed");
        assertThat(before.get().time()).isBefore(cutoff.get());
        assertThat(acquired.get()).isAfterOrEqualTo(cutoff.get());
        after.assertBound(waiterPid.get(),"read committed");
        assertThat(after.time()).isAfterOrEqualTo(acquired.get());
        assertThat(after.entries.stream().map(e->e.label)).contains("CLOCK","ORG","POSITION","GROUP","ROLE");
        assertThat(after.allowed()).isFalse();
        assertThat(markerCount()).isZero();
        assertThat(scopedCounts()).isEqualTo(unchanged);
        evidence("SECURITY_WRITER_FIRST_ACTUAL_CUTOFF",holder.get(),waiterPid.get(),began.get(),cutoff.get(),acquired.get(),after);
    }

    private OffsetDateTime prepare(TemporalCase scenario) {
        prepareSubject(scenario.subject);
        return securityTransaction().execute(status->{
            tenant(migration);securityGuard.forTenantSecurityChange(List.of(context.tenantId()));
            if(scenario.tree)assertThat(migration.update("update public.scope_rule set kind='DEPT_TREE' where tenant_id=? and id=?",context.tenantId(),scopeId)).isEqualTo(1);
            OffsetDateTime cutoff=migration.queryForObject("select clock_timestamp()+interval '1500 milliseconds'",(rs,row)->rs.getObject(1,OffsetDateTime.class));
            String column=scenario.start?"valid_from":"valid_to"; // enum-selected SQL identifier only
            String table=scenario.membership?switch(scenario.subject){case "ORG"->"member_org";case "POSITION"->"member_position";case "GROUP"->"group_member";default->"member_org";}:"role_grant";
            String target=scenario.membership?switch(scenario.subject){case "ORG"->"org_id";case "POSITION"->"position_id";case "GROUP"->"group_id";default->"org_id";}:"id";
            UUID id=scenario.membership?switch(scenario.subject){case "ORG"->inheritedOrg;case "POSITION"->positionId;case "GROUP"->groupId;default->fixture.organizationAId();}:grantId;
            String member=scenario.membership?" and member_id=?":"";
            var bindings=new ArrayList<Object>(List.of(cutoff,context.tenantId(),id));if(scenario.membership)bindings.add(context.memberId());
            assertThat(migration.update("update public."+table+" set "+column+"=? where tenant_id=? and "+target+"=?"+member,bindings.toArray())).isEqualTo(1);
            return cutoff;
        });
    }

    private void prepareSubject(String subject) {
        securityTransaction().executeWithoutResult(status->{
            tenant(migration);securityGuard.forTenantSecurityChange(List.of(context.tenantId()));
            if(!subject.equals("MEMBER")) {
                migration.update("insert into public.org_unit(tenant_id,id,code,name) values (?,?,?,'Synthetic clock org')",context.tenantId(),inheritedOrg,"clock-"+inheritedOrg);
                migration.update("insert into public.\"position\"(tenant_id,id,code,name,org_id) values (?,?,?,'Synthetic clock position',?)",context.tenantId(),positionId,"clock-"+positionId,inheritedOrg);
                migration.update("insert into public.user_group(tenant_id,id,code,name) values (?,?,?,'Synthetic clock group')",context.tenantId(),groupId,"clock-"+groupId);
                migration.update("insert into public.member_org(tenant_id,member_id,org_id,kind,valid_from) values (?,?,?,'CONCURRENT',clock_timestamp()-interval '1 day')",context.tenantId(),context.memberId(),inheritedOrg);
                migration.update("insert into public.member_position(tenant_id,member_id,position_id,valid_from) values (?,?,?,clock_timestamp()-interval '1 day')",context.tenantId(),context.memberId(),positionId);
                migration.update("insert into public.group_member(tenant_id,member_id,group_id,valid_from) values (?,?,?,clock_timestamp()-interval '1 day')",context.tenantId(),context.memberId(),groupId);
                extraDirectory=true;
            }
            assertThat(migration.update("update public.role_grant set subject_kind=?,member_id=?,org_id=?,position_id=?,group_id=? where tenant_id=? and id=?",
                    subject,subject.equals("MEMBER")?context.memberId():null,subject.equals("ORG")?inheritedOrg:null,
                    subject.equals("POSITION")?positionId:null,subject.equals("GROUP")?groupId:null,context.tenantId(),grantId)).isEqualTo(1);
        });
    }

    private Decision evaluate(boolean candidate,OffsetDateTime pauseAt) {
        try(var capture=observer.capture(pauseAt)) {
            Throwable failure=catchThrowable(()->{
                if(candidate)policy.requireAssignableMember(context,context.memberId(),fixture.organizationAId(),"finding.create");
                else policy.requireAction(context,"finding.create",fixture.organizationAId());
            });
            if(failure!=null)assertThat(failure).isInstanceOfAny(AccessDeniedException.class,com.mightyoung.softwarefactory.dualprevention.FindingNotVisibleException.class);
            return new Decision(failure==null,capture.copy());
        }
    }
    private Decision evaluateAndMaybeMark(boolean candidate,OffsetDateTime pauseAt) {
        Decision decision=evaluate(candidate,pauseAt);
        if(decision.allowed())assertThat(runtime.update("insert into public.spring_session_attributes(session_primary_id,attribute_name,attribute_bytes) select primary_id,?,? from public.spring_session where session_id=?",
                marker,new SerializingConverter().convert("synthetic temporal commit marker"),sessionIds.iterator().next())).isEqualTo(1);
        return decision;
    }
    private int markerCount(){return runtime.queryForObject("select count(*) from public.spring_session_attributes where attribute_name=?",Integer.class,marker);}
    /** Scoped row counts only, not a claim of complete engine/business rollback acceptance. */
    private Map<String,Long> scopedCounts() {
        var read=template(transactions);read.setReadOnly(true);read.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        return read.execute(status->{
            tenant(runtime);var counts=new LinkedHashMap<String,Long>();
            for(String table:List.of("finding","finding_command_receipt","finding_transition","workflow_binding",
                    "audit_event","remediation_round","remediation_notice","evidence_set","evidence_item","file_object","file_link")) {
                if(readable(table))counts.put(table,runtime.queryForObject("select count(*) from public."+table+" where tenant_id=?",Long.class,context.tenantId()));
            }
            if(readable("event_publication"))counts.put("event_publication",runtime.queryForObject("select count(*) from public.event_publication where serialized_event like ?",Long.class,"%"+context.tenantId()+"%"));
            for(String table:List.of("act_ru_execution","act_ru_task","act_hi_procinst","act_hi_taskinst","act_ru_actinst","act_hi_actinst")) {
                if(readable(table))counts.put(table,runtime.queryForObject("select count(*) from public."+table+" where tenant_id_=?",Long.class,context.tenantId().toString()));
            }
            return Map.copyOf(counts);
        });
    }
    private boolean readable(String table) {
        return Boolean.TRUE.equals(runtime.queryForObject("select case when to_regclass(?) is null then false else has_table_privilege(current_user,to_regclass(?),'SELECT') end",Boolean.class,"public."+table,"public."+table));
    }

    private void evidence(String name,int holder,int waiter,OffsetDateTime begin,OffsetDateTime cutoff,OffsetDateTime acquired,Decision result) {
        try {var value=new LinkedHashMap<String,Object>();value.put("case",name);value.put("holderPid",holder);value.put("waiterPid",waiter);
            value.put("transactionBegin",begin==null?null:begin.toString());value.put("cutoff",cutoff.toString());value.put("guardAcquired",acquired==null?null:acquired.toString());
            value.put("evaluatedAt",result.time().toString());value.put("queryPids",result.entries.stream().map(e->e.label+":"+e.pid).toList());value.put("allowed",result.allowed());value.put("committedMarkerRows",markerCount());
            System.out.println("AUTH_CLOCK_EVIDENCE "+json.writeValueAsString(value));
        }catch(Exception failure){throw new IllegalStateException("Unable to retain safe authorization clock evidence",failure);}
    }

    private void awaitActualWait(int holder,int waiter,Future<?> waiting) {
        long deadline=System.nanoTime()+Duration.ofSeconds(3).toNanos();
        while(System.nanoTime()<deadline) {
            if(Boolean.TRUE.equals(runtime.queryForObject("select ?=any(pg_blocking_pids(?)) and exists(select 1 from pg_locks where pid=? and locktype='advisory' and not granted)",Boolean.class,holder,waiter,waiter))) {assertThat(waiting.isDone()).isFalse();return;}
            assertThat(waiting.isDone()).as("Actual advisory wait must be observed").isFalse();LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
        }throw new AssertionError("No matching PostgreSQL advisory wait observed");
    }
    private static int awaitPid(CompletableFuture<Integer> pid){try{return pid.get(3,TimeUnit.SECONDS);}catch(Exception e){throw new IllegalStateException("Missing backend pid",e);}}
    private static int pid(JdbcTemplate jdbc){return jdbc.queryForObject("select pg_backend_pid()",Integer.class);}
    private static OffsetDateTime now(JdbcTemplate jdbc){return jdbc.queryForObject("select clock_timestamp()",(rs,row)->rs.getObject(1,OffsetDateTime.class));}
    private static OffsetDateTime transactionTime(JdbcTemplate jdbc){return jdbc.queryForObject("select transaction_timestamp()",(rs,row)->rs.getObject(1,OffsetDateTime.class));}
    private static void awaitDatabaseTime(JdbcTemplate jdbc,OffsetDateTime cutoff){long deadline=System.nanoTime()+Duration.ofSeconds(3).toNanos();while(System.nanoTime()<deadline){if(!now(jdbc).isBefore(cutoff))return;LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));}throw new AssertionError("DB cutoff did not pass within bound");}
    private void tenant(JdbcTemplate jdbc){jdbc.queryForObject("select set_config('app.tenant_id',?,true)",String.class,fixture.tenantAId().toString());}
    private TransactionTemplate commandTransaction(){return template(transactions);}
    private TransactionTemplate securityTransaction(){return template(migrationTransactions);}
    private static TransactionTemplate template(PlatformTransactionManager manager){var t=new TransactionTemplate(manager);t.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);t.setTimeout(15);return t;}
    private static String required(String name){var value=System.getenv(name);if(value==null||value.isBlank())throw new IllegalStateException("Missing "+name);return value;}

    @AfterEach void closeOwnedFixture() throws Exception {
        try {if(executor!=null){executor.shutdownNow();assertThat(executor.awaitTermination(10,TimeUnit.SECONDS)).isTrue();}}
        finally {
            if(originalListeners!=null)dsl.configuration().set(originalListeners);
            try {
                if(browser!=null)browser.close();trace.awaitIdle();
                if(fixture!=null){var owned=new HashSet<>(sessionIds);owned.addAll(sessions.findByPrincipalName(fixture.accountId().toString()).keySet());fixture.cleanupSessions(owned);}
                if(extraDirectory)securityTransaction().executeWithoutResult(status->{
                    tenant(migration);securityGuard.forTenantSecurityChange(List.of(context.tenantId()));
                    migration.update("update public.role_grant set subject_kind='MEMBER',member_id=?,org_id=null,position_id=null,group_id=null where tenant_id=? and id=?",context.memberId(),context.tenantId(),grantId);
                    migration.update("delete from public.member_org where tenant_id=? and member_id=? and org_id=?",context.tenantId(),context.memberId(),inheritedOrg);
                    migration.update("delete from public.member_position where tenant_id=? and member_id=? and position_id=?",context.tenantId(),context.memberId(),positionId);
                    migration.update("delete from public.group_member where tenant_id=? and member_id=? and group_id=?",context.tenantId(),context.memberId(),groupId);
                    migration.update("delete from public.\"position\" where tenant_id=? and id=?",context.tenantId(),positionId);
                    migration.update("delete from public.user_group where tenant_id=? and id=?",context.tenantId(),groupId);
                    migration.update("delete from public.org_unit where tenant_id=? and id=?",context.tenantId(),inheritedOrg);
                });
            } finally {try{if(fixture!=null)fixture.close();}finally{if(migrationSource!=null)migrationSource.close();}}
        }
    }
    @AfterAll static void closeTls() throws Exception {if(https!=null)https.close();}

    enum TemporalCase {
        MEMBER_GRANT_END("MEMBER",false,false,false,false),ORG_GRANT_END("ORG",false,false,false,false),
        POSITION_GRANT_END("POSITION",false,false,false,false),GROUP_GRANT_END("GROUP",false,false,false,false),
        ORG_MEMBERSHIP_END("ORG",true,false,false,false),POSITION_MEMBERSHIP_END("POSITION",true,false,false,false),GROUP_MEMBERSHIP_END("GROUP",true,false,false,false),
        TARGET_MEMBERSHIP_END("MEMBER",true,false,true,false),TREE_SEED_END("MEMBER",true,false,false,true),
        MEMBER_GRANT_START("MEMBER",false,true,false,false),ORG_MEMBERSHIP_START("ORG",true,true,false,false),
        POSITION_MEMBERSHIP_START("POSITION",true,true,false,false),GROUP_MEMBERSHIP_START("GROUP",true,true,false,false);
        final String subject;final boolean membership,start,candidate,tree;
        TemporalCase(String subject,boolean membership,boolean start,boolean candidate,boolean tree){this.subject=subject;this.membership=membership;this.start=start;this.candidate=candidate;this.tree=tree;}
    }
    record Decision(boolean allowed,List<SqlEntry> entries) {
        OffsetDateTime time(){return entries.stream().flatMap(e->e.bindings.stream()).filter(OffsetDateTime.class::isInstance).map(OffsetDateTime.class::cast).findFirst().orElseThrow();}
        void assertBound(int backend,String isolation){
            assertThat(entries).isNotEmpty().allSatisfy(e->assertThat(e.pid).isEqualTo(backend));
            var clocks=entries.stream().filter(e->e.label.equals("CLOCK")).toList();assertThat(clocks).hasSize(1);
            var clock=clocks.getFirst();var at=time();
            if(isolation.equals("read committed"))assertThat(at).isAfterOrEqualTo(clock.before).isBeforeOrEqualTo(clock.after);
            else assertThat(at).isEqualTo(clock.transactionAt);
            assertThat(entries.stream().filter(e->!e.label.equals("CLOCK"))).isNotEmpty().allSatisfy(e->{
                var times=e.bindings.stream().filter(OffsetDateTime.class::isInstance).toList();assertThat(times).isNotEmpty().allMatch(at::equals);
            });
        }
    }
    static final class SqlEntry {
        final String label;final int pid;final OffsetDateTime transactionAt,before;final List<Object> bindings;
        OffsetDateTime after;
        SqlEntry(String label,int pid,OffsetDateTime transactionAt,OffsetDateTime before,List<Object> bindings){this.label=label;this.pid=pid;this.transactionAt=transactionAt;this.before=before;this.bindings=bindings;}
    }
    static final class SqlObservation implements ExecuteListener {
        private static final long serialVersionUID=1L;
        private final ThreadLocal<Capture> active=new ThreadLocal<>();
        private final ThreadLocal<SqlEntry> current=new ThreadLocal<>();
        Capture capture(OffsetDateTime pause){if(active.get()!=null)throw new IllegalStateException("Nested SQL observation");var capture=new Capture(pause);active.set(capture);return capture;}
        @Override public void executeStart(ExecuteContext ctx){
            var capture=active.get();if(capture==null)return;
            String sql=ctx.sql().toLowerCase(java.util.Locale.ROOT).replace("\"","");
            String label=sql.contains("transaction_isolation")?"CLOCK":sql.contains("target_membership")?"CANDIDATE":sql.contains("role_grant")?"ROLE":sql.contains("with recursive")?"TREE":sql.contains("member_org")?"ORG":sql.contains("member_position")?"POSITION":sql.contains("group_member")?"GROUP":null;
            if(label==null){current.remove();return;}
            try(var statement=ctx.connection().prepareStatement("select pg_backend_pid(),transaction_timestamp(),clock_timestamp()");var result=statement.executeQuery()){
                if(!result.next())throw new IllegalStateException("Missing SQL observation");
                var entry=new SqlEntry(label,result.getInt(1),result.getObject(2,OffsetDateTime.class),result.getObject(3,OffsetDateTime.class),ctx.query()==null?List.of():new ArrayList<>(ctx.query().getBindValues()));
                capture.entries.add(entry);current.set(entry);
            }catch(SQLException failure){throw new IllegalStateException("Unable to observe actual SQL connection",failure);}
        }
        @Override public void executeEnd(ExecuteContext ctx){
            var capture=active.get();var entry=current.get();if(capture==null||entry==null||!entry.label.equals("GROUP")||capture.pause==null)return;
            long deadline=System.nanoTime()+Duration.ofSeconds(3).toNanos();
            while(System.nanoTime()<deadline){if(!connectionNow(ctx.connection()).isBefore(capture.pause))return;LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));}
            throw new AssertionError("Non-overlap query barrier did not cross actual DB cutoff");
        }
        @Override public void fetchEnd(ExecuteContext ctx){var entry=current.get();if(active.get()!=null&&entry!=null&&entry.label.equals("CLOCK"))entry.after=connectionNow(ctx.connection());}
        private static OffsetDateTime connectionNow(Connection connection){try(var statement=connection.prepareStatement("select clock_timestamp()");var result=statement.executeQuery()){if(!result.next())throw new IllegalStateException("Missing DB time");return result.getObject(1,OffsetDateTime.class);}catch(SQLException e){throw new IllegalStateException("Unable to observe DB time",e);}}
        final class Capture implements AutoCloseable {
            final OffsetDateTime pause;final List<SqlEntry> entries=new ArrayList<>();
            Capture(OffsetDateTime pause){this.pause=pause;}
            List<SqlEntry> copy(){return List.copyOf(entries);}
            public void close(){active.remove();current.remove();}
        }
    }
}
