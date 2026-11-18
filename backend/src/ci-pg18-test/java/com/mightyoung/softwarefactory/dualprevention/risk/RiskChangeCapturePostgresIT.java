package com.mightyoung.softwarefactory.dualprevention.risk;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

/** Explicit, non-skipping acceptance against the job-owned PostgreSQL 18 target. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Execution(ExecutionMode.SAME_THREAD)
class RiskChangeCapturePostgresIT {
    private CiPg18RiskCaptureFixture fixture;
    @BeforeAll void open(){fixture=CiPg18RiskCaptureFixture.open(CiPg18CaptureTarget.fromEnvironment());}
    @AfterAll void close(){if(fixture!=null)fixture.close();}

    @Test @Order(1) void fullMigrationHistoryAndExactCauseSchema(){
        fixture.assertMigrationsAndCauseSchema();fixture.verifyRuntimeRights();
    }

    @Test @Order(2) void firstReplayAndActorRotationKeepOriginalAttribution(){scenario(()->{
        var event=fixture.seedEvent(fixture.tenantA());String before=fixture.sourceSnapshot(event);
        var original=service(fixture.serviceActor()).capture(event);
        assertFalse(original.replay(),"First delivery must insert");
        assertTrue(original.recordedByServiceActorId().equals(fixture.serviceActor()),"Original actor must be recorded");
        assertCapture(event,original);
        var replay=service(fixture.serviceActor()).capture(event);
        var rotated=service(fixture.alternateServiceActor()).capture(event);
        assertTrue(replay.replay()&&rotated.replay(),"Both duplicate deliveries must replay");
        sameAttribution(original,replay);sameAttribution(original,rotated);
        assertCause(event,1);unchanged(event,before);
    });}

    @Test @Order(3) void runtimePrivilegesAndTenantRlsAreExact(){scenario(()->{
        fixture.verifyRuntimeRights();
        var a=fixture.seedEvent(fixture.tenantA());var b=fixture.seedEvent(fixture.tenantB());
        String beforeA=fixture.sourceSnapshot(a),beforeB=fixture.sourceSnapshot(b);
        service(fixture.serviceActor()).capture(a);service(fixture.serviceActor()).capture(b);
        try(var connection=fixture.openRuntime()){
            assertEquals(0,visible(connection,a,b),"Unset tenant must expose no cause");
            connection.setAutoCommit(false);tenant(connection,fixture.tenantA());
            assertEquals(1,visible(connection,a,b),"Tenant A must expose exactly its cause");
            assertTrue(visibleOne(connection,a)&&!visibleOne(connection,b),"Tenant A must hide tenant B");
            connection.rollback();tenant(connection,fixture.tenantB());
            assertEquals(1,visible(connection,a,b),"Tenant B must expose exactly its cause");
            assertTrue(visibleOne(connection,b)&&!visibleOne(connection,a),"Tenant B must hide tenant A");
            connection.rollback();
        }
        var cross=fixture.seedEvent(fixture.tenantB());String beforeCross=fixture.sourceSnapshot(cross);
        expectSqlState(false,fixture.tenantA(),"42501",connection->{
            try(var statement=connection.prepareStatement("insert into public.risk_change_cause(tenant_id,source_command_id,consumer_version,recorded_at,recorded_by_service_actor_id) values(?,?,1,pg_catalog.clock_timestamp(),?)")){
                statement.setObject(1,cross.tenantId());statement.setObject(2,cross.commandId());statement.setObject(3,fixture.serviceActor());statement.executeUpdate();
            }
        });
        assertCause(cross,0);unchanged(a,beforeA);unchanged(b,beforeB);unchanged(cross,beforeCross);
    });}

    @Test @Order(4) void forgedSourceTupleCreatesNoCause(){scenario(()->{
        var event=fixture.seedEvent(fixture.tenantA());String before=fixture.sourceSnapshot(event);
        var forged=List.of(
            new RiskScenarioDraftChangedEvent(event.tenantId(),event.commandId(),event.resourceType(),event.resourceId(),"risk_scenario.update",2,event.occurredAt(),event.traceId(),"UPDATE"),
            copy(event,event.tenantId(),UUID.randomUUID(),event.resourceId(),event.occurredAt(),event.traceId()),
            copy(event,event.tenantId(),event.commandId(),UUID.randomUUID(),event.occurredAt(),event.traceId()),
            copy(event,event.tenantId(),event.commandId(),event.resourceId(),event.occurredAt().plusNanos(1000),event.traceId()),
            copy(event,event.tenantId(),event.commandId(),event.resourceId(),event.occurredAt(),UUID.randomUUID()),
            copy(event,fixture.tenantB(),event.commandId(),event.resourceId(),event.occurredAt(),event.traceId()));
        for(var candidate:forged){unavailable(()->service(fixture.serviceActor()).capture(candidate));assertCause(candidate,0);}
        assertCause(event,0);unchanged(event,before);
    });}

    @Test @Order(5) void missingInstantExecuteRefusesAndRestoredGrantCaptures(){scenario(()->{
        var event=fixture.seedEvent(fixture.tenantA());String before=fixture.sourceSnapshot(event);
        fixture.revokeInstantFunction();
        try{unavailable(()->service(fixture.serviceActor()).capture(event));assertCause(event,0);}
        finally{fixture.grantInstantFunction();}
        fixture.verifyRuntimeRights();
        assertFalse(service(fixture.serviceActor()).capture(event).replay(),"Restored exact function grant must permit the original delivery");
        assertCause(event,1);unchanged(event,before);
    });}

    @Test @Order(6) void runtimeMutationPrivilegesDeny(){scenario(()->mutations(false,"42501"));}
    @Test @Order(7) void ownerMutationTriggersReject55000(){scenario(()->mutations(true,"55000"));}
    @Test @Order(8) void winnerCommitBlocksThenWaiterReplays(){scenario(()->concurrent(false));}
    @Test @Order(9) void winnerRollbackBlocksThenWaiterInserts(){scenario(()->concurrent(true));}

    private void concurrent(boolean rollbackWinner)throws Exception {
        var event=fixture.seedEvent(fixture.tenantA());String before=fixture.sourceSnapshot(event);
        try(var barrier=new RiskCaptureJdbcBarrier(fixture.runtimeSource(),rollbackWinner);var admin=fixture.openAdmin()){
            var capture=RiskChangeCaptureService.create(barrier,fixture.serviceActor(),Set.of(fixture.tenantA(),fixture.tenantB()));
            var executor=Executors.newFixedThreadPool(2);
            try{
                Future<RiskChangeCaptureService.Capture> winner=executor.submit(()->capture.capture(event));
                barrier.awaitWinnerInsert();
                Future<RiskChangeCaptureService.Capture> waiter=executor.submit(()->capture.capture(event));
                try{
                    var observation=barrier.awaitActualUniqueIndexBlock(admin);
                    assertTrue(observation.blockedByWinner()&&observation.transactionIdWait(),"Real unique-index transaction lock must block the second physical connection");
                }finally{barrier.releaseWinner();}
                if(rollbackWinner){
                    try{winner.get(5,TimeUnit.SECONDS);fail("Injected precommit failure must reject the winner");}
                    catch(ExecutionException expected){assertUnavailable(expected.getCause());}
                    var inserted=waiter.get(5,TimeUnit.SECONDS);assertFalse(inserted.replay(),"Waiter must insert after acknowledged winner rollback");assertCapture(event,inserted);
                    assertEquals(0,barrier.winnerCommitCalls(),"Rollback schedule must never attempt winner commit");
                    assertEquals(1,barrier.winnerRollbackCalls(),"Rollback must be attempted exactly once");
                    assertTrue(barrier.winnerRolledBack()&&!barrier.winnerCommitted(),"Actual rollback acknowledgment is required");
                }else{
                    var inserted=winner.get(5,TimeUnit.SECONDS);var replay=waiter.get(5,TimeUnit.SECONDS);
                    assertFalse(inserted.replay(),"Winner must insert");assertTrue(replay.replay(),"Waiter must replay after winner commit");
                    assertCapture(event,inserted);sameAttribution(inserted,replay);
                    assertEquals(1,barrier.winnerCommitCalls(),"Winner must commit exactly once");
                    assertTrue(barrier.winnerCommitted()&&!barrier.winnerRolledBack(),"Actual commit acknowledgment is required");
                }
                assertTrue(barrier.waiterCommitted(),"Waiter commit must be acknowledged");
                assertEquals(2,barrier.physicalConnectionCount(),"Capture schedules must use exactly two physical connections");
            }finally{
                barrier.releaseWinner();executor.shutdownNow();
                assertTrue(executor.awaitTermination(12,TimeUnit.SECONDS),"Capture tasks must terminate without a reset or compensating write");
            }
        }
        assertCause(event,1);unchanged(event,before);
    }

    private void mutations(boolean admin,String expected)throws Exception {
        var event=fixture.seedEvent(fixture.tenantA());String before=fixture.sourceSnapshot(event);
        var recorded=service(fixture.serviceActor()).capture(event);
        for(String sql:List.of("update public.risk_change_cause set consumer_version=consumer_version where tenant_id=? and source_command_id=?",
                "delete from public.risk_change_cause where tenant_id=? and source_command_id=?","truncate table public.risk_change_cause")){
            expectSqlState(admin,event.tenantId(),expected,connection->{try(var statement=connection.prepareStatement(sql)){
                if(!sql.startsWith("truncate")){statement.setObject(1,event.tenantId());statement.setObject(2,event.commandId());}statement.execute();
            }});
            assertCause(event,1);sameAttribution(recorded,service(fixture.serviceActor()).capture(event));unchanged(event,before);
        }
    }
    private void expectSqlState(boolean admin,UUID tenant,String expected,SqlAction action)throws Exception {
        try(var connection=admin?fixture.openAdmin():fixture.openRuntime()){
            connection.setAutoCommit(false);tenant(connection,tenant);
            String state=null;
            try{action.run(connection);}catch(SQLException failure){state=failure.getSQLState();}
            finally{connection.rollback();}
            assertTrue(expected.equals(state),"Database denial must have the expected SQLState");
        }
    }
    private static void tenant(Connection connection,UUID tenant)throws SQLException {
        try(var statement=connection.prepareStatement("select pg_catalog.set_config('app.tenant_id',?,true)")){
            statement.setString(1,tenant.toString());try(var rows=statement.executeQuery()){
                assertTrue(rows.next()&&tenant.toString().equals(rows.getString(1))&&!rows.next(),"Transaction-local tenant must be exact");
            }
        }
    }
    private static int visible(Connection connection,RiskScenarioDraftChangedEvent a,RiskScenarioDraftChangedEvent b)throws SQLException {
        try(var statement=connection.prepareStatement("select tenant_id,source_command_id from public.risk_change_cause where source_command_id in (?,?)")){
            statement.setObject(1,a.commandId());statement.setObject(2,b.commandId());int count=0;
            try(var rows=statement.executeQuery()){while(rows.next()){assertTrue(rows.getObject(1) instanceof UUID&&rows.getObject(2) instanceof UUID,"Cause identity must retain UUID types");count++;}}return count;
        }
    }
    private static boolean visibleOne(Connection connection,RiskScenarioDraftChangedEvent event)throws SQLException {
        try(var statement=connection.prepareStatement("select source_command_id from public.risk_change_cause where tenant_id=? and source_command_id=?")){
            statement.setObject(1,event.tenantId());statement.setObject(2,event.commandId());try(var rows=statement.executeQuery()){
                boolean found=rows.next();if(found)assertTrue(event.commandId().equals(rows.getObject(1))&&!rows.next(),"Visible cause must be exact and unique");return found;
            }
        }
    }
    private RiskChangeCaptureService service(UUID actor){return RiskChangeCaptureService.create(fixture.runtimeSource(),actor,Set.of(fixture.tenantA(),fixture.tenantB()));}
    private void assertCause(RiskScenarioDraftChangedEvent event,long expected){assertEquals(expected,fixture.causeCount(event.tenantId(),event.commandId()),"Cause count must match the schedule");}
    private void unchanged(RiskScenarioDraftChangedEvent event,String before){assertTrue(before.equals(fixture.sourceSnapshot(event)),"Source graph and audit history must remain unchanged");}
    private static void assertCapture(RiskScenarioDraftChangedEvent event,RiskChangeCaptureService.Capture value){
        assertTrue(event.tenantId().equals(value.tenantId())&&event.commandId().equals(value.commandId())&&value.consumerVersion()==1,"Capture must preserve exact source identity and version");
        assertTrue(value.recordedAt()!=null&&value.recordedAt().getNano()%1000==0,"Database attribution must retain microsecond precision");
    }
    private static void sameAttribution(RiskChangeCaptureService.Capture a,RiskChangeCaptureService.Capture b){
        assertTrue(a.tenantId().equals(b.tenantId())&&a.commandId().equals(b.commandId())&&a.consumerVersion()==b.consumerVersion()
                &&a.recordedAt().equals(b.recordedAt())&&a.recordedByServiceActorId().equals(b.recordedByServiceActorId()),"Replay must preserve all five original cause columns");
    }
    private static RiskScenarioDraftChangedEvent copy(RiskScenarioDraftChangedEvent e,UUID tenant,UUID command,UUID resource,Instant time,UUID trace){
        return new RiskScenarioDraftChangedEvent(tenant,command,e.resourceType(),resource,e.actionCode(),e.version(),time,trace,e.changeKind());
    }
    private static void unavailable(Runnable action){try{action.run();fail("Capture must reject the invalid operation");}catch(IllegalStateException expected){assertUnavailable(expected);}}
    private static void assertUnavailable(Throwable failure){
        assertTrue(failure instanceof IllegalStateException&&"Risk change capture is unavailable".equals(failure.getMessage())
                &&failure.getCause()==null&&failure.getSuppressed().length==0,"Capture must expose only its fixed safe exception");
    }
    private static void scenario(CheckedAction action){
        try{action.run();}catch(AssertionError failure){throw failure;}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new AssertionError("CI PG18 scenario interrupted");}
        catch(Exception failure){throw new AssertionError("CI PG18 scenario failed; inspect only the safe phase diagnostic");}
    }
    @FunctionalInterface private interface CheckedAction{void run()throws Exception;}
    @FunctionalInterface private interface SqlAction{void run(Connection connection)throws SQLException;}
}
