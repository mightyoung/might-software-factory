package com.mightyoung.softwarefactory.support;

import com.mightyoung.softwarefactory.authorization.*;
import com.mightyoung.softwarefactory.identity.HumanSessionAuthorityTransaction;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import javax.sql.DataSource;
import org.jooq.SQLDialect;
import org.jooq.conf.Settings;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real jOOQ and Spring pipeline; the driver is a fixture, so this is not PostgreSQL acceptance. */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class G014JooqFinalQueryTest {
    private static final String SQL="select 7 as answer";
    @Test @Order(1) void coldFirstUseDirectlyInFinalPermanentlyPoisonsEvenWhenParentJvmIsWarm(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path output)throws Exception {
        // Deliberately consume normal first-use initialization in this JVM. Class/suite order is irrelevant.
        run(false,false,true);
        String javaBinary=java.nio.file.Path.of(System.getProperty("java.home"),"bin","java").toString();
        String classpath=System.getProperty("surefire.test.class.path",System.getProperty("java.class.path"));
        var mockitoAgent=java.nio.file.Path.of(org.mockito.Mockito.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        assertThat(mockitoAgent).isRegularFile();
        var log=output.resolve("cold-final-probe.log");
        Process child=new ProcessBuilder(javaBinary,"-javaagent:"+mockitoAgent,"-cp",classpath,ColdFinalProbe.class.getName())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            boolean completed=child.waitFor(30,java.util.concurrent.TimeUnit.SECONDS);
            assertThat(completed).as("fresh JVM cold-FINAL probe completed; log: %s",log).isTrue();
            String transcript=java.nio.file.Files.readString(log);
            assertThat(child.exitValue()).as("cold-FINAL child output: %s",transcript).isZero();
            assertThat(transcript.lines()).contains("G014_COLD_FINAL_REJECTED");
        }finally{
            if(child.isAlive()){child.destroyForcibly();assertThat(child.waitFor(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();}
            // Preserve the child's actual transcript in Surefire XML, including failures and JVM banners.
            System.out.print(java.nio.file.Files.readString(log));
        }
    }
    /** A fresh process, never a logger reset, proves the actual pinned jOOQ first-use path. */
    public static final class ColdFinalProbe {
        public static void main(String[] args)throws Exception {
            new G014JooqFinalQueryTest().run(false,true,false);
            System.out.println("G014_COLD_FINAL_REJECTED");
        }
    }
    @Test @Order(2) void typedJooqFinalReadUsesSameGuardedLeaseAndNoMetadataOrWarnings()throws Exception {run(false,false,true);}
    @Test @Order(3) void optionalJooqWarningFetchIsRejectedRatherThanSilentlyFaked()throws Exception {run(true,true,true);}
    private void run(boolean fetchWarnings,boolean rejection,boolean stagingRead)throws Exception {
        var pool=mock(DataSource.class);var driver=mock(Connection.class);var statement=mock(PreparedStatement.class);var rows=mock(ResultSet.class);
        when(pool.getConnection()).thenReturn(driver);when(driver.getAutoCommit()).thenReturn(true,false);
        when(driver.isReadOnly()).thenReturn(false);when(driver.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_READ_COMMITTED);
        when(driver.prepareStatement(SQL)).thenReturn(statement);when(statement.execute()).thenReturn(true);when(statement.getResultSet()).thenReturn(rows);
        when(rows.next()).thenReturn(true,false,true,false);when(rows.getInt(1)).thenReturn(7);
        var metadata=mock(DatabaseMetaData.class);when(driver.getMetaData()).thenReturn(metadata);
        when(metadata.getDatabaseMajorVersion()).thenReturn(18);when(metadata.getDatabaseMinorVersion()).thenReturn(0);
        when(metadata.getDatabaseProductVersion()).thenReturn("18.0");
        var manager=new G014JdbcTransactionManager(pool,List.of(new TransactionSqlSealGate.SqlTemplate(SQL,0,true,null)));
        var authority=mock(HumanSessionAuthorityTransaction.class);var actions=mock(EntityActionAuthorization.class);
        var scopes=mock(EntityActionAuthorization.FinalActionScopes.class);when(scopes.observedAt()).thenReturn(Instant.parse("2026-11-04T00:00:00Z"));
        when(actions.requireFinalCommand(any(),any(),any())).thenReturn(scopes);
        when(authority.withGuarded(any(),any())).thenAnswer(invocation->{
            Function<HumanSessionAuthorityTransaction.GuardedBoundary,Object> work=invocation.getArgument(1);
            Object result=work.apply(mock(HumanSessionAuthorityTransaction.GuardedBoundary.class));
            var current=manager.currentFrame().jdbc.connection;current.getAutoCommit();current.isReadOnly();current.getTransactionIsolation();return result;
        });
        var metadataAttempts=new java.util.concurrent.atomic.AtomicInteger();
        var observedSource=new TransactionAwareDataSourceProxy(manager.commandDataSource()){
            @Override public Connection getConnection()throws SQLException {
                Connection actual=super.getConnection();
                return (Connection)java.lang.reflect.Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},
                        (proxy,method,args)->{
                            if(method.getName().equals("getMetaData"))metadataAttempts.incrementAndGet();
                            try{return method.invoke(actual,args);}
                            catch(java.lang.reflect.InvocationTargetException failure){throw failure.getCause();}
                        });
            }
        };
        var context=DSL.using(observedSource,SQLDialect.POSTGRES,
                new Settings().withExecuteLogging(false).withFetchWarnings(false));
        var plan=mock(TransactionSqlSealGate.CommandPlan.class);when(plan.action()).thenReturn(RegisteredEntityAction.WORKSHOP_CREATE);
        when(plan.commandId()).thenReturn(UUID.randomUUID());when(plan.targetId()).thenReturn(UUID.randomUUID());
        var validator=new TransactionSqlSealGate.RegisteredCommandValidator(){
            public void requireOwnedPlan(TransactionSqlSealGate.CommandPlan candidate){if(candidate!=plan)throw new IllegalStateException();}
            public Set<RegisteredEntityAction> requiredActions(TransactionSqlSealGate.CommandPlan ignored){return Set.of(RegisteredEntityAction.WORKSHOP_CREATE);}
            public void requireStaged(TransactionSqlSealGate.CommandPlan ignored,List<TransactionSqlSealGate.SqlExecution> actual){assertThat(actual).isEmpty();}
            public void requireFinal(TransactionSqlSealGate.CommandPlan ignored,EntityActionAuthorization.FinalActionScopes ignoredScopes){
                assertThat(context.resultQuery(SQL).coerce(DSL.field("answer",Integer.class)).fetchSingle().get(0,Integer.class)).isEqualTo(7);
            }
        };
        var gate=new TransactionSqlSealGate(manager,authority,actions,Map.of(RegisteredEntityAction.WORKSHOP_CREATE,validator));
        var scope=mock(HumanSessionAuthorityTransaction.WriteScope.class);when(scope.guard()).thenReturn(HumanSessionAuthorityTransaction.Guard.FINDING_COMMAND);
        var transaction=new TransactionTemplate(manager);transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        G014JdbcGuard[] lease=new G014JdbcGuard[1];
        org.assertj.core.api.ThrowableAssert.ThrowingCallable work=()->transaction.execute(status->{
            gate.bindCommand(plan,scope);lease[0]=manager.currentFrame().jdbc;
            if(stagingRead){
                // The production graph must perform initial authorization SQL in STAGING on this same context/guard.
                // Exercise real jOOQ acquisition/version inspection here, never mutate its private logger/cache.
                assertThat(context.resultQuery(SQL).coerce(DSL.field("answer",Integer.class)).fetchSingle().get(0,Integer.class)).isEqualTo(7);
                clearInvocations(driver);metadataAttempts.set(0);
            }
            context.settings().setFetchWarnings(fetchWarnings);
            gate.finalizeCommand();return null;
        });
        if(rejection){assertThatThrownBy(work).isInstanceOf(RuntimeException.class);assertThat(lease[0].poisoned()).isTrue();verify(driver,never()).commit();verify(driver).rollback();}
        else{assertThatCode(work).doesNotThrowAnyException();verify(driver,times(1)).commit();}
        assertThat(metadataAttempts.get()).as("actual jOOQ metadata invocation before delegating to guarded connection")
                .isEqualTo(stagingRead?0:1);
        verify(pool,times(1)).getConnection();
        if(stagingRead)verify(statement,times(2)).execute();else verify(statement,never()).execute();
        verify(statement,never()).executeQuery();
        verify(rows,never()).getMetaData();verify(statement,never()).getMetaData();verify(driver,never()).getMetaData();
        // Even the negative case is rejected before delegating the unsupported getter.
        verify(statement,never()).getWarnings();
    }
}
