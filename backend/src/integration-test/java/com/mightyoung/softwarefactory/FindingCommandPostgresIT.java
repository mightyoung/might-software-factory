package com.mightyoung.softwarefactory;

import com.mightyoung.softwarefactory.dualprevention.ModulithFindingWorkflowEventPublisher;
import com.mightyoung.softwarefactory.dualprevention.finding.workflow.FindingWorkflowEventPublisher;
import com.mightyoung.softwarefactory.dualprevention.finding.workflow.FindingWorkflowRepository;
import com.mightyoung.softwarefactory.dualprevention.finding.workflow.FindingWorkflowCase;
import com.mightyoung.softwarefactory.dualprevention.finding.workflow.FindingWorkflowContracts;
import com.mightyoung.softwarefactory.dualprevention.finding.workflow.FindingWorkflowCommandService;
import com.mightyoung.softwarefactory.dualprevention.finding.workflow.FindingWorkflowAccessPolicy;
import com.mightyoung.softwarefactory.dualprevention.finding.workflow.FindingWorkflowAssignmentResolver;
import com.mightyoung.softwarefactory.dualprevention.finding.workflow.FindingWorkflowEvidencePolicy;
import com.mightyoung.softwarefactory.identity.SessionContextResolver;
import com.mightyoung.softwarefactory.workflow.WorkflowFacade;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.springframework.beans.factory.annotation.Qualifier;
import org.jooq.ExecuteContext;
import org.jooq.ExecuteListener;
import org.jooq.ExecuteListenerProvider;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.function.Function;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.*;
import java.time.Duration;
import java.util.concurrent.locks.LockSupport;
import static org.assertj.core.api.Assertions.assertThat;

/** Full production login/context, Flowable, audit and transactional Modulith registry on an exclusive target.
 * Business history/ancestors and owned process deployments remain for reinspection. No UI/device acceptance.
 */
