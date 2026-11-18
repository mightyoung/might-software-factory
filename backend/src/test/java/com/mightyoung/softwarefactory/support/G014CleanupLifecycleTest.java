package com.mightyoung.softwarefactory.support;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import com.mightyoung.softwarefactory.authorization.EntityActionAuthorization;
import com.mightyoung.softwarefactory.authorization.RegisteredEntityAction;
import com.mightyoung.softwarefactory.dualprevention.asset.AssetFoundationFailure;
import com.mightyoung.softwarefactory.identity.HumanSessionAuthorityTransaction;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Actual original Spring/G014 lifecycle with controlled JDBC; no real PostgreSQL or receipt replay claim. */
class G014CleanupLifecycleTest {
    private static final String SECRET="PRIVATE_G014_CLEANUP_SENTINEL";
    private enum FailureKind { SQL, RUNTIME, ERROR }
    private enum CleanupStep {
        ISOLATION("restore.isolation"),READ_ONLY("restore.readOnly"),AUTO_COMMIT("restore.autoCommit"),CLOSE("close");
        final String operation;CleanupStep(String operation){this.operation=operation;}
    }
    private static Stream<Arguments> cleanupFailures(){
        return Arrays.stream(CleanupStep.values()).flatMap(step->Arrays.stream(FailureKind.values()).map(kind->Arguments.of(step,kind)));
    }
    private static Stream<Arguments> baselineFailures(){
        return Stream.of("baseline.autoCommit","baseline.readOnly","baseline.isolation")
                .flatMap(step->Arrays.stream(FailureKind.values()).map(kind->Arguments.of(step,kind)));
    }
    private static Stream<Arguments> setupFailures(){
        return Stream.of("setup.readOnly","setup.isolation","setup.autoCommit")
                .flatMap(step->Arrays.stream(FailureKind.values()).map(kind->Arguments.of(step,kind)));
    }
    private static Stream<Arguments> manualBaselines(){
        return Stream.of(false,true).flatMap(readOnly->Stream.of(Connection.TRANSACTION_READ_COMMITTED,Connection.TRANSACTION_SERIALIZABLE)
                .map(isolation->Arguments.of(readOnly,isolation)));
    }
    private static Stream<Arguments> businessCleanupFailures(){
        return Stream.of(403,409).flatMap(status->Arrays.stream(CleanupStep.values()).flatMap(step->
                Arrays.stream(FailureKind.values()).map(kind->Arguments.of(status,step,kind))));
    }

    @Test void completeImmutableBaselinePrecedesMutationAndSurvivesFinalSealObservations()throws Exception {
        Fixture f=fixture(null,FailureKind.SQL,true,true,Connection.TRANSACTION_SERIALIZABLE);
        String result=f.transaction.execute(status->{
            f.seen.set(f.manager.currentFrame().jdbc);
            assertThat(f.state.autoCommit).isFalse();assertThat(f.state.readOnly).isFalse();
            assertThat(f.state.isolation).isEqualTo(Connection.TRANSACTION_READ_COMMITTED);
            f.seal();return "original result";
        });
        assertThat(result).isEqualTo("original result");
        assertThat(f.state.operations.subList(0,4)).containsExactly("acquire","getAutoCommit","isReadOnly","getIsolation");
        assertThat(f.state.operations).containsSubsequence("setReadOnly:false","setIsolation:2","setAutoCommit:false",
                "commit","setIsolation:8","setReadOnly:true","setAutoCommit:true","close");
        assertThat(f.state.autoCommit).isTrue();assertThat(f.state.readOnly).isTrue();
        assertThat(f.state.isolation).isEqualTo(Connection.TRANSACTION_SERIALIZABLE);
        assertThat(f.seen.get().phase()).isEqualTo(G014JdbcGuard.Phase.COMMITTED);
        assertThat(f.seen.get().cleanupOutcome()).isEqualTo(G014JdbcGuard.CleanupOutcome.COMPLETE);
        assertThat(f.manager.retainedUnusableLeaseCount()).isZero();assertUnbound(f);
    }

