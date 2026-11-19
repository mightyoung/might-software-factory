package com.mightyoung.softwarefactory;

import com.mightyoung.softwarefactory.auditops.*;
import java.sql.SQLException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.parallel.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.*;
import static org.assertj.core.api.Assertions.*;

/** Real HTTPS login -> neutral application authority -> generic ledger -> PostgreSQL, never a synthetic adapter. */
@SpringBootTest(classes=SoftwareFactoryApplication.class,webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(ToolLedgerPostgresIT.FaultConfiguration.class)
@ActiveProfiles("lan-postgres-compatibility")
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ToolLedgerPostgresIT {
    private static final String BASE="/api/v1/apps/tooling/entities/tool";
    private static IdentityAcceptanceHttps https;
    @LocalServerPort int port;
    @Autowired PasswordEncoder passwords;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate runtime;
    @Autowired PlatformTransactionManager manager;
    @Autowired Faults faults;
    ToolLedgerPostgresFixture fixture;
    final List<IdentityAcceptanceHttps.Browser> browsers=new ArrayList<>();
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p)throws Exception {
        var target=IdentityAcceptanceTarget.preflightFromEnvironment(System.getenv());
        if(IdentityLoginPostgresIT.validateRuntimePrivilegeProfile(System.getenv(),target.databaseName())!=IdentityLoginPostgresIT.RuntimePrivilegeProfile.D1_APPLICATION)throw new IllegalStateException("D1 profile required");
        if(https==null)https=IdentityAcceptanceHttps.create();
        p.add("spring.datasource.url",target::jdbcUrl);p.add("spring.datasource.username",()->System.getenv("LAN_PG_APP_USERNAME"));p.add("spring.datasource.password",()->System.getenv("LAN_PG_APP_PASSWORD"));
        p.add("spring.datasource.hikari.maximum-pool-size",()->8);p.add("spring.datasource.hikari.minimum-idle",()->1);
        p.add("server.address",()->"127.0.0.1");p.add("server.ssl.enabled",()->true);p.add("server.ssl.key-store",()->https.keyStore().toUri().toString());p.add("server.ssl.key-store-password",https::password);p.add("server.ssl.key-store-type",()->"PKCS12");p.add("server.ssl.key-alias",()->"identity-acceptance");
        p.add("spring.session.jdbc.cleanup-cron",()->"-");p.add("software-factory.security.login-throttle.source-attempts",()->1000);p.add("software-factory.security.login-throttle.global-attempts",()->10000);
    }
    @BeforeAll void prepare(){fixture=new ToolLedgerPostgresFixture(passwords);}
    @AfterEach void clearFaults()throws Exception{faults.mode.set(null);for(var browser:browsers)browser.close();browsers.clear();}
    @AfterAll void close()throws Exception{try{if(fixture!=null)fixture.close();}finally{if(https!=null){https.close();https=null;}}}

    @Test void keeperCrudCommitsRootAuditRevisionFactAndReceiptTogether()throws Exception {
        var keeper=login("keeper");String code=code();var before=fixture.counts();
        var command=data(expect(post(keeper,BASE+"/records",create(code,fixture.custody),UUID.randomUUID()),200));UUID id=UUID.fromString(command.path("id").asString());
        assertThat(command.propertyNames()).containsExactlyInAnyOrder("commandId","id","version","definitionVersion","eventId","occurredAt","replay");
        var detail=data(expect(keeper.get(BASE+"/records/"+id),200));
        assertThat(detail.path("values").path("organizationId").asString()).isEqualTo(fixture.org.toString());assertThat(detail.path("values").path("itemState").asString()).isEqualTo("NORMAL");
        assertThat(detail.path("effectiveActions").toString()).contains("tool.update");
        expect(post(keeper,BASE+"/records/"+id+"/update",update(1,"Renamed"),UUID.randomUUID()),200);
        var counts=fixture.counts();assertThat(counts.get("tool")).isEqualTo(before.get("tool")+1);for(String key:List.of("ledger_record_revision","ledger_command_receipt","audit"))assertThat(counts.get(key)).as(key).isEqualTo(before.get(key)+2);
        fixture.read(db->{var rows=db.queryForList("select version,action,snapshot,change_fact from public.ledger_record_revision where tenant_id=? and record_id=? order by version",fixture.tenant,id);assertThat(rows).hasSize(2);assertThat(rows.get(0).get("action")).isEqualTo("CREATE");assertThat(rows.get(1).get("action")).isEqualTo("UPDATE");assertThat(rows.get(1).get("snapshot").toString()).contains("Renamed");assertThat(rows.get(0).get("snapshot").toString()).doesNotContain("Renamed");return null;});
    }
    @Test void readerNeedsNoKeeperWhileMaintenanceRequiresScopeKeeper()throws Exception {
        var keeper=login("keeper");UUID id=id(post(keeper,BASE+"/records",create(code(),fixture.custody),UUID.randomUUID()));
        var reader=login("reader");var detail=data(expect(reader.get(BASE+"/records/"+id),200));assertThat(detail.path("effectiveActions").toString()).doesNotContain("tool.update");
        expect(post(reader,BASE+"/records/"+id+"/update",update(1,"Denied"),UUID.randomUUID()),403);
        var nonkeeper=login("nonkeeper");var refs=data(expect(post(nonkeeper,BASE+"/references/custodyScope/query",reference("CREATE",Map.of()),null),200));assertThat(refs.path("options").size()).isZero();
        var before=fixture.counts();expect(post(nonkeeper,BASE+"/records",create(code(),fixture.custody),UUID.randomUUID()),400);assertThat(fixture.counts()).isEqualTo(before);
        expect(post(keeper,BASE+"/records",create(code(),fixture.unassignedCustody),UUID.randomUUID()),400);
    }
    @Test void writeOnlyMembersGetOnlyCommandMetadataAndNeverReadAuthority()throws Exception {
        var client=login("createonly");var response=data(expect(post(client,BASE+"/records",create(code(),fixture.custody),UUID.randomUUID()),200));assertThat(response.has("values")).isFalse();
        expect(client.get(BASE+"/records/"+response.path("id").asString()),403);expect(post(client,BASE+"/query",query(null),null),403);
        var updateOnly=login("updateonly");expect(post(updateOnly,BASE+"/records/"+response.path("id").asString()+"/update",update(1,"Updated without read"),UUID.randomUUID()),200);
        expect(updateOnly.get(BASE+"/records/"+response.path("id").asString()),403);
    }
    @Test void protectedFieldsInvalidUsesAndUntrustedReferenceContextAreRejected()throws Exception {
        var keeper=login("keeper");var before=fixture.counts();
        for(String key:List.of("tenantId","actorId","id","version","organizationId","itemState")) {
            var values=new LinkedHashMap<>(createValues(code(),fixture.custody));values.put(key,key.equals("version")?1:"injected");expect(post(keeper,BASE+"/records",json.writeValueAsString(Map.of("definitionVersion","1","values",values)),UUID.randomUUID()),400);
        }
        expect(post(keeper,BASE+"/records",create(code(),fixture.foreignCustody),UUID.randomUUID()),400);
        expect(post(keeper,BASE+"/references/custodyScope/query",reference("EDIT",Map.of()),null),400);
        expect(post(keeper,BASE+"/references/custodyScope/query",reference("CREATE",Map.of("organizationId",fixture.org.toString())),null),400);
        expect(post(keeper,BASE+"/records",create(code(),fixture.custody).replace("\"definitionVersion\":\"1\"","\"definitionVersion\":\"old\""),UUID.randomUUID()),409);
        assertThat(fixture.counts()).isEqualTo(before);
    }
    @Test void tenantOrganizationAndCatalogPredicatesAreConsistentAcrossReadsAndWrites()throws Exception {
        var outside=login("outside");var foreign=login("foreign");var keeper=login("keeper");UUID outsideId=id(post(outside,BASE+"/records",create(code(),fixture.outsideCustody),UUID.randomUUID()));UUID foreignId=id(post(foreign,BASE+"/records",create(code(),fixture.foreignCustody),UUID.randomUUID()));
        for(UUID target:List.of(outsideId,foreignId)){expect(keeper.get(BASE+"/records/"+target),404);expect(post(keeper,BASE+"/records/"+target+"/update",update(1,"Hidden"),UUID.randomUUID()),404);}
        String code=code();UUID own=id(post(keeper,BASE+"/records",create(code,fixture.custody),UUID.randomUUID()));
        var list=data(expect(post(keeper,BASE+"/query",query(code),null),200));assertThat(list.path("total").asLong()).isEqualTo(1);assertThat(list.path("records").size()).isEqualTo(1);assertThat(list.path("records").get(0).path("id").asString()).isEqualTo(own.toString());
        var refs=data(expect(post(keeper,BASE+"/references/custodyScope/query",reference("CREATE",Map.of()),null),200));assertThat(refs.path("options").size()).isEqualTo(1);assertThat(refs.path("options").get(0).path("id").asString()).isEqualTo(fixture.custody.toString());
        // Operator-owned imported archive states exercise catalog policy; D1 has no lifecycle command or occupancy claim.
        var reader=login("reader");
        for(String state:List.of("DAMAGED","LOST","RETIRED")) {
            UUID hidden=UUID.randomUUID();String hiddenCode=code();UUID actor=fixture.actors.get("keeper").principal();
            fixture.security(db->db.update("insert into public.tool(tenant_id,id,version,created_at,updated_at,created_by_actor_id,updated_by_actor_id,code,name,custody_scope_id,organization_id,item_state) values (?,?,1,current_timestamp,current_timestamp,?,?,?,'Imported archive',?,?,?)",fixture.tenant,hidden,actor,actor,hiddenCode,fixture.custody,fixture.org,state));
            var before=fixture.counts();
            expect(keeper.get(BASE+"/records/"+hidden),404);expect(reader.get(BASE+"/records/"+hidden),404);
            expect(post(keeper,BASE+"/records/"+hidden+"/update",update(1,"Hidden archive"),UUID.randomUUID()),404);
            var hiddenQuery=data(expect(post(reader,BASE+"/query",query(hiddenCode),null),200));assertThat(hiddenQuery.path("total").asLong()).isZero();assertThat(hiddenQuery.path("records").size()).isZero();
            assertThat(fixture.counts()).isEqualTo(before);
        }
    }
    @Test void duplicateCodeAndConcurrentVersionCompetitionHaveOneDurableWinner()throws Exception {
        var a=login("keeper");var b=login("keeper");String code=code();UUID target=id(post(a,BASE+"/records",create(code,fixture.custody),UUID.randomUUID()));var before=fixture.counts();
        var duplicate=expect(post(a,BASE+"/records",create(code,fixture.custody),UUID.randomUUID()),409);assertThat(json.readTree(duplicate.body()).path("code").asString()).isEqualTo("COMMAND_CONFLICT");assertThat(fixture.counts()).isEqualTo(before);
        CompletableFuture<IdentityAcceptanceHttps.Response> first=CompletableFuture.supplyAsync(()->uncheckedPost(a,target,"Winner A"));CompletableFuture<IdentityAcceptanceHttps.Response> second=CompletableFuture.supplyAsync(()->uncheckedPost(b,target,"Winner B"));
        assertThat(List.of(first.get(12,TimeUnit.SECONDS).status(),second.get(12,TimeUnit.SECONDS).status())).containsExactlyInAnyOrder(200,409);
        var after=fixture.counts();assertThat(after.get("tool")).isEqualTo(before.get("tool"));for(String key:List.of("ledger_record_revision","ledger_command_receipt","audit"))assertThat(after.get(key)).isEqualTo(before.get(key)+1);
    }
    @Test void concurrentSameKeySerializesAndReplaysOrConflictsWithoutDuplicateEffects()throws Exception {
        for(boolean identical:List.of(true,false)) {
            var firstClient=login("keeper");var secondClient=login("keeper");UUID key=UUID.randomUUID();
            String firstBody=create(code(),fixture.custody),secondBody=identical?firstBody:create(code(),fixture.custody);var before=fixture.counts();
            var hold=new AuditHold(new CountDownLatch(1),new CountDownLatch(1));faults.hold.set(hold);
            CompletableFuture<IdentityAcceptanceHttps.Response> first=CompletableFuture.supplyAsync(()->uncheckedCreate(firstClient,firstBody,key));
            CompletableFuture<IdentityAcceptanceHttps.Response> second=null;
            try {
                assertThat(hold.entered().await(8,TimeUnit.SECONDS)).as("First real audit appended while command transaction remains open").isTrue();
                second=CompletableFuture.supplyAsync(()->uncheckedCreate(secondClient,secondBody,key));
                long deadline=System.nanoTime()+Duration.ofSeconds(3).toNanos();boolean queued=false;
                while(System.nanoTime()<deadline) {
                    queued=fixture.read(db->Boolean.TRUE.equals(db.queryForObject("select exists(select 1 from pg_locks where locktype='advisory' and classid=? and objsubid=2 and not granted)",Boolean.class,0x4D53464C)));
                    if(queued)break;LockSupport.parkNanos(Duration.ofMillis(15).toNanos());
                }
                assertThat(queued).as("Second session genuinely queues on ledger idempotency transaction lock").isTrue();assertThat(second.isDone()).isFalse();
            } finally {hold.release().countDown();faults.hold.set(null);}
            var winner=data(expect(first.get(12,TimeUnit.SECONDS),200));var follower=second.get(12,TimeUnit.SECONDS);
            if(identical) {
                var replay=data(expect(follower,200));assertThat(winner.path("replay").asBoolean()).isFalse();assertThat(replay.path("replay").asBoolean()).isTrue();
                for(String field:List.of("id","commandId","eventId","version"))assertThat(replay.path(field)).isEqualTo(winner.path(field));
            } else {expect(follower,409);assertThat(json.readTree(follower.body()).path("code").asString()).isEqualTo("IDEMPOTENCY_CONFLICT");}
            var after=fixture.counts();for(String table:before.keySet())assertThat(after.get(table)).as(table).isEqualTo(before.get(table)+1);
            boolean lockRemains=Boolean.TRUE.equals(fixture.read(db->db.queryForObject("select exists(select 1 from pg_locks where locktype='advisory' and classid=? and objsubid=2)",Boolean.class,0x4D53464C)));assertThat(lockRemains).as("Transaction completion releases idempotency locks").isFalse();
        }
    }
    private IdentityAcceptanceHttps.Response uncheckedCreate(IdentityAcceptanceHttps.Browser browser,String body,UUID key){try{return post(browser,BASE+"/records",body,key);}catch(Exception e){throw new CompletionException(e);}}
    @Test void replayHasNoEffectsAndRechecksRevokedKeeperAndRoleAuthority()throws Exception {
        var keeper=login("keeper");UUID key=UUID.randomUUID();String body=create(code(),fixture.custody);var original=data(expect(post(keeper,BASE+"/records",body,key),200));var before=fixture.counts();
        var replay=data(expect(post(keeper,BASE+"/records",body,key),200));assertThat(replay.path("replay").asBoolean()).isTrue();for(String field:List.of("id","commandId","eventId","version"))assertThat(replay.path(field)).isEqualTo(original.path(field));assertThat(fixture.counts()).isEqualTo(before);
        expect(post(keeper,BASE+"/records",create(code(),fixture.custody),key),409);
        fixture.expireKeeper("keeper",true);try{expect(post(keeper,BASE+"/records",body,key),404);}finally{fixture.expireKeeper("keeper",false);}
        fixture.enableGrant("keeper",false);try{expect(post(keeper,BASE+"/records",body,key),403);}finally{fixture.enableGrant("keeper",true);}
        assertThat(fixture.counts()).isEqualTo(before);
    }
    @Test void expiryInactiveScopeAndRevokedMemberDenyWritesWithoutPartialEffects()throws Exception {
        var keeper=login("keeper");UUID target=id(post(keeper,BASE+"/records",create(code(),fixture.custody),UUID.randomUUID()));var before=fixture.counts();
        fixture.expireKeeper("keeper",true);try{expect(post(keeper,BASE+"/records/"+target+"/update",update(1,"Expired"),UUID.randomUUID()),404);}finally{fixture.expireKeeper("keeper",false);}
        fixture.enableScope(false);try{expect(keeper.get(BASE+"/records/"+target),404);expect(post(keeper,BASE+"/records",create(code(),fixture.custody),UUID.randomUUID()),400);}finally{fixture.enableScope(true);}
        fixture.enableMember("keeper",false);try{assertThat(post(keeper,BASE+"/records/"+target+"/update",update(1,"Revoked"),UUID.randomUUID()).status()).isIn(401,403);}finally{fixture.enableMember("keeper",true);}
        assertThat(fixture.counts()).isEqualTo(before);
    }
    @Test void failuresAfterRealAuditAndDuringRevisionInsertRollBackEverything()throws Exception {
        var keeper=login("keeper");
        for(String mode:List.of("AFTER_AUDIT","INVALID_AUDIT")) {
            var before=fixture.counts();faults.mode.set(mode);expect(post(keeper,BASE+"/records",create(code(),fixture.custody),UUID.randomUUID()),503);assertThat(fixture.counts()).isEqualTo(before);
        }
    }
    @Test void runtimeAclRlsAndProtectedColumnsAreEnforcedByPostgres()throws Exception {
        D1RuntimePrivilegeAssertions.assertApplication(runtime);
        assertThat(runtime.queryForObject("select count(*) from public.tool",Integer.class)).isZero();
        var keeper=login("keeper");UUID target=id(post(keeper,BASE+"/records",create(code(),fixture.custody),UUID.randomUUID()));
        for(String column:List.of("tenant_id","id","code","custody_scope_id","organization_id","item_state","created_by_actor_id")) {
            sqlState(()->new TransactionTemplate(manager).executeWithoutResult(s->{runtime.queryForObject("select set_config('app.tenant_id',?,true)",String.class,fixture.tenant.toString());runtime.update("update public.tool set "+column+"="+column+" where id=?",target);}),"42501");
        }
        sqlState(()->runtime.update("delete from public.ledger_command_receipt where false"),"42501");
    }
    @Test void receiptRevisionForeignKeysJsonFactsAndAppendOnlyGuardsArePhysical()throws Exception {
        var keeper=login("keeper");UUID own=id(post(keeper,BASE+"/records",create(code(),fixture.custody),UUID.randomUUID()));var foreign=login("foreign");UUID other=id(post(foreign,BASE+"/records",create(code(),fixture.foreignCustody),UUID.randomUUID()));
        UUID foreignAudit=fixture.tx.execute(s->{ToolLedgerPostgresFixture.context(fixture.jdbc,fixture.otherTenant);return fixture.jdbc.queryForObject("select audit_event_id from public.ledger_record_revision where tenant_id=? and record_id=?",UUID.class,fixture.otherTenant,other);});
        var before=fixture.counts();
        String copyReceipt="insert into public.ledger_command_receipt select tenant_id,actor_id,account_id,member_id,app_key,entity_key,action,cast(? as uuid),cast(? as uuid),record_id,result_version,request_hash,definition_version,result,completed_at from public.ledger_command_receipt where tenant_id=? and record_id=?";
        sqlState(()->fixture.security(db->db.update(copyReceipt,UUID.randomUUID(),UUID.randomUUID(),fixture.tenant,own)),"23503");
        sqlState(()->fixture.security(db->db.update("insert into public.ledger_command_receipt select tenant_id,actor_id,account_id,member_id,app_key,entity_key,action,cast(? as uuid),command_id,record_id,result_version,request_hash,definition_version,jsonb_set(result,'{eventId}',to_jsonb(cast(? as text))),completed_at from public.ledger_command_receipt where tenant_id=? and record_id=?",UUID.randomUUID(),UUID.randomUUID().toString(),fixture.tenant,own)),"23514");
        UUID command=UUID.randomUUID(),event=UUID.randomUUID();
        sqlState(()->fixture.security(db->db.update("insert into public.ledger_record_revision select tenant_id,app_key,entity_key,record_id,version+1,cast(? as uuid),cast(? as uuid),definition_version,action,cast(? as uuid),actor_id,account_id,member_id,occurred_at,snapshot,change_fact || jsonb_build_object('version',version+1,'commandId',cast(? as text),'eventId',cast(? as text)) from public.ledger_record_revision where tenant_id=? and record_id=?",command,event,foreignAudit,command.toString(),event.toString(),fixture.tenant,own)),"23503");
        for(String table:List.of("ledger_command_receipt","ledger_record_revision")) {
            sqlState(()->fixture.security(db->db.update("update public."+table+" set record_id=record_id where tenant_id=? and record_id=?",fixture.tenant,own)),"23514");
            sqlState(()->fixture.security(db->db.update("delete from public."+table+" where tenant_id=? and record_id=?",fixture.tenant,own)),"23514");
        }
        sqlState(()->fixture.security(db->db.execute("truncate public.ledger_command_receipt,public.ledger_record_revision")),"23514");
        assertThat(fixture.counts()).isEqualTo(before);
        String fk=fixture.read(db->db.queryForObject("select pg_get_constraintdef(oid) from pg_constraint where conname='ledger_receipt_exact_revision_fk'",String.class));assertThat(fk).contains("actor_id","account_id","member_id","action","definition_version","result_version","command_id");
    }
    @Test void actualTenantSecurityWriterBlocksCommandAndFreshKeeperRevocationWins()throws Exception {
        var keeper=login("keeper");UUID target=id(post(keeper,BASE+"/records",create(code(),fixture.custody),UUID.randomUUID()));var before=fixture.counts();var future=new AtomicReference<CompletableFuture<IdentityAcceptanceHttps.Response>>();
        try {
            fixture.security(db->{
                int holder=db.queryForObject("select pg_backend_pid()",Integer.class);
                db.update("update public.tool_custodian set valid_to=current_timestamp where tenant_id=? and custody_scope_id=? and member_id=?",fixture.tenant,fixture.custody,fixture.actors.get("keeper").member());
                future.set(CompletableFuture.supplyAsync(()->uncheckedPost(keeper,target,"Must wait and fail")));
                long deadline=System.nanoTime()+Duration.ofSeconds(3).toNanos();boolean waiting=false;
                while(System.nanoTime()<deadline) {
                    waiting=Boolean.TRUE.equals(db.queryForObject("select exists(select 1 from pg_locks l where l.locktype='advisory' and not l.granted and ?=any(pg_blocking_pids(l.pid)))",Boolean.class,holder));
                    if(waiting)break;LockSupport.parkNanos(Duration.ofMillis(15).toNanos());
                }
                assertThat(waiting).as("Observe a genuine advisory-lock waiter").isTrue();assertThat(future.get().isDone()).isFalse();
            });
            expect(future.get().get(10,TimeUnit.SECONDS),404);
        } finally {fixture.expireKeeper("keeper",false);}
        assertThat(fixture.counts()).isEqualTo(before);
    }

    private IdentityAcceptanceHttps.Browser login(String name)throws Exception{var actor=fixture.actors.get(name);var browser=https.browser(port);browsers.add(browser);expect(browser.get("/api/v1/auth/csrf"),204);expect(browser.post("/api/v1/auth/login",json.writeValueAsString(Map.of("loginKey",actor.login(),"password",actor.password(),"tenantCode",name.equals("foreign")?fixture.foreignCode:fixture.tenantCode)),browser.csrf()),204);expect(browser.get("/api/v1/auth/csrf"),204);return browser;}
    private IdentityAcceptanceHttps.Response post(IdentityAcceptanceHttps.Browser browser,String path,String body,UUID key)throws Exception{return browser.postWithHeaders(path,body,browser.csrf(),key==null?Map.of():Map.of("Idempotency-Key",key.toString()));}
    private IdentityAcceptanceHttps.Response uncheckedPost(IdentityAcceptanceHttps.Browser browser,UUID id,String name){try{return post(browser,BASE+"/records/"+id+"/update",update(1,name),UUID.randomUUID());}catch(Exception e){throw new CompletionException(e);}}
    private IdentityAcceptanceHttps.Response expect(IdentityAcceptanceHttps.Response response,int status){assertThat(response.status()).as("HTTP status; response trace %s",response.trace()).isEqualTo(status);return response;}
    private JsonNode data(IdentityAcceptanceHttps.Response response)throws Exception{return json.readTree(response.body()).path("data");}
    private UUID id(IdentityAcceptanceHttps.Response response)throws Exception{return UUID.fromString(data(expect(response,200)).path("id").asString());}
    private String create(String code,UUID custody)throws Exception{return json.writeValueAsString(Map.of("definitionVersion","1","values",createValues(code,custody)));}
    private Map<String,Object> createValues(String code,UUID custody){return Map.of("code",code,"name","Owned D1 tool","model","M1","description","Synthetic acceptance record","custodyScopeId",custody.toString());}
    private String update(long version,String name)throws Exception{return json.writeValueAsString(Map.of("definitionVersion","1","expectedVersion",version,"values",Map.of("name",name)));}
    private String reference(String use,Map<String,Object> context)throws Exception{return json.writeValueAsString(Map.of("definitionVersion","1","use",use,"search","","context",context,"size",20));}
    private String query(String code)throws Exception{Map<String,Object> q=new LinkedHashMap<>();q.put("page",Map.of("number",1));q.put("size",20);if(code!=null)q.put("filterAST",Map.of("operator","AND","conditions",List.of(Map.of("field","code","operator","EQ","value",code))));return json.writeValueAsString(Map.of("definitionVersion","1","query",q));}
    private String code(){return "D1-"+UUID.randomUUID();}
    private void sqlState(Runnable work,String expected){Throwable t=catchThrowable(work::run);assertThat(t).isInstanceOf(DataAccessException.class);assertThat(((DataAccessException)t).getMostSpecificCause()).isInstanceOfSatisfying(SQLException.class,e->assertThat(e.getSQLState()).isEqualTo(expected));}
    record AuditHold(CountDownLatch entered,CountDownLatch release) {}
    static final class Faults {final AtomicReference<String> mode=new AtomicReference<>();final AtomicReference<AuditHold> hold=new AtomicReference<>();}
    @TestConfiguration(proxyBeanMethods=false) static class FaultConfiguration {
        @Bean Faults faults(){return new Faults();}
        @Bean @Primary LedgerAuditAppend faultAudit(JooqLedgerAuditAppend actual,Faults faults){return(decision,command,id,trace,fields)->{UUID real=actual.appendSuccess(decision,command,id,trace,fields);var hold=faults.hold.getAndSet(null);if(hold!=null){hold.entered().countDown();try{if(!hold.release().await(10,TimeUnit.SECONDS))throw new IllegalStateException("Controlled audit hold timed out");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException("Controlled audit hold interrupted",e);}}String mode=faults.mode.getAndSet(null);if("AFTER_AUDIT".equals(mode))throw new IllegalStateException("Controlled late audit failure");return "INVALID_AUDIT".equals(mode)?UUID.randomUUID():real;};}
    }
}
