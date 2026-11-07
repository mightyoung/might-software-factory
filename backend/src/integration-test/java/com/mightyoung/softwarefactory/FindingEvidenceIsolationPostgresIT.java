package com.mightyoung.softwarefactory;

import com.mightyoung.softwarefactory.files.LocalPrivateFileStorageAdapter;
import org.flowable.engine.RepositoryService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.OffsetDateTime;
import java.security.MessageDigest;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/** Actual HTTPS/PG read isolation with retained preplaced signed evidence; not upload/scanner acceptance. */
@SpringBootTest(classes = SoftwareFactoryApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("lan-postgres-compatibility")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class FindingEvidenceIsolationPostgresIT {
    private static IdentityAcceptanceHttps https;
    private static FindingRemediationPostgresFixture.Storage storage;
    @LocalServerPort private int port;
    @Autowired private PasswordEncoder passwords;
    @Autowired private ObjectMapper json;
    @Autowired private RepositoryService deployments;
    @Autowired private LocalPrivateFileStorageAdapter fileVerifier;
    private FindingRemediationPostgresFixture fixture;
    private final List<IdentityAcceptanceHttps.Browser> browsers = new ArrayList<>();
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

    @BeforeEach
    void seedAndDeploy() {
        fixture = new FindingRemediationPostgresFixture(passwords, storage, fileVerifier);
        deployments.createDeployment().name(fixture.business.run + "-read-isolation")
                .tenantId(fixture.business.tenantId.toString())
                .addClasspathResource("processes/finding-remediation-v1.bpmn20.xml")
                .addClasspathResource("processes/finding-remediation-v2.bpmn20.xml").deploy();
    }

    @AfterEach
    void closeSessions() {
        try { browsers.forEach(IdentityAcceptanceHttps.Browser::close); }
        finally { browsers.clear(); loggedIn.clear(); if (fixture != null) fixture.close(); }
    }

    @AfterAll
    static void closeTls() throws Exception {
        if (https != null) https.close(); // Retain fixture blobs, signed receipts, keys and manifests.
    }

    @Test
    void authorizedReaderDownloadsOnlyTheSealedImmutableVersionWithoutCreatingEvidence() throws Exception {
        Submitted submitted = submitEvidence();
        var client = login(fixture.reporter());
        var before = evidenceState();
        long receipts = receiptCount();
        assertStatus(client.get(detailPath(submitted)), 200);
        var response = client.get(contentPath(submitted));
        assertStatus(response, 200);
        assertThat(response.body().getBytes(StandardCharsets.UTF_8)).isEqualTo(Files.readAllBytes(blob(submitted)));
        assertThat(response.http().headers().firstValue("Content-Type")).contains("application/octet-stream");
        assertThat(response.http().headers().firstValue("Content-Disposition"))
                .contains("attachment; filename=\"evidence.bin\"");
        assertThat(response.http().headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(response.http().headers().firstValue("X-Content-Type-Options")).contains("nosniff");
        assertThat(response.http().headers().firstValueAsLong("Content-Length").orElseThrow())
                .isEqualTo(Files.size(blob(submitted)));
        assertRefusal(client.get(contentPath(submitted).replace(submitted.finding().toString(),
                fixture.business.legacyFindingId.toString())), 404, submitted);
        assertRefusal(client.get(contentPath(submitted).replace("/remediation-rounds/1/", "/remediation-rounds/2/")),
                404, submitted);
        assertUnchanged(before, receipts);
    }

    @Test
    void sameTenantMemberWithoutReadPermissionCannotReadFindingOrSubmittedContent() throws Exception {
        Submitted submitted = submitEvidence();
        // The real submitter has remediation/verify authority but deliberately has no finding.read grant.
        var client = login(fixture.remediator());
        var before = evidenceState();
        long receipts = receiptCount();
        assertRefusal(client.get(detailPath(submitted)), 403, submitted);
        assertRefusal(client.get(contentPath(submitted)), 403, submitted);
        assertUnchanged(before, receipts);
    }

    @Test
    void authorizedReaderInAnotherTenantCannotResolveForeignFindingOrSubmittedContent() throws Exception {
        Submitted submitted = submitEvidence();
        try (var foreign = new BusinessWorkflowPostgresFixture(passwords)) {
            var client = browser();
            login(client, foreign.reporter.loginKey(), foreign.reporter.password(), foreign.tenantCode);
            // Prove this is an authenticated read-authorized user, rather than a missing-permission denial.
            assertStatus(client.get("/api/v1/entities/finding/" + foreign.legacyFindingId), 200);
            var before = evidenceState();
            var foreignBefore = foreign.read(db -> db.queryForObject("select count(*) from public.file_object where tenant_id=?", Long.class, foreign.tenantId));
            long receipts = receiptCount();
            assertRefusal(client.get(detailPath(submitted)), 404, submitted);
            assertRefusal(client.get(contentPath(submitted)), 404, submitted);
            assertUnchanged(before, receipts);
            long foreignAfter = foreign.read(db -> db.queryForObject(
                    "select count(*) from public.file_object where tenant_id=?", Long.class, foreign.tenantId));
            assertThat(foreignAfter).isEqualTo(foreignBefore);
        }
    }

    @Test
    void missingAndTamperedPinnedBytesFailClosedAndReadsDoNotPublishReplacementEvidence() throws Exception {
        Submitted submitted = submitEvidence();
        var client = login(fixture.reporter());
        Path blob = blob(submitted);
        byte[] original = Files.readAllBytes(blob);
        var before = evidenceState();
        long receipts = receiptCount();
        Path unavailable = blob.resolveSibling("fixture-v1.unavailable");
        Path diagnostic = storage.root.resolve(".acceptance-manifests")
                .resolve("read-isolation-" + submitted.evidence().fileId() + ".json");
        Map<String, Object> proof = new LinkedHashMap<>();
        proof.put("purpose", "retained preplaced-byte read refusal; not upload/scanner acceptance");
        proof.put("tenantId", fixture.business.tenantId.toString());
        proof.put("findingId", submitted.finding().toString());
        proof.put("fileId", submitted.evidence().fileId().toString());
        proof.put("originalSha256", digest(original));
        Files.move(blob, unavailable, StandardCopyOption.ATOMIC_MOVE);
        try {
            var response = client.get(contentPath(submitted));
            proof.put("missingStatus", response.status());
            writeDiagnostic(diagnostic, proof);
            assertRefusal(response, 503, submitted);
        } finally { Files.move(unavailable, blob, StandardCopyOption.ATOMIC_MOVE); }
        assertUnchanged(before, receipts);

        byte[] tampered = original.clone();
        tampered[0] ^= 1; // Same length: verification must compare actual bytes, not just metadata/size.
        Path retainedTamper = diagnostic.resolveSibling(diagnostic.getFileName() + ".tampered.bin");
        Files.createFile(retainedTamper, PosixFilePermissions.asFileAttribute(
                PosixFilePermissions.fromString("rw-------")));
        Files.write(retainedTamper, tampered);
        proof.put("tamperedSha256", digest(tampered));
        proof.put("retainedTamperedBytes", storage.root.relativize(retainedTamper).toString());
        try {
            Files.setPosixFilePermissions(blob, PosixFilePermissions.fromString("rw-------"));
            Files.write(blob, tampered, StandardOpenOption.TRUNCATE_EXISTING);
            Files.setPosixFilePermissions(blob, PosixFilePermissions.fromString("r--------"));
            var response = client.get(contentPath(submitted));
            proof.put("tamperedStatus", response.status());
            writeDiagnostic(diagnostic, proof);
            assertRefusal(response, 503, submitted);
            assertUnchanged(before, receipts);
        } finally {
            // Restore the owned fresh blob so retained sealed DB evidence remains replayable.
            // Separate altered bytes, hashes and actual refusal statuses remain in private diagnostics.
            Files.setPosixFilePermissions(blob, PosixFilePermissions.fromString("rw-------"));
            Files.write(blob, original, StandardOpenOption.TRUNCATE_EXISTING);
            Files.setPosixFilePermissions(blob, PosixFilePermissions.fromString("r--------"));
            proof.put("restoredSha256", digest(Files.readAllBytes(blob)));
            writeDiagnostic(diagnostic, proof);
        }
        var restored = client.get(contentPath(submitted));
        assertStatus(restored, 200);
        assertThat(restored.body().getBytes(StandardCharsets.UTF_8)).isEqualTo(original);
        assertUnchanged(before, receipts);
    }

    private Submitted submitEvidence() throws Exception {
        var client = login(fixture.reporter());
        UUID create = UUID.randomUUID();
        fixture.recordCommand(create);
        var created = client.postWithHeaders("/api/v1/findings", json.writeValueAsString(Map.of(
                "organizationId", fixture.business.organizationId,
                "description", "Retained read isolation fixture", "discoveredAt", OffsetDateTime.now().minusHours(1).toString(),
                "grade", "UNKNOWN", "sourceCategory", "FIELD_OBSERVATION", "urgency", "NORMAL", "location", "Fixture area")),
                client.csrf(), Map.of("Idempotency-Key", create.toString()));
        assertStatus(created, 200);
        UUID finding = UUID.fromString(json.readTree(created.body()).path("data").path("findingId").asString());
        command(fixture.person(), finding, "confirm/person", Map.of("expectedVersion", 1, "reason", "Confirmed"));
        command(fixture.safety(), finding, "confirm/safety", Map.of("expectedVersion", 2, "reason", "Confirmed", "grade", "GENERAL"));
        command(fixture.safety(), finding, "dispatch", Map.of("expectedVersion", 3,
                "assigneeMemberId", fixture.remediator().memberId(), "dueAt", OffsetDateTime.now().plusDays(2).toString(), "requirements", "Remediate"));
        var evidence = fixture.evidence(finding, 1, fixture.remediator());
        JsonNode submitted = command(fixture.remediator(), finding, "remediation-submissions", Map.of(
                "expectedVersion", 4, "evidenceSetId", evidence.setId(), "statement", "Retained evidence ready for read isolation"));
        assertThat(submitted.path("state").asString()).isEqualTo("ACCEPTANCE_PENDING");
        assertThat(submitted.path("version").asLong()).isEqualTo(5);
        return new Submitted(finding, evidence);
    }

    private JsonNode command(FindingRemediationPostgresFixture.Member member, UUID finding,
                             String suffix, Map<String, Object> body) throws Exception {
        UUID id = UUID.randomUUID();
        fixture.recordCommand(id);
        var client = login(member);
        var response = client.postWithHeaders("/api/v1/findings/" + finding + "/" + suffix,
                json.writeValueAsString(body), client.csrf(), Map.of("Idempotency-Key", id.toString()));
        assertStatus(response, 200);
        return json.readTree(response.body()).path("data");
    }

    private IdentityAcceptanceHttps.Browser login(FindingRemediationPostgresFixture.Member member) throws Exception {
        var client = loggedIn.get(member.accountId());
        if (client == null) {
            client = browser();
            login(client, member.loginKey(), member.password(), fixture.business.tenantCode);
            loggedIn.put(member.accountId(), client);
        }
        return client;
    }

    private IdentityAcceptanceHttps.Browser browser() {
        var client = https.browser(port);
        browsers.add(client);
        return client;
    }

    private void login(IdentityAcceptanceHttps.Browser client, String key, String password, String tenant) throws Exception {
        assertStatus(client.get("/api/v1/auth/csrf"), 204);
        assertStatus(client.post("/api/v1/auth/login", json.writeValueAsString(Map.of(
                "loginKey", key, "password", password, "tenantCode", tenant)), client.csrf()), 204);
        assertStatus(client.get("/api/v1/auth/csrf"), 204);
    }

    private void assertRefusal(IdentityAcceptanceHttps.Response response, int status, Submitted submitted) throws Exception {
        assertStatus(response, status);
        assertThat(response.http().headers().firstValue("Content-Disposition")).isEmpty();
        assertThat(response.body()).doesNotContain("Explicit B4 fixture evidence", storage.root.toString(),
                submitted.evidence().receiptId().toString(), submitted.evidence().fileId().toString(), "fixture-v1");
        assertThat(json.readTree(response.body()).path("code").asString()).isEqualTo(
                status == 503 ? "DEPENDENCY_UNAVAILABLE" : status == 404 ? "RESOURCE_NOT_VISIBLE" : "ACTION_DENIED");
    }

    private static void assertStatus(IdentityAcceptanceHttps.Response response, int status) {
        assertThat(response.status()).as("Actual HTTPS read status").isEqualTo(status);
    }

    private EvidenceState evidenceState() {
        return fixture.read(db -> new EvidenceState(fixture.counts(),
                db.queryForList("select to_jsonb(f)::text from public.file_object f where tenant_id=? order by id",
                        String.class, fixture.business.tenantId),
                db.queryForList("select to_jsonb(f)::text from public.file_link f where tenant_id=? order by id",
                        String.class, fixture.business.tenantId),
                db.queryForList("select to_jsonb(e)::text from public.evidence_item e where tenant_id=? order by id",
                        String.class, fixture.business.tenantId)));
    }

    private void assertUnchanged(EvidenceState before, long receipts) throws Exception {
        assertThat(evidenceState()).isEqualTo(before);
        assertThat(receiptCount()).isEqualTo(receipts);
    }

    private static String digest(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private void writeDiagnostic(Path path, Map<String, Object> proof) throws Exception {
        if (!Files.exists(path)) Files.createFile(path, PosixFilePermissions.asFileAttribute(
                PosixFilePermissions.fromString("rw-------")));
        Files.writeString(path, json.writeValueAsString(proof));
    }

    record EvidenceState(Map<String, Long> counts, List<String> files, List<String> links, List<String> items) { }

    private long receiptCount() throws Exception {
        try (var receipts = Files.list(storage.root.resolve(".receipt-registry"))) { return receipts.count(); }
    }

    private Path blob(Submitted submitted) {
        return storage.root.resolve(fixture.business.tenantId.toString())
                .resolve(submitted.evidence().fileId().toString()).resolve("fixture-v1.blob");
    }

    private static String detailPath(Submitted submitted) { return "/api/v1/entities/finding/" + submitted.finding(); }
    private static String contentPath(Submitted submitted) {
        return "/api/v1/findings/" + submitted.finding() + "/remediation-rounds/1/evidence-items/"
                + submitted.evidence().itemId() + "/content";
    }

    record Submitted(UUID finding, FindingRemediationPostgresFixture.Evidence evidence) { }
}