    @ParameterizedTest @MethodSource("manualBaselines")
    void initialManualCommitRemainsFalseAndOnlyChangedAttributesAreRestored(boolean readOnly,int isolation)throws Exception {
        Fixture f=fixture(null,FailureKind.SQL,false,readOnly,isolation);
        f.transaction.execute(status->{f.seen.set(f.manager.currentFrame().jdbc);f.seal();return null;});
        assertThat(f.state.autoCommit).isFalse();assertThat(f.state.readOnly).isEqualTo(readOnly);
        assertThat(f.state.isolation).isEqualTo(isolation);
        verify(f.driver,never()).setAutoCommit(anyBoolean());
        verify(f.driver,times(readOnly?2:0)).setReadOnly(anyBoolean());
        verify(f.driver,times(isolation==Connection.TRANSACTION_READ_COMMITTED?0:2)).setTransactionIsolation(anyInt());
        assertThat(f.state.operations).containsSubsequence("commit","close");
        assertThat(f.seen.get().phase()).isEqualTo(G014JdbcGuard.Phase.COMMITTED);
        assertThat(f.manager.retainedUnusableLeaseCount()).isZero();assertUnbound(f);
    }

    @Test void acknowledgedBusinessRollbackRestoresFullBaselineBeforeOnePoolReturn()throws Exception {
        Fixture f=fixture(null,FailureKind.SQL,true,true,Connection.TRANSACTION_SERIALIZABLE);
        var original=new AssetFoundationFailure(409);
        Throwable failure=catchThrowable(()->f.transaction.execute(status->{f.seen.set(f.manager.currentFrame().jdbc);throw original;}));
        assertThat(failure).isSameAs(original);
        assertThat(f.state.operations).containsSubsequence("rollback","setIsolation:8","setReadOnly:true","setAutoCommit:true","close");
        assertThat(f.state.autoCommit).isTrue();assertThat(f.state.readOnly).isTrue();
        assertThat(f.state.isolation).isEqualTo(Connection.TRANSACTION_SERIALIZABLE);
        assertThat(f.seen.get().phase()).isEqualTo(G014JdbcGuard.Phase.ROLLED_BACK);
        assertThat(f.seen.get().cleanupOutcome()).isEqualTo(G014JdbcGuard.CleanupOutcome.COMPLETE);
        verify(f.driver,never()).commit();verify(f.driver,times(1)).rollback();verify(f.driver,times(1)).close();
        assertThat(f.manager.retainedUnusableLeaseCount()).isZero();assertUnbound(f);
    }

