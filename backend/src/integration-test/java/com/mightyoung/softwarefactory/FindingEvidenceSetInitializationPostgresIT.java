package com.mightyoung.softwarefactory;

import com.mightyoung.softwarefactory.authorization.PostgresAuthorizationWriteGuard;
import com.mightyoung.softwarefactory.dualprevention.finding.evidenceupload.FindingEvidenceSetInitializationContracts.InitializeCommand;
import com.mightyoung.softwarefactory.dualprevention.finding.evidenceupload.FindingEvidenceSetInitializationRepository;
import com.mightyoung.softwarefactory.files.FileEvidenceTrustUnavailableException;
import com.mightyoung.softwarefactory.files.UploadProducerReadiness;
import com.mightyoung.softwarefactory.workflow.WorkflowFacade;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.TaskService;
import org.jooq.ExecuteContext;
import org.jooq.ExecuteListener;
import org.jooq.ExecuteListenerProvider;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.parallel.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.lang.reflect.*;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import static org.assertj.core.api.Assertions.*;

/** Actual HTTPS/session/CSRF, production initializer and real V2 task/binding; no mocked authority.
 * Source preparation only until separately approved V16/grants and an acknowledged owned target.
 * Overlay only this file and its fixture onto a fresh PRE17 source copy for the V16 phase.
 * This fixture does not prove full create→dispatch, producer/sealing, or old-source continuity.
 */
