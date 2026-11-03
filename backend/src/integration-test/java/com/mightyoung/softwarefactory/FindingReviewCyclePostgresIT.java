package com.mightyoung.softwarefactory;

import com.mightyoung.softwarefactory.dualprevention.ModulithFindingWorkflowEventPublisher;
import com.mightyoung.softwarefactory.dualprevention.finding.workflow.*;
import com.mightyoung.softwarefactory.workflow.*;
import org.flowable.engine.*;
import org.jooq.ExecuteContext;
import org.jooq.ExecuteListener;
import org.jooq.ExecuteListenerProvider;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.parallel.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.lang.reflect.*;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Stream;
import static org.assertj.core.api.Assertions.*;

/** Real HTTPS + production service/repository/Flowable. Requires separately approved V17/grants.
 * Explicit fixture deployment does NOT prove no-auto-deploy or genuine PRE17 continuity.
 */
@SpringBootTest(classes=SoftwareFactoryApplication.class, webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(FindingReviewCyclePostgresIT.AcceptanceConfiguration.class)
@ActiveProfiles("lan-postgres-compatibility")
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FindingReviewCyclePostgresIT {
    static IdentityAcceptanceHttps https;
    @LocalServerPort int port;
    @Autowired FindingReviewCyclePostgresFixture fixture;
    @Autowired ObjectMapper json;
    @Autowired TaskService tasks;
    @Autowired RuntimeService runtime;
    @Autowired Faults faults;
    private final Map<UUID,IdentityAcceptanceHttps.Browser> browsers = new HashMap<>();

    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) throws Exception {
        IdentityAcceptanceTarget.validateInputs(System.getenv());
        var target = FindingReviewCyclePostgresFixture.validateTarget();
        if (https == null) https = IdentityAcceptanceHttps.create();
        p.add("spring.datasource.url",target::jdbcUrl);
        p.add("spring.datasource.username",()->System.getenv("LAN_PG_APP_USERNAME"));
        p.add("spring.datasource.password",()->System.getenv("LAN_PG_APP_PASSWORD"));
        p.add("spring.datasource.hikari.maximum-pool-size",()->6);
        p.add("server.address",()->"127.0.0.1"); p.add("server.ssl.enabled",()->true);
        p.add("server.ssl.key-store",()->https.keyStore().toUri().toString());
        p.add("server.ssl.key-store-password",https::password); p.add("server.ssl.key-store-type",()->"PKCS12");
        p.add("server.ssl.key-alias",()->"identity-acceptance");
        // This explicit-deployment profile is deliberately not the G6-07 main-startup proof.
        p.add("spring.flyway.enabled",()->false); p.add("flowable.database-schema-update",()->false);
        p.add("flowable.check-process-definitions",()->false);
        p.add("flowable.async-executor-activate",()->false); p.add("flowable.async-history-executor-activate",()->false);
    }
    // Reuse five real authenticated sessions across the class; this suite tests command authority,
    // not login throughput. Production IP/account login budgets and lockout stay unchanged.
    @BeforeEach void authenticateBeforeCommandSnapshots() throws Exception {
        for(var actor:List.of(fixture.business.reporter,fixture.business.person,fixture.business.safety,
                fixture.business.remediator,fixture.business.alternatePerson)) {
            var existing=browsers.get(actor.accountId());
            if(existing!=null) {
                var current=existing.get("/api/v1/session/current");
                if(current.status()==401) { existing.close(); browsers.remove(actor.accountId()); }
                else expect(current,200);
            }
            login(actor);
        }
    }
    @AfterEach void releaseBrowsers() {
        faults.plan.set(null);
        fixture.assertPoolTenantCleared();
    }
    @AfterAll void releaseTlsAndAuthenticatedSessions() throws Exception {
        try { browsers.values().forEach(IdentityAcceptanceHttps.Browser::close); browsers.clear(); }
        finally { if (https != null) https.close(); }
    }
    enum Stage { PERSON, SAFETY }

    @Test void twoReturnsOpenFreshCyclesPreserveEveryStatementAndReplayHistoricalCycle() throws Exception {
        UUID id=create(); String description=field(id,"description",String.class);
        UUID returned=UUID.randomUUID(); String returnBody=decision(1,1,Stage.PERSON,"RETURN_FOR_INFORMATION","First return");
        JsonNode first=ok(review(fixture.business.person,id,returned,returnBody));
        assertProjection(id,"REPORTER_SUPPLEMENT_PENDING",2,1,"UNKNOWN"); assertTask(id,"reporterSupplement",fixture.business.reporter.memberId());
        UUID supplemented=UUID.randomUUID(); String supplementBody=supplement(2,1,"Original reporter addition");
        JsonNode second=ok(post(fixture.business.reporter,id,"supplements",supplemented,supplementBody));
        assertProjection(id,"PERSON_CONFIRM_PENDING",3,2,"UNKNOWN"); assertTask(id,"personConfirm",fixture.business.person.memberId());
        approve(id,3,2,Stage.PERSON);
        ok(review(fixture.business.safety,id,UUID.randomUUID(),decision(4,2,Stage.SAFETY,"RETURN_FOR_INFORMATION","Second return")));
        assertProjection(id,"REPORTER_SUPPLEMENT_PENDING",5,2,"UNKNOWN");
        ok(post(fixture.business.reporter,id,"supplements",UUID.randomUUID(),supplement(5,2,"Second reporter addition")));
        approve(id,6,3,Stage.PERSON); approve(id,7,3,Stage.SAFETY);
        assertProjection(id,"SAFETY_DISPATCH_PENDING",8,3,"GENERAL"); assertTask(id,"safetyDispatch",fixture.business.safety.memberId());
        assertThat(field(id,"description",String.class)).isEqualTo(description);
        assertThat(field(id,"round_no",Integer.class)).isEqualTo(1);
        var committed=fixture.snapshot();
        assertThat(ok(review(fixture.business.person,id,returned,returnBody))).isEqualTo(first);
        assertThat(ok(post(fixture.business.reporter,id,"supplements",supplemented,supplementBody))).isEqualTo(second);
        assertThat(fixture.snapshot()).isEqualTo(committed);
        JsonNode history=ok(login(fixture.business.reporter).get(path(id,"review-history?pageSize=20")));
        assertThat(history.path("reviewCycle").asInt()).isEqualTo(3);
        assertThat(history.path("entries").size()).isEqualTo(7);
        Set<String> ids=new HashSet<>();
        for(JsonNode entry:history.path("entries")) {
            assertThat(ids.add(entry.path("id").asString())).isTrue();
            assertThat(entry.path("actorDisplay").path("provenance").asString()).isIn("CURRENT_DIRECTORY","UNAVAILABLE","RECORDED_SNAPSHOT");
            if(entry.path("origin").asString().equals("REPORTER_SUPPLEMENT")) {
                assertThat(entry.path("stage").isNull()).isTrue(); assertThat(entry.path("decision").isNull()).isTrue();
                assertThat(entry.path("nextReviewCycle").asInt()).isEqualTo(entry.path("reviewCycle").asInt()+1);
            } else assertThat(entry.path("origin").asString()).isEqualTo("REVIEW_DECISION");
        }
        assertThat(fixture.snapshot()).isEqualTo(committed);
        fixture.business.read(db->{
            assertThat(db.queryForObject("select count(*) from public.finding_review_cycle where tenant_id=? and finding_id=?",Integer.class,fixture.business.tenantId,id)).isEqualTo(3);
            assertThat(db.queryForObject("select count(*) from public.finding_command_participant where tenant_id=? and finding_id=?",Integer.class,fixture.business.tenantId,id)).isEqualTo(7);
            return null;
        });
    }

    @ParameterizedTest @EnumSource(Stage.class)
    void notEstablishedAtEitherActualStageEndsPinnedInstanceAndReplaysOnce(Stage stage) throws Exception {
        UUID id=at(stage); long version=stage==Stage.PERSON?1:2;
        UUID command=UUID.randomUUID(); String body=decision(version,1,stage,"NOT_ESTABLISHED","No established hazard");
        JsonNode result=ok(review(actor(stage),id,command,body));
        assertProjection(id,"NOT_ESTABLISHED",version+1,1,"UNKNOWN");
        assertThat(runtime.createProcessInstanceQuery().processInstanceId(instance(id)).count()).isZero();
        var committed=fixture.snapshot(); assertThat(ok(review(actor(stage),id,command,body))).isEqualTo(result);
        assertThat(fixture.snapshot()).isEqualTo(committed);
    }

    @Test void staleCycleWrongRoleWrongReporterLegacyRouteAndChangedFingerprintHaveNoEffects() throws Exception {
        UUID id=create(); var before=fixture.snapshot();
        expect(review(fixture.business.reporter,id,UUID.randomUUID(),decision(1,1,Stage.PERSON,"APPROVE","wrong role")),403);
        expect(review(fixture.business.alternatePerson,id,UUID.randomUUID(),decision(1,1,Stage.PERSON,"APPROVE","not assigned")),403);
        expect(review(fixture.business.person,id,UUID.randomUUID(),decision(1,2,Stage.PERSON,"APPROVE","future cycle")),409);
        expect(post(fixture.business.person,id,"confirm/person",UUID.randomUUID(),json.writeValueAsString(Map.of("expectedVersion",1,"reason","legacy route"))),409);
        assertThat(fixture.snapshot()).isEqualTo(before);
        UUID command=UUID.randomUUID(); String body=decision(1,1,Stage.PERSON,"RETURN_FOR_INFORMATION","return");
        ok(review(fixture.business.person,id,command,body)); var returned=fixture.snapshot();
        expect(review(fixture.business.person,id,command,decision(1,1,Stage.PERSON,"RETURN_FOR_INFORMATION","changed")),409);
        expect(review(fixture.business.person,id,command,decision(1,2,Stage.PERSON,"RETURN_FOR_INFORMATION","return")),409);
        expect(post(fixture.business.safety,id,"supplements",UUID.randomUUID(),supplement(2,1,"wrong reporter")),403);
        assertThat(fixture.snapshot()).isEqualTo(returned);
        ok(post(fixture.business.reporter,id,"supplements",UUID.randomUUID(),supplement(2,1,"reporter supplied")));
        var next=fixture.snapshot();
        expect(review(fixture.business.person,id,UUID.randomUUID(),decision(3,1,Stage.PERSON,"APPROVE","old cycle")),409);
        expect(review(fixture.business.safety,id,UUID.randomUUID(),decision(3,2,Stage.SAFETY,"APPROVE","old person proof")),422);
        assertThat(fixture.snapshot()).isEqualTo(next);
    }

    @Test void nullForbiddenGradeUnicodeBlankUnknownAndIdentityFieldsAreRejectedWithoutWrites() throws Exception {
        UUID id=create(); var before=fixture.snapshot();
        for (String invalid : List.of(
                "{\"expectedVersion\":1,\"reviewCycle\":1,\"stage\":\"PERSON\",\"decision\":\"APPROVE\",\"reason\":\"x\",\"grade\":null}",
                "{\"expectedVersion\":1,\"reviewCycle\":1,\"stage\":\"PERSON\",\"decision\":\"APPROVE\",\"reason\":\"x\",\"actorMemberId\":\""+fixture.business.person.memberId()+"\"}",
                decision(1,1,Stage.PERSON,"APPROVE","\u00a0\u2007\u202f"),
                decision(1,1,Stage.PERSON,"APPROVE","x".repeat(4001)),
                decision(1,1,Stage.PERSON,"UNSUPPORTED","invalid enum"))) {
            expect(review(fixture.business.person,id,UUID.randomUUID(),invalid),400);
            assertThat(fixture.snapshot()).isEqualTo(before);
        }
    }

    @Test void reviewAndSupplementReplayRequireCurrentlyEligibleOriginalParticipant() throws Exception {
        UUID id=create(), command=UUID.randomUUID(); String body=decision(1,1,Stage.PERSON,"RETURN_FOR_INFORMATION","return");
        ok(review(fixture.business.person,id,command,body));
        ok(post(fixture.business.reporter,id,"supplements",UUID.randomUUID(),supplement(2,1,"addition")));
        fixture.securityWrite(db->db.update("update public.role_grant set valid_to=current_timestamp where tenant_id=? and member_id=? and valid_to is null",fixture.business.tenantId,fixture.business.person.memberId()));
        try {
            var before=fixture.snapshot(); expect(review(fixture.business.person,id,command,body),403);
            assertThat(fixture.snapshot()).isEqualTo(before);
        } finally {
            fixture.securityWrite(db->db.update("update public.role_grant set valid_to=null where tenant_id=? and member_id=?",fixture.business.tenantId,fixture.business.person.memberId()));
        }
    }

    @Test void currentRouteRevocationBlocksSupplementAndCannotReuseEarlierCyclePersonProof() throws Exception {
        UUID id=create(); ok(review(fixture.business.person,id,UUID.randomUUID(),decision(1,1,Stage.PERSON,"RETURN_FOR_INFORMATION","return")));
        fixture.business.expirePersonAssignments();
        try {
            var before=fixture.snapshot(); expect(post(fixture.business.reporter,id,"supplements",UUID.randomUUID(),supplement(2,1,"missing route")),422);
            assertThat(fixture.snapshot()).isEqualTo(before);
        } finally {
            fixture.securityWrite(db->db.update("update public.finding_stage_assignment set valid_to=null where tenant_id=? and organization_id=? and stage='PERSON_CONFIRM'",fixture.business.tenantId,fixture.business.organizationId));
        }
    }

    @Test void runtimeRlsAndPoolCleanupHideEveryNewHistoryWithoutOrWithForeignTenant() throws Exception {
        UUID id=create(); ok(review(fixture.business.person,id,UUID.randomUUID(),decision(1,1,Stage.PERSON,"RETURN_FOR_INFORMATION","rls")));
        for(String table:FindingReviewCyclePostgresFixture.HISTORIES.subList(0,4)) {
            assertThat(fixture.<Long>runtime(null,db->db.queryForObject("select count(*) from public."+table,Long.class))).isZero();
            assertThat(fixture.<Long>runtime(UUID.randomUUID(),db->db.queryForObject("select count(*) from public."+table,Long.class))).isZero();
            fixture.assertPoolTenantCleared();
        }
        assertThat(fixture.<Long>runtime(fixture.business.tenantId,db->db.queryForObject("select count(*) from public.finding_review_decision where finding_id=?",Long.class,id))).isEqualTo(1);
    }

    @Test void realSqlRejectsEveryRequiredNullAndGradeBypassAndHistoriesAreIntrinsicallyImmutable() throws Exception {
        UUID id=create(); ok(review(fixture.business.person,id,UUID.randomUUID(),decision(1,1,Stage.PERSON,"RETURN_FOR_INFORMATION","sql")));
        ok(post(fixture.business.reporter,id,"supplements",UUID.randomUUID(),supplement(2,1,"sql statement")));
        approve(id,3,2,Stage.PERSON); approve(id,4,2,Stage.SAFETY);
        var before=fixture.snapshot();
        for(String table:FindingReviewCyclePostgresFixture.HISTORIES.subList(0,4)) {
            List<String> columns=fixture.business.read(db->db.queryForList("select column_name from information_schema.columns where table_schema='public' and table_name=? and is_nullable='NO' order by ordinal_position",String.class,table));
            assertThat(columns).isNotEmpty();
            for(String column:columns) {
                // Real PostgreSQL checks NOT NULL before uniqueness/FK; no successful synthetic clone is committed.
                sqlState("23502",()->fixture.rollbackProbe(db->db.update("insert into public."+table+" select (jsonb_populate_record(null::public."+table+",to_jsonb(t)||jsonb_build_object(?,null))).* from public."+table+" t where tenant_id=? limit 1",column,fixture.business.tenantId)));
            }
        }
        sqlState("23514",()->fixture.rollbackProbe(db->db.update("insert into public.finding_review_decision select (jsonb_populate_record(null::public.finding_review_decision,to_jsonb(t)||'{\"confirmed_grade\":null}'::jsonb)).* from public.finding_review_decision t where tenant_id=? and finding_id=? and stage='SAFETY' and decision='APPROVE'",fixture.business.tenantId,id)));
        sqlState("23514",()->fixture.rollbackProbe(db->db.update("insert into public.finding_command_receipt select (jsonb_populate_record(null::public.finding_command_receipt,to_jsonb(t)||'{\"result_review_cycle\":null}'::jsonb)).* from public.finding_command_receipt t where tenant_id=? and result_finding_id=? and completed_at is not null limit 1",fixture.business.tenantId,id)));
        sqlState("23514",()->fixture.rollbackProbe(db->{
            UUID fresh=UUID.randomUUID();
            db.update("insert into public.finding_command_receipt(tenant_id,command_id,actor_id,action,request_hash) values (?,?,?,'finding.review.decision',?)",fixture.business.tenantId,fresh,fixture.business.person.principalId(),"0".repeat(64));
            return db.update("update public.finding_command_receipt set result_finding_id=?,result_state='SAFETY_DISPATCH_PENDING',result_round_no=1,result_version=5,completed_at=current_timestamp where tenant_id=? and command_id=?",id,fixture.business.tenantId,fresh);
        }));
        for(String table:List.of("finding_review_cycle","finding_review_decision","finding_report_supplement","finding_command_participant","finding_transition")) {
            sqlState("23514",()->fixture.rollbackProbe(db->db.update("update public."+table+" set tenant_id=tenant_id where tenant_id=?",fixture.business.tenantId)));
            sqlState("23514",()->fixture.rollbackProbe(db->db.update("delete from public."+table+" where tenant_id=?",fixture.business.tenantId)));
        }
        sqlState("23514",()->fixture.rollbackProbe(db->db.update("update public.finding set description='replacement' where tenant_id=? and id=?",fixture.business.tenantId,id)));
        sqlState("23514",()->fixture.rollbackProbe(db->db.update("update public.finding set review_protocol='LEGACY' where tenant_id=? and id=?",fixture.business.tenantId,id)));
        sqlState("23503",()->fixture.rollbackProbe(db->db.update("update public.finding set review_cycle=review_cycle+1 where tenant_id=? and id=?",fixture.business.tenantId,id)));
        assertThat(fixture.snapshot()).isEqualTo(before);
        fixture.runtime(fixture.business.tenantId,db->{
            assertThat(db.queryForObject("select public.finding_review_utf16_length(?)",Integer.class,"😀".repeat(2000))).isEqualTo(4000);
            assertThat(db.queryForObject("select public.finding_review_text_valid(?)",Boolean.class,"😀".repeat(2000))).isTrue();
            assertThat(db.queryForObject("select public.finding_review_text_valid(?)",Boolean.class,"😀".repeat(2000)+"x")).isFalse();
            assertThat(db.queryForObject("select public.finding_review_text_valid(?)",Boolean.class,"\u00a0\u2007\u202f")).isFalse(); return null;
        });
    }

    @Test void competingReturnAndApprovalAndCompetingSupplementsHaveExactlyOneActualEffect() throws Exception {
        UUID id=create(); login(fixture.business.person); login(fixture.business.reporter);
        var statuses=race(()->review(fixture.business.person,id,UUID.randomUUID(),decision(1,1,Stage.PERSON,"RETURN_FOR_INFORMATION","competing return")).status(),
                ()->review(fixture.business.person,id,UUID.randomUUID(),decision(1,1,Stage.PERSON,"APPROVE","competing approve")).status());
        assertThat(statuses).containsExactly(200,409);
        assertThat(field(id,"version",Long.class)).isEqualTo(2);
        fixture.business.read(db->{assertThat(db.queryForObject("select count(*) from public.finding_review_decision where tenant_id=? and finding_id=?",Integer.class,fixture.business.tenantId,id)).isEqualTo(1);return null;});
        UUID returned=create(); ok(review(fixture.business.person,returned,UUID.randomUUID(),decision(1,1,Stage.PERSON,"RETURN_FOR_INFORMATION","supplement race")));
        assertThat(race(()->post(fixture.business.reporter,returned,"supplements",UUID.randomUUID(),supplement(2,1,"first")).status(),
                ()->post(fixture.business.reporter,returned,"supplements",UUID.randomUUID(),supplement(2,1,"second")).status())).containsExactly(200,409);
        assertProjection(returned,"PERSON_CONFIRM_PENDING",3,2,"UNKNOWN");
    }

    enum FaultPoint { DECISION, SUCCESSOR_CYCLE, SUPPLEMENT, FINDING, BEFORE_AUDIT, AFTER_AUDIT, ENGINE, RECEIPT, PUBLICATION }
    static Stream<Arguments> rollbackCases() {
        return Stream.of(FaultPoint.values()).flatMap(point -> {
            if(point==FaultPoint.DECISION) return Stream.of(Arguments.of(point,false));
            if(point==FaultPoint.SUCCESSOR_CYCLE||point==FaultPoint.SUPPLEMENT) return Stream.of(Arguments.of(point,true));
            return Stream.of(Arguments.of(point,false),Arguments.of(point,true));
        });
    }
    @ParameterizedTest @MethodSource("rollbackCases")
    void actualParticipantBoundaryFaultRollsBackEveryObservedParticipantAndSameKeyRetriesOnce(FaultPoint point,boolean isSupplement) throws Exception {
        UUID id=create();
        if(isSupplement) ok(review(fixture.business.person,id,UUID.randomUUID(),decision(1,1,Stage.PERSON,"RETURN_FOR_INFORMATION","prepare rollback")));
        UUID command=UUID.randomUUID(); String body=isSupplement?supplement(2,1,"rollback addition"):decision(1,1,Stage.PERSON,"RETURN_FOR_INFORMATION","rollback decision");
        var before=fixture.snapshot(); var plan=new FaultPlan(command,point); faults.plan.set(plan);
        expect(isSupplement?post(fixture.business.reporter,id,"supplements",command,body):review(fixture.business.person,id,command,body),503);
        assertThat(plan.reached).as("actual boundary was reached before failure").isTrue();
        assertThat(fixture.snapshot()).isEqualTo(before); faults.plan.set(null);
        JsonNode result=ok(isSupplement?post(fixture.business.reporter,id,"supplements",command,body):review(fixture.business.person,id,command,body));
        var committed=fixture.snapshot();
        assertThat(ok(isSupplement?post(fixture.business.reporter,id,"supplements",command,body):review(fixture.business.person,id,command,body))).isEqualTo(result);
        assertThat(fixture.snapshot()).isEqualTo(committed);
    }

    private UUID create() throws Exception {
        var body=Map.of("organizationId",fixture.business.organizationId,"description",fixture.business.run+" review candidate "+UUID.randomUUID(),"discoveredAt",OffsetDateTime.now().minusHours(1).toString(),"grade","UNKNOWN","sourceCategory","FIELD_OBSERVATION","urgency","NORMAL","location","Owned G006 acceptance fixture");
        UUID id=UUID.fromString(ok(login(fixture.business.reporter).postWithHeaders("/api/v1/findings",json.writeValueAsString(body),login(fixture.business.reporter).csrf(),Map.of("Idempotency-Key",UUID.randomUUID().toString()))).path("findingId").asString());
        assertProjection(id,"PERSON_CONFIRM_PENDING",1,1,"UNKNOWN"); assertTask(id,"personConfirm",fixture.business.person.memberId());
        assertThat(fixture.business.<String>read(db->db.queryForObject("select definition_id from public.workflow_binding where tenant_id=? and business_id=?",String.class,fixture.business.tenantId,id))).isEqualTo(fixture.definitionId);
        return id;
    }
    private UUID at(Stage stage) throws Exception { UUID id=create(); if(stage==Stage.SAFETY) approve(id,1,1,Stage.PERSON); return id; }
    private void approve(UUID id,long version,int cycle,Stage stage) throws Exception { ok(review(actor(stage),id,UUID.randomUUID(),decision(version,cycle,stage,"APPROVE","Owned approval"))); }
    private BusinessWorkflowPostgresFixture.Actor actor(Stage stage) { return stage==Stage.PERSON?fixture.business.person:fixture.business.safety; }
    private String decision(long version,int cycle,Stage stage,String decision,String reason) throws Exception {
        Map<String,Object> body=new LinkedHashMap<>(Map.of("expectedVersion",version,"reviewCycle",cycle,"stage",stage.name(),"decision",decision,"reason",reason));
        if(stage==Stage.SAFETY&&decision.equals("APPROVE")) body.put("grade","GENERAL"); return json.writeValueAsString(body);
    }
    private String supplement(long version,int cycle,String text) throws Exception { return json.writeValueAsString(Map.of("expectedVersion",version,"reviewCycle",cycle,"statement",text)); }
    private IdentityAcceptanceHttps.Response review(BusinessWorkflowPostgresFixture.Actor actor,UUID id,UUID command,String body) throws Exception { return post(actor,id,"review-decisions",command,body); }
    private IdentityAcceptanceHttps.Response post(BusinessWorkflowPostgresFixture.Actor actor,UUID id,String suffix,UUID command,String body) throws Exception { var browser=login(actor); return browser.postWithHeaders(path(id,suffix),body,browser.csrf(),Map.of("Idempotency-Key",command.toString())); }
    private static String path(UUID id,String suffix) { return "/api/v1/findings/"+id+"/"+suffix; }
    private IdentityAcceptanceHttps.Browser login(BusinessWorkflowPostgresFixture.Actor actor) throws Exception {
        var existing=browsers.get(actor.accountId()); if(existing!=null) return existing;
        var browser=https.browser(port); browsers.put(actor.accountId(),browser);
        expect(browser.get("/api/v1/auth/csrf"),204);
        expect(browser.post("/api/v1/auth/login",json.writeValueAsString(Map.of("loginKey",actor.loginKey(),"password",actor.password(),"tenantCode",fixture.business.tenantCode)),browser.csrf()),204);
        expect(browser.get("/api/v1/auth/csrf"),204); return browser;
    }
    private JsonNode ok(IdentityAcceptanceHttps.Response response) throws Exception { expect(response,200); return json.readTree(response.body()).path("data"); }
    private static void expect(IdentityAcceptanceHttps.Response response,int status) { assertThat(response.status()).as("real HTTPS command status").isEqualTo(status); }
    private <T>T field(UUID id,String column,Class<T> type) {
        if(!Set.of("description","state","grade","version","round_no","review_cycle","review_protocol").contains(column)) throw new IllegalArgumentException();
        return fixture.business.read(db->db.queryForObject("select "+column+" from public.finding where tenant_id=? and id=?",type,fixture.business.tenantId,id));
    }
    private String instance(UUID id) { return fixture.business.read(db->db.queryForObject("select process_instance_id from public.workflow_binding where tenant_id=? and business_id=?",String.class,fixture.business.tenantId,id)); }
    private void assertProjection(UUID id,String state,long version,int cycle,String grade) {
        assertThat(field(id,"state",String.class)).isEqualTo(state); assertThat(field(id,"version",Long.class)).isEqualTo(version);
        assertThat(field(id,"review_cycle",Integer.class)).isEqualTo(cycle); assertThat(field(id,"grade",String.class)).isEqualTo(grade);
        assertThat(field(id,"review_protocol",String.class)).isEqualTo("CYCLE_V1");
    }
    private void assertTask(UUID id,String key,UUID actor) {
        var task=tasks.createTaskQuery().processInstanceId(instance(id)).singleResult(); assertThat(task).isNotNull();
        assertThat(task.getTaskDefinitionKey()).isEqualTo(key); assertThat(task.getAssignee()).isEqualTo(actor.toString());
    }
    private static void sqlState(String expected,org.assertj.core.api.ThrowableAssert.ThrowingCallable probe) {
        Throwable failure=catchThrowable(probe); assertThat(failure).isNotNull(); SQLException sql=null;
        for(Throwable cause=failure;cause!=null;cause=cause.getCause()) if(cause instanceof SQLException e) sql=e;
        assertThat((Throwable)sql).isNotNull(); assertThat(sql.getSQLState()).isEqualTo(expected);
    }
    private static List<Integer> race(Callable<Integer> first,Callable<Integer> second) throws Exception {
        try(var executor=Executors.newFixedThreadPool(2)) {
            var start=new CyclicBarrier(2);
            var a=executor.submit(()->{start.await(10,TimeUnit.SECONDS);return first.call();});
            var b=executor.submit(()->{start.await(10,TimeUnit.SECONDS);return second.call();});
            return Stream.of(a.get(30,TimeUnit.SECONDS),b.get(30,TimeUnit.SECONDS)).sorted().toList();
        }
    }

    static final class FaultPlan { final UUID command; final FaultPoint point; volatile boolean reached; FaultPlan(UUID command,FaultPoint point){this.command=command;this.point=point;} }
    static final class Faults {
        final AtomicReference<FaultPlan> plan=new AtomicReference<>(); final ThreadLocal<UUID> current=new ThreadLocal<>();
        void hit(FaultPoint point) { var p=plan.get(); if(p!=null&&p.point==point&&p.command.equals(current.get())) { p.reached=true; throw new DataAccessResourceFailureException("Owned G006 transaction boundary failure: "+point); } }
    }
    @TestConfiguration(proxyBeanMethods=false) static class AcceptanceConfiguration {
        @Bean(destroyMethod="close") FindingReviewCyclePostgresFixture reviewCycleFixture(PasswordEncoder passwords,RepositoryService definitions) throws Exception { return new FindingReviewCyclePostgresFixture(passwords,definitions); }
        @Bean @Primary FindingReviewDefinitionProperties reviewedOwnedPins(FindingReviewCyclePostgresFixture fixture) { return fixture.properties(); }
        @Bean Faults reviewCycleFaults() { return new Faults(); }
        @Bean @Primary FindingWorkflowRepository observedRealRepository(@Qualifier("jooqFindingWorkflowRepository") FindingWorkflowRepository real,Faults faults) {
            return new FindingWorkflowRepository() {
                @Override public <T>T inTenantTransaction(UUID tenant,Function<Transaction,T> work) {
                    return real.inTenantTransaction(tenant,actual->{
                        Transaction observed=(Transaction)Proxy.newProxyInstance(Transaction.class.getClassLoader(),new Class<?>[]{Transaction.class},(proxy,method,args)->{
                            if(method.getName().equals("claimCommand")||method.getName().equals("readCommand")) faults.current.set(((FindingWorkflowContracts.WorkflowCommand)args[0]).commandId());
                            Object value=invoke(actual,method,args);
                            switch(method.getName()) {
                                case "appendReviewDecision" -> faults.hit(FaultPoint.DECISION);
                                case "appendReviewCycle" -> faults.hit(FaultPoint.SUCCESSOR_CYCLE);
                                case "appendSupplement" -> faults.hit(FaultPoint.SUPPLEMENT);
                                case "updateFinding" -> faults.hit(FaultPoint.FINDING);
                                case "appendTransition" -> faults.hit(FaultPoint.AFTER_AUDIT);
                                case "completeCommand" -> faults.hit(FaultPoint.RECEIPT);
                            }
                            return value;
                        });
                        try { return work.apply(observed); } finally { faults.current.remove(); }
                    });
                }
            };
        }
        @Bean @Primary WorkflowFacade observedRealWorkflow(FlowableWorkflowFacade real,Faults faults) {
            return (WorkflowFacade)Proxy.newProxyInstance(WorkflowFacade.class.getClassLoader(),new Class<?>[]{WorkflowFacade.class},(proxy,method,args)->{
                Object result=invoke(real,method,args); if(method.getName().equals("completeTask")) faults.hit(FaultPoint.ENGINE); return result;
            });
        }
        @Bean ExecuteListenerProvider observeActualAuditBoundary(Faults faults) { return ()->new ExecuteListener() {
            @Override public void executeStart(ExecuteContext ctx) { if(ctx.sql()!=null&&ctx.sql().toLowerCase(Locale.ROOT).contains("insert into public.audit_event")) faults.hit(FaultPoint.BEFORE_AUDIT); }
        }; }
        @Bean @Primary FindingWorkflowEventPublisher observedRealPublication(ModulithFindingWorkflowEventPublisher real,Faults faults) { return event->{real.publish(event);faults.hit(FaultPoint.PUBLICATION);}; }
        private static Object invoke(Object actual,Method method,Object[] args) throws Throwable { try{return method.invoke(actual,args);}catch(InvocationTargetException failure){throw failure.getCause();} }
    }
}
