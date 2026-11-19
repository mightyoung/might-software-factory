package com.mightyoung.softwarefactory;

import com.mightyoung.softwarefactory.dualprevention.ModulithFindingWorkflowEventPublisher;
import com.mightyoung.softwarefactory.dualprevention.finding.workflow.FindingWorkflowEventPublisher;
import com.mightyoung.softwarefactory.dualprevention.finding.workflow.FindingWorkflowRepository;
import com.mightyoung.softwarefactory.workflow.WorkflowFacade;
import org.flowable.engine.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.parallel.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;

/** Known-lineage V13 only: production HTTPS commands, actual PostgreSQL and exact V1/V2 Flowable bindings. */
@SpringBootTest(classes=SoftwareFactoryApplication.class,webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(FindingNotEstablishedPostgresIT.FaultConfiguration.class)
@ActiveProfiles("lan-postgres-compatibility")
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class FindingNotEstablishedPostgresIT {
    private static IdentityAcceptanceHttps https;
    @LocalServerPort int port;
    @Autowired PasswordEncoder passwords;
    @Autowired ObjectMapper json;
    @Autowired RepositoryService definitions;
    @Autowired RuntimeService runtime;
    @Autowired TaskService tasks;
    @Autowired FindingWorkflowRepository repository;
    @Autowired WorkflowFacade workflow;
    @Autowired JdbcTemplate jdbc;
    @Autowired LateFailure late;
    private BusinessWorkflowPostgresFixture fixture;
    private final Map<UUID,IdentityAcceptanceHttps.Browser> browsers=new HashMap<>();

    @DynamicPropertySource static void properties(DynamicPropertyRegistry p)throws Exception {
        IdentityAcceptanceTarget.validateInputs(System.getenv());
        var target=BusinessWorkflowPostgresFixture.validateTarget();if(https==null)https=IdentityAcceptanceHttps.create();
        p.add("spring.datasource.url",target::jdbcUrl);p.add("spring.datasource.username",()->System.getenv("LAN_PG_APP_USERNAME"));p.add("spring.datasource.password",()->System.getenv("LAN_PG_APP_PASSWORD"));
        p.add("spring.datasource.hikari.maximum-pool-size",()->4);p.add("spring.datasource.hikari.minimum-idle",()->1);
        p.add("server.address",()->"127.0.0.1");p.add("server.ssl.enabled",()->true);p.add("server.ssl.key-store",()->https.keyStore().toUri().toString());p.add("server.ssl.key-store-password",https::password);p.add("server.ssl.key-store-type",()->"PKCS12");p.add("server.ssl.key-alias",()->"identity-acceptance");
        p.add("spring.session.jdbc.cleanup-cron",()->"-");p.add("spring.session.jdbc.initialize-schema",()->"never");p.add("spring.flyway.enabled",()->false);p.add("spring.modulith.events.jdbc.schema-initialization.enabled",()->false);
        p.add("flowable.database-schema-update",()->false);p.add("flowable.check-process-definitions",()->false);p.add("flowable.async-executor-activate",()->false);p.add("flowable.async-history-executor-activate",()->false);
    }
    @BeforeEach void seed(){late.command.set(null);late.proof.set(null);fixture=new BusinessWorkflowPostgresFixture(passwords);
        definitions.createDeployment().name(fixture.run+"-v1-v2").tenantId(fixture.tenantId.toString())
                .addClasspathResource("processes/finding-remediation-v1.bpmn20.xml")
                .addClasspathResource("processes/finding-remediation-v2.bpmn20.xml").deploy();
    }
    @AfterEach void close(){late.command.set(null);try{browsers.values().forEach(IdentityAcceptanceHttps.Browser::close);}finally{browsers.clear();if(fixture!=null)fixture.close();}}
    @AfterAll static void closeTls()throws Exception{if(https!=null)https.close();}
    enum Stage{PERSON,SAFETY}

    @ParameterizedTest @EnumSource(Stage.class)
    void realStageClosePinsV2EndsOnceAndReplaysWithoutAnyAdditionalWrite(Stage stage)throws Exception {
        UUID finding=atStage(stage);long version=stage==Stage.PERSON?1:2;
        UUID command=UUID.randomUUID();String body=closeBody(stage,version,"Owned terminal decision");
        String grade=field(finding,"grade",String.class),instance=instance(finding);
        var result=result(close(stage,finding,command,body),200);
        assertThat(result.path("state").asString()).isEqualTo("NOT_ESTABLISHED");assertThat(result.path("version").asLong()).isEqualTo(version+1);
        assertThat(field(finding,"grade",String.class)).isEqualTo(grade);assertClosed(command,finding,version+1,instance);
        Snapshot committed=snapshot();assertThat(result(close(stage,finding,command,body),200)).isEqualTo(result);assertThat(snapshot()).isEqualTo(committed);
        expect(close(stage,finding,command,closeBody(stage,version,"Changed terminal decision")),409);assertThat(snapshot()).isEqualTo(committed);
    }

    @ParameterizedTest @EnumSource(Stage.class)
    void lateFailureAfterActualClosePublicationRestoresTaskHistoryAndEveryParticipantThenRetries(Stage stage)throws Exception {
        UUID finding=atStage(stage);long version=stage==Stage.PERSON?1:2;String instance=instance(finding);
        var task=tasks.createTaskQuery().processInstanceId(instance).singleResult();assertThat(task).isNotNull();String taskId=task.getId();
        UUID command=UUID.randomUUID();String body=closeBody(stage,version,"Rollback terminal decision");Snapshot before=snapshot();late.command.set(command);
        expect(close(stage,finding,command,body),503);
        Proof proof=late.proof.get();assertThat(proof).isNotNull();assertThat(proof.state()).isEqualTo("NOT_ESTABLISHED");assertThat(proof.version()).isEqualTo(version+1);
        assertThat(proof.receipts()).isEqualTo(1);assertThat(proof.confirmations()).isEqualTo(1);assertThat(proof.transitions()).isEqualTo(1);assertThat(proof.audit()).isEqualTo(1);assertThat(proof.publications()).isPositive();assertThat(proof.active()).isZero();assertThat(proof.finished()).isEqualTo(1);
        assertThat(snapshot()).isEqualTo(before);assertThat(tasks.createTaskQuery().taskId(taskId).count()).isEqualTo(1);assertThat(fixture.publications(command)).isZero();
        result(close(stage,finding,command,body),200);assertClosed(command,finding,version+1,instance);Snapshot committed=snapshot();
        result(close(stage,finding,command,body),200);assertThat(snapshot()).isEqualTo(committed);
    }

    @Test void relatedCreationUsesSameTenantTerminalParentAndDatabaseGuardsKeepTerminalAndLinkImmutable()throws Exception {
        UUID parent=atStage(Stage.PERSON);result(close(Stage.PERSON,parent,UUID.randomUUID(),closeBody(Stage.PERSON,1,"Terminal parent")),200);
        UUID create=UUID.randomUUID();String body=createBody("Related synthetic report",parent);
        var created=result(post(fixture.reporter,"/api/v1/findings",create,body),200);UUID child=UUID.fromString(created.path("findingId").asString());
        assertThat(field(child,"related_finding_id",UUID.class)).isEqualTo(parent);assertDefinition(child,"findingRemediationV2");
        Snapshot committed=snapshot();assertThat(result(post(fixture.reporter,"/api/v1/findings",create,body),200)).isEqualTo(created);assertThat(snapshot()).isEqualTo(committed);
        expect(post(fixture.reporter,"/api/v1/findings",UUID.randomUUID(),createBody("Nonterminal parent refused",child)),422);assertThat(snapshot()).isEqualTo(committed);
        try(var foreign=new BusinessWorkflowPostgresFixture(passwords)) {
            expect(post(fixture.reporter,"/api/v1/findings",UUID.randomUUID(),createBody("Foreign parent hidden",foreign.legacyFindingId)),404);
            assertThat(foreign.count("finding")).isEqualTo(1);assertThat(foreign.count("workflow_binding")).isZero();
        }
        assertThat(snapshot()).isEqualTo(committed);
        assertConstraint("update public.finding set state='DISCOVERED' where tenant_id=? and id=?",parent,"prevent_not_established_finding_reopen");
        String immutableLinkUpdate="update public.finding set related_finding_id=null where tenant_id=? and id=?";
        Throwable runtimeFailure=catchThrowable(()->repository.inTenantTransaction(fixture.tenantId,tx->{jdbc.update(immutableLinkUpdate,fixture.tenantId,child);return null;}));
        assertThat(runtimeFailure).isInstanceOf(DataAccessException.class);
        assertThat(sqlFailure(runtimeFailure).getSQLState()).as("runtime cannot update an immutable link column").isEqualTo("42501");
        assertConstraint(immutableLinkUpdate,child,"finding_related_finding_immutable");
        assertThat(snapshot()).isEqualTo(committed);
    }

    @Test void actualLegacyV1RejectsCloseWithoutEffectsButBothNormalApprovalsContinue()throws Exception {
        UUID finding=fixture.legacyFindingId;
        // Fixture-only adoption models an existing V1 instance, using the real guarded repository and engine.
        repository.inTenantTransaction(fixture.tenantId,tx->{
            var current=tx.findForUpdate(finding).orElseThrow();tx.updateFinding(current.initializeDiscoveredWorkflow(fixture.reporter.memberId(),1),1);
            workflow.startProcess(fixture.tenantId,"FINDING",finding,1,"findingRemediationV1",Map.of(
                    "personConfirmerMemberId",fixture.person.memberId().toString(),"safetyConfirmerMemberId",fixture.safety.memberId().toString(),
                    "safetyDispatcherMemberId",fixture.safety.memberId().toString(),"safetyVerifierMemberId",fixture.safety.memberId().toString()));return null;
        });
        assertDefinition(finding,"findingRemediationV1");Snapshot before=snapshot();
        expect(close(Stage.PERSON,finding,UUID.randomUUID(),closeBody(Stage.PERSON,2,"V1 close prohibited")),409);assertThat(snapshot()).isEqualTo(before);
        result(post(fixture.person,path(finding,"confirm/person"),UUID.randomUUID(),json.writeValueAsString(Map.of("expectedVersion",2,"reason","V1 personal approval"))),200);
        assertTask(finding,"safetyConfirm",fixture.safety.memberId());Snapshot afterPerson=snapshot();
        expect(close(Stage.SAFETY,finding,UUID.randomUUID(),closeBody(Stage.SAFETY,3,"V1 safety close prohibited")),409);assertThat(snapshot()).isEqualTo(afterPerson);
        result(post(fixture.safety,path(finding,"confirm/safety"),UUID.randomUUID(),json.writeValueAsString(Map.of("expectedVersion",3,"reason","V1 safety approval","grade","GENERAL"))),200);
        assertThat(field(finding,"state",String.class)).isEqualTo("SAFETY_DISPATCH_PENDING");assertTask(finding,"safetyDispatch",fixture.safety.memberId());assertDefinition(finding,"findingRemediationV1");
    }

    private UUID atStage(Stage stage)throws Exception {
        var created=result(post(fixture.reporter,"/api/v1/findings",UUID.randomUUID(),createBody("Owned close candidate",null)),200);UUID finding=UUID.fromString(created.path("findingId").asString());
        assertDefinition(finding,"findingRemediationV2");assertTask(finding,"personConfirm",fixture.person.memberId());
        if(stage==Stage.SAFETY){result(post(fixture.person,path(finding,"confirm/person"),UUID.randomUUID(),json.writeValueAsString(Map.of("expectedVersion",1,"reason","Actual V2 personal approval"))),200);assertTask(finding,"safetyConfirm",fixture.safety.memberId());}
        return finding;
    }
    private void assertDefinition(UUID finding,String key){String id=fieldBinding(finding,"definition_id");var definition=definitions.createProcessDefinitionQuery().processDefinitionId(id).singleResult();assertThat(definition).isNotNull();assertThat(definition.getKey()).isEqualTo(key);assertThat(definition.getTenantId()).isEqualTo(fixture.tenantId.toString());var instance=runtime.createProcessInstanceQuery().processInstanceId(instance(finding)).singleResult();assertThat(instance).isNotNull();assertThat(instance.getProcessDefinitionId()).isEqualTo(id);}
    private void assertClosed(UUID command,UUID finding,long version,String instance){assertThat(runtime.createProcessInstanceQuery().processInstanceId(instance).count()).isZero();assertThat(tasks.createTaskQuery().processInstanceId(instance).count()).isZero();fixture.read(db->{assertThat(db.queryForObject("select count(*) from public.act_hi_procinst where proc_inst_id_=? and end_time_ is not null",Long.class,instance)).isEqualTo(1);assertThat(db.queryForObject("select count(*) from public.finding_confirmation where tenant_id=? and command_id=? and decision='NOT_ESTABLISHED'",Long.class,fixture.tenantId,command)).isEqualTo(1);assertThat(db.queryForObject("select count(*) from public.finding_command_receipt where tenant_id=? and command_id=? and result_finding_id=? and result_version=? and completed_at is not null",Long.class,fixture.tenantId,command,finding,version)).isEqualTo(1);return null;});assertThat(fixture.publications(command)).isPositive();}
    private void assertConstraint(String sql,UUID id,String expected) {
        // A direct database-trigger probe uses the gated migration identity, not extra runtime grants.
        var target=FixturePostgresTarget.preflightRepository(System.getenv());
        var source=new DriverManagerDataSource(target.jdbcUrl(),System.getenv("LAN_PG_USERNAME"),System.getenv("LAN_PG_PASSWORD"));
        var management=new JdbcTemplate(source);
        var transaction=new TransactionTemplate(new DataSourceTransactionManager(source));
        Throwable failure=catchThrowable(()->transaction.executeWithoutResult(status->{
            management.queryForObject("select set_config('app.tenant_id',?,true)",String.class,fixture.tenantId.toString());
            management.update(sql,fixture.tenantId,id);
            status.setRollbackOnly();
        }));
        assertThat(failure).isInstanceOf(DataAccessException.class);
        SQLException pg=sqlFailure(failure);
        assertThat(pg.getSQLState()).isEqualTo("23514");
        assertThat((Throwable)pg).isInstanceOf(org.postgresql.util.PSQLException.class);
        assertThat(((org.postgresql.util.PSQLException)pg).getServerErrorMessage().getConstraint()).isEqualTo(expected);
    }
    private static SQLException sqlFailure(Throwable failure) {
        SQLException pg=null;
        for(Throwable cause=failure;cause!=null;cause=cause.getCause())if(cause instanceof SQLException s)pg=s;
        assertThat((Throwable)pg).isNotNull();
        return pg;
    }
    private void assertTask(UUID finding,String key,UUID actor){var task=tasks.createTaskQuery().processInstanceId(instance(finding)).singleResult();assertThat(task).isNotNull();assertThat(task.getTaskDefinitionKey()).isEqualTo(key);assertThat(task.getAssignee()).isEqualTo(actor.toString());}
    private String instance(UUID finding){return fieldBinding(finding,"process_instance_id");}
    private String fieldBinding(UUID finding,String field){if(!Set.of("process_instance_id","definition_id").contains(field))throw new IllegalArgumentException();return fixture.read(db->db.queryForObject("select "+field+" from public.workflow_binding where tenant_id=? and business_id=?",String.class,fixture.tenantId,finding));}
    private <T>T field(UUID finding,String column,Class<T> type){if(!Set.of("grade","state","related_finding_id").contains(column))throw new IllegalArgumentException();return fixture.read(db->db.queryForObject("select "+column+" from public.finding where tenant_id=? and id=?",type,fixture.tenantId,finding));}
    record Snapshot(Map<String,Long> rows,List<Map<String,Object>> findings,List<Map<String,Object>> bindings,List<String> tasks){}
    private Snapshot snapshot(){return fixture.read(db->{Map<String,Long> counts=new LinkedHashMap<>();for(String table:List.of("finding","finding_confirmation","finding_transition","finding_command_receipt","audit_event","workflow_binding","remediation_round","remediation_notice"))counts.put(table,db.queryForObject("select count(*) from public."+table+" where tenant_id=?",Long.class,fixture.tenantId));counts.put("publications",db.queryForObject("select count(*) from public.event_publication where serialized_event like ?",Long.class,"%"+fixture.tenantId+"%"));counts.put("sequence",db.queryForObject("select coalesce(sum(last_sequence),0) from public.finding_number_sequence where tenant_id=?",Long.class,fixture.tenantId));counts.put("active",db.queryForObject("select count(*) from public.act_ru_execution where tenant_id_=?",Long.class,fixture.tenantId.toString()));counts.put("finished",db.queryForObject("select count(*) from public.act_hi_procinst where tenant_id_=? and end_time_ is not null",Long.class,fixture.tenantId.toString()));return new Snapshot(counts,db.queryForList("select id,state,grade,version,related_finding_id from public.finding where tenant_id=? order by id",fixture.tenantId),db.queryForList("select business_id,definition_id,process_instance_id from public.workflow_binding where tenant_id=? order by business_id",fixture.tenantId),tasks.createTaskQuery().taskTenantId(fixture.tenantId.toString()).list().stream().map(t->t.getId()+"/"+t.getTaskDefinitionKey()+"/"+t.getAssignee()).sorted().toList());});}
    private String closeBody(Stage stage,long version,String reason)throws Exception{return json.writeValueAsString(Map.of("expectedVersion",version,"stage",stage.name(),"reason",reason));}
    private String createBody(String description,UUID related)throws Exception{Map<String,Object> body=new LinkedHashMap<>(Map.of("organizationId",fixture.organizationId,"description",fixture.run+description,"discoveredAt",OffsetDateTime.now().minusHours(1).toString(),"grade","UNKNOWN","sourceCategory","FIELD_OBSERVATION","urgency","NORMAL","location","Synthetic closed finding location"));if(related!=null)body.put("relatedFindingId",related);return json.writeValueAsString(body);}
    private IdentityAcceptanceHttps.Response close(Stage stage,UUID finding,UUID command,String body)throws Exception{return post(stage==Stage.PERSON?fixture.person:fixture.safety,path(finding,"close-not-established"),command,body);}
    private static String path(UUID finding,String suffix){return "/api/v1/findings/"+finding+"/"+suffix;}
    private IdentityAcceptanceHttps.Response post(BusinessWorkflowPostgresFixture.Actor actor,String path,UUID command,String body)throws Exception{return login(actor).postWithHeaders(path,body,login(actor).csrf(),Map.of("Idempotency-Key",command.toString()));}
    private IdentityAcceptanceHttps.Browser login(BusinessWorkflowPostgresFixture.Actor actor)throws Exception {var existing=browsers.get(actor.accountId());if(existing!=null)return existing;var browser=https.browser(port);browsers.put(actor.accountId(),browser);expect(browser.get("/api/v1/auth/csrf"),204);expect(browser.post("/api/v1/auth/login",json.writeValueAsString(Map.of("loginKey",actor.loginKey(),"password",actor.password(),"tenantCode",fixture.tenantCode)),browser.csrf()),204);expect(browser.get("/api/v1/auth/csrf"),204);return browser;}
    private JsonNode result(IdentityAcceptanceHttps.Response response,int status)throws Exception{expect(response,status);return json.readTree(response.body()).path("data");}
    private static void expect(IdentityAcceptanceHttps.Response response,int status){assertThat(response.status()).as("real close HTTPS status").isEqualTo(status);}
    record Proof(String state,long version,long receipts,long confirmations,long transitions,long audit,long publications,long active,long finished){}
    static final class LateFailure{final AtomicReference<UUID> command=new AtomicReference<>();final AtomicReference<Proof> proof=new AtomicReference<>();}
    @TestConfiguration(proxyBeanMethods=false) static class FaultConfiguration {
        @Bean LateFailure closeLateFailure(){return new LateFailure();}
        @Bean @Primary FindingWorkflowEventPublisher actualClosePublicationThenFailure(ModulithFindingWorkflowEventPublisher real,LateFailure late,JdbcTemplate db){return event->{real.publish(event);UUID expected=late.command.get();if(event.commandId().equals(expected)&&late.command.compareAndSet(expected,null)){
            var state=db.queryForMap("select state,version from public.finding where tenant_id=? and id=?",event.tenantId(),event.findingId());String instance=db.queryForObject("select process_instance_id from public.workflow_binding where tenant_id=? and business_id=?",String.class,event.tenantId(),event.findingId());
            long receipt=db.queryForObject("select count(*) from public.finding_command_receipt where tenant_id=? and command_id=? and completed_at is not null",Long.class,event.tenantId(),event.commandId());long confirmations=db.queryForObject("select count(*) from public.finding_confirmation where tenant_id=? and command_id=? and decision='NOT_ESTABLISHED'",Long.class,event.tenantId(),event.commandId());long transitions=db.queryForObject("select count(*) from public.finding_transition where tenant_id=? and command_id=?",Long.class,event.tenantId(),event.commandId());long audit=db.queryForObject("select count(*) from public.audit_event where tenant_id=? and resource_id=? and diff_redacted->>'toVersion'=?",Long.class,event.tenantId(),event.findingId(),Long.toString(event.version()));long publications=db.queryForObject("select count(*) from public.event_publication where serialized_event like ?",Long.class,"%"+event.commandId()+"%");long active=db.queryForObject("select count(*) from public.act_ru_execution where proc_inst_id_=?",Long.class,instance);long finished=db.queryForObject("select count(*) from public.act_hi_procinst where proc_inst_id_=? and end_time_ is not null",Long.class,instance);
            late.proof.set(new Proof((String)state.get("state"),((Number)state.get("version")).longValue(),receipt,confirmations,transitions,audit,publications,active,finished));throw new DataAccessResourceFailureException("Controlled owned close failure after actual publication");
        }};}
    }
}
