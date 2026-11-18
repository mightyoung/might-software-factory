package com.mightyoung.softwarefactory;

import com.mightyoung.softwarefactory.testing.CiPg18EphemeralTarget;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

/** Orchestrator only: all source and recovery business actions execute in distinct real JVMs. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class CiPg18RecoveryPostgresIT {
    private static final List<String> CASES=List.of("actualSourceNamedListenerCaptureAndAckCommit",
            "causeCommitWithKnownAckRollbackRecoversExactlyOnce","serviceRevocationLeavesIncompleteThenCurrentEligibilityRestoresRecovery",
            "newJvmRecoversPublicationLeftByTerminatedProducer","unknownAckCommitThenThrowIsObservedBeforeAnyFurtherDispatch");
    private static final int[] WORKERS={1,3,3,2,3};
    private final Map<String,Map<String,Object>> evidence=new LinkedHashMap<>();
    private CiPg18EphemeralTarget target;
    private CiPg18RecoveryFixture fixture;
    private Path workerRoot;
    private boolean failed;

    @BeforeAll void open()throws Exception {
        for(String name:CASES)evidence.put(name,row(name,"NOT_REACHED",List.of(),false,false));
        writeProcessEvidence();
        target=CiPg18EphemeralTarget.from(System.getenv(),CiPg18EphemeralTarget.Lane.RECOVERY);
        Path ownership=Path.of(System.getenv("MSF_CI_PG18_OWNERSHIP_FILE")).toRealPath();
        workerRoot=ownership.getParent().resolve("recovery-workers");privateDirectory(workerRoot);
        fixture=CiPg18RecoveryFixture.bootstrap(target,target.recoveryCapturePassword());
    }
    @AfterAll void close()throws Exception {
        try{if(fixture!=null)fixture.writeEvidence();}finally{if(fixture!=null)fixture.close();writeProcessEvidence();}
    }

    @Test void actualSourceNamedListenerCaptureAndAckCommit()throws Exception {
        run(CASES.get(0),test->{
            fixture.verifyGrantOptionsRefused();
            var producer=test.producer();producer.send("CREATE");producer.await("SOURCE_COMMITTED");
            var before=observe(test.id);assertSource(before,false,0);assertNoOtherIncomplete(before);
            producer.send("DELIVER");var delivery=producer.await("DELIVERY_FINISHED");producer.finish();
            assertDelivery(delivery,0,1,1,0,false,false,1,1,0);
            var after=observe(test.id);assertSource(after,true,1);assertStable(before,after);assertNoActiveRuntimeTransactions();
        });
    }
    @Test void causeCommitWithKnownAckRollbackRecoversExactlyOnce()throws Exception {
        run(CASES.get(1),test->{
            var before=test.commitAndExitProducer();
            var broken=test.consumer(before,CiPg18RecoveryWorkerTarget.Fault.ACK_UPDATE_ROLLBACK);
            broken.send("RECOVER");var failedAck=broken.await("DELIVERY_FINISHED");broken.finish();
            assertDelivery(failedAck,1,1,0,1,false,true,1,1,0);
            var middle=observe(test.id);assertSource(middle,false,1);assertStable(before,middle);assertNoActiveRuntimeTransactions();
            var recovered=test.consumer(middle,CiPg18RecoveryWorkerTarget.Fault.NONE);
            recovered.send("RECOVER");var success=recovered.await("DELIVERY_FINISHED");recovered.finish();
            assertDelivery(success,0,1,1,0,false,false,1,1,0);
            var after=observe(test.id);assertSource(after,true,1);assertStable(middle,after);assertThat(after.causeAt).isEqualTo(middle.causeAt);
        });
    }
    @Test void serviceRevocationLeavesIncompleteThenCurrentEligibilityRestoresRecovery()throws Exception {
        run(CASES.get(2),test->{
            var before=test.commitAndExitProducer();fixture.setServiceActive(false);assertService(false);
            try{
                var rejected=test.consumer(before,CiPg18RecoveryWorkerTarget.Fault.NONE);
                rejected.send("RECOVER");var refusal=rejected.await("DELIVERY_FINISHED");rejected.finish();
                assertDelivery(refusal,1,0,0,0,false,false,1,0,1);
                var middle=observe(test.id);assertSource(middle,false,0);assertStable(before,middle);assertNoActiveRuntimeTransactions();
            }finally{fixture.setServiceActive(true);}
            assertService(true);
            var restored=test.consumer(before,CiPg18RecoveryWorkerTarget.Fault.NONE);
            restored.send("RECOVER");var success=restored.await("DELIVERY_FINISHED");restored.finish();
            assertDelivery(success,0,1,1,0,false,false,1,1,0);assertSource(observe(test.id),true,1);
        });
    }
    @Test void newJvmRecoversPublicationLeftByTerminatedProducer()throws Exception {
        run(CASES.get(3),test->{
            var producer=test.producer();producer.send("CREATE");producer.await("SOURCE_COMMITTED");
            var before=observe(test.id);assertSource(before,false,0);assertNoOtherIncomplete(before);
            producer.terminate();assertThat(producer.exited()).isTrue();assertThat(producer.cleanupVerified()).isTrue();
            var consumer=test.consumer(before,CiPg18RecoveryWorkerTarget.Fault.NONE);
            assertThat(consumer.pid()).isNotEqualTo(producer.pid());assertThat(consumer.start()).isNotEqualTo(producer.start());
            consumer.send("RECOVER");var result=consumer.await("DELIVERY_FINISHED");consumer.finish();
            assertDelivery(result,0,1,1,0,false,false,1,1,0);
            var after=observe(test.id);assertSource(after,true,1);assertStable(before,after);test.restart=true;
        });
    }
    @Test void unknownAckCommitThenThrowIsObservedBeforeAnyFurtherDispatch()throws Exception {
        run(CASES.get(4),test->{
            var before=test.commitAndExitProducer();
            var uncertain=test.consumer(before,CiPg18RecoveryWorkerTarget.Fault.ACK_COMMIT_UNKNOWN);
            uncertain.send("RECOVER");var unknown=uncertain.await("UNKNOWN_ACK");uncertain.finish();
            assertDelivery(unknown,1,1,1,0,true,true,1,1,0);
            var observed=observe(test.id);assertSource(observed,true,1);assertStable(before,observed);assertNoActiveRuntimeTransactions();
            test.unknownObserved=true;
            var reconciled=test.consumer(observed,CiPg18RecoveryWorkerTarget.Fault.NONE);
            reconciled.send("RECOVER");var noWork=reconciled.await("DELIVERY_FINISHED");reconciled.finish();
            assertDelivery(noWork,0,0,0,0,false,false,0,0,0);
            var after=observe(test.id);assertSource(after,true,1);assertStable(observed,after);assertThat(after.causeAt).isEqualTo(observed.causeAt);
        });
    }

    private interface Scenario{void run(Case value)throws Exception;}
    private void run(String name,Scenario action)throws Exception {
        if(failed)throw new IllegalStateException("Prior recovery acceptance failed; no further dispatch");
        var test=new Case(name);boolean passed=false;
        try{fixture.setServiceActive(true);assertService(true);action.run(test);passed=true;}
        catch(Exception|Error failure){failed=true;throw failure;}
        finally{
            try{test.close();}catch(Exception|Error failure){passed=false;failed=true;throw failure;}
            finally{evidence.put(name,row(name,passed?"PASSED":"FAILED",test.workers,test.restart,test.unknownObserved));writeProcessEvidence();}
        }
    }
    private final class Case implements AutoCloseable {
        final UUID id=UUID.randomUUID();final String name;final Path directory;final List<CiPg18RecoveryProcess> workers=new ArrayList<>();
        boolean restart,unknownObserved;
        Case(String name)throws Exception{this.name=name;directory=workerRoot.resolve("case-"+id);
            if(Files.exists(directory,LinkOption.NOFOLLOW_LINKS))throw new IllegalStateException("Recovery case directory already exists");privateDirectory(directory);}
        CiPg18RecoveryProcess producer()throws Exception{return start(CiPg18RecoveryWorkerTarget.Mode.PRODUCER,CiPg18RecoveryWorkerTarget.Fault.NONE,null);}
        Snapshot commitAndExitProducer()throws Exception {
            var producer=producer();producer.send("CREATE");producer.await("SOURCE_COMMITTED");var snapshot=observe(id);
            assertSource(snapshot,false,0);assertNoOtherIncomplete(snapshot);producer.exitNormally();assertNoActiveRuntimeTransactions();return snapshot;
        }
        CiPg18RecoveryProcess consumer(Snapshot snapshot,CiPg18RecoveryWorkerTarget.Fault fault)throws Exception {
            if(workers.stream().anyMatch(worker->!worker.exited()))throw new IllegalStateException("Prior worker still alive");
            return start(CiPg18RecoveryWorkerTarget.Mode.CONSUMER,fault,snapshot);
        }
        CiPg18RecoveryProcess start(CiPg18RecoveryWorkerTarget.Mode mode,CiPg18RecoveryWorkerTarget.Fault fault,Snapshot snapshot)throws Exception {
            if(workers.size()>=WORKERS[CASES.indexOf(name)])throw new IllegalStateException("Worker budget exceeded");
            Map<String,Object> config=new LinkedHashMap<>();
            config.put("schemaVersion",1);config.put("caseId",id.toString());config.put("mode",mode.name());config.put("fault",fault.name());
            config.put("jdbcUrl",target.jdbcUrl());config.put("containerId",target.containerId());config.put("instanceId",target.instanceId().toString());
            config.put("imageDigest",target.imageDigest());config.put("repository",System.getenv("GITHUB_REPOSITORY"));
            config.put("runId",System.getenv("GITHUB_RUN_ID"));config.put("runAttempt",System.getenv("GITHUB_RUN_ATTEMPT"));
            config.put("ownershipFile",System.getenv("MSF_CI_PG18_OWNERSHIP_FILE"));
            config.put("runtimePasswordFile",System.getenv("MSF_CI_PG18_RUNTIME_PASSWORD_FILE"));
            config.put("capturePasswordFile",System.getenv("MSF_CI_PG18_CAPTURE_PASSWORD_FILE"));
            config.put("tenantId",fixture.tenant().toString());config.put("serviceActorId",fixture.serviceActor().toString());
            if(mode==CiPg18RecoveryWorkerTarget.Mode.PRODUCER){
                var bootstrap=fixture.bootstrapState();var identity=bootstrap.identity();
                config.put("adminPasswordFile",System.getenv("MSF_CI_PG18_ADMIN_PASSWORD_FILE"));config.put("accountId",identity.account().toString());
                config.put("actorId",identity.actor().toString());config.put("memberId",identity.member().toString());
                config.put("organizationId",identity.organization().toString());config.put("hazardId",bootstrap.hazard().toString());
            }else{config.put("sourceCommandId",snapshot.command.toString());config.put("publicationId",snapshot.publication.toString());}
            byte[] bytes=JsonMapper.builder().build().writeValueAsBytes(config);
            Path path=directory.resolve("config-"+workers.size()+".json");CiPg18RecoveryProcess.privateWrite(path,bytes);
            var worker=new CiPg18RecoveryProcess(path,bytes,mode,id,directory);workers.add(worker);worker.awaitReady();return worker;
        }
        @Override public void close(){RuntimeException failure=null;for(var worker:workers)try{worker.close();}catch(RuntimeException problem){failure=problem;}
            if(failure!=null)throw failure;}
    }

    private Snapshot observe(UUID key)throws Exception {
        try(Connection connection=fixture.observer()){
            try(var check=connection.createStatement();var rows=check.executeQuery("show transaction_read_only")){assertThat(rows.next()).isTrue();assertThat(rows.getString(1)).isEqualTo("on");}
            UUID command,scenario,audit;String trace;
            try(var query=connection.prepareStatement("select command_id,result_scenario_id,audit_event_id,trace_id from public.risk_scenario_receipt where tenant_id=? and idempotency_key=?")){
                query.setObject(1,fixture.tenant());query.setObject(2,key);
                try(var rows=query.executeQuery()){assertThat(rows.next()).isTrue();command=rows.getObject(1,UUID.class);scenario=rows.getObject(2,UUID.class);audit=rows.getObject(3,UUID.class);trace=rows.getString(4);assertThat(rows.next()).isFalse();}
            }
            UUID publication;boolean completed;
            try(var query=connection.prepareStatement("select id,listener_id,completion_date,serialized_event from public.event_publication where event_type=? and serialized_event like ?")){
                query.setString(1,"com.mightyoung.softwarefactory.dualprevention.risk.RiskScenarioDraftChangedEvent");query.setString(2,"%"+command+"%");
                try(var rows=query.executeQuery()){assertThat(rows.next()).isTrue();publication=rows.getObject(1,UUID.class);assertThat(rows.getString(2)).isEqualTo("risk-scenario-draft-change");
                    completed=rows.getObject(3)!=null;String json=rows.getString(4);assertThat(json.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(65536);
                    var event=JsonMapper.builder().build().readTree(json);assertThat(event.get("commandId").stringValue()).isEqualTo(command.toString());
                    assertThat(event.get("tenantId").stringValue()).isEqualTo(fixture.tenant().toString());assertThat(event.get("resourceId").stringValue()).isEqualTo(scenario.toString());
                    assertThat(event.get("actionCode").stringValue()).isEqualTo("risk_scenario.create");assertThat(event.get("changeKind").stringValue()).isEqualTo("CREATE");
                    assertThat(event.get("traceId").stringValue()).isEqualTo(trace);assertThat(event.get("version").longValue()).isEqualTo(1);assertThat(rows.next()).isFalse();}
            }
            List<String> queries=List.of("select count(*) from public.risk_scenario where tenant_id=? and id=?",
                    "select count(*) from public.risk_scenario_revision where tenant_id=? and command_id=?",
                    "select count(*) from public.risk_hazard where tenant_id=? and scenario_id=?",
                    "select count(*) from public.risk_hazard_revision where tenant_id=? and command_id=?",
                    "select count(*) from public.risk_scenario_revision_hazard where tenant_id=? and scenario_id=?",
                    "select count(*) from public.risk_scenario_receipt where tenant_id=? and command_id=?",
                    "select count(*) from public.audit_event where tenant_id=? and id=?");
            List<Long> counts=new ArrayList<>();for(int index=0;index<queries.size();index++)counts.add(scalar(connection,queries.get(index),fixture.tenant(),
                    index==0||index==2||index==4?scenario:index==6?audit:command));
            long causes=0;String causeAt=null;
            try(var query=connection.prepareStatement("select consumer_version,recorded_by_service_actor_id,recorded_at from public.risk_change_cause where tenant_id=? and source_command_id=?")){
                query.setObject(1,fixture.tenant());query.setObject(2,command);try(var rows=query.executeQuery()){if(rows.next()){
                    causes=1;assertThat(rows.getInt(1)).isEqualTo(1);assertThat(rows.getObject(2,UUID.class)).isEqualTo(fixture.serviceActor());causeAt=rows.getString(3);assertThat(rows.next()).isFalse();}}
            }
            long incomplete=scalar(connection,"select count(*) from public.event_publication where event_type=? and completion_date is null",
                    "com.mightyoung.softwarefactory.dualprevention.risk.RiskScenarioDraftChangedEvent");
            return new Snapshot(command,scenario,publication,List.copyOf(counts),completed,causes,causeAt,incomplete);
        }
    }
    private void assertSource(Snapshot value,boolean complete,long causes){assertThat(value.counts).containsExactly(1L,1L,1L,1L,1L,1L,1L);assertThat(value.completed).isEqualTo(complete);assertThat(value.causes).isEqualTo(causes);}
    private static void assertStable(Snapshot first,Snapshot next){assertThat(next.command).isEqualTo(first.command);assertThat(next.scenario).isEqualTo(first.scenario);assertThat(next.publication).isEqualTo(first.publication);assertThat(next.counts).isEqualTo(first.counts);}
    private static void assertNoOtherIncomplete(Snapshot value){assertThat(value.incomplete).isEqualTo(1);}
    private void assertService(boolean active)throws Exception {try(var connection=fixture.observer();var query=connection.prepareStatement("select kind,account_id,status from public.principal where id=?")){
        query.setObject(1,fixture.serviceActor());try(var rows=query.executeQuery()){assertThat(rows.next()).isTrue();assertThat(rows.getString(1)).isEqualTo("SERVICE");assertThat(rows.getObject(2)).isNull();assertThat(rows.getString(3)).isEqualTo(active?"ACTIVE":"DISABLED");assertThat(rows.next()).isFalse();}}}
    private void assertNoActiveRuntimeTransactions()throws Exception {try(var connection=fixture.observer()){assertThat(scalar(connection,
            "select count(*) from pg_stat_activity where datname=current_database() and usename in ('msf_ci_recovery_runtime','msf_ci_recovery_capture_runtime') and xact_start is not null")).isZero();}}
    private static long scalar(Connection connection,String sql,Object... values)throws Exception {try(var query=connection.prepareStatement(sql)){for(int i=0;i<values.length;i++)query.setObject(i+1,values[i]);try(var rows=query.executeQuery()){assertThat(rows.next()).isTrue();long count=rows.getLong(1);assertThat(rows.next()).isFalse();return count;}}}
    private static void assertDelivery(Map<String,Object> value,int failures,int rows,int commits,int rollbacks,boolean unknown,boolean fault,int captureBorrows,int captureCommits,int captureRollbacks){
        var expected=Map.of("failures",failures,"completionRows",rows,"ackCommits",commits,"ackRollbacks",rollbacks,"unknown",unknown,
                "faultReached",fault,"captureBorrows",captureBorrows,"captureCommits",captureCommits,"captureRollbacks",captureRollbacks);
        for(var entry:expected.entrySet()){Object actual=value.get(entry.getKey());if(entry.getValue() instanceof Number number)assertThat(CiPg18RecoveryProcess.number(actual)).isEqualTo(number.longValue());else assertThat(actual).isEqualTo(entry.getValue());}
    }
    private static Map<String,Object> row(String name,String status,List<CiPg18RecoveryProcess> workers,boolean restart,boolean unknown){
        Map<String,Object> row=new LinkedHashMap<>();row.put("name",name);row.put("status",status);row.put("workersStarted",workers.size());
        row.put("workersExited",workers.stream().filter(CiPg18RecoveryProcess::exited).count());
        row.put("ownershipVerified",!workers.isEmpty()&&workers.stream().allMatch(CiPg18RecoveryProcess::ownershipVerified));
        row.put("processCleanupVerified",!workers.isEmpty()&&workers.stream().allMatch(CiPg18RecoveryProcess::cleanupVerified));
        row.put("restartDistinctProcesses",restart);row.put("unknownObservedBeforeRecovery",unknown);return row;
    }
    private void writeProcessEvidence()throws Exception {Files.createDirectories(Path.of("target"));Files.write(Path.of("target/ci-pg18-recovery-process-evidence.json"),
            JsonMapper.builder().build().writeValueAsBytes(Map.of("schemaVersion",1,"database","msf_ci_risk_recovery","cases",new ArrayList<>(evidence.values()))));}
    private static void privateDirectory(Path path)throws Exception {if(!Files.exists(path,LinkOption.NOFOLLOW_LINKS))Files.createDirectory(path,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        if(Files.isSymbolicLink(path)||!Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS)||!Files.getPosixFilePermissions(path).equals(PosixFilePermissions.fromString("rwx------")))throw new IllegalStateException("Recovery private directory rejected");}
    private record Snapshot(UUID command,UUID scenario,UUID publication,List<Long> counts,boolean completed,long causes,String causeAt,long incomplete){}
}
