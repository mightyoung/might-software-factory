package com.mightyoung.softwarefactory;

import com.mightyoung.softwarefactory.authorization.PostgresAuthorizationWriteGuard;
import com.mightyoung.softwarefactory.dualprevention.ModulithFindingWorkflowEventPublisher;
import com.mightyoung.softwarefactory.dualprevention.finding.workflow.FindingWorkflowEventPublisher;
import com.mightyoung.softwarefactory.files.LocalPrivateFileStorageAdapter;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flowable.engine.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.parallel.*;
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
import org.springframework.transaction.support.*;
import tools.jackson.databind.*;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

import static org.assertj.core.api.Assertions.*;

/**
 * Actual HTTPS submit versus a cooperating, managed fixture metadata writer, in both commit orders.
 * There is no production evidence-edit endpoint: this proves V6 sealing and the parent-before-item
 * lock protocol, not acceptance of an unimplemented edit API or upload/scanner ingestion.
 * All synthetic business history, signed receipts and immutable blobs are retained.
 */
@SpringBootTest(classes = SoftwareFactoryApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(FindingEvidenceSealConcurrencyPostgresIT.BarrierConfiguration.class)
@ActiveProfiles("lan-postgres-compatibility")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class FindingEvidenceSealConcurrencyPostgresIT {
    private static IdentityAcceptanceHttps https;
    private static FindingRemediationPostgresFixture.Storage storage;
    @LocalServerPort private int port;
    @Autowired private PasswordEncoder passwords;
    @Autowired private ObjectMapper json;
    @Autowired private RepositoryService deployments;
    @Autowired private RuntimeService runtime;
    @Autowired private TaskService tasks;
    @Autowired private HistoryService history;
    @Autowired private LocalPrivateFileStorageAdapter fileVerifier;
    @Autowired private Controls controls;
    private FindingRemediationPostgresFixture fixture;
    private HikariDataSource writerSource;
    private JdbcTemplate writer;
    private TransactionTemplate writerTx;
    private PostgresAuthorizationWriteGuard writerGuard;
    private ExecutorService workers;
    private final Map<UUID, IdentityAcceptanceHttps.Browser> loggedIn = new HashMap<>();
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p)throws Exception {
        var target=FindingRemediationPostgresFixture.validateTarget();
        if(storage==null)storage=FindingRemediationPostgresFixture.Storage.prepare();
        if(https==null)https=IdentityAcceptanceHttps.create();
        p.add("spring.datasource.url",target::jdbcUrl);p.add("spring.datasource.username",()->System.getenv("LAN_PG_APP_USERNAME"));p.add("spring.datasource.password",()->System.getenv("LAN_PG_APP_PASSWORD"));p.add("spring.datasource.hikari.maximum-pool-size",()->4);p.add("spring.datasource.hikari.minimum-idle",()->1);
        p.add("server.address",()->"127.0.0.1");p.add("server.ssl.enabled",()->true);p.add("server.ssl.key-store",()->https.keyStore().toUri().toString());p.add("server.ssl.key-store-password",https::password);p.add("server.ssl.key-store-type",()->"PKCS12");p.add("server.ssl.key-alias",()->"identity-acceptance");
        p.add("spring.session.jdbc.cleanup-cron",()->"-"); // Preserve sessions outside this fixture.
        p.add("flowable.check-process-definitions",()->false);p.add("flowable.database-schema-update",()->false);p.add("flowable.async-executor-activate",()->false);p.add("flowable.async-history-executor-activate",()->false);p.add("spring.flyway.enabled",()->false);p.add("spring.session.jdbc.initialize-schema",()->"never");p.add("spring.modulith.events.jdbc.schema-initialization.enabled",()->false);
        p.add("software-factory.files.local.root",()->storage.root.toString());p.add("software-factory.files.local.receipt-hmac-key-base64",()->Base64.getEncoder().encodeToString(storage.key));p.add("software-factory.files.local.receipt-key-id",()->"b4-local-key-1");p.add("software-factory.files.local.verifier-id",()->"b4-local-verifier");p.add("software-factory.files.local.policy-version",()->"b4-fixture-v1");p.add("software-factory.files.local.max-bytes",()->1048576);
    }

    @BeforeEach void seedAndDeploy() {
        fixture = new FindingRemediationPostgresFixture(passwords, storage, fileVerifier);
        deployments.createDeployment().name(fixture.business.run + "-seal-race")
                .tenantId(tenant().toString())
                .addClasspathResource("processes/finding-remediation-v1.bpmn20.xml")
                .addClasspathResource("processes/finding-remediation-v2.bpmn20.xml").deploy();
        // Explicit owner-role fixture writer, never presented as the app's unimplemented edit API.
        var config = new HikariConfig();
        config.setJdbcUrl(FindingRemediationPostgresFixture.validateTarget().jdbcUrl());
        config.setUsername(System.getenv("LAN_PG_USERNAME"));
        config.setPassword(System.getenv("LAN_PG_PASSWORD"));
        config.setMaximumPoolSize(2); config.setMinimumIdle(0);
        config.setPoolName("b4-seal-writer-" + UUID.randomUUID());
        writerSource = new HikariDataSource(config);
        writer = new JdbcTemplate(writerSource);
        writerTx = new TransactionTemplate(new DataSourceTransactionManager(writerSource));
        writerTx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        writerTx.setTimeout(20);
        writerGuard = new PostgresAuthorizationWriteGuard(writerSource);
        workers = Executors.newFixedThreadPool(2);
        controls.barrier.set(null); controls.commits.clear();
    }

    @AfterEach void closeOwnedResources() throws Exception {
        var active = controls.barrier.getAndSet(null);
        if (active != null) active.release.countDown();
        Throwable workerFailure = null;
        try {
            if (workers != null) {
                workers.shutdownNow();
                try {
                    assertThat(workers.awaitTermination(20, TimeUnit.SECONDS))
                            .as("evidence seal race workers terminated").isTrue();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw interrupted;
                }
            }
        } catch (Exception | Error failure) {
            workerFailure = failure;
            throw failure;
        } finally {
            try {
                try { loggedIn.values().forEach(IdentityAcceptanceHttps.Browser::close); }
                finally {
                    loggedIn.clear();
                    try { if (writerSource != null) writerSource.close(); }
                    finally { if (fixture != null) fixture.close(); }
                }
            } catch (Exception | Error cleanupFailure) {
                if (workerFailure != null) workerFailure.addSuppressed(cleanupFailure);
                else throw cleanupFailure;
            }
        }
    }

    @AfterAll static void closeTls() throws Exception {
        if (https != null) https.close(); // Keep blobs, signed receipts, keys and manifests.
    }

    @Test void committedMetadataEditWinsAndWaitingHttpSubmissionRejectsWithoutWorkflowEffects() throws Exception {
        UUID finding = dispatch();
        var evidence = fixture.evidence(finding, 1, fixture.remediator());
        var client = login(fixture.remediator()); // Authenticate before either transaction holds a lock.
        UUID command = UUID.randomUUID(); fixture.recordCommand(command);
        State before = state(finding);
        var files = fileRows(); byte[] bytes = Files.readAllBytes(blob(evidence));
        var edit = new Barrier(null);
        Future<Throwable> editing = workers.submit(() -> catchThrowable(() -> editMetadata(evidence, edit)));
        try {
            await(edit.entered, "fixture edit acquired parent and changed item");
            int holder = edit.pid.get(3, TimeUnit.SECONDS);
            Future<IdentityAcceptanceHttps.Response> submitting = workers.submit(() -> submit(client, finding, evidence, command));
            int waiter = awaitDatabaseWait(holder, null, submitting);
            assertThat(waiter).isNotEqualTo(holder);
            assertThat(controls.commits).isEmpty();
            edit.release.countDown();
            assertThat(editing.get(15, TimeUnit.SECONDS)).isNull();
            expect(submitting.get(15, TimeUnit.SECONDS), 422);
            assertThat(controls.commits).containsExactly("edit");

            var expectedCounts = new TreeMap<>(before.counts());
            expectedCounts.compute("evidenceRevision", (key, revision) -> revision + 1);
            assertThat(state(finding)).isEqualTo(new State(expectedCounts, before.finding(), before.tasks(), before.active(), before.finished()));
            fixture.read(db -> {
                assertThat(db.queryForObject("select state from public.evidence_item where tenant_id=? and id=?", String.class, tenant(), evidence.itemId())).isEqualTo("QUARANTINED");
                assertThat(db.queryForObject("select submission_sealed_at is null from public.evidence_set where tenant_id=? and id=?", Boolean.class, tenant(), evidence.setId())).isTrue();
                assertThat(db.queryForObject("select count(*) from public.finding_command_receipt where tenant_id=? and command_id=?", Long.class, tenant(), command)).isZero();
                assertThat(db.queryForObject("select count(*) from public.event_publication where serialized_event like ?", Long.class, "%" + command + "%")).isZero();
                return null;
            });
            assertTask(finding, "remediation", fixture.remediator().memberId());
            assertRetained(evidence, files, bytes);
        } finally { edit.release.countDown(); }
    }

    @Test void committedHttpSubmissionSealsSnapshotAndWaitingMetadataEditRollsBack() throws Exception {
        UUID finding = dispatch();
        var evidence = fixture.evidence(finding, 1, fixture.remediator());
        var client = login(fixture.remediator());
        UUID command = UUID.randomUUID(); fixture.recordCommand(command);
        State before = state(finding);
        var files = fileRows(); byte[] bytes = Files.readAllBytes(blob(evidence));
        var submission = new Barrier(command); controls.barrier.set(submission);
        Future<IdentityAcceptanceHttps.Response> submitting = workers.submit(() -> submit(client, finding, evidence, command));
        var edit = new Barrier(null);
        try {
            await(submission.entered, "real publisher reached after snapshot seal before commit");
            int holder = submission.pid.get(3, TimeUnit.SECONDS);
            Future<Throwable> editing = workers.submit(() -> catchThrowable(() -> editMetadata(evidence, edit)));
            int waiter = edit.pid.get(3, TimeUnit.SECONDS);
            awaitDatabaseWait(holder, waiter, editing);
            assertThat(edit.entered.getCount()).isEqualTo(1); // Has not passed the parent lock.
            assertThat(controls.commits).isEmpty();
            submission.release.countDown();
            JsonNode result = result(submitting.get(15, TimeUnit.SECONDS));
            assertThat(result.path("state").asString()).isEqualTo("ACCEPTANCE_PENDING");
            assertThat(result.path("version").asLong()).isEqualTo(5);
            Throwable refusal = editing.get(15, TimeUnit.SECONDS);
            assertThat(refusal).isNotNull();
            assertThat(rootCause(refusal).getMessage()).contains("evidence set is sealed by a remediation submission");
            assertThat(controls.commits).containsExactly("submit");
            assertSubmitted(finding, evidence, command, before);
            State committed = state(finding);
            var sealedRows = evidenceRows();
            assertThat(sealedRows).isEqualTo(submission.sealedRows.get(3, TimeUnit.SECONDS));
            // Replay the actual production command: no duplicate receipt, event, audit, snapshot or engine work.
            assertThat(result(submit(client, finding, evidence, command))).isEqualTo(result);
            assertThat(state(finding)).isEqualTo(committed);
            assertThat(evidenceRows()).isEqualTo(sealedRows);
            assertRetained(evidence, files, bytes);
        } finally { submission.release.countDown(); edit.release.countDown(); controls.barrier.set(null); }
    }

    /** Managed fixture writer: authorization guard -> tenant -> evidence parent -> source item. */
    private void editMetadata(FindingRemediationPostgresFixture.Evidence evidence, Barrier barrier) {
        writerTx.executeWithoutResult(status -> {
            writerGuard.forFindingCommand(tenant());
            writer.queryForObject("select set_config('app.tenant_id',?,true)", String.class, tenant().toString());
            writer.execute("set local lock_timeout='10s'");
            barrier.pid.complete(writer.queryForObject("select pg_backend_pid()", Integer.class));
            assertThat(writer.queryForObject("select id from public.evidence_set where tenant_id=? and id=? for update", UUID.class, tenant(), evidence.setId())).isEqualTo(evidence.setId());
            // The V6 trigger performs the revision increment and refuses this write after sealing.
            assertThat(writer.update("update public.evidence_item set state='QUARANTINED' where tenant_id=? and id=? and evidence_set_id=?", tenant(), evidence.itemId(), evidence.setId())).isEqualTo(1);
            afterCommit(controls, "edit");
            barrier.entered.countDown();
            await(barrier.release, "release fixture edit transaction");
        });
    }

    private int awaitDatabaseWait(int holder, Integer expectedWaiter, Future<?> pending) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            List<Integer> blocked = writer.queryForList("""
                    select a.pid from pg_stat_activity a
                     where ?=any(pg_blocking_pids(a.pid))
                       and (?::integer is null or a.pid=?::integer)
                       and exists (select 1 from pg_locks l where l.pid=a.pid
                                   and l.locktype='transactionid' and not l.granted)
                    """, Integer.class, holder, expectedWaiter, expectedWaiter);
            if (!blocked.isEmpty()) {
                assertThat(blocked).hasSize(1);
                assertThat(pending.isDone()).isFalse();
                return blocked.getFirst();
            }
            assertThat(pending.isDone()).as("operation finished before the observed parent-row wait").isFalse();
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
        }
        throw new AssertionError("No matching PostgreSQL transaction lock wait observed");
    }

    private void assertSubmitted(UUID finding, FindingRemediationPostgresFixture.Evidence evidence, UUID command, State before) {
        var after = state(finding);
        var expected = new TreeMap<>(before.counts());
        for (String table : List.of("finding_command_receipt", "finding_transition", "audit_event", "remediation_submission", "remediation_submission_evidence", "evidenceRevision", "sealedSets", "sealedSubmissions", "manifestItems"))
            expected.compute(table, (key, count) -> count + 1);
        expected.put("publications", after.counts().get("publications"));
        assertThat(after.counts()).isEqualTo(expected);
        assertThat(after.counts().get("publications")).isGreaterThan(before.counts().get("publications"));
        assertThat(after.active()).isEqualTo(1); assertThat(after.finished()).isZero();
        fixture.read(db -> {
            assertThat(db.queryForObject("select state from public.finding where tenant_id=? and id=?", String.class, tenant(), finding)).isEqualTo("ACCEPTANCE_PENDING");
            assertThat(db.queryForObject("select version from public.finding where tenant_id=? and id=?", Long.class, tenant(), finding)).isEqualTo(5);
            assertThat(db.queryForObject("select count(*) from public.finding_command_receipt where tenant_id=? and command_id=? and result_finding_id=? and result_version=5 and completed_at is not null", Long.class, tenant(), command, finding)).isEqualTo(1);
            assertThat(db.queryForObject("select count(*) from public.finding_transition where tenant_id=? and command_id=? and to_version=5", Long.class, tenant(), command)).isEqualTo(1);
            assertThat(db.queryForObject("select count(*) from public.audit_event where tenant_id=? and resource_id=? and diff_redacted->>'toVersion'='5'", Long.class, tenant(), finding)).isEqualTo(1);
            assertThat(db.queryForObject("select count(*) from public.event_publication where serialized_event like ?", Long.class, "%" + command + "%")).isPositive();
            assertThat(db.queryForObject("select state from public.evidence_item where tenant_id=? and id=?", String.class, tenant(), evidence.itemId())).isEqualTo("READY");
            assertThat(db.queryForObject("select count(*) from public.evidence_set where tenant_id=? and id=? and submission_sealed_at is not null", Long.class, tenant(), evidence.setId())).isEqualTo(1);
            assertThat(db.queryForObject("select count(*) from public.remediation_submission where tenant_id=? and finding_id=? and evidence_set_id=? and evidence_snapshot_count=1 and evidence_snapshot_manifest=array[cast(? as uuid)] and evidence_snapshots_sealed_at is not null", Long.class, tenant(), finding, evidence.setId(), evidence.itemId())).isEqualTo(1);
            assertThat(db.queryForObject("""
                    select count(*) from public.remediation_submission_evidence s
                    join public.file_object f on f.tenant_id=s.tenant_id and f.id=s.file_id
                    where s.tenant_id=? and s.evidence_set_id=? and s.evidence_item_id=?
                      and s.file_id=? and s.file_link_id=? and s.verification_receipt_id=?
                      and s.sha256=f.checksum and s.storage_version=f.storage_version
                      and s.owner_member_id=f.owner_member_id and s.owner_actor_id=f.owner_actor_id
                      and s.verification_level='CONTENT' and s.verifier_id='b4-local-verifier'
                      and s.verifier_key_id='b4-local-key-1' and s.verification_policy_version='b4-fixture-v1'
                    """, Long.class, tenant(), evidence.setId(), evidence.itemId(), evidence.fileId(), evidence.linkId(), evidence.receiptId())).isEqualTo(1);
            assertThat(db.queryForObject("select verified_at from public.remediation_submission_evidence where tenant_id=? and evidence_item_id=?", OffsetDateTime.class, tenant(), evidence.itemId()))
                    .isEqualTo(fileVerifier.findByReceiptId(tenant(), evidence.receiptId()).orElseThrow().verifiedAt());
            return null;
        });
        assertTask(finding, "safetyVerification", fixture.safety().memberId());
    }

    private UUID dispatch() throws Exception {
        var client = login(fixture.reporter()); UUID create = UUID.randomUUID(); fixture.recordCommand(create);
        UUID finding = UUID.fromString(result(client.postWithHeaders("/api/v1/findings", json.writeValueAsString(Map.of(
                "organizationId", fixture.business.organizationId, "description", "Retained evidence seal concurrency fixture",
                "discoveredAt", OffsetDateTime.now().minusHours(1).toString(), "grade", "UNKNOWN",
                "sourceCategory", "FIELD_OBSERVATION", "urgency", "NORMAL", "location", "Fixture area")),
                client.csrf(), Map.of("Idempotency-Key", create.toString()))).path("findingId").asString());
        command(fixture.person(), finding, "confirm/person", Map.of("expectedVersion", 1, "reason", "Confirmed"));
        command(fixture.safety(), finding, "confirm/safety", Map.of("expectedVersion", 2, "reason", "Confirmed", "grade", "GENERAL"));
        command(fixture.safety(), finding, "dispatch", Map.of("expectedVersion", 3, "assigneeMemberId", fixture.remediator().memberId(), "dueAt", OffsetDateTime.now().plusDays(2).toString(), "requirements", "Fixture remediation"));
        assertTask(finding, "remediation", fixture.remediator().memberId());
        return finding;
    }

    private void command(FindingRemediationPostgresFixture.Member member, UUID finding, String suffix, Map<String, Object> body) throws Exception {
        UUID id = UUID.randomUUID(); fixture.recordCommand(id); var client = login(member);
        result(client.postWithHeaders("/api/v1/findings/" + finding + "/" + suffix, json.writeValueAsString(body), client.csrf(), Map.of("Idempotency-Key", id.toString())));
    }
    private IdentityAcceptanceHttps.Response submit(IdentityAcceptanceHttps.Browser client, UUID finding, FindingRemediationPostgresFixture.Evidence evidence, UUID command) throws Exception {
        return client.postWithHeaders("/api/v1/findings/" + finding + "/remediation-submissions", json.writeValueAsString(Map.of("expectedVersion", 4, "evidenceSetId", evidence.setId(), "statement", "Actual retained signed evidence seal race")), client.csrf(), Map.of("Idempotency-Key", command.toString()));
    }
    private IdentityAcceptanceHttps.Browser login(FindingRemediationPostgresFixture.Member member) throws Exception {
        var existing = loggedIn.get(member.accountId()); if (existing != null) return existing;
        var client = https.browser(port); loggedIn.put(member.accountId(), client);
        expect(client.get("/api/v1/auth/csrf"), 204);
        expect(client.post("/api/v1/auth/login", json.writeValueAsString(Map.of("loginKey", member.loginKey(), "password", member.password(), "tenantCode", fixture.business.tenantCode)), client.csrf()), 204);
        expect(client.get("/api/v1/auth/csrf"), 204); return client;
    }
    private JsonNode result(IdentityAcceptanceHttps.Response response) throws Exception { expect(response, 200); return json.readTree(response.body()).path("data"); }
    private static void expect(IdentityAcceptanceHttps.Response response, int expected) { assertThat(response.status()).as("real production HTTPS status").isEqualTo(expected); }
    private UUID tenant() { return fixture.business.tenantId; }
    private String instance(UUID finding) { return fixture.read(db -> db.queryForObject("select process_instance_id from public.workflow_binding where tenant_id=? and business_id=?", String.class, tenant(), finding)); }
    private void assertTask(UUID finding, String expected, UUID assignee) { var task = tasks.createTaskQuery().processInstanceId(instance(finding)).singleResult(); assertThat(task).isNotNull(); assertThat(task.getTaskDefinitionKey()).isEqualTo(expected); assertThat(task.getAssignee()).isEqualTo(assignee.toString()); }
    record State(Map<String, Long> counts, String finding, List<String> tasks, long active, long finished) { }
    private State state(UUID finding) {
        String process = instance(finding);
        return new State(fixture.counts(), fixture.read(db -> db.queryForObject("select to_jsonb(f)::text from public.finding f where tenant_id=? and id=?", String.class, tenant(), finding)),
                tasks.createTaskQuery().processInstanceId(process).list().stream().map(t -> t.getId() + "/" + t.getTaskDefinitionKey() + "/" + t.getAssignee()).sorted().toList(),
                runtime.createProcessInstanceQuery().processInstanceId(process).count(), history.createHistoricProcessInstanceQuery().processInstanceId(process).finished().count());
    }
    private List<String> fileRows() { return rows(List.of("file_object", "file_link")); }
    private List<String> evidenceRows() { return rows(List.of("evidence_set", "evidence_item", "remediation_submission", "remediation_submission_evidence")); }
    private List<String> rows(List<String> tables) { return fixture.read(db -> { var result = new ArrayList<String>(); for (String table : tables) result.addAll(db.queryForList("select to_jsonb(r)::text from public." + table + " r where tenant_id=? order by to_jsonb(r)::text", String.class, tenant())); return List.copyOf(result); }); }
    private Path blob(FindingRemediationPostgresFixture.Evidence evidence) { return storage.root.resolve(tenant().toString()).resolve(evidence.fileId().toString()).resolve("fixture-v1.blob"); }
    private void assertRetained(FindingRemediationPostgresFixture.Evidence evidence, List<String> files, byte[] bytes) throws Exception { assertThat(fileRows()).isEqualTo(files); assertThat(Files.readAllBytes(blob(evidence))).isEqualTo(bytes); assertThat(fileVerifier.findByReceiptId(tenant(), evidence.receiptId())).isPresent(); }
    private static Throwable rootCause(Throwable failure) { while (failure.getCause() != null) failure = failure.getCause(); return failure; }
    private static void await(CountDownLatch latch, String description) {
        try { assertThat(latch.await(15, TimeUnit.SECONDS)).as(description).isTrue(); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException("Interrupted evidence sealing barrier", interrupted); }
    }
    private static void afterCommit(Controls controls, String operation) { TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() { @Override public void afterCommit() { controls.commits.add(operation); } }); }
    static final class Barrier {
        final UUID command;
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        final CompletableFuture<Integer> pid = new CompletableFuture<>();
        final CompletableFuture<List<String>> sealedRows = new CompletableFuture<>();
        Barrier(UUID command) { this.command = command; }
    }
    static final class Controls {
        final AtomicReference<Barrier> barrier = new AtomicReference<>();
        final List<String> commits = new CopyOnWriteArrayList<>();
    }
    @TestConfiguration(proxyBeanMethods = false)
    static class BarrierConfiguration {
        @Bean Controls sealRaceControls() { return new Controls(); }
        @Bean @Primary FindingWorkflowEventPublisher publicationBarrier(ModulithFindingWorkflowEventPublisher real, Controls controls, JdbcTemplate jdbc) {
            return event -> {
                real.publish(event); // Keep the production Modulith publication path inside the transaction.
                var barrier = controls.barrier.get();
                if (barrier != null && event.commandId().equals(barrier.command)) {
                    assertThat(jdbc.queryForObject("""
                            select count(*) from public.remediation_submission s
                            join public.evidence_set e on e.tenant_id=s.tenant_id and e.id=s.evidence_set_id
                            where s.tenant_id=? and s.command_id=? and s.evidence_snapshots_sealed_at is not null
                              and e.submission_sealed_at is not null and s.evidence_snapshot_count=1
                            """, Long.class, event.tenantId(), event.commandId())).isEqualTo(1);
                    assertThat(jdbc.queryForObject("select count(*) from public.event_publication where serialized_event like ?", Long.class, "%" + event.commandId() + "%")).isPositive();
                    var sealed = new ArrayList<String>();
                    for (String table : List.of("evidence_set", "evidence_item", "remediation_submission", "remediation_submission_evidence"))
                        sealed.addAll(jdbc.queryForList("select to_jsonb(r)::text from public." + table + " r where tenant_id=? order by to_jsonb(r)::text", String.class, event.tenantId()));
                    barrier.sealedRows.complete(List.copyOf(sealed));
                    afterCommit(controls, "submit");
                    barrier.pid.complete(jdbc.queryForObject("select pg_backend_pid()", Integer.class));
                    barrier.entered.countDown();
                    await(barrier.release, "release real submit after seal and publication");
                }
            };
        }
    }
}
