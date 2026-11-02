package com.mightyoung.softwarefactory;

import com.mightyoung.softwarefactory.dualprevention.ModulithFindingWorkflowEventPublisher;
import com.mightyoung.softwarefactory.dualprevention.finding.workflow.FindingWorkflowEventPublisher;
import com.mightyoung.softwarefactory.files.LocalPrivateFileStorageAdapter;
import org.flowable.engine.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.parallel.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import tools.jackson.databind.*;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.assertThat;

/** Actual HTTP, PostgreSQL, Flowable and signed local-file receipts. Fixture files/rows remain for reinspection. */
@SpringBootTest(classes=SoftwareFactoryApplication.class,webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(FindingRemediationPostgresIT.FaultConfiguration.class)
@ActiveProfiles("lan-postgres-compatibility")
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class FindingRemediationPostgresIT {
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
    @Autowired private Fault fault;
    private FindingRemediationPostgresFixture fixture;
    private final Map<UUID,IdentityAcceptanceHttps.Browser> loggedIn=new HashMap<>();
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
    @BeforeEach void seedAndDeploy(){fault.command.set(null);fault.proof.set(null);fixture=new FindingRemediationPostgresFixture(passwords,storage,fileVerifier);deployments.createDeployment().name(fixture.business.run+"-remediation").tenantId(fixture.business.tenantId.toString()).addClasspathResource("processes/finding-remediation-v1.bpmn20.xml").deploy();}
    @AfterEach void closeSessions(){try{loggedIn.values().forEach(IdentityAcceptanceHttps.Browser::close);}finally{loggedIn.clear();if(fixture!=null)fixture.close();}}
    @AfterAll static void closeTls()throws Exception{if(https!=null)https.close(); /* Storage, receipts, HMAC key and manifests intentionally retained. */}

    @Test void twoRealRemediationRoundsRejectReassignAndFinallyCloseWithPinnedEvidence()throws Exception {
        UUID finding=dispatchFirstRound();
        var first=fixture.evidence(finding,1,fixture.remediator());
        var submitted=command(fixture.remediator(),finding,"remediation-submissions",Map.of("expectedVersion",4,"evidenceSetId",first.setId(),"statement","First actual fixture remediation"));
        assertResult(submitted,"ACCEPTANCE_PENDING",1,5);assertTask(finding,"safetyVerification",fixture.safety().memberId());assertSealed(first);
        var rejected=command(fixture.safety(),finding,"verifications",Map.of("expectedVersion",5,"decision","FAIL","reason","Synthetic gap remains"));
        assertResult(rejected,"RECTIFYING",2,6);assertTask(finding,"safetyDispatch",fixture.safety().memberId());
        var redispatched=command(fixture.safety(),finding,"dispatch",Map.of("expectedVersion",6,"assigneeMemberId",fixture.replacement.memberId(),"dueAt",OffsetDateTime.now().plusDays(2).toString(),"requirements","Reassigned second remediation"));
        assertResult(redispatched,"RECTIFYING",2,7);assertTask(finding,"remediation",fixture.replacement.memberId());
        var second=fixture.evidence(finding,2,fixture.replacement);
        assertResult(command(fixture.replacement,finding,"remediation-submissions",Map.of("expectedVersion",7,"evidenceSetId",second.setId(),"statement","Gap corrected in second round")),"ACCEPTANCE_PENDING",2,8);assertSealed(second);
        assertResult(command(fixture.safety(),finding,"verifications",Map.of("expectedVersion",8,"decision","PASS")),"CLOSED",2,9);
        assertThat(runtime.createProcessInstanceQuery().processInstanceId(instance(finding)).count()).isZero();assertThat(tasks.createTaskQuery().processInstanceId(instance(finding)).count()).isZero();assertThat(history.createHistoricProcessInstanceQuery().processInstanceId(instance(finding)).finished().count()).isEqualTo(1);
        fixture.read(db->{assertThat(db.queryForList("select state from public.remediation_round where tenant_id=? and finding_id=? order by round_no",String.class,tenant(),finding)).containsExactly("FAILED","CLOSED");assertThat(db.queryForList("select state from public.remediation_notice where tenant_id=? and finding_id=? order by round_no",String.class,tenant(),finding)).containsExactly("NEEDS_REWORK","VERIFIED");return null;});
        State closed=state(finding);assertThat(closed.state()).isEqualTo("CLOSED");assertThat(closed.version()).isEqualTo(9);assertThat(closed.round()).isEqualTo(2);
        for(String table:List.of("finding_command_receipt","finding_transition","audit_event"))assertThat(closed.counts().get(table)).isEqualTo(9);
        assertThat(fixture.counts().get("verification")).isEqualTo(2);assertThat(fixture.counts().get("remediation_submission")).isEqualTo(2);assertThat(fixture.counts().get("manifestItems")).isEqualTo(2);
    }

    @Test void legitimateVerifyScopeCannotSelfVerifyAndCurrentAssigneeCannotReuseAnOldRoundSet()throws Exception {
        UUID finding=dispatchFirstRound();var first=fixture.evidence(finding,1,fixture.remediator());
        // Same finding and current replacement owner: this additional valid set will later be wrong only by round.
        var oldRoundForReplacement=fixture.evidence(finding,1,fixture.replacement);
        command(fixture.remediator(),finding,"remediation-submissions",Map.of("expectedVersion",4,"evidenceSetId",first.setId(),"statement","First submission"));
        State submitted=state(finding);
        expect(post(fixture.remediator(),finding,"verifications",UUID.randomUUID(),Map.of("expectedVersion",5,"decision","PASS")),403);assertThat(state(finding)).isEqualTo(submitted);
        command(fixture.safety(),finding,"verifications",Map.of("expectedVersion",5,"decision","FAIL","reason","Rework required"));
        command(fixture.safety(),finding,"dispatch",Map.of("expectedVersion",6,"assigneeMemberId",fixture.replacement.memberId(),"dueAt",OffsetDateTime.now().plusDays(2).toString(),"requirements","Second round"));
        State current=state(finding);
        expect(post(fixture.replacement,finding,"remediation-submissions",UUID.randomUUID(),Map.of("expectedVersion",7,"evidenceSetId",oldRoundForReplacement.setId(),"statement","Wrong round evidence")),422);assertThat(state(finding)).isEqualTo(current);
        expect(post(fixture.safety(),finding,"verifications",UUID.randomUUID(),Map.of("expectedVersion",5,"decision","PASS")),409);assertThat(state(finding)).isEqualTo(current);
        assertTask(finding,"remediation",fixture.replacement.memberId());
    }

    @Test void submitPublicationFailureRollsBackSnapshotSealsAndEngineThenSameKeyRetries()throws Exception {
        UUID finding=dispatchFirstRound();var evidence=fixture.evidence(finding,1,fixture.remediator());
        faultThenRetry(fixture.remediator(),finding,"remediation-submissions",Map.of("expectedVersion",4,"evidenceSetId",evidence.setId(),"statement","Late failing submission"),"ACCEPTANCE_PENDING",1,5,true);
        assertSealed(evidence);assertTask(finding,"safetyVerification",fixture.safety().memberId());
    }
    @Test void failedVerificationPublicationFailureRollsBackNextRoundAndNoticeThenSameKeyRetries()throws Exception {
        UUID finding=acceptancePending();
        faultThenRetry(fixture.safety(),finding,"verifications",Map.of("expectedVersion",5,"decision","FAIL","reason","Actual rework late failure"),"RECTIFYING",2,6,true);
        assertTask(finding,"safetyDispatch",fixture.safety().memberId());assertThat(fixture.counts().get("remediation_round")).isEqualTo(2);
    }
    @Test void passedVerificationPublicationFailureRestoresRuntimeTaskAndHistoryThenSameKeyClosesOnce()throws Exception {
        UUID finding=acceptancePending();
        faultThenRetry(fixture.safety(),finding,"verifications",Map.of("expectedVersion",5,"decision","PASS"),"CLOSED",1,6,false);
        assertThat(runtime.createProcessInstanceQuery().processInstanceId(instance(finding)).count()).isZero();assertThat(history.createHistoricProcessInstanceQuery().processInstanceId(instance(finding)).finished().count()).isEqualTo(1);
    }

    private void faultThenRetry(FindingRemediationPostgresFixture.Member actor,UUID finding,String suffix,Map<String,Object> body,String expectedState,int round,long version,boolean processStillActive)throws Exception {
        State before=state(finding);
        assertThat(before.rounds()).containsExactly(new LifecycleRow(finding,1,"OPEN"));
        assertThat(before.notices()).hasSize(1);
        LifecycleRow notice=before.notices().getFirst();
        assertThat(notice.roundNo()).isEqualTo(1);assertThat(notice.state()).isEqualTo("ISSUED");
        UUID command=UUID.randomUUID();fault.command.set(command);
        expect(post(actor,finding,suffix,command,body),503);
        Proof written=fault.proof.get();assertThat(written).isNotNull();assertThat(written.state()).isEqualTo(expectedState);assertThat(written.version()).isEqualTo(version);assertThat(written.completedReceipt()).isEqualTo(1);assertThat(written.publications()).isPositive();assertThat(written.audit()).isEqualTo(1);assertThat(written.activeProcess()).isEqualTo(processStillActive?1:0);
        if(suffix.equals("remediation-submissions")) {
            assertThat(written.rounds()).containsExactly(new LifecycleRow(finding,1,"OPEN"));
            assertThat(written.notices()).containsExactly(notice);
            for(String participant:List.of("remediation_submission","remediation_submission_evidence","sealedSets"))assertThat(written.rows().get(participant)).isEqualTo(before.counts().get(participant)+1);
        } else {
            if(expectedState.equals("RECTIFYING")) {
                assertThat(written.rounds()).containsExactly(new LifecycleRow(finding,1,"FAILED"),new LifecycleRow(finding,2,"OPEN"));
                assertThat(written.notices()).containsExactly(new LifecycleRow(notice.id(),1,"NEEDS_REWORK"));
            } else {
                assertThat(written.rounds()).containsExactly(new LifecycleRow(finding,1,"CLOSED"));
                assertThat(written.notices()).containsExactly(new LifecycleRow(notice.id(),1,"VERIFIED"));
            }
            assertThat(written.rows().get("verification")).isEqualTo(before.counts().get("verification")+1);
            assertThat(written.rows().get("remediation_round")).isEqualTo(before.counts().get("remediation_round")+(expectedState.equals("RECTIFYING")?1:0));
        }
        assertThat(state(finding)).isEqualTo(before);
        assertResult(result(post(actor,finding,suffix,command,body),200),expectedState,round,version);
        assertCommand(command,finding,version);State retried=state(finding);
        assertThat(retried.rounds()).isEqualTo(written.rounds());assertThat(retried.notices()).isEqualTo(written.notices());
        assertResult(result(post(actor,finding,suffix,command,body),200),expectedState,round,version);assertThat(state(finding)).isEqualTo(retried);
    }
    private UUID acceptancePending()throws Exception {UUID finding=dispatchFirstRound();var evidence=fixture.evidence(finding,1,fixture.remediator());command(fixture.remediator(),finding,"remediation-submissions",Map.of("expectedVersion",4,"evidenceSetId",evidence.setId(),"statement","Awaiting actual verification"));return finding;}
    private UUID dispatchFirstRound()throws Exception {
        UUID create=UUID.randomUUID();var response=login(fixture.reporter()).postWithHeaders("/api/v1/findings",json.writeValueAsString(Map.of("organizationId",fixture.business.organizationId,"description","B4 explicit fixture finding","discoveredAt",OffsetDateTime.now().minusHours(1).toString(),"grade","UNKNOWN","sourceCategory","FIELD_OBSERVATION","urgency","NORMAL","location","Fixture work area")),login(fixture.reporter()).csrf(),Map.of("Idempotency-Key",create.toString()));fixture.recordCommand(create);
        JsonNode created=result(response,200);UUID finding=UUID.fromString(created.path("findingId").asString());assertCommand(create,finding,1);
        command(fixture.person(),finding,"confirm/person",Map.of("expectedVersion",1,"reason","Personal confirmation"));command(fixture.safety(),finding,"confirm/safety",Map.of("expectedVersion",2,"reason","Safety confirmation","grade","GENERAL"));
        command(fixture.safety(),finding,"dispatch",Map.of("expectedVersion",3,"assigneeMemberId",fixture.remediator().memberId(),"dueAt",OffsetDateTime.now().plusDays(2).toString(),"requirements","First remediation"));assertTask(finding,"remediation",fixture.remediator().memberId());return finding;
    }
    private JsonNode command(FindingRemediationPostgresFixture.Member actor,UUID finding,String suffix,Map<String,Object> body)throws Exception {UUID id=UUID.randomUUID();JsonNode result=result(post(actor,finding,suffix,id,body),200);assertCommand(id,finding,result.path("version").asLong());State after=state(finding);assertThat(result(post(actor,finding,suffix,id,body),200)).isEqualTo(result);assertThat(state(finding)).isEqualTo(after);return result;}
    private IdentityAcceptanceHttps.Response post(FindingRemediationPostgresFixture.Member actor,UUID finding,String suffix,UUID command,Map<String,Object> body)throws Exception {fixture.recordCommand(command);var client=login(actor);return client.postWithHeaders("/api/v1/findings/"+finding+"/"+suffix,json.writeValueAsString(body),client.csrf(),Map.of("Idempotency-Key",command.toString()));}
    private IdentityAcceptanceHttps.Browser login(FindingRemediationPostgresFixture.Member member)throws Exception {var existing=loggedIn.get(member.accountId());if(existing!=null)return existing;var client=https.browser(port);loggedIn.put(member.accountId(),client);expect(client.get("/api/v1/auth/csrf"),204);expect(client.post("/api/v1/auth/login",json.writeValueAsString(Map.of("loginKey",member.loginKey(),"password",member.password(),"tenantCode",fixture.business.tenantCode)),client.csrf()),204);expect(client.get("/api/v1/auth/csrf"),204);return client;}
    private JsonNode result(IdentityAcceptanceHttps.Response response,int expected)throws Exception{expect(response,expected);return json.readTree(response.body()).path("data");}
    private static void expect(IdentityAcceptanceHttps.Response response,int expected){assertThat(response.status()).as("B4 real HTTPS status").isEqualTo(expected);}
    private static void assertResult(JsonNode result,String state,int round,long version){assertThat(result.path("state").asString()).isEqualTo(state);assertThat(result.path("roundNo").asInt()).isEqualTo(round);assertThat(result.path("version").asLong()).isEqualTo(version);}
    private UUID tenant(){return fixture.business.tenantId;}
    private String instance(UUID finding){return fixture.read(db->db.queryForObject("select process_instance_id from public.workflow_binding where tenant_id=? and business_id=?",String.class,tenant(),finding));}
    private void assertTask(UUID finding,String expected,UUID assignee){var task=tasks.createTaskQuery().processInstanceId(instance(finding)).singleResult();assertThat(task).isNotNull();assertThat(task.getTaskDefinitionKey()).isEqualTo(expected);assertThat(task.getAssignee()).isEqualTo(assignee.toString());}
    private void assertCommand(UUID command,UUID finding,long version){fixture.read(db->{assertThat(db.queryForObject("select count(*) from public.finding_command_receipt where tenant_id=? and command_id=? and result_finding_id=? and result_version=? and completed_at is not null",Long.class,tenant(),command,finding,version)).isEqualTo(1);assertThat(db.queryForObject("select count(*) from public.finding_transition where tenant_id=? and command_id=? and to_version=?",Long.class,tenant(),command,version)).isEqualTo(1);assertThat(db.queryForObject("select count(*) from public.audit_event where tenant_id=? and resource_id=? and diff_redacted->>'toVersion'=?",Long.class,tenant(),finding,Long.toString(version))).isEqualTo(1);assertThat(db.queryForObject("select count(*) from public.event_publication where serialized_event like ?",Long.class,"%"+command+"%")).isPositive();return null;});}
    private void assertSealed(FindingRemediationPostgresFixture.Evidence evidence){
        var persistedTime=fixture.read(db->db.queryForObject("select verified_at from public.remediation_submission_evidence where tenant_id=? and evidence_set_id=? and evidence_item_id=?",OffsetDateTime.class,tenant(),evidence.setId(),evidence.itemId()));
        var issuedTime=fileVerifier.findByReceiptId(tenant(),evidence.receiptId()).orElseThrow().verifiedAt();
        assertThat(persistedTime.isEqual(issuedTime)).as("Signed receipt survives PG precision: persisted nano remainder=%s, issued nano remainder=%s",persistedTime.getNano()%1000,issuedTime.getNano()%1000).isTrue();
        fixture.read(db->{assertThat(db.queryForObject("select count(*) from public.evidence_set where tenant_id=? and id=? and submission_sealed_at is not null",Long.class,tenant(),evidence.setId())).isEqualTo(1);assertThat(db.queryForObject("select count(*) from public.remediation_submission where tenant_id=? and evidence_set_id=? and evidence_snapshots_sealed_at is not null and evidence_snapshot_count=1 and evidence_snapshot_manifest=array[cast(? as uuid)]",Long.class,tenant(),evidence.setId(),evidence.itemId())).isEqualTo(1);assertThat(db.queryForObject("select count(*) from public.remediation_submission_evidence s join public.file_object f on f.tenant_id=s.tenant_id and f.id=s.file_id where s.tenant_id=? and s.evidence_set_id=? and s.evidence_item_id=? and s.file_id=? and s.verification_receipt_id=? and s.verification_level='CONTENT' and s.sha256=f.checksum and s.storage_version=f.storage_version and s.owner_member_id=f.owner_member_id and s.owner_actor_id=f.owner_actor_id and s.verifier_id='b4-local-verifier' and s.verifier_key_id='b4-local-key-1' and s.verification_policy_version='b4-fixture-v1' and s.verified_at is not null",Long.class,tenant(),evidence.setId(),evidence.itemId(),evidence.fileId(),evidence.receiptId())).isEqualTo(1);return null;});}
    // Rounds have no surrogate id: their identity is tenant + finding id + roundNo.
    record LifecycleRow(UUID id,int roundNo,String state){}
    private static List<LifecycleRow> roundStates(JdbcTemplate jdbc,UUID tenant,UUID finding) {
        return jdbc.query("select finding_id,round_no,state from public.remediation_round where tenant_id=? and finding_id=? order by round_no,finding_id",
                (row,index)->new LifecycleRow(row.getObject("finding_id",UUID.class),row.getInt("round_no"),row.getString("state")),tenant,finding);
    }
    private static List<LifecycleRow> noticeStates(JdbcTemplate jdbc,UUID tenant,UUID finding) {
        return jdbc.query("select id,round_no,state from public.remediation_notice where tenant_id=? and finding_id=? order by round_no,id",
                (row,index)->new LifecycleRow(row.getObject("id",UUID.class),row.getInt("round_no"),row.getString("state")),tenant,finding);
    }
    record State(Map<String,Long> counts,String state,int round,long version,List<String> tasks,long active,long finished,List<LifecycleRow> rounds,List<LifecycleRow> notices){}
    private State state(UUID finding){var row=fixture.read(db->db.queryForMap("select state,round_no,version from public.finding where tenant_id=? and id=?",tenant(),finding));String instance=instance(finding);List<String> taskState=tasks.createTaskQuery().processInstanceId(instance).list().stream().map(task->task.getId()+"/"+task.getTaskDefinitionKey()+"/"+task.getAssignee()).sorted().toList();return new State(fixture.counts(),(String)row.get("state"),((Number)row.get("round_no")).intValue(),((Number)row.get("version")).longValue(),taskState,runtime.createProcessInstanceQuery().processInstanceId(instance).count(),history.createHistoricProcessInstanceQuery().processInstanceId(instance).finished().count(),fixture.read(db->roundStates(db,tenant(),finding)),fixture.read(db->noticeStates(db,tenant(),finding)));}
    record Proof(String state,long version,long completedReceipt,long publications,long audit,long activeProcess,Map<String,Long> rows,List<LifecycleRow> rounds,List<LifecycleRow> notices){}
    static final class Fault{final AtomicReference<UUID> command=new AtomicReference<>();final AtomicReference<Proof> proof=new AtomicReference<>();}
    @TestConfiguration(proxyBeanMethods=false)
    static class FaultConfiguration {
        @Bean org.springframework.web.servlet.config.annotation.WebMvcConfigurer safeFailureDiagnostics() {
            return new org.springframework.web.servlet.config.annotation.WebMvcConfigurer() {
                @Override public void extendHandlerExceptionResolvers(java.util.List<org.springframework.web.servlet.HandlerExceptionResolver> resolvers) {
                    resolvers.addFirst((request,response,handler,failure)->{
                        StringBuilder diagnostic=new StringBuilder();int depth=0;
                        for(Throwable cause=failure;cause!=null && depth++<8;cause=cause.getCause()) {
                            diagnostic.append(cause.getClass().getSimpleName());
                            if(cause instanceof java.sql.SQLException sql)diagnostic.append("[SQLSTATE=").append(sql.getSQLState()).append(']');
                            if(cause.getStackTrace().length>0)diagnostic.append('@').append(cause.getStackTrace()[0]);
                            diagnostic.append('>');
                        }
                        // Class/SQLState/stack location only: no messages, SQL, parameters or content.
                        System.err.println("B4_FAILURE_DIAGNOSTIC="+diagnostic);
                        return null; // Preserve the production resolver and response.
                    });
                }
            };
        }
        @Bean Fault remediationFault(){return new Fault();}
        @Bean @Primary FindingWorkflowEventPublisher realPublicationThenFault(ModulithFindingWorkflowEventPublisher real,Fault fault,JdbcTemplate jdbc,RuntimeService runtime){return event->{real.publish(event);UUID expected=fault.command.get();if(event.commandId().equals(expected)&&fault.command.compareAndSet(expected,null)){
            long receipts=jdbc.queryForObject("select count(*) from public.finding_command_receipt where tenant_id=? and command_id=? and completed_at is not null",Long.class,event.tenantId(),event.commandId());long publications=jdbc.queryForObject("select count(*) from public.event_publication where serialized_event like ?",Long.class,"%"+event.commandId()+"%");long audits=jdbc.queryForObject("select count(*) from public.audit_event where tenant_id=? and resource_id=? and diff_redacted->>'toVersion'=?",Long.class,event.tenantId(),event.findingId(),Long.toString(event.version()));long active=runtime.createProcessInstanceQuery().processInstanceTenantId(event.tenantId().toString()).processInstanceBusinessKey("FINDING:"+event.findingId()).count();
            var persisted=jdbc.queryForMap("select state,version from public.finding where tenant_id=? and id=?",event.tenantId(),event.findingId());Map<String,Long> rows=new HashMap<>();
            for(String table:List.of("remediation_submission","remediation_submission_evidence","verification","remediation_round"))rows.put(table,jdbc.queryForObject("select count(*) from public."+table+" where tenant_id=? and finding_id=?",Long.class,event.tenantId(),event.findingId()));
            rows.put("sealedSets",jdbc.queryForObject("select count(*) from public.evidence_set where tenant_id=? and finding_id=? and submission_sealed_at is not null",Long.class,event.tenantId(),event.findingId()));
            fault.proof.set(new Proof((String)persisted.get("state"),((Number)persisted.get("version")).longValue(),receipts,publications,audits,active,Map.copyOf(rows),roundStates(jdbc,event.tenantId(),event.findingId()),noticeStates(jdbc,event.tenantId(),event.findingId())));throw new DataAccessResourceFailureException("Synthetic B4 failure after real publication");}};}
    }
}
