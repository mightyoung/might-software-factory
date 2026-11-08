package com.mightyoung.softwarefactory;

import com.mightyoung.softwarefactory.authorization.PostgresAuthorizationWriteGuard;
import com.mightyoung.softwarefactory.dualprevention.finding.evidenceupload.*;
import com.mightyoung.softwarefactory.files.UploadProducerReadiness;
import com.mightyoung.softwarefactory.identity.SessionContextResolver;
import com.mightyoung.softwarefactory.workflow.WorkflowFacade;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.TaskService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static com.mightyoung.softwarefactory.dualprevention.finding.evidenceupload.FindingEvidenceUploadContracts.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real PG durable-core proof with test-only producer capability; no bytes/scanner/HTTP acceptance. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("lan-postgres-compatibility")
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class FindingEvidenceUploadIntentPostgresIT {
    @Autowired PasswordEncoder passwords;
    @Autowired FindingEvidenceUploadRepository writes;
    @Autowired FindingEvidenceUploadReadRepository reads;
    @Autowired FindingEvidenceUploadAccessPolicy access;
    @Autowired WorkflowFacade workflow;
    @Autowired RepositoryService definitions;
    @Autowired TaskService tasks;
    @Autowired PlatformTransactionManager manager;
    @Autowired org.jooq.DSLContext dsl;
    @Autowired javax.sql.DataSource dataSource;
    @Autowired JdbcTemplate jdbc;
    @Autowired PostgresAuthorizationWriteGuard guard;
    FindingEvidenceUploadIntentPostgresFixture fixture;
    FindingEvidenceUploadService service;
    UploadProducerReadiness producer;
    UploadProducerReadiness.Capability capability;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
        var target=FindingEvidenceUploadIntentPostgresFixture.target();
        p.add("spring.datasource.url",target::jdbcUrl);p.add("spring.datasource.username",()->System.getenv("LAN_PG_APP_USERNAME"));p.add("spring.datasource.password",()->System.getenv("LAN_PG_APP_PASSWORD"));
        p.add("spring.datasource.hikari.maximum-pool-size",()->4);p.add("spring.datasource.hikari.minimum-idle",()->0);
        p.add("spring.flyway.enabled",()->false);p.add("spring.session.jdbc.initialize-schema",()->"never");p.add("spring.session.jdbc.cleanup-cron",()->"-");
        p.add("spring.modulith.events.jdbc.schema-initialization.enabled",()->false);p.add("flowable.database-schema-update",()->false);p.add("flowable.check-process-definitions",()->false);p.add("flowable.async-executor-activate",()->false);p.add("flowable.async-history-executor-activate",()->false);
    }
    @BeforeEach void seed() {
        fixture=new FindingEvidenceUploadIntentPostgresFixture(passwords);
        var sessions=mock(SessionContextResolver.class);when(sessions.currentTenant()).thenReturn(fixture.owner());
        producer=mock(UploadProducerReadiness.class);
        capability=new UploadProducerReadiness.Capability("test-only-config-v1","test-only-policy-v1","test-only-scanner-v1",Set.of("text/plain"),1024,10,2,2048,2,2048,Duration.ofMinutes(5),Duration.ofSeconds(10),1);
        when(producer.requireReady()).thenReturn(capability);when(producer.isCurrent(capability)).thenReturn(true);
        service=new FindingEvidenceUploadService(sessions,producer,writes,reads,access);
        String xml="""
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:flowable="http://flowable.org/bpmn" targetNamespace="urn:g021:test">
            <process id="g021UploadFixture" isExecutable="true"><startEvent id="start"/><sequenceFlow id="toTask" sourceRef="start" targetRef="remediation"/>
            <userTask id="remediation" flowable:assignee="${owner}"/><sequenceFlow id="toEnd" sourceRef="remediation" targetRef="end"/><endEvent id="end"/></process></definitions>
            """;
        definitions.createDeployment().tenantId(fixture.business.tenantId.toString()).addString("g021-upload-fixture.bpmn20.xml",xml).deploy();
        command().executeWithoutResult(s->{tenant();guard.forFindingCommand(fixture.business.tenantId);workflow.startProcess(fixture.business.tenantId,"FINDING",fixture.business.legacyFindingId,1,"g021UploadFixture",Map.of("owner",fixture.owner().memberId().toString()));});
    }
    @AfterEach void close(){if(fixture!=null)fixture.close();}
    ReserveCommand command(UUID key) {return new ReserveCommand(key,fixture.business.legacyFindingId,1,fixture.setId,1,0,"proof.txt","text/plain",64);}
    TransactionTemplate command(){var tx=new TransactionTemplate(manager);tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);tx.setTimeout(5);return tx;}
    void tenant(){jdbc.queryForObject("select set_config('app.tenant_id',?,true)",String.class,fixture.business.tenantId.toString());}
    @Test void durableReplayAndChangedBodyConflictKeepOneIntentAndAudit() {
        var c=command(UUID.randomUUID());long audit=fixture.count("audit_event");
        var first=service.reserve(c);assertThat(first.replayed()).isFalse();
        assertThat(service.reserve(c)).isEqualTo(new Reservation(first.status(),true));
        assertThat(service.status(c.findingId(),1,first.status().intentId())).isEqualTo(first.status());
        var changed=new ReserveCommand(c.idempotencyKey(),c.findingId(),1,c.evidenceSetId(),1,0,"changed.txt","text/plain",64);
        assertThatThrownBy(()->service.reserve(changed)).isInstanceOf(FindingEvidenceUploadException.class);
        assertThat(fixture.count("finding_evidence_upload_intent")).isEqualTo(1);assertThat(fixture.count("audit_event")).isEqualTo(audit+1);
        assertThat(fixture.count("file_object")).isZero();assertThat(fixture.count("evidence_item")).isZero();
    }
    @Test void forcedRlsAndIntrinsicOwnershipRejectDirectSqlBypasses() {
        var saved=service.reserve(command(UUID.randomUUID()));
        command().executeWithoutResult(s->{
            jdbc.queryForObject("select set_config('app.tenant_id',?,true)",String.class,UUID.randomUUID().toString());
            assertThat(jdbc.queryForObject("select count(*) from public.finding_evidence_upload_intent where id=?",Long.class,saved.status().intentId())).isZero();
        });
        assertSqlState("42501",()->command().executeWithoutResult(s->{tenant();jdbc.update("update public.finding_evidence_upload_intent set filename='changed' where tenant_id=?",fixture.business.tenantId);}));
        // Owner has normal invoker privileges and FORCE RLS: the immutable trigger still fires.
        assertSqlState("23514",()->fixture.write(db->db.update("update public.finding_evidence_upload_intent set filename='changed' where tenant_id=?",fixture.business.tenantId)));
        String columns="tenant_id,id,owner_account_id,owner_actor_id,owner_member_id,operation,idempotency_key,fingerprint_version,payload_sha256,finding_id,round_no,notice_id,evidence_set_id,expected_finding_version,expected_evidence_revision,filename,declared_media_type,declared_size_bytes,producer_configuration_version,upload_policy_version,state,created_at,expires_at";
        String tail=",owner_member_id,operation,cast(? as uuid),fingerprint_version,payload_sha256,finding_id,round_no,notice_id,evidence_set_id,expected_finding_version,expected_evidence_revision,filename,declared_media_type,declared_size_bytes,producer_configuration_version,upload_policy_version,state,created_at,expires_at from public.finding_evidence_upload_intent where tenant_id=? and id=?";
        assertSqlState("23514",()->command().executeWithoutResult(s->{tenant();jdbc.update("insert into public.finding_evidence_upload_intent("+columns+") select tenant_id,cast(? as uuid),cast(? as uuid),owner_actor_id"+tail,UUID.randomUUID(),fixture.business.reporter.accountId(),UUID.randomUUID(),fixture.business.tenantId,saved.status().intentId());}));
        assertThat(fixture.count("finding_evidence_upload_intent")).isEqualTo(1);
    }
    @Test void directIdentityNoticeAndSetSplicesCannotCreateReservation() {
        var saved=service.reserve(command(UUID.randomUUID()));
        for (var splice:Map.of("owner_account_id",fixture.business.reporter.accountId(),
                "owner_actor_id",fixture.business.reporter.principalId(),"owner_member_id",fixture.business.reporter.memberId(),
                "notice_id",UUID.randomUUID(),"evidence_set_id",UUID.randomUUID()).entrySet()) {
            assertSqlState("23514",()->copyIntent(saved.status().intentId(),splice.getKey(),splice.getValue()));
        }
        assertThat(fixture.count("finding_evidence_upload_intent")).isEqualTo(1);
        fixture.write(db->{
            var foreignKeys=db.queryForList("select pg_get_constraintdef(oid) from pg_constraint where conrelid='public.finding_evidence_upload_intent'::regclass and contype='f'",String.class);
            assertThat(foreignKeys).anyMatch(def->def.contains("evidence_set_id, finding_id, round_no, owner_member_id") && def.contains("REFERENCES evidence_set"));
            assertThat(foreignKeys).anyMatch(def->def.contains("notice_id, finding_id, round_no") && def.contains("REFERENCES remediation_notice"));
            return null;
        });
    }
    void copyIntent(UUID original,String field,UUID replacement) {
        // Field comes only from the fixed test map above, never a request or arbitrary identifier.
        String[] columns={"tenant_id","id","owner_account_id","owner_actor_id","owner_member_id","operation","idempotency_key","fingerprint_version","payload_sha256","finding_id","round_no","notice_id","evidence_set_id","expected_finding_version","expected_evidence_revision","filename","declared_media_type","declared_size_bytes","producer_configuration_version","upload_policy_version","state","created_at","expires_at"};
        List<Object> values=new ArrayList<>();List<String> selected=new ArrayList<>();
        for(String column:columns) {
            if(column.equals("id")||column.equals("idempotency_key")||column.equals(field)) {selected.add("cast(? as uuid)");values.add(column.equals(field)?replacement:UUID.randomUUID());}
            else selected.add(column);
        }
        values.add(fixture.business.tenantId);values.add(original);
        command().executeWithoutResult(s->{tenant();jdbc.update("insert into public.finding_evidence_upload_intent("+String.join(",",columns)+") select "+String.join(",",selected)+" from public.finding_evidence_upload_intent where tenant_id=? and id=?",values.toArray());});
    }
    @Test void currentAuthorityStillPrecedesReplayAndVersionChecksOnlyApplyToNewIntent() {
        var c=command(UUID.randomUUID());var saved=service.reserve(c);
        fixture.write(db->db.update("update public.finding set version=version+1 where tenant_id=? and id=?",fixture.business.tenantId,c.findingId()));
        assertThat(service.reserve(c).status()).isEqualTo(saved.status());
        assertThatThrownBy(()->service.reserve(command(UUID.randomUUID()))).isInstanceOf(FindingEvidenceUploadException.class);
        fixture.write(db->db.update("update public.account set status='DISABLED' where id=?",fixture.owner().accountId()));
        assertThatThrownBy(()->service.reserve(c)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->service.status(c.findingId(),1,saved.status().intentId())).isInstanceOf(RuntimeException.class);
        assertThat(fixture.count("finding_evidence_upload_intent")).isEqualTo(1);
    }
    @Test void missingAndForeignTenantCannotInsertEvenWithValidOwnedValues() {
        var saved=service.reserve(command(UUID.randomUUID()));
        var values=fixture.write(db->db.queryForMap("select * from public.finding_evidence_upload_intent where tenant_id=? and id=?",fixture.business.tenantId,saved.status().intentId()));
        for(String context:List.of("",UUID.randomUUID().toString())) {
            values.put("id",UUID.randomUUID());values.put("idempotency_key",UUID.randomUUID());
            var columns=new ArrayList<>(values.keySet());
            Throwable failure=catchThrowable(()->command().executeWithoutResult(status->{
                jdbc.queryForObject("select set_config('app.tenant_id',?,true)",String.class,context);
                jdbc.update("insert into public.finding_evidence_upload_intent("+String.join(",",columns)+") values ("+String.join(",",Collections.nCopies(columns.size(),"?"))+")",columns.stream().map(values::get).toArray());
            }));
            assertThat(failure).isNotNull(); // Invoker ownership trigger may reject before RLS WITH CHECK.
            assertThat(sqlState(failure)).isIn("23514","42501");
        }
        assertThat(fixture.count("finding_evidence_upload_intent")).isEqualTo(1);
    }
    @Test void nonHumanPrincipalCannotBeSplicedIntoHistoricalOwner() {
        var saved=service.reserve(command(UUID.randomUUID()));UUID actor=UUID.randomUUID();
        fixture.write(db->db.update("insert into public.principal(id,kind,account_id,status) values (?,'SERVICE',null,'ACTIVE')",actor));
        assertSqlState("23514",()->copyIntent(saved.status().intentId(),"owner_actor_id",actor));
    }
    @Test void expiredFixtureReservationReplaysStableIdentityAndDoesNotConsumeActiveQuota() {
        var first=service.reserve(command(UUID.randomUUID()));UUID key=UUID.randomUUID();UUID expired=UUID.randomUUID();
        // Retained synthetic historical reservation; no sleep or mutation of an immutable committed row.
        fixture.write(db->{
            var row=db.queryForMap("select * from public.finding_evidence_upload_intent where tenant_id=? and id=?",fixture.business.tenantId,first.status().intentId());
            row.put("id",expired);row.put("idempotency_key",key);
            row.put("created_at",java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(600)));
            row.put("expires_at",java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(300)));
            var columns=new ArrayList<>(row.keySet());
            return db.update("insert into public.finding_evidence_upload_intent("+String.join(",",columns)+") values ("+String.join(",",Collections.nCopies(columns.size(),"?"))+")",columns.stream().map(row::get).toArray());
        });
        var replay=service.reserve(command(key));assertThat(replay.replayed()).isTrue();
        assertThat(replay.status().intentId()).isEqualTo(expired);assertThat(replay.status().state()).isEqualTo(State.EXPIRED);
        assertThat(service.status(fixture.business.legacyFindingId,1,expired)).isEqualTo(replay.status());
        assertThat(service.reserve(command(UUID.randomUUID())).replayed()).isFalse();
        var failed=catchThrowable(()->service.reserve(command(UUID.randomUUID())));
        assertThat(failed).isInstanceOf(FindingEvidenceUploadException.class);
        assertThat(((FindingEvidenceUploadException)failed).reason()).isEqualTo(FindingEvidenceUploadException.Reason.QUOTA_EXCEEDED);
        assertThat(fixture.count("finding_evidence_upload_intent")).isEqualTo(3);
    }
    @Test void revisionDriftAllowsExactReplayButRejectsNewStaleRevision() {
        var c=command(UUID.randomUUID());var first=service.reserve(c);
        fixture.write(db->db.update("update public.evidence_set set evidence_revision=evidence_revision+1 where tenant_id=? and id=?",fixture.business.tenantId,fixture.setId));
        assertThat(service.reserve(c).status()).isEqualTo(first.status());
        assertThatThrownBy(()->service.reserve(command(UUID.randomUUID()))).isInstanceOf(FindingEvidenceUploadException.class);
    }
    @Test void currentCredentialAndTenantAndMemberEpochsRevokeReplayAndStatus() {
        var c=command(UUID.randomUUID());var first=service.reserve(c);
        for(String field:List.of("credential","tenant","member")) {
            fixture.write(db->{
                if(field.equals("credential"))return db.update("update public.account set credential_version=credential_version+1 where id=?",fixture.owner().accountId());
                if(field.equals("tenant"))return db.update("update public.tenant set auth_epoch=auth_epoch+1 where id=?",fixture.business.tenantId);
                return db.update("update public.tenant_member set auth_epoch=auth_epoch+1 where tenant_id=? and id=?",fixture.business.tenantId,fixture.owner().memberId());
            });
            assertThatThrownBy(()->service.reserve(c)).isInstanceOf(RuntimeException.class);
            assertThatThrownBy(()->service.status(c.findingId(),1,first.status().intentId())).isInstanceOf(RuntimeException.class);
            // Restore only this synthetic fixture generation so each denial is isolated.
            fixture.write(db->{
                db.update("update public.account set credential_version=1 where id=?",fixture.owner().accountId());
                db.update("update public.tenant set auth_epoch=1 where id=?",fixture.business.tenantId);
                return db.update("update public.tenant_member set auth_epoch=1 where tenant_id=? and id=?",fixture.business.tenantId,fixture.owner().memberId());
            });
            assertThat(service.reserve(c).replayed()).isTrue();
        }
    }
    static String sqlState(Throwable failure) {
        while(failure!=null && !(failure instanceof java.sql.SQLException))failure=failure.getCause();
        assertThat(failure).isInstanceOf(java.sql.SQLException.class);return ((java.sql.SQLException)failure).getSQLState();
    }
    static void assertSqlState(String expected,Runnable work) {
        Throwable failure=catchThrowable(work::run);assertThat(failure).isNotNull();
        while (failure!=null && !(failure instanceof java.sql.SQLException)) failure=failure.getCause();
        assertThat(failure).isInstanceOf(java.sql.SQLException.class);
        assertThat(((java.sql.SQLException)failure).getSQLState()).isEqualTo(expected);
    }
    @Test void injectedLostCommitAcknowledgmentThrowsThenFreshTransactionRecoversUniqueIntentAndAudit() {
        var thrown=new java.util.concurrent.atomic.AtomicBoolean();
        PlatformTransactionManager lostAcknowledgment=new PlatformTransactionManager() {
            @Override public org.springframework.transaction.TransactionStatus getTransaction(TransactionDefinition definition) {
                return manager.getTransaction(definition);
            }
            @Override public void commit(org.springframework.transaction.TransactionStatus status) {
                manager.commit(status); // Real PG commit completes before the injected driver-boundary failure.
                if (thrown.compareAndSet(false,true)) throw new org.springframework.transaction.TransactionSystemException("Test-only lost commit acknowledgment");
            }
            @Override public void rollback(org.springframework.transaction.TransactionStatus status) {manager.rollback(status);}
        };
        var faultedWrites=new JooqFindingEvidenceUploadRepository(dsl,lostAcknowledgment,guard,dataSource);
        var sessions=mock(SessionContextResolver.class);when(sessions.currentTenant()).thenReturn(fixture.owner());
        var faultedService=new FindingEvidenceUploadService(sessions,producer,faultedWrites,reads,access);
        var c=command(UUID.randomUUID());long beforeAudit=fixture.count("audit_event");
        assertThatThrownBy(()->faultedService.reserve(c)).isInstanceOf(org.springframework.transaction.TransactionSystemException.class);
        assertThat(thrown).isTrue();
        // These are independent transactions/connections after cleanup, not the failed call's callback.
        assertThat(fixture.count("finding_evidence_upload_intent")).isEqualTo(1);
        assertThat(fixture.count("audit_event")).isEqualTo(beforeAudit+1);
        var recovered=service.reserve(c);assertThat(recovered.replayed()).isTrue();
        assertThat(service.reserve(c)).isEqualTo(recovered);
        assertThat(service.status(c.findingId(),1,recovered.status().intentId())).isEqualTo(recovered.status());
        assertThat(fixture.count("finding_evidence_upload_intent")).isEqualTo(1);
        assertThat(fixture.count("audit_event")).isEqualTo(beforeAudit+1);
    }
    @Test void failureAfterRealInsertAndSuccessAuditRollsBothBack() {
        long audit=fixture.count("audit_event");when(producer.isCurrent(capability)).thenAnswer(call->{
            assertThat(jdbc.queryForObject("select count(*) from public.finding_evidence_upload_intent where tenant_id=?",Long.class,fixture.business.tenantId)).isEqualTo(1);
            assertThat(jdbc.queryForObject("select count(*) from public.audit_event where tenant_id=?",Long.class,fixture.business.tenantId)).isEqualTo(audit+1);
            return false;
        });
        assertThatThrownBy(()->service.reserve(command(UUID.randomUUID()))).isInstanceOf(RuntimeException.class);
        assertThat(fixture.count("finding_evidence_upload_intent")).isZero();assertThat(fixture.count("audit_event")).isEqualTo(audit);
    }
    @Test void concurrentSameKeyAndQuotaAreSerializedOnPhysicalConnections() throws Exception {
        var c=command(UUID.randomUUID());ExecutorService executor=Executors.newFixedThreadPool(3);
        try {
            CountDownLatch start=new CountDownLatch(1);
            var first=executor.submit(()->{start.await();return service.reserve(c);});var replay=executor.submit(()->{start.await();return service.reserve(c);});start.countDown();
            var a=first.get(10,TimeUnit.SECONDS);var b=replay.get(10,TimeUnit.SECONDS);
            assertThat(a.status()).isEqualTo(b.status());assertThat(a.replayed()).isNotEqualTo(b.replayed());
            var x=executor.submit(()->attempt(command(UUID.randomUUID())));var y=executor.submit(()->attempt(command(UUID.randomUUID())));
            assertThat(List.of(x.get(10,TimeUnit.SECONDS),y.get(10,TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);
            assertThat(fixture.count("finding_evidence_upload_intent")).isEqualTo(2);
        } finally {executor.shutdownNow();assertThat(executor.awaitTermination(10,TimeUnit.SECONDS)).isTrue();}
    }
    boolean attempt(ReserveCommand c){try{service.reserve(c);return true;}catch(FindingEvidenceUploadException expected){assertThat(expected.reason()).isEqualTo(FindingEvidenceUploadException.Reason.QUOTA_EXCEEDED);return false;}}
    @Test void repeatableReadFacadeUsesOuterSnapshotAcrossCommittedAssignmentOnSecondConnection() throws Exception {
        var rr=new TransactionTemplate(manager);rr.setReadOnly(true);rr.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);rr.setTimeout(10);
        ExecutorService executor=Executors.newSingleThreadExecutor();
        try {
            rr.executeWithoutResult(s->{
                tenant();int outer=jdbc.queryForObject("select pg_backend_pid()",Integer.class);
                var before=workflow.readCurrentTask(fixture.business.tenantId,"FINDING",fixture.business.legacyFindingId,"remediation");
                try {
                    int second=executor.submit(()->command().execute(inner->{tenant();int pid=jdbc.queryForObject("select pg_backend_pid()",Integer.class);tasks.setAssignee(before.taskId(),fixture.business.alternatePerson.memberId().toString());return pid;})).get(5,TimeUnit.SECONDS);
                    assertThat(second).isNotEqualTo(outer);
                }catch(Exception failure){throw new AssertionError(failure);}
                assertThat(workflow.readCurrentTask(fixture.business.tenantId,"FINDING",fixture.business.legacyFindingId,"remediation").assignee()).isEqualTo(before.assignee());
            });
            rr.executeWithoutResult(s->{tenant();assertThat(workflow.readCurrentTask(fixture.business.tenantId,"FINDING",fixture.business.legacyFindingId,"remediation").assignee()).isEqualTo(fixture.business.alternatePerson.memberId().toString());});
            assertThatThrownBy(()->service.reserve(command(UUID.randomUUID()))).isInstanceOf(RuntimeException.class);
            assertThat(fixture.count("finding_evidence_upload_intent")).isZero();
        }finally{executor.shutdownNow();assertThat(executor.awaitTermination(10,TimeUnit.SECONDS)).isTrue();}
    }
}