    @ParameterizedTest @MethodSource("cleanupFailures")
    void trueCommitCleanupFaultKeepsCommittedFactAndStopsSubsequentOperations(CleanupStep step,FailureKind kind)throws Exception {
        Fixture f=fixture(step.operation,kind,true,true,Connection.TRANSACTION_SERIALIZABLE);
        AtomicReference<G014JdbcTransactionManager.Frame> observed=new AtomicReference<>();
        Throwable failure=catchThrowable(()->f.transaction.execute(status->{observed.set(f.manager.currentFrame());f.seen.set(observed.get().jdbc);f.seal();return "never success";}));
        assertThat(failure).isExactlyInstanceOf(G014JdbcTransactionManager.LifecycleException.class).hasNoCause();
        assertThat(((G014JdbcTransactionManager.LifecycleException)failure).outcome())
                .isEqualTo(G014JdbcTransactionManager.LifecycleOutcome.COMMITTED_CLEANUP_FAILED);
        assertThat(failure.getMessage()).isEqualTo("G014 committed transaction cleanup failed");
        assertThat(observed.get().unknownOutcome).isNull();
        assertThat(failure.getSuppressed()).isEmpty();
        assertThat(f.seen.get().phase()).isEqualTo(G014JdbcGuard.Phase.COMMITTED);
        assertThat(f.seen.get().cleanupOutcome()).isEqualTo(step==CleanupStep.CLOSE
                ?G014JdbcGuard.CleanupOutcome.CLOSE_UNKNOWN:G014JdbcGuard.CleanupOutcome.RESET_FAILED);
        verify(f.driver,times(1)).commit();verify(f.driver,never()).rollback();
        switch(step){
            case ISOLATION->{verify(f.driver,never()).setReadOnly(true);verify(f.driver,never()).setAutoCommit(true);verify(f.driver,never()).close();}
            case READ_ONLY->{verify(f.driver).setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);verify(f.driver,never()).setAutoCommit(true);verify(f.driver,never()).close();}
            case AUTO_COMMIT->{verify(f.driver).setReadOnly(true);verify(f.driver,never()).close();}
            case CLOSE->{verify(f.driver,times(1)).close();assertThat(f.state.returned).isTrue();}
        }
        List<String> before=List.copyOf(f.state.operations);
        assertThatThrownBy(f.seen.get()::cleanup).isInstanceOf(IllegalStateException.class);
        assertThat(f.state.operations).containsExactlyElementsOf(before);
        assertThat(f.manager.retainedUnusableLeaseCount()).isEqualTo(1);assertUnbound(f);
    }

    @ParameterizedTest @MethodSource("baselineFailures")
    void incompleteBaselineHasNoMutationRollbackCloseOrFabricatedUnknown(String operation,FailureKind kind)throws Exception {
        Fixture f=fixture(operation,kind,true,true,Connection.TRANSACTION_SERIALIZABLE);
        Throwable failure=catchThrowable(()->f.transaction.execute(status->{throw new AssertionError("body must not run");}));
        assertThat(failure).isExactlyInstanceOf(CannotCreateTransactionException.class)
                .hasMessage("G014 connection baseline is unavailable").hasNoCause();
        assertThat(failure).isNotInstanceOf(G014JdbcTransactionManager.LifecycleException.class);
        verify(f.driver,never()).setAutoCommit(anyBoolean());verify(f.driver,never()).setReadOnly(anyBoolean());
        verify(f.driver,never()).setTransactionIsolation(anyInt());verify(f.driver,never()).commit();
        verify(f.driver,never()).rollback();verify(f.driver,never()).close();
        assertThat(f.manager.retainedUnusableLeaseCount()).isEqualTo(1);assertUnbound(f);
    }

    @ParameterizedTest @MethodSource("setupFailures")
    void setupSetterTakingEffectThenThrowingRestoresEveryPossiblyChangedAttributeAfterRollbackAck(String operation,FailureKind kind)throws Exception {
        Fixture f=fixture(operation,kind,true,true,Connection.TRANSACTION_SERIALIZABLE);
        Throwable failure=catchThrowable(()->f.transaction.execute(status->{throw new AssertionError("body must not run");}));
        assertThat(failure).isInstanceOf(CannotCreateTransactionException.class)
                .isNotInstanceOf(G014JdbcTransactionManager.LifecycleException.class);
        assertThat(f.state.baselineComplete()).isTrue();assertThat(f.state.acknowledged).isTrue();
        assertThat(f.state.autoCommit).isTrue();assertThat(f.state.readOnly).isTrue();
        assertThat(f.state.isolation).isEqualTo(Connection.TRANSACTION_SERIALIZABLE);
        verify(f.driver,never()).commit();verify(f.driver,times(1)).rollback();verify(f.driver,times(1)).close();
        assertThat(f.state.operations).containsSubsequence("rollback","close");
        if(operation.equals("setup.readOnly")){
            assertSafeCauseTree(failure);assertThat(f.state.readOnlyAtFailure).isFalse();
            assertThat(f.state.operations).containsSubsequence("rollback","setReadOnly:true","close");
            verify(f.driver,never()).setTransactionIsolation(anyInt());verify(f.driver,never()).setAutoCommit(anyBoolean());
        }else if(operation.equals("setup.isolation")){
            assertThat(f.state.operations).containsSubsequence("rollback","setIsolation:8","setReadOnly:true","close");
            verify(f.driver,never()).setAutoCommit(anyBoolean());
        }else assertThat(f.state.operations).containsSubsequence("rollback","setIsolation:8","setReadOnly:true","setAutoCommit:true","close");
        assertThat(f.manager.retainedUnusableLeaseCount()).isZero();assertUnbound(f);
    }

    @ParameterizedTest @MethodSource("businessCleanupFailures")
    void acknowledgedRollbackCleanupFaultPreservesOriginalBusiness403Or409(int status,CleanupStep step,FailureKind kind)throws Exception {
        Fixture f=fixture(step.operation,kind,true,true,Connection.TRANSACTION_SERIALIZABLE);
        var original=new AssetFoundationFailure(status);
        Throwable failure=catchThrowable(()->f.transaction.execute(transaction->{f.seen.set(f.manager.currentFrame().jdbc);throw original;}));
        assertThat(failure).isSameAs(original);assertThat(((AssetFoundationFailure)failure).status()).isEqualTo(status);
        assertThat(f.seen.get().phase()).isEqualTo(G014JdbcGuard.Phase.ROLLED_BACK);
        verify(f.driver,never()).commit();verify(f.driver,times(1)).rollback();
        verify(f.driver,times(step==CleanupStep.CLOSE?1:0)).close();
        assertThat(f.manager.retainedUnusableLeaseCount()).isEqualTo(1);assertUnbound(f);
    }

    @Test void unknownTransactionOutcomesTakePriorityOverAllCleanupFacts()throws Exception {
        for(boolean commit:List.of(false,true))for(FailureKind kind:FailureKind.values()){
            Fixture f=fixture(commit?"commit":"rollback",kind,true,true,Connection.TRANSACTION_SERIALIZABLE);
            Throwable failure=catchThrowable(()->f.transaction.execute(status->{
                f.seen.set(f.manager.currentFrame().jdbc);
                if(!commit)throw new AssetFoundationFailure(409);
                f.seal();return null;
            }));
            assertThat(failure).isExactlyInstanceOf(G014JdbcTransactionManager.LifecycleException.class).hasNoCause();
            assertThat(((G014JdbcTransactionManager.LifecycleException)failure).outcome()).isEqualTo(commit
                    ?G014JdbcTransactionManager.LifecycleOutcome.COMMIT_UNKNOWN:G014JdbcTransactionManager.LifecycleOutcome.ROLLBACK_UNKNOWN);
            assertThat(f.seen.get().phase()).isEqualTo(G014JdbcGuard.Phase.UNKNOWN);
            assertThat(f.seen.get().cleanupOutcome()).isEqualTo(G014JdbcGuard.CleanupOutcome.NOT_ATTEMPTED);
            verify(f.driver,times(commit?1:0)).commit();verify(f.driver,times(commit?0:1)).rollback();
            verify(f.driver,never()).setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
            verify(f.driver,never()).setReadOnly(true);verify(f.driver,never()).setAutoCommit(true);verify(f.driver,never()).close();
            assertThat(f.manager.retainedUnusableLeaseCount()).isEqualTo(1);assertUnbound(f);
        }
    }

    @Test void preboundPoolAndPrivateCommandHoldersRefuseBeforeBorrowAndRemainUntouched()throws Exception {
        for(boolean privateSource:List.of(false,true)){
            Fixture f=fixture(null,FailureKind.SQL,true,false,Connection.TRANSACTION_READ_COMMITTED);
            Object key=privateSource?f.manager.commandDataSource():f.pool;
            Connection foreignConnection=mock(Connection.class);ConnectionHolder foreign=new ConnectionHolder(foreignConnection);
            TransactionSynchronizationManager.bindResource(key,foreign);
            try{
                assertThatThrownBy(()->f.transaction.execute(status->{throw new AssertionError("body");}))
                        .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
                assertThat(TransactionSynchronizationManager.getResource(key)).isSameAs(foreign);
                verifyNoInteractions(f.pool,f.driver,foreignConnection);
            }finally{assertThat(TransactionSynchronizationManager.unbindResource(key)).isSameAs(foreign);}
        }
    }

    @Test void actualInfoAndDebugCleanupAndIncompleteBaselineLogsNeverRenderOriginalFailures()throws Exception {
        for(Level level:List.of(Level.INFO,Level.DEBUG))withLogs(level,events->{
            for(CleanupStep step:CleanupStep.values())for(FailureKind kind:FailureKind.values()){
                Fixture f=fixture(step.operation,kind,true,true,Connection.TRANSACTION_SERIALIZABLE);
                Throwable failure=catchThrowable(()->f.transaction.execute(status->{f.seal();return null;}));
                assertThat(failure).isExactlyInstanceOf(G014JdbcTransactionManager.LifecycleException.class).hasNoCause();assertUnbound(f);
            }
            for(String operation:List.of("baseline.autoCommit","baseline.readOnly","baseline.isolation"))for(FailureKind kind:FailureKind.values()){
                Fixture f=fixture(operation,kind,true,true,Connection.TRANSACTION_SERIALIZABLE);
                assertThatThrownBy(()->f.transaction.execute(status->{throw new AssertionError("body");}))
                        .isExactlyInstanceOf(CannotCreateTransactionException.class).hasNoCause();assertUnbound(f);
            }
            for(FailureKind kind:FailureKind.values()){
                Fixture f=fixture("setup.readOnly",kind,true,true,Connection.TRANSACTION_SERIALIZABLE);
                Throwable failure=catchThrowable(()->f.transaction.execute(status->{throw new AssertionError("body");}));
                assertThat(failure).isExactlyInstanceOf(CannotCreateTransactionException.class);
                assertSafeCauseTree(failure);assertThat(f.state.readOnlyAtFailure).isFalse();
                assertThat(f.state.readOnly).isTrue();verify(f.driver,times(1)).rollback();verify(f.driver,times(1)).close();
                assertUnbound(f);
            }
            StringBuilder rendered=new StringBuilder();for(ILoggingEvent event:events.list){
                rendered.append(event.getFormattedMessage()).append('\n');
                if(event.getThrowableProxy()!=null)rendered.append(ThrowableProxyUtil.asString(event.getThrowableProxy()));
            }
            assertThat(rendered.toString()).contains("G014 acknowledged transaction cleanup did not complete").doesNotContain(SECRET);
        });
    }

    private static void assertSafeCauseTree(Throwable failure){
        Set<Throwable> visited=Collections.newSetFromMap(new IdentityHashMap<>());
        List<Throwable> queue=new ArrayList<>();queue.add(failure);
        for(int i=0;i<queue.size();i++){
            Throwable current=queue.get(i);if(!visited.add(current))continue;
            assertThat(current.getMessage()).doesNotContain(SECRET);
            assertThat(current.getSuppressed()).isEmpty();
            if(current.getCause()!=null)queue.add(current.getCause());
            if(current instanceof SQLException sql){
                assertThat((Object)sql.getNextException()).isNull();
                assertThat(sql.getCause()).isNull();
            }
        }
    }
    private static void assertUnbound(Fixture f){
        assertThat(TransactionSynchronizationManager.hasResource(f.manager.commandDataSource())).isFalse();
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
        assertThatThrownBy(f.manager::currentFrame).isInstanceOf(IllegalStateException.class);
    }
    private record Fixture(DataSource pool,Connection driver,Driver state,G014JdbcTransactionManager manager,
            TransactionTemplate transaction,TransactionSqlSealGate gate,AtomicReference<G014JdbcGuard> seen){
        void seal(){
            var plan=mock(TransactionSqlSealGate.CommandPlan.class);when(plan.commandId()).thenReturn(UUID.randomUUID());
            when(plan.targetId()).thenReturn(UUID.randomUUID());when(plan.action()).thenReturn(RegisteredEntityAction.WORKSHOP_CREATE);
            var scope=mock(HumanSessionAuthorityTransaction.WriteScope.class);
            when(scope.guard()).thenReturn(HumanSessionAuthorityTransaction.Guard.FINDING_COMMAND);
            gate.bindCommand(plan,scope);gate.finalizeCommand();
        }
    }
    private static Fixture fixture(String fault,FailureKind kind,boolean autoCommit,boolean readOnly,int isolation)throws Exception {
        Driver state=new Driver(fault,kind,autoCommit,readOnly,isolation);
        Connection driver=mock(Connection.class,invocation->state.invoke(invocation.getMethod().getName(),invocation.getArguments()));
        DataSource pool=mock(DataSource.class);when(pool.getConnection()).thenAnswer(call->{state.operations.add("acquire");return driver;});
        G014JdbcTransactionManager manager=new G014JdbcTransactionManager(pool,List.of());
        TransactionTemplate transaction=new TransactionTemplate(manager);transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        var authority=mock(HumanSessionAuthorityTransaction.class);var actions=mock(EntityActionAuthorization.class);
        var scopes=mock(EntityActionAuthorization.FinalActionScopes.class);when(scopes.observedAt()).thenReturn(Instant.parse("2026-11-04T00:00:00Z"));
        when(actions.requireFinalCommand(any(),any(),any())).thenReturn(scopes);
        var validator=mock(TransactionSqlSealGate.RegisteredCommandValidator.class);
        when(validator.requiredActions(any())).thenReturn(Set.of(RegisteredEntityAction.WORKSHOP_CREATE));
        when(authority.withGuarded(any(),any())).thenAnswer(call->{
            java.util.function.Function<HumanSessionAuthorityTransaction.GuardedBoundary,Object> body=call.getArgument(1);
            Object result=body.apply(mock(HumanSessionAuthorityTransaction.GuardedBoundary.class));
            Connection lease=manager.currentFrame().jdbc.connection;lease.getAutoCommit();lease.isReadOnly();lease.getTransactionIsolation();return result;
        });
        TransactionSqlSealGate gate=new TransactionSqlSealGate(manager,authority,actions,Map.of(RegisteredEntityAction.WORKSHOP_CREATE,validator));
        clearInvocations(pool,driver);return new Fixture(pool,driver,state,manager,transaction,gate,new AtomicReference<>());
    }
    private static final class Driver {
        final String fault;final FailureKind kind;final List<String> operations=new ArrayList<>();
        final Set<String> initialReads=new HashSet<>();boolean autoCommit,readOnly,acknowledged,returned;int isolation;
        Boolean readOnlyAtFailure;
        Driver(String fault,FailureKind kind,boolean autoCommit,boolean readOnly,int isolation){
            this.fault=fault;this.kind=kind;this.autoCommit=autoCommit;this.readOnly=readOnly;this.isolation=isolation;
        }
        boolean baselineComplete(){return initialReads.size()==3;}
        void fail(String operation)throws Throwable {
            if(!Objects.equals(fault,operation))return;
            if(operation.equals("setup.readOnly"))readOnlyAtFailure=readOnly;
            SQLException sql=new SQLException(SECRET,"XX000",7,new IllegalStateException(SECRET+" cause"));
            sql.setNextException(new SQLException(SECRET+" next"));sql.addSuppressed(new IllegalArgumentException(SECRET+" suppressed"));
            switch(kind){case SQL->throw sql;case RUNTIME->throw new IllegalStateException(SECRET,sql);case ERROR->throw new AssertionError(SECRET,sql);}
        }
        void read(String attribute)throws Throwable {if(!baselineComplete()){fail("baseline."+attribute);initialReads.add(attribute);}}
        Object invoke(String name,Object[] args)throws Throwable {
            return switch(name){
                case "toString"->SECRET+" connection";
                case "getAutoCommit"->{operations.add("getAutoCommit");read("autoCommit");yield autoCommit;}
                case "isReadOnly"->{operations.add("isReadOnly");read("readOnly");yield readOnly;}
                case "getTransactionIsolation"->{operations.add("getIsolation");read("isolation");yield isolation;}
                case "setAutoCommit"->{autoCommit=(Boolean)args[0];operations.add("setAutoCommit:"+autoCommit);fail((acknowledged?"restore.":"setup.")+"autoCommit");yield null;}
                case "setReadOnly"->{readOnly=(Boolean)args[0];operations.add("setReadOnly:"+readOnly);fail((acknowledged?"restore.":"setup.")+"readOnly");yield null;}
                case "setTransactionIsolation"->{isolation=(Integer)args[0];operations.add("setIsolation:"+isolation);fail((acknowledged?"restore.":"setup.")+"isolation");yield null;}
                case "commit"->{operations.add("commit");fail("commit");acknowledged=true;yield null;}
                case "rollback"->{operations.add("rollback");fail("rollback");acknowledged=true;yield null;}
                case "close"->{operations.add("close");returned=true;fail("close");yield null;}
                default->throw new AssertionError("Unexpected driver operation "+name);
            };
        }
    }
    private static void withLogs(Level level,LogCase action)throws Exception {
        Logger root=(Logger)LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        List<Logger> loggers=List.of(root,(Logger)LoggerFactory.getLogger(G014JdbcTransactionManager.class),
                (Logger)LoggerFactory.getLogger("org.springframework.jdbc.datasource.DataSourceUtils"),
                (Logger)LoggerFactory.getLogger(TransactionTemplate.class));
        List<Level> old=new ArrayList<>();for(Logger logger:loggers){old.add(logger.getLevel());logger.setLevel(level);}
        ListAppender<ILoggingEvent> events=new ListAppender<>();events.start();root.addAppender(events);
        try{action.run(events);}finally{root.detachAppender(events);events.stop();for(int i=0;i<loggers.size();i++)loggers.get(i).setLevel(old.get(i));}
    }
    @FunctionalInterface private interface LogCase {void run(ListAppender<ILoggingEvent> events)throws Exception;}
}