@SpringBootTest(classes=SoftwareFactoryApplication.class, webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(FindingEvidenceSetInitializationPostgresIT.AcceptanceConfiguration.class)
@ActiveProfiles("lan-postgres-compatibility")
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class FindingEvidenceSetInitializationPostgresIT {
    private static final List<String> RECEIPT_COLUMNS=List.of("tenant_id","id","owner_account_id","owner_actor_id","owner_member_id","operation","idempotency_key","fingerprint_version","payload_sha256","finding_id","round_no","notice_id","evidence_set_id","expected_finding_version","initial_evidence_revision","created_at");
    // Successful private TLS material and keytool diagnostics remain available for evidence review.
    static IdentityAcceptanceHttps https;
    @LocalServerPort int port;
    @Autowired PasswordEncoder passwords;
    @Autowired ObjectMapper json;
    @Autowired RepositoryService definitions;
    @Autowired WorkflowFacade workflow;
    @Autowired TaskService tasks;
    @Autowired PlatformTransactionManager manager;
    @Autowired JdbcTemplate app;
    @Autowired PostgresAuthorizationWriteGuard guard;
    @Autowired UploadProducerReadiness producer;
    @Autowired Faults faults;
    FindingEvidenceSetInitializationPostgresFixture fixture;
    final Map<UUID,IdentityAcceptanceHttps.Browser> browsers = new HashMap<>();

    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) throws Exception {
        IdentityAcceptanceTarget.validateInputs(System.getenv());
        var target = FindingEvidenceSetInitializationPostgresFixture.validateTarget();
        if (https == null) https = IdentityAcceptanceHttps.create();
        p.add("spring.datasource.url", target::jdbcUrl);
        p.add("spring.datasource.username", () -> System.getenv("LAN_PG_APP_USERNAME"));
        p.add("spring.datasource.password", () -> System.getenv("LAN_PG_APP_PASSWORD"));
        p.add("spring.datasource.hikari.maximum-pool-size", () -> 6);
        p.add("server.address", () -> "127.0.0.1"); p.add("server.ssl.enabled", () -> true);
        p.add("server.ssl.key-store", () -> https.keyStore().toUri().toString());
        p.add("server.ssl.key-store-password", https::password); p.add("server.ssl.key-store-type", () -> "PKCS12");
        p.add("server.ssl.key-alias", () -> "identity-acceptance");
        p.add("spring.flyway.enabled", () -> false); p.add("spring.sql.init.mode", () -> "never");
        p.add("spring.session.jdbc.initialize-schema", () -> "never"); p.add("spring.session.jdbc.cleanup-cron", () -> "-");
        p.add("spring.modulith.events.jdbc.schema-initialization.enabled", () -> false);
        p.add("flowable.database-schema-update", () -> false); p.add("flowable.check-process-definitions", () -> false);
        p.add("flowable.async-executor-activate", () -> false); p.add("flowable.async-history-executor-activate", () -> false);
        p.add("software-factory.findings.evidence-set-initialization.owner-open-sets", () -> 2);
        p.add("software-factory.findings.evidence-set-initialization.tenant-open-sets", () -> 3);
    }

    @BeforeEach void ownedTenantAndRealSession() throws Exception {
        // Fifteen invocations use at most eighteen real logins; each browser is reused for all
        // commands in its owned case, and races copy the same real session instead of logging in.
        fixture = new FindingEvidenceSetInitializationPostgresFixture(passwords, definitions);
        login(fixture.business.remediator);
    }
    @AfterEach void releaseOnlyOwnedSessionsAndPools() {
        faults.plan.set(null);
        browsers.values().forEach(IdentityAcceptanceHttps.Browser::close); browsers.clear();
        if (fixture != null) { fixture.assertPoolTenantCleared(); fixture.close(); }
    }

    @Test void first201CreatesOnlyAnEmptyOwnedSetReceiptAndAuditWithoutProducer() throws Exception {
        var candidate = candidate(fixture.business.remediator);
        assertThatThrownBy(producer::requireReady).isInstanceOf(FileEvidenceTrustUnavailableException.class);
        var before = fixture.snapshot(); UUID key = UUID.randomUUID();
        var response = post(candidate, key, 1); JsonNode data = data(response, 201);
        UUID set = UUID.fromString(data.path("evidenceSetId").asString());
        assertThat(data.path("state").asString()).isEqualTo("UPLOADING");
        assertThat(data.path("initialEvidenceRevision").asLong()).isZero();
        assertThat(response.http().headers().firstValue("Location")).isEmpty();
        assertThat(response.http().headers().firstValue("Cache-Control")).contains("no-store");
        fixture.read(db -> {
            var row = db.queryForMap("select * from public.evidence_set where tenant_id=? and id=?", fixture.business.tenantId, set);
            assertThat(row.get("finding_id")).isEqualTo(candidate.findingId());
            assertThat(row.get("owner_member_id")).isEqualTo(candidate.owner().memberId());
            assertThat(row.get("evidence_revision")).isEqualTo(0L);
            assertThat(row.get("verified_at")).isNull(); assertThat(row.get("submission_sealed_at")).isNull();
            assertThat(db.queryForObject("select owner_account_id from public.finding_evidence_set_initialization_receipt where tenant_id=? and evidence_set_id=?", UUID.class, fixture.business.tenantId, set)).isEqualTo(candidate.owner().accountId());
            assertThat(db.queryForObject("select created_at from public.evidence_set where tenant_id=? and id=?", OffsetDateTime.class, fixture.business.tenantId, set).toInstant()).isEqualTo(OffsetDateTime.parse(data.path("createdAt").asString()).toInstant());
            return null;
        });
        var after = fixture.snapshot();
        for (String table : before.rows().keySet())
            if (!Set.of("evidence_set", FindingEvidenceSetInitializationPostgresFixture.RECEIPTS, "audit_event").contains(table))
                assertThat(after.rows().get(table)).as("unchanged initializer participant %s", table).isEqualTo(before.rows().get(table));
        assertThat(count("evidence_set")).isEqualTo(1); assertThat(count(FindingEvidenceSetInitializationPostgresFixture.RECEIPTS)).isEqualTo(1);
        assertThat(count("audit_event")).isEqualTo(before.rows().get("audit_event").size() + 1L);
        assertThat(count("file_object")).isZero(); assertThat(count("file_link")).isZero(); assertThat(count("evidence_item")).isZero();
        assertCurrentTask(candidate);
    }

    @Test void exactReplayReturnsStableInitialFactsAfterVersionAndEditableRevisionChange() throws Exception {
        var candidate = candidate(fixture.business.remediator); UUID key = UUID.randomUUID();
        JsonNode first = data(post(candidate,key,1),201); UUID set = UUID.fromString(first.path("evidenceSetId").asString());
        fixture.securityWrite(db -> {
            db.update("update public.finding set version=version+1 where tenant_id=? and id=?", fixture.business.tenantId,candidate.findingId());
            db.update("insert into public.evidence_item(tenant_id,id,evidence_set_id,source_kind,source_url,state,verification_level) values (?,?,?,'URL','https://invalid.example/g024-unverified','QUARANTINED','UNVERIFIED')",fixture.business.tenantId,UUID.randomUUID(),set);
            return null;
        });
        var before = fixture.snapshot(); assertThat(data(post(candidate,key,1),200)).isEqualTo(first);
        expect(post(candidate,UUID.randomUUID(),1),409);
        expect(post(candidate,key,2),409);
        assertThat(fixture.snapshot()).isEqualTo(before);
    }

    @Test void actorScopedKeysCannotBeReusedForAnotherFindingButAnotherOwnerHasItsOwnNamespace() throws Exception {
        var owner = candidate(fixture.business.remediator); var another = candidate(fixture.business.remediator);
        var otherActor = candidate(fixture.business.alternatePerson); UUID key = UUID.randomUUID();
        JsonNode first = data(post(owner,key,1),201); var before = fixture.snapshot();
        expect(post(another,key,1),409); assertThat(fixture.snapshot()).isEqualTo(before);
        JsonNode second = data(post(otherActor,key,1),201);
        assertThat(second.path("evidenceSetId").asString()).isNotEqualTo(first.path("evidenceSetId").asString());
        assertThat(count(FindingEvidenceSetInitializationPostgresFixture.RECEIPTS)).isEqualTo(2);
    }

    enum AuthorityChange { GRANT, CREDENTIAL, ACCOUNT, ASSIGNMENT }
    @ParameterizedTest @EnumSource(AuthorityChange.class)
    void currentAuthorityIsCheckedBeforeSuccessfulReceiptReplay(AuthorityChange change) throws Exception {
        var candidate = candidate(fixture.business.remediator); UUID key = UUID.randomUUID(); data(post(candidate,key,1),201);
        fixture.securityWrite(db -> {
            switch (change) {
                case GRANT -> db.update("update public.role_grant set valid_to=current_timestamp where tenant_id=? and member_id=? and valid_to is null",fixture.business.tenantId,candidate.owner().memberId());
                case CREDENTIAL -> db.update("update public.account set credential_version=credential_version+1 where id=?",candidate.owner().accountId());
                case ACCOUNT -> db.update("update public.account set status='DISABLED' where id=?",candidate.owner().accountId());
                case ASSIGNMENT -> db.update("update public.remediation_notice set assignee_id=? where tenant_id=? and id=?",fixture.business.alternatePerson.memberId(),fixture.business.tenantId,candidate.noticeId());
            }
            return null;
        });
        var before = fixture.snapshot();
        expect(postWithExistingSession(candidate,key,1),switch(change) { case GRANT -> 403; case CREDENTIAL,ACCOUNT -> 401; case ASSIGNMENT -> 404; });
        assertThat(fixture.snapshot()).isEqualTo(before);
    }

    @Test void ownerAndTenantQuotasCountNonemptySetsAndIgnoreFailedClosedAndOldRoundMetadata() throws Exception {
        var current = candidate(fixture.business.remediator);
        fixture.seedSet(current,"UPLOADING",true); fixture.seedSet(current,"QUARANTINED",true);
        fixture.seedSet(current,"FAILED",false);
        fixture.seedSet(fixture.seedCase(fixture.business.remediator,1,1,"CLOSED"),"UPLOADING",false);
        fixture.seedSet(fixture.seedCase(fixture.business.remediator,2,1,"OPEN"),"UPLOADING",false);
        var before = fixture.snapshot(); expect(post(current,UUID.randomUUID(),1),429);
        assertThat(fixture.snapshot()).isEqualTo(before);
        var other = candidate(fixture.business.alternatePerson); data(post(other,UUID.randomUUID(),1),201);
        before = fixture.snapshot(); expect(post(other,UUID.randomUUID(),1),429); assertThat(fixture.snapshot()).isEqualTo(before);
        // No READY or fabricated seal: their positive exclusion proof belongs to actual G025 sealing.
    }

    @Test void sameKeyContendersCommitExactlyOneSetReceiptAndAudit() throws Exception {
        var candidate = candidate(fixture.business.remediator); UUID key = UUID.randomUUID(); long audit = count("audit_event");
        try (var a=login(candidate.owner()).copyTo(port); var b=login(candidate.owner()).copyTo(port)) {
            var responses = race(() -> post(a,candidate,key,1), () -> post(b,candidate,key,1));
            assertThat(responses.stream().map(IdentityAcceptanceHttps.Response::status).sorted().toList()).containsExactly(200,201);
            assertThat(json.readTree(responses.get(0).body()).path("data")).isEqualTo(json.readTree(responses.get(1).body()).path("data"));
        }
        assertThat(count("evidence_set")).isEqualTo(1); assertThat(count(FindingEvidenceSetInitializationPostgresFixture.RECEIPTS)).isEqualTo(1);
        assertThat(count("audit_event")).isEqualTo(audit+1);
    }

    @Test void quotaLockWinnerSerializesAnActuallyWaitingConcurrentInitializer() throws Exception {
        var candidate = candidate(fixture.business.remediator); fixture.seedSet(candidate,"UPLOADING",true);
        var otherOwner = candidate(fixture.business.alternatePerson); fixture.seedSet(otherOwner,"UPLOADING",true);
        UUID first = UUID.randomUUID(), second = UUID.randomUUID(); var gate = new Hold(first,second);
        faults.hold.set(gate);
        try (var a=login(candidate.owner()).copyTo(port); var b=login(otherOwner.owner()).copyTo(port);
             var executor=Executors.newFixedThreadPool(2)) {
            Future<IdentityAcceptanceHttps.Response> winner=executor.submit(() -> post(a,candidate,first,1));
            assertThat(gate.entered.await(10,TimeUnit.SECONDS)).as("winner holds actual initialized transaction").isTrue();
            Future<IdentityAcceptanceHttps.Response> waiter=executor.submit(() -> post(b,otherOwner,second,1));
            try {
                assertThat(gate.waiterEntered.await(3,TimeUnit.SECONDS)).as("second request reached its actual quota lock").isTrue();
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3); boolean blocked=false;
                while(System.nanoTime()<deadline && !(blocked=hasWaitingAdvisoryLock(gate.pid,gate.waiterPid))) Thread.sleep(10);
                assertThat(blocked).as("second real HTTP transaction waits on winner's advisory lock").isTrue();
            } finally { gate.release.countDown(); }
            expect(winner.get(20,TimeUnit.SECONDS),201); expect(waiter.get(20,TimeUnit.SECONDS),429);
        } finally { gate.release.countDown(); faults.hold.set(null); }
        assertThat(count("evidence_set")).isEqualTo(3); assertThat(count(FindingEvidenceSetInitializationPostgresFixture.RECEIPTS)).isEqualTo(1);
    }

    @Test void receiptRlsAndIntrinsicProvenanceRejectSqlBypassesAndMutation() throws Exception {
        var candidate=candidate(fixture.business.remediator); data(post(candidate,UUID.randomUUID(),1),201);
        var original=fixture.read(db -> db.queryForMap("select * from public.finding_evidence_set_initialization_receipt where tenant_id=?",fixture.business.tenantId));
        for(UUID context:Arrays.asList(null,UUID.randomUUID())) {
            assertThat(fixture.<Long>runtime(context,db -> db.queryForObject("select count(*) from public.finding_evidence_set_initialization_receipt where tenant_id=?",Long.class,fixture.business.tenantId))).isZero();
            sqlStates(Set.of("23514","42501"),() -> fixture.runtime(context,db -> insertReceiptValues(db,original)));
        }
        var before=fixture.snapshot();
        sqlState("42501",() -> fixture.runtime(fixture.business.tenantId,db -> db.update("update public.finding_evidence_set_initialization_receipt set payload_sha256=repeat('0',64) where tenant_id=?",fixture.business.tenantId)));
        for(String action:List.of("update public.finding_evidence_set_initialization_receipt set payload_sha256=repeat('0',64) where tenant_id=?", "delete from public.finding_evidence_set_initialization_receipt where tenant_id=?"))
            sqlState("23514",() -> fixture.rollbackProbe(db -> db.update(action,fixture.business.tenantId)));
        for(var replacement:Map.of("owner_account_id",fixture.business.reporter.accountId(),"owner_actor_id",fixture.business.reporter.principalId(),"owner_member_id",fixture.business.reporter.memberId(),"notice_id",UUID.randomUUID(),"evidence_set_id",UUID.randomUUID()).entrySet())
            sqlState("23514",() -> fixture.rollbackProbe(db -> copyReceipt(db,replacement.getKey(),replacement.getValue())));
        assertThat(fixture.snapshot()).isEqualTo(before);
    }

    @Test void authenticationCsrfForeignFindingAndUnknownTaskAssignmentNeverCreateASet() throws Exception {
        var candidate=candidate(fixture.business.remediator); var before=fixture.snapshot();
        try(var anonymous=https.browser(port)) {
            expect(anonymous.get("/api/v1/auth/csrf"),204);
            expect(anonymous.post(path(candidate),"{\"expectedFindingVersion\":1}",anonymous.csrf()),401);
        }
        var browser=login(candidate.owner()); expect(browser.postWithHeaders(path(candidate),"{\"expectedFindingVersion\":1}",null,Map.of("Idempotency-Key",UUID.randomUUID().toString())),403);
        var foreign=new FindingEvidenceSetInitializationPostgresFixture.Case(UUID.randomUUID(),UUID.randomUUID(),1,candidate.owner());
        expect(post(foreign,UUID.randomUUID(),1),404); assertThat(fixture.snapshot()).isEqualTo(before);
        // Real task assignment is changed only within the actual application's guarded transaction.
        command().executeWithoutResult(status -> { tenant(); guard.forFindingCommand(fixture.business.tenantId); tasks.setAssignee(task(candidate).getId(),null); });
        before=fixture.snapshot(); expect(post(candidate,UUID.randomUUID(),1),422); assertThat(fixture.snapshot()).isEqualTo(before);
    }

    enum FaultPoint { SET, RECEIPT, AUDIT }
    @ParameterizedTest @EnumSource(FaultPoint.class)
    void actualInsertBoundaryFailureRollsBackEveryObservedParticipantAndSameKeyRetriesOnce(FaultPoint point) throws Exception {
        var candidate=candidate(fixture.business.remediator); UUID key=UUID.randomUUID();
        var before=fixture.snapshot(); var plan=new FaultPlan(key,point); faults.plan.set(plan);
        expect(post(candidate,key,1),503); assertThat(plan.reached).as("actual INSERT completed before injected failure").isTrue();
        assertThat(fixture.snapshot()).isEqualTo(before); faults.plan.set(null);
        JsonNode first=data(post(candidate,key,1),201); var committed=fixture.snapshot();
        assertThat(data(post(candidate,key,1),200)).isEqualTo(first); assertThat(fixture.snapshot()).isEqualTo(committed);
    }

    private FindingEvidenceSetInitializationPostgresFixture.Case candidate(BusinessWorkflowPostgresFixture.Actor actor) {
        var candidate=fixture.createCase(actor);
        command().executeWithoutResult(status -> {
            tenant(); guard.forFindingCommand(fixture.business.tenantId);
            workflow.startProcess(fixture.business.tenantId,"FINDING",candidate.findingId(),1,"findingRemediationV2",Map.of(
                    "personConfirmerMemberId",fixture.business.person.memberId().toString(),"safetyConfirmerMemberId",fixture.business.safety.memberId().toString(),
                    "safetyDispatcherMemberId",fixture.business.safety.memberId().toString(),"remediationAssigneeMemberId",actor.memberId().toString(),"safetyVerifierMemberId",fixture.business.safety.memberId().toString()));
            workflow.completeTask(fixture.business.tenantId,"FINDING",candidate.findingId(),"personConfirm",fixture.business.person.memberId(),Map.of("confirmationDecision","APPROVE"));
            workflow.completeTask(fixture.business.tenantId,"FINDING",candidate.findingId(),"safetyConfirm",fixture.business.safety.memberId(),Map.of("confirmationDecision","APPROVE"));
            workflow.completeTask(fixture.business.tenantId,"FINDING",candidate.findingId(),"safetyDispatch",fixture.business.safety.memberId(),Map.of());
        });
        assertCurrentTask(candidate); return candidate;
    }
    private void assertCurrentTask(FindingEvidenceSetInitializationPostgresFixture.Case candidate) {
        var task=task(candidate); assertThat(task).isNotNull(); assertThat(task.getTaskDefinitionKey()).isEqualTo("remediation");
        assertThat(task.getAssignee()).isEqualTo(candidate.owner().memberId().toString());
        assertThat(task.getProcessDefinitionId()).isEqualTo(fixture.definitionId);
    }
    private org.flowable.task.api.Task task(FindingEvidenceSetInitializationPostgresFixture.Case candidate) {
        String instance=fixture.read(db -> db.queryForObject("select process_instance_id from public.workflow_binding where tenant_id=? and business_id=?",String.class,fixture.business.tenantId,candidate.findingId()));
        return tasks.createTaskQuery().processInstanceId(instance).singleResult();
    }
    private TransactionTemplate command() { var tx=new TransactionTemplate(manager); tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED); tx.setTimeout(5); return tx; }
    private void tenant() { app.queryForObject("select set_config('app.tenant_id',?,true)",String.class,fixture.business.tenantId.toString()); }
    private long count(String table) { return fixture.snapshot().rows().get(table).size(); }
    private boolean hasWaitingAdvisoryLock(int blocker,int waiter) {
        return Boolean.TRUE.equals(fixture.read(db -> db.queryForObject("select exists(select 1 from pg_locks where locktype='advisory' and not granted and pid=? and ?=any(pg_blocking_pids(pid)))",Boolean.class,waiter,blocker)));
    }
    private IdentityAcceptanceHttps.Response post(FindingEvidenceSetInitializationPostgresFixture.Case candidate,UUID key,long version) throws Exception { return post(login(candidate.owner()),candidate,key,version); }
    private IdentityAcceptanceHttps.Response postWithExistingSession(FindingEvidenceSetInitializationPostgresFixture.Case candidate,UUID key,long version) throws Exception { return post(browsers.get(candidate.owner().accountId()),candidate,key,version); }
    private IdentityAcceptanceHttps.Response post(IdentityAcceptanceHttps.Browser browser,FindingEvidenceSetInitializationPostgresFixture.Case candidate,UUID key,long version) throws Exception { return browser.postWithHeaders(path(candidate),json.writeValueAsString(Map.of("expectedFindingVersion",version)),browser.csrf(),Map.of("Idempotency-Key",key.toString())); }
    private static String path(FindingEvidenceSetInitializationPostgresFixture.Case candidate) { return "/api/v1/findings/"+candidate.findingId()+"/remediation-rounds/"+candidate.roundNo()+"/evidence-sets"; }
    private IdentityAcceptanceHttps.Browser login(BusinessWorkflowPostgresFixture.Actor actor) throws Exception {
        var existing=browsers.get(actor.accountId()); if(existing!=null)return existing;
        var browser=https.browser(port); browsers.put(actor.accountId(),browser); expect(browser.get("/api/v1/auth/csrf"),204);
        expect(browser.post("/api/v1/auth/login",json.writeValueAsString(Map.of("loginKey",actor.loginKey(),"password",actor.password(),"tenantCode",fixture.business.tenantCode)),browser.csrf()),204);
        expect(browser.get("/api/v1/auth/csrf"),204); assertThat(browser.cookie("SESSION").orElseThrow().getSecure()).isTrue(); return browser;
    }
    private JsonNode data(IdentityAcceptanceHttps.Response response,int status) throws Exception { expect(response,status); return json.readTree(response.body()).path("data"); }
    private static void expect(IdentityAcceptanceHttps.Response response,int status) { assertThat(response.status()).as("actual HTTPS initializer status").isEqualTo(status); }
    private static List<IdentityAcceptanceHttps.Response> race(Callable<IdentityAcceptanceHttps.Response> a,Callable<IdentityAcceptanceHttps.Response> b) throws Exception {
        try(var executor=Executors.newFixedThreadPool(2)) { var start=new CyclicBarrier(2);
            var first=executor.submit(() -> {start.await(10,TimeUnit.SECONDS);return a.call();});
            var second=executor.submit(() -> {start.await(10,TimeUnit.SECONDS);return b.call();});
            return List.of(first.get(30,TimeUnit.SECONDS),second.get(30,TimeUnit.SECONDS));
        }
    }
    private static void sqlState(String expected,org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        sqlStates(Set.of(expected),action);
    }
    private static void sqlStates(Set<String> expected,org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        Throwable failure=catchThrowable(action); assertThat(failure).isNotNull(); SQLException sql=null;
        for(Throwable cause=failure;cause!=null;cause=cause.getCause())if(cause instanceof SQLException found)sql=found;
        assertThat((Throwable)sql).isNotNull(); assertThat(sql.getSQLState()).isIn(expected);
    }
    private static int insertReceiptValues(JdbcTemplate db,Map<String,Object> original) {
        var values=new LinkedHashMap<>(original); values.put("id",UUID.randomUUID()); values.put("idempotency_key",UUID.randomUUID());
        return db.update("insert into public.finding_evidence_set_initialization_receipt("+String.join(",",RECEIPT_COLUMNS)+") values ("+String.join(",",Collections.nCopies(RECEIPT_COLUMNS.size(),"?"))+")",RECEIPT_COLUMNS.stream().map(values::get).toArray());
    }
    private int copyReceipt(JdbcTemplate db,String field,UUID replacement) {
        if(!RECEIPT_COLUMNS.contains(field))throw new IllegalArgumentException();
        List<Object> parameters=new ArrayList<>(); List<String> selections=new ArrayList<>();
        for(String column:RECEIPT_COLUMNS)if(column.equals("id")||column.equals("idempotency_key")||column.equals(field)) { selections.add("cast(? as uuid)"); parameters.add(column.equals(field)?replacement:UUID.randomUUID()); } else selections.add(column);
        parameters.add(fixture.business.tenantId);
        return db.update("insert into public.finding_evidence_set_initialization_receipt("+String.join(",",RECEIPT_COLUMNS)+") select "+String.join(",",selections)+" from public.finding_evidence_set_initialization_receipt where tenant_id=?",parameters.toArray());
    }

    static final class FaultPlan { final UUID key; final FaultPoint point; volatile boolean reached; FaultPlan(UUID key,FaultPoint point){this.key=key;this.point=point;} }
    static final class Hold {
        final UUID key,waiterKey;
        final CountDownLatch entered=new CountDownLatch(1),waiterEntered=new CountDownLatch(1),release=new CountDownLatch(1);
        volatile int pid,waiterPid;
        Hold(UUID key,UUID waiterKey){this.key=key;this.waiterKey=waiterKey;}
    }
    static final class Faults {
        final AtomicReference<FaultPlan> plan=new AtomicReference<>(); final AtomicReference<Hold> hold=new AtomicReference<>(); final ThreadLocal<UUID> current=new ThreadLocal<>();
    }
    @TestConfiguration(proxyBeanMethods=false) static class AcceptanceConfiguration {
        @Bean Faults initializerFaults() { return new Faults(); }
        @Bean @Primary FindingEvidenceSetInitializationRepository observedActualInitializer(@Qualifier("jooqFindingEvidenceSetInitializationRepository") FindingEvidenceSetInitializationRepository real,Faults faults,JdbcTemplate jdbc) {
            return new FindingEvidenceSetInitializationRepository() {
                @Override public <T>T inTenantTransaction(UUID tenant,Function<Transaction,T> work) {
                    return real.inTenantTransaction(tenant,actual -> {
                        Transaction observed=(Transaction)Proxy.newProxyInstance(Transaction.class.getClassLoader(),new Class<?>[]{Transaction.class},(proxy,method,args) -> {
                            if(!method.getName().equals("initialize"))return invoke(actual,method,args);
                            UUID key=((InitializeCommand)args[1]).idempotencyKey(); faults.current.set(key);
                            try {
                                var hold=faults.hold.get();
                                if(hold!=null&&hold.key.equals(key)) {
                                    hold.pid=jdbc.queryForObject("select pg_backend_pid()",Integer.class); hold.entered.countDown();
                                    if(!hold.release.await(10,TimeUnit.SECONDS))throw new IllegalStateException("Owned initializer barrier timed out");
                                }
                                return invoke(actual,method,args);
                            } finally { faults.current.remove(); }
                        });
                        return work.apply(observed);
                    });
                }
            };
        }
        @Bean ExecuteListenerProvider actualInitializerInsertBoundaries(Faults faults) { return () -> new ExecuteListener() {
            @Override public void executeStart(ExecuteContext context) {
                var hold=faults.hold.get();
                if(hold==null||context.sql()==null||!context.sql().contains("pg_advisory_xact_lock"))return;
                if(!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes request)
                        ||!hold.waiterKey.toString().equals(request.getRequest().getHeader("Idempotency-Key")))return;
                try(var query=context.connection().prepareStatement("select pg_backend_pid()"); var row=query.executeQuery()) {
                    if(!row.next())throw new IllegalStateException("Missing actual waiting transaction identity");
                    hold.waiterPid=row.getInt(1); hold.waiterEntered.countDown();
                } catch(SQLException failure) { throw new DataAccessResourceFailureException("Could not observe owned quota waiter",failure); }
            }
            @Override public void executeEnd(ExecuteContext context) {
                var plan=faults.plan.get(); if(plan==null||!plan.key.equals(faults.current.get())||context.sql()==null||context.rows()!=1)return;
                String sql=context.sql().toLowerCase(Locale.ROOT).replaceAll("\\s+"," ");
                String table=switch(plan.point) { case SET -> "evidence_set"; case RECEIPT -> "finding_evidence_set_initialization_receipt"; case AUDIT -> "audit_event"; };
                if(sql.matches("(?s).*insert into public\\."+table+"\\b.*")) { plan.reached=true; throw new DataAccessResourceFailureException("Owned G024 participant fault"); }
            }
        }; }
        private static Object invoke(Object target,Method method,Object[] args) throws Throwable { try{return method.invoke(target,args);}catch(InvocationTargetException failure){throw failure.getCause();} }
    }
}