@SpringBootTest(classes = SoftwareFactoryApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(FindingCommandPostgresIT.LateFailureConfiguration.class)
@ActiveProfiles("lan-postgres-compatibility")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class FindingCommandPostgresIT {
    private static IdentityAcceptanceHttps https;
    @LocalServerPort private int port;
    @Autowired private PasswordEncoder passwords;
    @Autowired private ObjectMapper json;
    @Autowired private RepositoryService repository;
    @Autowired private RuntimeService engine;
    @Autowired private TaskService tasks;
    @Autowired private LateFailure late;
    @Autowired private CommandTime commandTime;
    private BusinessWorkflowPostgresFixture fixture;
    private final List<IdentityAcceptanceHttps.Browser> clients = new ArrayList<>();

    @DynamicPropertySource static void properties(DynamicPropertyRegistry properties) throws Exception {
        var target = BusinessWorkflowPostgresFixture.validateTarget();
        if (https == null) https = IdentityAcceptanceHttps.create();
        properties.add("spring.datasource.url",target::jdbcUrl);
        properties.add("spring.datasource.username",() -> System.getenv("LAN_PG_APP_USERNAME"));
        properties.add("spring.datasource.password",() -> System.getenv("LAN_PG_APP_PASSWORD"));
        properties.add("spring.datasource.hikari.maximum-pool-size",() -> 4);
        properties.add("spring.datasource.hikari.minimum-idle",() -> 1);
        properties.add("server.address",() -> "127.0.0.1");
        properties.add("server.ssl.enabled",() -> true);
        properties.add("server.ssl.key-store",() -> https.keyStore().toUri().toString());
        properties.add("server.ssl.key-store-password",https::password);
        properties.add("server.ssl.key-store-type",() -> "PKCS12");
        properties.add("server.ssl.key-alias",() -> "identity-acceptance");
        properties.add("flowable.database-schema-update",() -> false);
        properties.add("flowable.check-process-definitions",() -> false);
        properties.add("flowable.async-executor-activate",() -> false);
        properties.add("flowable.async-history-executor-activate",() -> false);
        properties.add("spring.flyway.enabled",() -> false);
        properties.add("spring.session.jdbc.initialize-schema",() -> "never");
        properties.add("spring.modulith.events.jdbc.schema-initialization.enabled",() -> false);
    }

    @BeforeEach void seedAndDeployRealProcess() {
        commandTime.clock.reset();
        late.command.set(null); late.proof.set(null); late.failures.clear(); late.pause.set(null); late.fault.set(null); late.frame.remove();
        fixture = new BusinessWorkflowPostgresFixture(passwords);
        repository.createDeployment().name(fixture.run).tenantId(fixture.tenantId.toString())
                .addClasspathResource("processes/finding-remediation-v1.bpmn20.xml").deploy();
    }
    @AfterEach void closeOnlyOwnedSessions() {
        try { clients.forEach(IdentityAcceptanceHttps.Browser::close); }
        finally { clients.clear(); if (fixture != null) fixture.close(); }
    }
    @AfterAll static void removeTls() throws Exception { if (https != null) https.close(); }

    @Test void productionCreateCommitsEveryParticipantAndReplayDoesNotDuplicateAnything() throws Exception {
        var reporter = login(fixture.reporter);
        UUID command = UUID.randomUUID(); String body = createBody("create-replay");
        JsonNode created = result(post(reporter,"/api/v1/findings",command,body),200);
        UUID finding = UUID.fromString(created.path("findingId").asString());
        assertThat(created.path("state").asString()).isEqualTo("PERSON_CONFIRM_PENDING");
        assertThat(created.path("version").asLong()).isEqualTo(1);
        assertTask(finding,"personConfirm",fixture.person.memberId());
        assertCommand(command,finding,1);
        Map<String,Long> before = counts();
        JsonNode replay = result(post(reporter,"/api/v1/findings",command,body),200);
        assertThat(replay).isEqualTo(created); assertThat(counts()).isEqualTo(before);
        expect(post(reporter,"/api/v1/findings",command,createBody("different-request")),409);
        assertThat(counts()).isEqualTo(before);
    }

    @Test void lateFailureAfterRealPublicationRollsBackBusinessEngineAuditOutboxAndReceiptThenRetries() throws Exception {
        var reporter = login(fixture.reporter);
        UUID command = UUID.randomUUID(); String body = createBody("late-failure");
        Map<String,Long> before = counts();
        late.command.set(command);
        expect(post(reporter,"/api/v1/findings",command,body),503);
        LateProof proof = late.proof.get();
        if (proof == null) printFailureDiagnostics();
        assertThat(proof).as("Failpoint must run after real publisher and all preceding writes").isNotNull();
        assertThat(proof.publications()).isPositive(); assertThat(proof.engineInstances()).isEqualTo(1);
        assertThat(proof.auditRows()).isEqualTo(1); assertThat(proof.completedReceipts()).isEqualTo(1);
        assertThat(counts()).isEqualTo(before);
        assertThat(fixture.publications(command)).isZero();
        assertThat(engine.createProcessInstanceQuery().processInstanceId(proof.processInstanceId()).count()).isZero();
        assertThat(tasks.createTaskQuery().processInstanceId(proof.processInstanceId()).count()).isZero();
        long history = fixture.read(db -> db.queryForObject("select count(*) from public.act_hi_procinst where proc_inst_id_=?",Long.class,proof.processInstanceId()));
        assertThat(history).isZero();
        JsonNode retried = result(post(reporter,"/api/v1/findings",command,body),200);
        UUID finding = UUID.fromString(retried.path("findingId").asString());
        assertCommand(command,finding,1); assertTask(finding,"personConfirm",fixture.person.memberId());
    }

    @Test void productionCreateDoubleConfirmationAndDispatchUseDistinctMembersAndReplaySafely() throws Exception {
        var reporter = login(fixture.reporter);
        JsonNode created = result(post(reporter,"/api/v1/findings",UUID.randomUUID(),createBody("full-route")),200);
        throughDispatch(UUID.fromString(created.path("findingId").asString()),1);
    }

    @Test void productionLegacyInitializationStartsTheRealProcessAndContinuesThroughDispatch() throws Exception {
        var reporter = login(fixture.reporter); UUID command = UUID.randomUUID();
        String path = "/api/v1/findings/" + fixture.legacyFindingId + "/workflow-initialization";
        String body = json.writeValueAsString(Map.of("expectedVersion",1));
        JsonNode initialized = result(post(reporter,path,command,body),200);
        assertThat(initialized.path("state").asString()).isEqualTo("PERSON_CONFIRM_PENDING");
        assertThat(initialized.path("version").asLong()).isEqualTo(2);
        assertCommand(command,fixture.legacyFindingId,2);
        assertThat(result(post(reporter,path,command,body),200)).isEqualTo(initialized);
        throughDispatch(fixture.legacyFindingId,2);
    }

    @Test void concurrentSameKeyAndPayloadCreateCommitsOnceAndReplaysTheSameResult() throws Exception {
        var first = login(fixture.reporter); var second = login(fixture.reporter);
        UUID command = UUID.randomUUID(); String body = createBody("parallel-same-key");
        Map<String,Long> before = counts();
        CountDownLatch start = new CountDownLatch(1); ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<IdentityAcceptanceHttps.Response> one = workers.submit(() -> { await(start,"start first replay caller"); return post(first,"/api/v1/findings",command,body); });
            Future<IdentityAcceptanceHttps.Response> two = workers.submit(() -> { await(start,"start second replay caller"); return post(second,"/api/v1/findings",command,body); });
            start.countDown();
            JsonNode resultOne = result(one.get(8,TimeUnit.SECONDS),200);
            JsonNode resultTwo = result(two.get(8,TimeUnit.SECONDS),200);
            assertThat(resultTwo).isEqualTo(resultOne);
            UUID finding = UUID.fromString(resultOne.path("findingId").asString());
            assertCommand(command,finding,1); assertTask(finding,"personConfirm",fixture.person.memberId());
            Map<String,Long> after = counts();
            for (String participant : before.keySet()) {
                long delta = Set.of("finding","finding_transition","finding_command_receipt","workflow_binding","audit_event","publications","engine","numberSequence").contains(participant) ? 1 : 0;
                assertThat(after.get(participant)).as("one committed create: %s",participant).isEqualTo(before.get(participant)+delta);
            }
        } finally { start.countDown(); stop(workers); }
    }

    @Test void sameKeyReplayAcrossBeijingMidnightKeepsOriginalResultAndNewKeyUsesNextBusinessDay() throws Exception {
        var reporter = login(fixture.reporter);
        commandTime.clock.set(Instant.parse("2026-11-02T15:59:59Z"));
        UUID command = UUID.randomUUID();
        String body = json.writeValueAsString(Map.of("organizationId",fixture.organizationId,
                "description",fixture.run + " midnight replay","discoveredAt","2026-11-02T15:00:00Z",
                "grade","UNKNOWN","sourceCategory","FIELD_OBSERVATION","urgency","NORMAL","location","Synthetic midnight location"));
        JsonNode created = result(post(reporter,"/api/v1/findings",command,body),200);
        assertThat(created.path("code").asString()).isEqualTo("YH-20261102-0001");
        Map<String,Long> beforeReplay = counts();
        commandTime.clock.set(Instant.parse("2026-11-02T16:00:01Z"));
        assertThat(result(post(reporter,"/api/v1/findings",command,body),200)).isEqualTo(created);
        assertThat(counts()).isEqualTo(beforeReplay);
        JsonNode nextDay = result(post(reporter,"/api/v1/findings",UUID.randomUUID(),body),200);
        assertThat(nextDay.path("code").asString()).isEqualTo("YH-20261103-0001");
        assertThat(nextDay.path("findingId").asString()).isNotEqualTo(created.path("findingId").asString());
        fixture.read(db -> {
            assertThat(db.queryForList("select business_day::text from public.finding_number_sequence where tenant_id=? order by business_day",String.class,fixture.tenantId))
                    .containsExactly("2026-11-02","2026-11-03");
            assertThat(db.queryForList("select last_sequence from public.finding_number_sequence where tenant_id=? order by business_day",Long.class,fixture.tenantId))
                    .containsExactly(1L,1L);
            return null;
        });
    }

    @Test void foreignFindingWrongTaskAssigneeMissingScopeAndDisabledEntitlementWriteNothing() throws Exception {
        var reporter = login(fixture.reporter); var person = login(fixture.person); var alternate = login(fixture.alternatePerson);
        JsonNode created = result(post(reporter,"/api/v1/findings",UUID.randomUUID(),createBody("denied-contexts")),200);
        UUID finding = UUID.fromString(created.path("findingId").asString());
        String body = json.writeValueAsString(Map.of("expectedVersion",1,"reason","Synthetic access boundary check"));
        Map<String,Long> baseline = counts();
        try (var foreign = new BusinessWorkflowPostgresFixture(passwords)) {
            long foreignFindings = foreign.count("finding");
            expect(post(person,"/api/v1/findings/" + foreign.legacyFindingId + "/confirm/person",UUID.randomUUID(),body),404);
            assertThat(foreign.count("finding")).isEqualTo(foreignFindings);
            for (String table : List.of("finding_transition","finding_confirmation","finding_command_receipt","workflow_binding","audit_event")) assertThat(foreign.count(table)).isZero();
        }
        assertThat(counts()).isEqualTo(baseline);
        String path = "/api/v1/findings/" + finding + "/confirm/person";
        // Alternate has the legitimate action/org scope, but is not the actual engine task assignee.
        expect(post(alternate,path,UUID.randomUUID(),body),403);
        assertThat(counts()).isEqualTo(baseline); assertTask(finding,"personConfirm",fixture.person.memberId());
        fixture.revokePersonGrant();
        expect(post(person,path,UUID.randomUUID(),body),403); assertThat(counts()).isEqualTo(baseline);
        fixture.disableSafetyApplication();
        expect(post(reporter,"/api/v1/findings",UUID.randomUUID(),createBody("disabled-application")),403);
        assertThat(counts()).isEqualTo(baseline); assertTask(finding,"personConfirm",fixture.person.memberId());
    }

    @Test void missingAndAmbiguousStageAssignmentsRejectRealCreateWithoutAnyParticipantWrite() throws Exception {
        var reporter = login(fixture.reporter);
        Map<String,Long> baseline = counts();
        fixture.expirePersonAssignments();
        expect(post(reporter,"/api/v1/findings",UUID.randomUUID(),createBody("missing-person-assignment")),422);
        assertThat(counts()).isEqualTo(baseline);
        // Both members retain independently seeded finding.confirm.person / EXPLICIT organization scope.
        // No entitlement or permission revocation masks the actual ambiguous route rejection.
        fixture.addTwoCurrentPersonAssignments();
        expect(post(reporter,"/api/v1/findings",UUID.randomUUID(),createBody("ambiguous-person-assignment")),422);
        assertThat(counts()).isEqualTo(baseline);
    }

    @Test void faultAfterActualEngineStartRollsBackEveryParticipantAndSameKeyRetriesOnce() throws Exception {
        assertIndependentFaultRollback(FaultStage.AFTER_ENGINE);
    }
    @Test void faultImmediatelyBeforeAuditInsertRollsBackEveryParticipantAndSameKeyRetriesOnce() throws Exception {
        assertIndependentFaultRollback(FaultStage.BEFORE_AUDIT);
    }
    @Test void faultAfterReceiptCompletionRollsBackEveryParticipantAndSameKeyRetriesOnce() throws Exception {
        assertIndependentFaultRollback(FaultStage.AFTER_RECEIPT);
    }
    private void assertIndependentFaultRollback(FaultStage stage) throws Exception {
        var reporter = login(fixture.reporter); UUID command = UUID.randomUUID();
        String body = createBody("fault-" + stage);
        FaultPlan plan = new FaultPlan(stage,command); late.fault.set(plan);
        Map<String,Long> baseline = counts();
        expect(post(reporter,"/api/v1/findings",command,body),503);
        FaultProof observed = plan.proof.get();
        if (observed == null) printFailureDiagnostics();
        assertThat(observed).as("actual data captured at %s",stage).isNotNull();
        assertThat(observed.processInstanceId()).isNotBlank();
        assertThat(observed.participants().get("finding")).isEqualTo(1);
        assertThat(observed.participants().get("binding")).isEqualTo(1);
        assertThat(observed.participants().get("engine")).isEqualTo(1);
        assertThat(observed.participants().get("transition")).isEqualTo(stage == FaultStage.AFTER_ENGINE ? 0 : 1);
        assertThat(observed.participants().get("audit")).isEqualTo(stage == FaultStage.AFTER_RECEIPT ? 1 : 0);
        assertThat(observed.participants().get("completedReceipt")).isEqualTo(stage == FaultStage.AFTER_RECEIPT ? 1 : 0);
        assertThat(observed.participants().get("publication")).isZero();
        assertThat(counts()).isEqualTo(baseline);
        assertThat(fixture.publications(command)).isZero();
        assertThat(engine.createProcessInstanceQuery().processInstanceId(observed.processInstanceId()).count()).isZero();
        assertThat(tasks.createTaskQuery().processInstanceId(observed.processInstanceId()).count()).isZero();
        long history = fixture.read(db -> db.queryForObject("select count(*) from public.act_hi_procinst where proc_inst_id_=?",Long.class,observed.processInstanceId()));
        assertThat(history).isZero();
        JsonNode retried = result(post(reporter,"/api/v1/findings",command,body),200);
        UUID finding = UUID.fromString(retried.path("findingId").asString());
        assertCommand(command,finding,1); assertTask(finding,"personConfirm",fixture.person.memberId());
        assertThat(plan.armed.get()).isFalse();
    }

    @Test void committedCredentialWriterBlocksRealCreateThenDeniesWithoutAnyParticipantWrite() throws Exception {
        var reporter = login(fixture.reporter); String body = createBody("writer-before-command");
        Map<String,Long> before = counts();
        ExecutorService workers = Executors.newFixedThreadPool(2);
        CompletableFuture<Integer> writerLocked = new CompletableFuture<>();
        java.util.concurrent.atomic.AtomicInteger writerConnection = new java.util.concurrent.atomic.AtomicInteger();
        CountDownLatch releaseWriter = new CountDownLatch(1);
        try {
            Future<?> writer = workers.submit(() -> fixture.bumpReporterCredentialVersion(writerConnection::set, () -> {
                writerLocked.complete(writerConnection.get());
                await(releaseWriter,"release credential writer");
            }));
            int blocker = writerLocked.get(3,TimeUnit.SECONDS);
            Future<IdentityAcceptanceHttps.Response> command = workers.submit(() -> post(reporter,"/api/v1/findings",UUID.randomUUID(),body));
            observeAdvisoryWait(blocker,command);
            releaseWriter.countDown(); writer.get(5,TimeUnit.SECONDS);
            expect(command.get(8,TimeUnit.SECONDS),403);
            assertThat(counts()).isEqualTo(before);
        } finally { releaseWriter.countDown(); stop(workers); }
    }

    @Test void realCommandCommitsBeforeWaitingCredentialWriterAndOldSessionCannotWriteAgain() throws Exception {
        var reporter = login(fixture.reporter); UUID commandId = UUID.randomUUID(); String body = createBody("command-before-writer");
        Pause pause = new Pause(commandId); late.pause.set(pause);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        CompletableFuture<Integer> writerPid = new CompletableFuture<>();
        try {
            Future<IdentityAcceptanceHttps.Response> command = workers.submit(() -> post(reporter,"/api/v1/findings",commandId,body));
            int blocker = pause.entered.get(3,TimeUnit.SECONDS);
            Future<?> writer = workers.submit(() -> fixture.bumpReporterCredentialVersion(writerPid::complete,() -> { }));
            int expectedWaiter = writerPid.get(3,TimeUnit.SECONDS);
            assertThat(observeAdvisoryWait(blocker,writer)).isEqualTo(expectedWaiter);
            pause.release.countDown();
            JsonNode success = result(command.get(8,TimeUnit.SECONDS),200);
            writer.get(5,TimeUnit.SECONDS);
            UUID finding = UUID.fromString(success.path("findingId").asString());
            assertCommand(commandId,finding,1); assertTask(finding,"personConfirm",fixture.person.memberId());
            Map<String,Long> committed = counts();
            expect(post(reporter,"/api/v1/findings",UUID.randomUUID(),createBody("stale-after-writer")),403);
            assertThat(counts()).isEqualTo(committed);
        } finally { pause.release.countDown(); late.pause.set(null); stop(workers); }
    }

    @Test void concurrentSameVersionPersonalConfirmationsAdvanceAtMostOnceAndStaleCommandWritesNothing() throws Exception {
        var reporter = login(fixture.reporter);
        JsonNode created = result(post(reporter,"/api/v1/findings",UUID.randomUUID(),createBody("version-race")),200);
        UUID finding = UUID.fromString(created.path("findingId").asString());
        var firstPerson = login(fixture.person); var secondPerson = login(fixture.person);
        String path = "/api/v1/findings/" + finding + "/confirm/person";
        String body = json.writeValueAsString(Map.of("expectedVersion",1,"reason","Concurrent personal confirmation"));
        Map<String,Long> before = counts();
        CountDownLatch start = new CountDownLatch(1); ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<IdentityAcceptanceHttps.Response> first = workers.submit(() -> { await(start,"start first confirmation"); return post(firstPerson,path,UUID.randomUUID(),body); });
            Future<IdentityAcceptanceHttps.Response> second = workers.submit(() -> { await(start,"start second confirmation"); return post(secondPerson,path,UUID.randomUUID(),body); });
            start.countDown();
            var one = first.get(8,TimeUnit.SECONDS); var two = second.get(8,TimeUnit.SECONDS);
            assertThat(List.of(one.status(),two.status())).containsExactlyInAnyOrder(200,409);
            assertTask(finding,"safetyConfirm",fixture.safety.memberId());
            Map<String,Long> after = counts();
            for (String participant : before.keySet()) {
                long delta = Set.of("finding_transition","finding_confirmation","finding_command_receipt","audit_event","publications").contains(participant) ? 1 : 0;
                assertThat(after.get(participant)).as("one winning confirmation: %s",participant).isEqualTo(before.get(participant)+delta);
            }
            expect(post(firstPerson,path,UUID.randomUUID(),body),409);
            assertThat(counts()).isEqualTo(after);
            long version = fixture.read(db -> db.queryForObject("select version from public.finding where tenant_id=? and id=?",Long.class,fixture.tenantId,finding));
            assertThat(version).isEqualTo(2);
        } finally { start.countDown(); stop(workers); }
    }

    private int observeAdvisoryWait(int blocker,Future<?> waiter) {
        long deadline = System.nanoTime()+Duration.ofSeconds(3).toNanos();
        while (System.nanoTime()<deadline) {
            Integer pid = fixture.waitingAdvisoryPid(blocker);
            if (pid != null) { assertThat(waiter.isDone()).isFalse(); return pid; }
            assertThat(waiter.isDone()).as("Real HTTP/writer must remain blocked until release").isFalse();
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
        }
        throw new AssertionError("No actual pg_locks advisory wait with matching pg_blocking_pids before deadline");
    }
    private static void await(CountDownLatch latch,String description) {
        try {
            if (!latch.await(8,TimeUnit.SECONDS)) throw new IllegalStateException("Timed out: " + description);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); throw new IllegalStateException("Interrupted: " + description,interrupted);
        }
    }
    private static void stop(ExecutorService workers) throws InterruptedException {
        workers.shutdownNow(); assertThat(workers.awaitTermination(10,TimeUnit.SECONDS)).isTrue();
    }

    private void throughDispatch(UUID finding,long version) throws Exception {
        var person = login(fixture.person); var safety = login(fixture.safety);
        UUID personCommand = UUID.randomUUID();
        String personBody = json.writeValueAsString(Map.of("expectedVersion",version,"reason","Synthetic personal confirmation"));
        String personPath = "/api/v1/findings/" + finding + "/confirm/person";
        JsonNode personal = result(post(person,personPath,personCommand,personBody),200);
        assertThat(personal.path("state").asString()).isEqualTo("SAFETY_CONFIRM_PENDING");
        assertCommand(personCommand,finding,version+1); assertTask(finding,"safetyConfirm",fixture.safety.memberId());
        assertThat(result(post(person,personPath,personCommand,personBody),200)).isEqualTo(personal);
        UUID safetyCommand = UUID.randomUUID();
        String safetyBody = json.writeValueAsString(Map.of("expectedVersion",version+1,"reason","Synthetic professional confirmation","grade","GENERAL"));
        String safetyPath = "/api/v1/findings/" + finding + "/confirm/safety";
        JsonNode confirmed = result(post(safety,safetyPath,safetyCommand,safetyBody),200);
        assertThat(confirmed.path("state").asString()).isEqualTo("SAFETY_DISPATCH_PENDING");
        assertCommand(safetyCommand,finding,version+2); assertTask(finding,"safetyDispatch",fixture.safety.memberId());
        assertThat(result(post(safety,safetyPath,safetyCommand,safetyBody),200)).isEqualTo(confirmed);
        UUID dispatchCommand = UUID.randomUUID();
        String dispatchBody = json.writeValueAsString(Map.of("expectedVersion",version+2,"assigneeMemberId",fixture.remediator.memberId(),
                "dueAt",OffsetDateTime.now().plusDays(2).toString(),"requirements","Synthetic remediation requirements"));
        String dispatchPath = "/api/v1/findings/" + finding + "/dispatch";
        JsonNode dispatched = result(post(safety,dispatchPath,dispatchCommand,dispatchBody),200);
        assertThat(dispatched.path("state").asString()).isEqualTo("RECTIFYING");
        assertCommand(dispatchCommand,finding,version+3); assertTask(finding,"remediation",fixture.remediator.memberId());
        Map<String,Long> beforeReplay = counts();
        assertThat(result(post(safety,dispatchPath,dispatchCommand,dispatchBody),200)).isEqualTo(dispatched);
        assertThat(counts()).isEqualTo(beforeReplay);
        assertThat(fixture.count("finding_confirmation")).isEqualTo(2);
        assertThat(fixture.count("remediation_notice")).isEqualTo(1);
        assertThat(fixture.count("remediation_round")).isEqualTo(1);
    }

    private Map<String,Long> counts() {
        Map<String,Long> counts = new LinkedHashMap<>();
        for (String table : List.of("finding","finding_transition","finding_confirmation","finding_command_receipt","workflow_binding","audit_event","remediation_notice","remediation_round")) counts.put(table,fixture.count(table));
        counts.put("engine",engine.createProcessInstanceQuery().processInstanceTenantId(fixture.tenantId.toString()).count());
        counts.put("numberSequence",fixture.read(db -> db.queryForObject("select coalesce(sum(last_sequence),0) from public.finding_number_sequence where tenant_id=?",Long.class,fixture.tenantId)));
        counts.put("publications",fixture.read(db -> db.queryForObject("select count(*) from public.event_publication where serialized_event like ?",Long.class,"%" + fixture.tenantId + "%")));
        return counts;
    }
    private void assertCommand(UUID command,UUID finding,long version) {
        fixture.read(db -> {
            assertThat(db.queryForObject("select count(*) from public.finding_command_receipt where tenant_id=? and command_id=? and result_finding_id=? and result_version=? and completed_at is not null",Long.class,fixture.tenantId,command,finding,version)).isEqualTo(1);
            assertThat(db.queryForObject("select count(*) from public.finding_transition where tenant_id=? and command_id=? and finding_id=? and to_version=?",Long.class,fixture.tenantId,command,finding,version)).isEqualTo(1);
            assertThat(db.queryForObject("select count(*) from public.audit_event where tenant_id=? and resource_id=? and diff_redacted->>'toVersion'=?",Long.class,fixture.tenantId,finding,Long.toString(version))).isEqualTo(1);
            return null;
        });
        assertThat(fixture.publications(command)).isPositive();
    }
    private void assertTask(UUID finding,String key,UUID assignee) {
        String instance = fixture.read(db -> db.queryForObject("select process_instance_id from public.workflow_binding where tenant_id=? and business_id=?",String.class,fixture.tenantId,finding));
        var task = tasks.createTaskQuery().processInstanceId(instance).singleResult();
        assertThat(task).isNotNull(); assertThat(task.getTaskDefinitionKey()).isEqualTo(key);
        assertThat(task.getAssignee()).isEqualTo(assignee.toString());
    }
    private String createBody(String description) throws Exception {
        return json.writeValueAsString(Map.of("organizationId",fixture.organizationId,"description",fixture.run + description,
                "discoveredAt",OffsetDateTime.now().minusHours(1).toString(),"grade","UNKNOWN","sourceCategory","FIELD_OBSERVATION","urgency","NORMAL","location","Synthetic location"));
    }
    private IdentityAcceptanceHttps.Browser login(BusinessWorkflowPostgresFixture.Actor actor) throws Exception {
        var client = https.browser(port); clients.add(client); expect(client.get("/api/v1/auth/csrf"),204);
        expect(client.post("/api/v1/auth/login",json.writeValueAsString(Map.of("loginKey",actor.loginKey(),"password",actor.password(),"tenantCode",fixture.tenantCode)),client.csrf()),204);
        expect(client.get("/api/v1/auth/csrf"),204); // Authentication rotates the CSRF token.
        return client;
    }
    private IdentityAcceptanceHttps.Response post(IdentityAcceptanceHttps.Browser client,String path,UUID command,String body) throws Exception {
        return client.postWithHeaders(path,body,client.csrf(),Map.of("Idempotency-Key",command.toString()));
    }
    private JsonNode result(IdentityAcceptanceHttps.Response response,int status) throws Exception { expect(response,status); return json.readTree(response.body()).path("data"); }
    private void expect(IdentityAcceptanceHttps.Response response,int status) {
        if (response.status() != status) printFailureDiagnostics();
        assertThat(response.status()).as("Production finding HTTPS status").isEqualTo(status);
    }
    private void printFailureDiagnostics() {
        // Exception messages, SQL text/bindings, credentials and response bodies are deliberately excluded.
        late.failures.forEach((trace, chain) -> System.err.println("Business acceptance diagnostic trace=" + trace + " " + chain));
    }

    /** Not a Spring Clock bean: only the production command-service constructor receives this clock. */
    static final class CommandTime {
        final CommandClock clock = new CommandClock(new AtomicReference<>(),ZoneOffset.UTC);
    }
    static final class CommandClock extends Clock {
        private final AtomicReference<Instant> fixed;
        private final ZoneId zone;
        CommandClock(AtomicReference<Instant> fixed,ZoneId zone) { this.fixed = fixed; this.zone = zone; }
        void reset() { fixed.set(null); }
        void set(Instant instant) { fixed.set(Objects.requireNonNull(instant)); }
        @Override public ZoneId getZone() { return zone; }
        @Override public Clock withZone(ZoneId zone) { return new CommandClock(fixed,Objects.requireNonNull(zone)); }
        @Override public Instant instant() { Instant value = fixed.get(); return value == null ? Clock.systemUTC().instant() : value; }
    }

    enum FaultStage { AFTER_ENGINE, BEFORE_AUDIT, AFTER_RECEIPT }
    record FaultProof(UUID findingId,String processInstanceId,Map<String,Long> participants) { }
    static final class FaultPlan {
        final FaultStage stage;
        final UUID command;
        final java.util.concurrent.atomic.AtomicBoolean armed = new java.util.concurrent.atomic.AtomicBoolean(true);
        final AtomicReference<FaultProof> proof = new AtomicReference<>();
        FaultPlan(FaultStage stage,UUID command) { this.stage = stage; this.command = command; }
    }
    static final class FaultFrame {
        final FaultPlan plan;
        final UUID tenant;
        UUID finding;
        FaultFrame(FaultPlan plan,UUID tenant) { this.plan = plan; this.tenant = tenant; }
    }

    /** Observe actual transaction-local rows and engine state, never substitute business outcomes. */
    private static void failAt(FaultStage stage,LateFailure late,JdbcTemplate jdbc,RuntimeService runtime) {
        FaultFrame frame = late.frame.get();
        if (frame == null || frame.plan.stage != stage || !frame.plan.armed.compareAndSet(true,false)) return;
        if (frame.finding == null) throw new IllegalStateException("Faultpoint has no actual finding binding");
        var process = runtime.createProcessInstanceQuery().processInstanceTenantId(frame.tenant.toString())
                .processInstanceBusinessKey("FINDING:" + frame.finding).singleResult();
        Map<String,Long> rows = new LinkedHashMap<>();
        rows.put("finding",jdbc.queryForObject("select count(*) from public.finding where tenant_id=? and id=?",Long.class,frame.tenant,frame.finding));
        rows.put("binding",jdbc.queryForObject("select count(*) from public.workflow_binding where tenant_id=? and business_id=?",Long.class,frame.tenant,frame.finding));
        rows.put("transition",jdbc.queryForObject("select count(*) from public.finding_transition where tenant_id=? and finding_id=?",Long.class,frame.tenant,frame.finding));
        rows.put("audit",jdbc.queryForObject("select count(*) from public.audit_event where tenant_id=? and resource_id=?",Long.class,frame.tenant,frame.finding));
        rows.put("completedReceipt",jdbc.queryForObject("select count(*) from public.finding_command_receipt where tenant_id=? and command_id=? and completed_at is not null",Long.class,frame.tenant,frame.plan.command));
        rows.put("publication",jdbc.queryForObject("select count(*) from public.event_publication where serialized_event like ?",Long.class,"%" + frame.plan.command + "%"));
        rows.put("engine",process == null ? 0L : 1L);
        frame.plan.proof.set(new FaultProof(frame.finding,process == null ? null : process.getId(),Map.copyOf(rows)));
        throw new DataAccessResourceFailureException("Synthetic transaction fault at " + stage);
    }

    record LateProof(String processInstanceId,long publications,long engineInstances,long auditRows,long completedReceipts) { }
    static final class Pause {
        final UUID command;
        final CompletableFuture<Integer> entered = new CompletableFuture<>();
        final CountDownLatch release = new CountDownLatch(1);
        Pause(UUID command) { this.command = command; }
    }
    static final class LateFailure {
        final AtomicReference<Pause> pause = new AtomicReference<>();
        final AtomicReference<FaultPlan> fault = new AtomicReference<>();
        final ThreadLocal<FaultFrame> frame = new ThreadLocal<>();
        final AtomicReference<UUID> command = new AtomicReference<>(); final AtomicReference<LateProof> proof = new AtomicReference<>();
        final java.util.concurrent.ConcurrentHashMap<String,List<String>> failures = new java.util.concurrent.ConcurrentHashMap<>();
    }
    @TestConfiguration(proxyBeanMethods = false)
    static class LateFailureConfiguration {
        @Bean LateFailure lateFailure() { return new LateFailure(); }
        @Bean CommandTime commandTime() { return new CommandTime(); }
        @Bean @Primary FindingWorkflowCommandService productionCommandServiceWithCommandOnlyClock(
                SessionContextResolver sessions,FindingWorkflowRepository repository,FindingWorkflowAccessPolicy access,
                FindingWorkflowAssignmentResolver assignments,FindingWorkflowEvidencePolicy evidence,
                WorkflowFacade workflow,FindingWorkflowEventPublisher publisher,CommandTime time) {
            return new FindingWorkflowCommandService(sessions,repository,access,assignments,evidence,workflow,publisher,time.clock);
        }
        @Bean @Primary FindingWorkflowRepository actualRepositoryWithBoundedFaults(
                @Qualifier("jooqFindingWorkflowRepository") FindingWorkflowRepository real,
                LateFailure late,JdbcTemplate jdbc,RuntimeService runtime) {
            return new FindingWorkflowRepository() {
                @Override public <T> T inTenantTransaction(UUID tenant,Function<Transaction,T> work) {
                    return real.inTenantTransaction(tenant,actual -> {
                        Transaction observed = (Transaction) Proxy.newProxyInstance(Transaction.class.getClassLoader(),
                                new Class<?>[]{Transaction.class},(proxy,method,args) -> {
                                    FaultPlan plan = late.fault.get();
                                    if (method.getName().equals("claimCommand") && plan != null
                                            && plan.command.equals(((FindingWorkflowContracts.WorkflowCommand)args[0]).commandId())) {
                                        late.frame.set(new FaultFrame(plan,tenant));
                                    }
                                    FaultFrame frame = late.frame.get();
                                    if (frame != null && method.getName().equals("appendTransition")) {
                                        frame.finding = ((FindingWorkflowContracts.Transition)args[0]).findingId();
                                        failAt(FaultStage.AFTER_ENGINE,late,jdbc,runtime);
                                    }
                                    Object result;
                                    try { result = method.invoke(actual,args); }
                                    catch (InvocationTargetException failure) { throw failure.getCause(); }
                                    if (frame != null && method.getName().equals("insertFinding")) {
                                        frame.finding = ((FindingWorkflowCase)args[0]).id();
                                    }
                                    if (frame != null && method.getName().equals("completeCommand")) {
                                        frame.finding = ((FindingWorkflowContracts.CommandResult)args[1]).findingId();
                                        failAt(FaultStage.AFTER_RECEIPT,late,jdbc,runtime);
                                    }
                                    return result;
                                });
                        try { return work.apply(observed); }
                        finally { late.frame.remove(); }
                    });
                }
            };
        }
        @Bean ExecuteListenerProvider failBeforeActualAuditInsert(LateFailure late,JdbcTemplate jdbc,RuntimeService runtime) {
            return () -> new ExecuteListener() {
                @Override public void executeStart(ExecuteContext context) {
                    if (context.sql() != null && context.sql().toLowerCase(Locale.ROOT).contains("insert into public.audit_event")) {
                        failAt(FaultStage.BEFORE_AUDIT,late,jdbc,runtime);
                    }
                }
            };
        }
        @Bean WebMvcConfigurer observeOnlyUnhandledCommandFailures(LateFailure late) {
            return new WebMvcConfigurer() {
                @Override public void extendHandlerExceptionResolvers(List<HandlerExceptionResolver> resolvers) {
                    resolvers.addFirst((request,response,handler,failure) -> {
                        List<String> chain = new ArrayList<>();
                        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
                        for (Throwable cause = failure; cause != null && seen.add(cause); cause = cause.getCause()) {
                            String item = cause.getClass().getName();
                            if (cause instanceof java.sql.SQLException sql) {
                                String state = sql.getSQLState();
                                item += " SQLState=" + (state != null && state.matches("[A-Z0-9]{5}") ? state : "unknown");
                                if ("42804".equals(state) && sql instanceof org.postgresql.util.PSQLException postgres
                                        && postgres.getServerErrorMessage() != null) {
                                    // Type mismatch summary only: never detail, SQL, parameters or server context.
                                    item += " typeMismatch=" + postgres.getServerErrorMessage().getMessage();
                                }
                            }
                            chain.add(item);
                            Arrays.stream(cause.getStackTrace())
                                    .filter(frame -> frame.getClassName().startsWith("com.mightyoung.softwarefactory."))
                                    .limit(4).map(frame -> frame.getClassName() + "." + frame.getMethodName() + ":" + frame.getLineNumber())
                                    .forEach(chain::add);
                        }
                        String trace = request.getHeader(IdentityAcceptanceHttps.TRACE_HEADER);
                        // Only observations for this test's UUID trace header; never expose arbitrary request headers.
                        if (trace != null && trace.matches("[0-9a-fA-F-]{36}")) late.failures.put(trace,List.copyOf(chain));
                        return null; // Continue to the actual production advice; do not consume or replace errors.
                    });
                }
            };
        }
        @Bean @Primary FindingWorkflowEventPublisher publicationThenSingleFailure(ModulithFindingWorkflowEventPublisher real,
                LateFailure late,JdbcTemplate jdbc,RuntimeService runtime) {
            return event -> {
                real.publish(event); // Actual synchronous publication registration remains part of the caller's transaction.
                Pause pause = late.pause.get();
                if (pause != null && pause.command.equals(event.commandId())) {
                    pause.entered.complete(jdbc.queryForObject("select pg_backend_pid()",Integer.class));
                    await(pause.release,"release actual command transaction after publication");
                }
                UUID expected = late.command.get();
                if (event.commandId().equals(expected) && late.command.compareAndSet(expected,null)) {
                    var instance = runtime.createProcessInstanceQuery().processInstanceTenantId(event.tenantId().toString())
                            .processInstanceBusinessKey("FINDING:" + event.findingId()).singleResult();
                    long publications = jdbc.queryForObject("select count(*) from public.event_publication where serialized_event like ?",Long.class,"%" + event.commandId() + "%");
                    long audit = jdbc.queryForObject("select count(*) from public.audit_event where tenant_id=? and resource_id=?",Long.class,event.tenantId(),event.findingId());
                    long receipt = jdbc.queryForObject("select count(*) from public.finding_command_receipt where tenant_id=? and command_id=? and completed_at is not null",Long.class,event.tenantId(),event.commandId());
                    late.proof.set(new LateProof(instance == null ? null : instance.getId(),publications,instance == null ? 0 : 1,audit,receipt));
                    throw new DataAccessResourceFailureException("Synthetic failure after actual transactional publication");
                }
            };
        }
    }
}
