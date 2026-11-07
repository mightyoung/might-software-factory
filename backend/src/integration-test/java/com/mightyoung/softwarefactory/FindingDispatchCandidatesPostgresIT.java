package com.mightyoung.softwarefactory;

import com.mightyoung.softwarefactory.authorization.PostgresAuthorizationWriteGuard;
import com.mightyoung.softwarefactory.identity.LoginAttemptLimiter;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.TaskService;
import org.jooq.ExecuteContext;
import org.jooq.ExecuteListener;
import org.jooq.ExecuteListenerProvider;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.parallel.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.*;
import java.net.URLEncoder;
import java.sql.Connection;
import java.sql.SQLException;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import static org.assertj.core.api.Assertions.assertThat;

/** Real HTTPS/session, PostgreSQL eligibility and production V2 dispatch. Owned history is retained. */
@SpringBootTest(classes=SoftwareFactoryApplication.class,webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(FindingDispatchCandidatesPostgresIT.QueryConfiguration.class)
@ActiveProfiles("lan-postgres-compatibility")
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class FindingDispatchCandidatesPostgresIT {
    private static IdentityAcceptanceHttps https;
    @LocalServerPort int port;
    @Autowired PasswordEncoder passwords;
    @Autowired ObjectMapper json;
    @Autowired RepositoryService repository;
    @Autowired TaskService tasks;
    @Autowired Queries queries;
    @Autowired CandidateLimiterClock limiterClock;
    BusinessWorkflowPostgresFixture fixture;
    OwnedDirectory directory;
    final Map<UUID,IdentityAcceptanceHttps.Browser> clients=new HashMap<>();

    @DynamicPropertySource static void properties(DynamicPropertyRegistry p)throws Exception {
        var target=BusinessWorkflowPostgresFixture.validateTarget();
        if(https==null)https=IdentityAcceptanceHttps.create();
        p.add("spring.datasource.url",target::jdbcUrl);
        p.add("spring.datasource.username",()->System.getenv("LAN_PG_APP_USERNAME"));
        p.add("spring.datasource.password",()->System.getenv("LAN_PG_APP_PASSWORD"));
        p.add("spring.datasource.hikari.maximum-pool-size",()->4);
        p.add("spring.datasource.hikari.minimum-idle",()->1);
        p.add("server.address",()->"127.0.0.1");p.add("server.ssl.enabled",()->true);
        p.add("server.ssl.key-store",()->https.keyStore().toUri().toString());
        p.add("server.ssl.key-store-password",https::password);p.add("server.ssl.key-store-type",()->"PKCS12");
        p.add("server.ssl.key-alias",()->"identity-acceptance");
        p.add("spring.session.jdbc.cleanup-cron",()->"-");
        p.add("flowable.database-schema-update",()->false);p.add("flowable.check-process-definitions",()->false);
        p.add("flowable.async-executor-activate",()->false);p.add("flowable.async-history-executor-activate",()->false);
        p.add("spring.flyway.enabled",()->false);p.add("spring.session.jdbc.initialize-schema",()->"never");
        p.add("spring.modulith.events.jdbc.schema-initialization.enabled",()->false);
    }
    @BeforeEach void seed(){
        limiterClock.advance(Duration.ofMinutes(2)); // Fixture budget control, not login capacity acceptance.
        fixture=new BusinessWorkflowPostgresFixture(passwords);directory=new OwnedDirectory(fixture);
        repository.createDeployment().name(fixture.run+"-candidates").tenantId(fixture.tenantId.toString())
                .addClasspathResource("processes/finding-remediation-v2.bpmn20.xml").deploy();
    }
    @AfterEach void close(){try{clients.values().forEach(IdentityAcceptanceHttps.Browser::close);clients.clear();}
        finally{try{if(directory!=null)directory.close();}finally{if(fixture!=null)fixture.close();}}}
    @AfterAll static void closeTls()throws Exception{if(https!=null)https.close();}

    @Test void exposesOnlyMinimalCandidatesAndCurrentFindingMetadataWithoutCaching()throws Exception {
        UUID finding=pending();var response=get(fixture.safety,finding,"");JsonNode page=page(response,200);
        assertThat(response.http().headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(fields(page)).containsExactlyInAnyOrder("data","findingVersion","roundNo","state","hasMore","nextAfterMemberId");
        assertThat(ids(page)).containsExactly(fixture.remediator.memberId());
        JsonNode member=page.path("data").get(0);
        assertThat(fields(member)).containsExactlyInAnyOrder("memberId","displayName");
        assertThat(member.path("displayName").asString()).isEqualTo(fixture.run);
        assertThat(page.path("findingVersion").asLong()).isEqualTo(3);
        assertThat(page.path("roundNo").asInt()).isEqualTo(1);
        assertThat(page.path("state").asString()).isEqualTo("SAFETY_DISPATCH_PENDING");
        assertThat(page.path("hasMore").asBoolean()).isFalse();assertThat(page.path("nextAfterMemberId").isNull()).isTrue();
    }

    @Test void filtersBeforeLimitAndUsesLastReturnedEligibleUuidWithLiteralPrefix()throws Exception {
        UUID finding=pending();String name="literal%_\\";
        directory.candidate(uuid(1),name+"ineligible","MEMBER","DISABLED");
        directory.candidate(uuid(2),name+"first","MEMBER","ACTIVE");
        directory.candidate(uuid(3),name+"second","MEMBER","ACTIVE");
        directory.candidate(uuid(4),"literalXXother","MEMBER","ACTIVE");
        String query="?q="+URLEncoder.encode(name,StandardCharsets.UTF_8)+"&limit=1";
        JsonNode first=page(get(fixture.safety,finding,query),200);
        assertThat(ids(first)).containsExactly(uuid(2));assertThat(first.path("hasMore").asBoolean()).isTrue();
        assertThat(first.path("nextAfterMemberId").asString()).isEqualTo(uuid(2).toString());
        JsonNode second=page(get(fixture.safety,finding,query+"&afterMemberId="+uuid(2)),200);
        assertThat(ids(second)).containsExactly(uuid(3));assertThat(second.path("hasMore").asBoolean()).isFalse();
        assertThat(second.path("nextAfterMemberId").isNull()).isTrue();
    }

    @ParameterizedTest @CsvSource({"MEMBER,EXPLICIT","ORG,EXPLICIT","POSITION,EXPLICIT","GROUP,EXPLICIT","MEMBER,DEPT","MEMBER,DEPT_TREE"})
    void everyGrantSourceIncludingOtherOrgMembershipCanActuallyReceiveDispatch(String source,String scope)throws Exception {
        UUID finding=pending(),candidate=uuid(20);directory.candidate(candidate,"source-candidate",source,"ACTIVE");
        if(!scope.equals("EXPLICIT"))directory.write(db->db.update("update public.scope_rule set kind=? where tenant_id=? and id=?",scope,fixture.tenantId,directory.scopes.get(candidate)));
        assertThat(ids(page(get(fixture.safety,finding,"?q=source-candidate"),200))).containsExactly(candidate);
        JsonNode result=command(fixture.safety,finding,"dispatch",Map.of("expectedVersion",3,"assigneeMemberId",candidate,
                "dueAt",OffsetDateTime.now().plusDays(1).toString(),"requirements","Acceptance dispatch"),200);
        assertThat(result.path("state").asString()).isEqualTo("RECTIFYING");
        String instance=fixture.read(db->db.queryForObject("select process_instance_id from public.workflow_binding where tenant_id=? and business_id=?",String.class,fixture.tenantId,finding));
        var task=tasks.createTaskQuery().processInstanceId(instance).singleResult();
        assertThat(task.getTaskDefinitionKey()).isEqualTo("remediation");assertThat(task.getAssignee()).isEqualTo(candidate.toString());
    }

    @ParameterizedTest @ValueSource(strings={"member","account","principal","nonhuman","membership-expired","membership-future","role","grant-expired","grant-future","permission","SELF","RELATION","scope"})
    void excludesInactiveExpiredWrongPermissionAndNonOrgScopeCandidates(String defect)throws Exception {
        UUID finding=pending(),candidate=uuid(30);directory.candidate(candidate,"excluded", "MEMBER","ACTIVE");
        directory.defect(candidate,defect);
        assertThat(ids(page(get(fixture.safety,finding,"?q=excluded"),200))).isEmpty();
    }

    @Test void unknownForeignOutOfScopeAndMissingDispatchActionHaveDistinctDenials()throws Exception {
        UUID finding=pending();
        page(get(fixture.safety,UUID.randomUUID(),""),404);
        page(get(fixture.reporter,finding,""),403);
        try(var foreign=new BusinessWorkflowPostgresFixture(passwords)){
            page(get(fixture.safety,foreign.legacyFindingId,""),404);
        }
        directory.write(db->{
            UUID otherOrg=UUID.randomUUID();
            db.update("insert into public.org_unit(tenant_id,id,code,name,active) values (?,?,?,?,true)",fixture.tenantId,otherOrg,"out-of-scope-"+UUID.randomUUID(),"Owned other scope");
            int moved=db.update("update public.scope_org set org_id=? where tenant_id=? and scope_rule_id in (select rp.scope_rule_id from public.role_permission rp join public.role_grant rg on rg.tenant_id=rp.tenant_id and rg.role_id=rp.role_id where rg.tenant_id=? and rg.member_id=?)",otherOrg,fixture.tenantId,fixture.tenantId,fixture.safety.memberId());
            assertThat(moved).as("Safety scope remains nonempty but excludes finding organization").isPositive();
        });
        page(get(fixture.safety,finding,""),404);
    }

    @Test void validatesQueryAndRejectsNonDispatchStage()throws Exception {
        UUID finding=pending();
        for(String query:List.of("?limit=0","?limit=51","?limit=abc","?limit=1&limit=2","?unknown=1","?memberId="+fixture.remediator.memberId(),"?q="+"x".repeat(101),"?q=%00","?q=%0A","?afterMemberId=not-a-uuid","?afterMemberId=1-1-1-1-1"))
            page(get(fixture.safety,finding,query),400);
        command(fixture.safety,finding,"dispatch",Map.of("expectedVersion",3,"assigneeMemberId",fixture.remediator.memberId(),"dueAt",OffsetDateTime.now().plusDays(1).toString(),"requirements","Advance stage"),200);
        page(get(fixture.safety,finding,""),409);
    }

    @Test void listingThenRevokingAssigneeStillRejectsPostWithoutBusinessOrEngineEffects()throws Exception {
        UUID finding=pending();assertThat(ids(page(get(fixture.safety,finding,""),200))).contains(fixture.remediator.memberId());
        Map<String,Object> before=snapshot(finding);
        directory.write(db->db.update("update public.role_grant set valid_to=current_timestamp where tenant_id=? and member_id=? and valid_to is null",fixture.tenantId,fixture.remediator.memberId()));
        command(fixture.safety,finding,"dispatch",Map.of("expectedVersion",3,"assigneeMemberId",fixture.remediator.memberId(),"dueAt",OffsetDateTime.now().plusDays(1).toString(),"requirements","Revoked candidate"),403);
        assertThat(snapshot(finding)).isEqualTo(before);
        assertThat(ids(page(get(fixture.safety,finding,""),200))).doesNotContain(fixture.remediator.memberId());
    }

    @Test void candidateSqlCountRemainsBoundedAsEligiblePopulationGrows()throws Exception {
        UUID finding=pending();login(fixture.safety);queries.sql.clear();
        page(get(fixture.safety,finding,"?limit=50"),200);int small=queries.eligibility().size();
        for(int i=100;i<112;i++)directory.candidate(uuid(i),"bulk-"+i,"MEMBER","ACTIVE");
        queries.sql.clear();queries.snapshots.clear();JsonNode page=page(get(fixture.safety,finding,"?limit=50"),200);
        assertThat(ids(page)).hasSize(13);List<String> sql=queries.eligibility();
        assertThat(small).isPositive();assertThat(sql).hasSize(small);assertThat(sql.size()).isLessThanOrEqualTo(4);
        assertThat(sql).allSatisfy(statement->{assertThat(statement.toLowerCase(Locale.ROOT)).doesNotContain("count(","for update","pg_advisory");});
        assertThat(queries.snapshots).hasSize(1).allSatisfy(snapshot->{
            assertThat(snapshot.readOnly()).isTrue();assertThat(snapshot.isolation()).isEqualTo(Connection.TRANSACTION_REPEATABLE_READ);
            assertThat(snapshot.statementTimeout()).isEqualTo("2s");assertThat(snapshot.advisoryLocks()).isZero();
        });
    }

    @Test void blockedCandidateReadCancelsAtStatementDeadlineAndSameSessionRecovers()throws Exception {
        UUID finding=pending();var client=login(fixture.safety);Map<String,Object> before=snapshot(finding);
        ExecutorService executor=Executors.newSingleThreadExecutor();Future<IdentityAcceptanceHttps.Response> request=null;
        try {
            try(Connection owner=directory.source.getConnection()){
                owner.setAutoCommit(false);
                try {
                    int blocker;
                    try(var statement=owner.createStatement()){
                        statement.setQueryTimeout(3);
                        try(var row=statement.executeQuery("select pg_backend_pid()")){
                            assertThat(row.next()).isTrue();blocker=row.getInt(1);
                        }
                        // Lock only membership: account credential validation remains available.
                        statement.execute("lock table public.member_org in access exclusive mode");
                    }
                    long started=System.nanoTime();
                    request=executor.submit(()->client.get("/api/v1/findings/"+finding+"/dispatch-candidates"));
                    boolean observed=false;long observeUntil=started+Duration.ofMillis(1500).toNanos();
                    while(System.nanoTime()<observeUntil&&!observed){
                        try(var statement=owner.prepareStatement("select exists(select 1 from pg_locks where locktype='relation' and relation='public.member_org'::regclass and not granted and ?=any(pg_blocking_pids(pid)))")){
                            statement.setQueryTimeout(1);statement.setInt(1,blocker);
                            try(var row=statement.executeQuery()){assertThat(row.next()).isTrue();observed=row.getBoolean(1);}
                        }
                        if(!observed)LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
                    }
                    assertThat(observed).as("Candidate request actually waits on owned membership table lock").isTrue();
                    var failed=request.get(8,TimeUnit.SECONDS);
                    long elapsed=System.nanoTime()-started;
                    expect(failed,503);JsonNode error=json.readTree(failed.body());
                    assertThat(error.path("code").asString()).isEqualTo("DEPENDENCY_UNAVAILABLE");
                    assertThat(error.path("message").asString()).isEqualTo("The finding command could not be completed safely");
                    assertThat(error.path("retryable").asBoolean()).isTrue();
                    assertThat(failed.body()).doesNotContain("member_org","statement_timeout","SQLSTATE","canceling statement",fixture.tenantId.toString(),fixture.safety.accountId().toString(),fixture.safety.loginKey());
                    assertThat(elapsed).as("Two-second SQL cancellation precedes five-second transaction deadline")
                            .isBetween(Duration.ofSeconds(1).toNanos(),Duration.ofSeconds(4).toNanos());
                } finally {owner.rollback();}
            }
            // Same authenticated browser/session; no claim about reborrowing the same physical connection.
            assertThat(ids(page(client.get("/api/v1/findings/"+finding+"/dispatch-candidates"),200)))
                    .containsExactly(fixture.remediator.memberId());
            assertThat(snapshot(finding)).isEqualTo(before);
        } finally {
            if(request!=null&&!request.isDone())request.cancel(true);
            executor.shutdownNow();assertThat(executor.awaitTermination(5,TimeUnit.SECONDS)).isTrue();
        }
    }

    private UUID pending()throws Exception {
        var client=login(fixture.reporter);
        var response=client.postWithHeaders("/api/v1/findings",json.writeValueAsString(Map.of("organizationId",fixture.organizationId,"description","Dispatch candidate acceptance","discoveredAt",OffsetDateTime.now().minusHours(1).toString(),"grade","UNKNOWN","sourceCategory","FIELD_OBSERVATION","urgency","NORMAL","location","Synthetic fixture")),client.csrf(),Map.of("Idempotency-Key",UUID.randomUUID().toString()));
        UUID finding=UUID.fromString(result(response,200).path("findingId").asString());
        command(fixture.person,finding,"confirm/person",Map.of("expectedVersion",1,"reason","Confirmed"),200);
        command(fixture.safety,finding,"confirm/safety",Map.of("expectedVersion",2,"reason","Confirmed","grade","GENERAL"),200);return finding;
    }
    private IdentityAcceptanceHttps.Browser login(BusinessWorkflowPostgresFixture.Actor actor)throws Exception {
        var existing=clients.get(actor.accountId());if(existing!=null)return existing;
        var client=https.browser(port);clients.put(actor.accountId(),client);
        expect(client.get("/api/v1/auth/csrf"),204);
        expect(client.post("/api/v1/auth/login",json.writeValueAsString(Map.of("loginKey",actor.loginKey(),"password",actor.password(),"tenantCode",fixture.tenantCode)),client.csrf()),204);
        expect(client.get("/api/v1/auth/csrf"),204);return client;
    }
    private IdentityAcceptanceHttps.Response get(BusinessWorkflowPostgresFixture.Actor actor,UUID finding,String query)throws Exception{return login(actor).get("/api/v1/findings/"+finding+"/dispatch-candidates"+query);}
    private JsonNode command(BusinessWorkflowPostgresFixture.Actor actor,UUID finding,String suffix,Map<String,Object> body,int status)throws Exception {
        var client=login(actor);return result(client.postWithHeaders("/api/v1/findings/"+finding+"/"+suffix,json.writeValueAsString(body),client.csrf(),Map.of("Idempotency-Key",UUID.randomUUID().toString())),status);
    }
    private JsonNode result(IdentityAcceptanceHttps.Response response,int status)throws Exception{expect(response,status);return json.readTree(response.body()).path("data");}
    private JsonNode page(IdentityAcceptanceHttps.Response response,int status)throws Exception{expect(response,status);return json.readTree(response.body());}
    private static void expect(IdentityAcceptanceHttps.Response response,int status){assertThat(response.status()).as("Real HTTPS response: %s",response.body()).isEqualTo(status);}
    private static Set<String> fields(JsonNode value){Set<String> names=new HashSet<>();value.properties().forEach(entry->names.add(entry.getKey()));return names;}
    private static List<UUID> ids(JsonNode page){List<UUID> result=new ArrayList<>();page.path("data").forEach(member->result.add(UUID.fromString(member.path("memberId").asString())));return result;}
    private static UUID uuid(int tail){return UUID.fromString("00000000-0000-0000-0000-"+String.format("%012d",tail));}
    private Map<String,Object> snapshot(UUID finding){return fixture.read(db->{Map<String,Object> result=new HashMap<>(db.queryForMap("select state,round_no,version from public.finding where tenant_id=? and id=?",fixture.tenantId,finding));
        for(String table:List.of("finding_transition","finding_command_receipt","audit_event","remediation_notice","remediation_round"))result.put(table,fixture.count(table));
        result.put("tasks",tasks.createTaskQuery().taskTenantId(fixture.tenantId.toString()).list().stream().map(t->t.getId()+"/"+t.getAssignee()).sorted().toList());return result;});}

    static final class Queries {
        final List<String> sql=new CopyOnWriteArrayList<>();
        final List<QuerySnapshot> snapshots=new CopyOnWriteArrayList<>();
        List<String> eligibility(){return sql.stream().filter(s->s.toLowerCase(Locale.ROOT).contains("tenant_member")&&s.toLowerCase(Locale.ROOT).contains("role_grant")).toList();}
    }
    record QuerySnapshot(boolean readOnly,int isolation,String statementTimeout,long advisoryLocks) {}
    /** Only limiter monotonic time changes; session/authentication clocks retain production behavior. */
    static final class CandidateLimiterClock {
        private final AtomicLong nanos=new AtomicLong();
        long nanoTime(){return nanos.get();}
        void advance(Duration duration){nanos.addAndGet(duration.toNanos());}
    }
    @TestConfiguration(proxyBeanMethods=false) static class QueryConfiguration {
        @Bean CandidateLimiterClock candidateLimiterClock(){return new CandidateLimiterClock();}
        @Bean @Primary LoginAttemptLimiter candidateLoginAttemptLimiter(CandidateLimiterClock clock){
            return new LoginAttemptLimiter(LoginAttemptLimiter.Config.defaults(),clock::nanoTime);
        }
        @Bean Queries candidateQueries(){return new Queries();}
        @Bean ExecuteListenerProvider candidateQueryListener(Queries queries){return ()->new ExecuteListener(){
            @Override public void executeStart(ExecuteContext context){
                String sql=context.sql();if(sql==null)return;queries.sql.add(sql);
                if(!sql.startsWith("select m.id, m.display_name"))return;
                // Raw JDBC avoids listener recursion; this is the actual candidate statement's connection.
                try(var statement=context.connection().createStatement();var rows=statement.executeQuery("select current_setting('statement_timeout'),(select count(*) from pg_locks where pid=pg_backend_pid() and locktype='advisory' and granted)")){
                    if(!rows.next())throw new IllegalStateException("Missing candidate transaction probe");
                    queries.snapshots.add(new QuerySnapshot(context.connection().isReadOnly(),context.connection().getTransactionIsolation(),rows.getString(1),rows.getLong(2)));
                }catch(SQLException failure){throw new IllegalStateException("Candidate transaction probe failed",failure);}
            }
        };}
    }

    /** All negative security mutations use the migrator's own managed READ COMMITTED transaction/global guard. */
    static final class OwnedDirectory implements AutoCloseable {
        final BusinessWorkflowPostgresFixture f;final HikariDataSource source;final JdbcTemplate jdbc;
        final TransactionTemplate tx;final PostgresAuthorizationWriteGuard guard;
        final Map<UUID,UUID> accounts=new HashMap<>(),principals=new HashMap<>(),roles=new HashMap<>(),scopes=new HashMap<>();
        OwnedDirectory(BusinessWorkflowPostgresFixture fixture){f=fixture;var target=BusinessWorkflowPostgresFixture.validateTarget();HikariConfig c=new HikariConfig();c.setJdbcUrl(target.jdbcUrl());c.setUsername(System.getenv("LAN_PG_USERNAME"));c.setPassword(System.getenv("LAN_PG_PASSWORD"));c.setMaximumPoolSize(1);c.setMinimumIdle(0);c.setPoolName("dispatch-candidate-owned");source=new HikariDataSource(c);jdbc=new JdbcTemplate(source);tx=new TransactionTemplate(new DataSourceTransactionManager(source));tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);guard=new PostgresAuthorizationWriteGuard(source);}
        void write(Consumer<JdbcTemplate> work){tx.executeWithoutResult(status->{guard.forGlobalSecurityChange();jdbc.queryForObject("select set_config('app.tenant_id',?,true)",String.class,f.tenantId.toString());work.accept(jdbc);});}
        void candidate(UUID member,String name,String kind,String status){write(db->{
            UUID account=UUID.randomUUID(),principal=UUID.randomUUID(),role=UUID.randomUUID(),scope=UUID.randomUUID();accounts.put(member,account);principals.put(member,principal);roles.put(member,role);scopes.put(member,scope);
            db.update("insert into public.account(id,login_key,status,credential_version) values (?,?,'ACTIVE',1)",account,"candidate-"+UUID.randomUUID());
            db.update("insert into public.principal(id,kind,account_id,status) values (?,'HUMAN',?,'ACTIVE')",principal,account);
            db.update("insert into public.tenant_member(tenant_id,id,account_id,status,auth_epoch,display_name) values (?,?,?,?,1,?)",f.tenantId,member,account,status,name);
            db.update("insert into public.member_org(tenant_id,member_id,org_id,kind,valid_from) values (?,?,?,'PRIMARY',current_timestamp-interval '1 day')",f.tenantId,member,f.organizationId);
            db.update("insert into public.\"role\"(tenant_id,id,code,name,active) values (?,?,?,?,true)",f.tenantId,role,"r-"+UUID.randomUUID(),name);
            db.update("insert into public.scope_rule(tenant_id,id,kind,version_no,definition) values (?,?,'EXPLICIT',1,'{}'::jsonb)",f.tenantId,scope);
            db.update("insert into public.scope_org(tenant_id,scope_rule_id,org_id) values (?,?,?)",f.tenantId,scope,f.organizationId);
            db.update("insert into public.role_permission(tenant_id,role_id,permission_id,scope_rule_id) select ?,?,id,? from public.permission where code='finding.remediate'",f.tenantId,role,scope);
            UUID subject=member;String column="member_id";
            if(kind.equals("ORG")){subject=UUID.randomUUID();column="org_id";
                db.update("insert into public.org_unit(tenant_id,id,code,name,active) values (?,?,?,?,true)",f.tenantId,subject,"org-"+UUID.randomUUID(),name);
                db.update("insert into public.member_org(tenant_id,member_id,org_id,kind,valid_from) values (?,?,?,'CONCURRENT',current_timestamp-interval '1 day')",f.tenantId,member,subject);
            }else if(kind.equals("POSITION")){subject=UUID.randomUUID();column="position_id";
                db.update("insert into public.\"position\"(tenant_id,id,code,name,active) values (?,?,?,?,true)",f.tenantId,subject,"pos-"+UUID.randomUUID(),name);
                db.update("insert into public.member_position(tenant_id,member_id,position_id,valid_from) values (?,?,?,current_timestamp-interval '1 day')",f.tenantId,member,subject);
            }else if(kind.equals("GROUP")){subject=UUID.randomUUID();column="group_id";
                db.update("insert into public.user_group(tenant_id,id,code,name,active) values (?,?,?,?,true)",f.tenantId,subject,"group-"+UUID.randomUUID(),name);
                db.update("insert into public.group_member(tenant_id,member_id,group_id,valid_from) values (?,?,?,current_timestamp-interval '1 day')",f.tenantId,member,subject);
            }
            db.update("insert into public.role_grant(tenant_id,id,role_id,subject_kind,"+column+",source_type,source_id,granted_by_member_id,valid_from) values (?,?,?,?,?,'DISPATCH_CANDIDATE_ACCEPTANCE',?,?,current_timestamp-interval '1 day')",f.tenantId,UUID.randomUUID(),role,kind,subject,UUID.randomUUID(),f.reporter.memberId());
        });}
        void defect(UUID member,String defect){write(db->{switch(defect){
            case "member"->db.update("update public.tenant_member set status='DISABLED' where tenant_id=? and id=?",f.tenantId,member);
            case "account"->db.update("update public.account set status='DISABLED' where id=?",accounts.get(member));
            case "principal"->db.update("update public.principal set status='DISABLED' where id=?",principals.get(member));
            case "nonhuman"->db.update("update public.principal set kind='SERVICE',account_id=null where id=?",principals.get(member));
            case "membership-expired"->db.update("update public.member_org set valid_to=current_timestamp where tenant_id=? and member_id=?",f.tenantId,member);
            case "membership-future"->db.update("update public.member_org set valid_from=current_timestamp+interval '1 day' where tenant_id=? and member_id=?",f.tenantId,member);
            case "org"->db.update("update public.org_unit set active=false where tenant_id=? and id=?",f.tenantId,f.organizationId);
            case "role"->db.update("update public.\"role\" set active=false where tenant_id=? and id=?",f.tenantId,roles.get(member));
            case "grant-expired"->db.update("update public.role_grant set valid_to=current_timestamp where tenant_id=? and role_id=?",f.tenantId,roles.get(member));
            case "grant-future"->db.update("update public.role_grant set valid_from=current_timestamp+interval '1 day' where tenant_id=? and role_id=?",f.tenantId,roles.get(member));
            case "permission"->db.update("update public.role_permission set permission_id=(select id from public.permission where code='finding.dispatch') where tenant_id=? and role_id=?",f.tenantId,roles.get(member));
            case "SELF","RELATION"->db.update("update public.scope_rule set kind=? where tenant_id=? and id=?",defect,f.tenantId,scopes.get(member));
            case "scope"->db.update("delete from public.scope_org where tenant_id=? and scope_rule_id=?",f.tenantId,scopes.get(member));
            default->throw new IllegalArgumentException(defect);
        }});}
        @Override public void close(){source.close();}
    }
}
