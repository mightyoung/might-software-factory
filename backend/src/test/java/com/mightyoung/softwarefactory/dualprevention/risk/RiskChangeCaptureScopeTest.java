package com.mightyoung.softwarefactory.dualprevention.risk;

import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.sql.DataSource;
import com.mightyoung.softwarefactory.authorization.AuthorizationWriteGuard;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.*;
import static com.mightyoung.softwarefactory.dualprevention.risk.RiskChangeCaptureScope.*;
import static com.mightyoung.softwarefactory.dualprevention.risk.RiskChangeCaptureServiceTest.refused;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RiskChangeCaptureScopeTest {
    @AfterEach void clear(){TransactionSynchronizationManager.clear();}
    @Test void bodyFinishOnlyAllowsThePrivateCommitKeyAndFinallyClosesBusinessAccess()throws Exception {
        var f=new Fixture();f.run(scope->{assertThat(scope.business(f.issuer)).isSameAs(f.connection);assertThat(scope.state()).isEqualTo(State.BODY_OPEN);});
        assertThat(f.scope.state()).isEqualTo(State.CLOSED);refused(()->f.scope.business(f.issuer));assertThat(f.scope.state()).isEqualTo(State.REJECTED);
        f.scope.close();assertThat(f.scope.state()).isEqualTo(State.REJECTED);verify(f.connection).commit();verify(f.connection).close();
    }
    @Test void bodyFinishedCannotExecuteAnotherBusinessOperationEvenBeforeCommit()throws Exception {
        var f=new Fixture();refused(()->f.run(scope->{scope.finish(f.issuer);refused(()->scope.business(f.issuer));scope.close();throw rejected();}));
        assertThat(f.scope.state()).isEqualTo(State.REJECTED);verify(f.connection).rollback();verify(f.connection,never()).commit();
    }
    @ParameterizedTest @ValueSource(strings={"HOLDER_EXECUTE","HOLDER_NEXT","HOLDER_CLOSE","PHYSICAL_EXECUTE","PHYSICAL_NEXT","PHYSICAL_CLOSE"})
    void gucExecuteNextAndCloseDriftRejectBeforeAnyDownstreamOperationAndCannotBeRestored(String fault)throws Exception {
        var f=new Fixture();var reached=new AtomicInteger();refused(()->f.run(scope->{f.fault=fault;scope.business(f.issuer);reached.incrementAndGet();}));
        assertThat(reached.get()).isZero();assertThat(f.scope.state()).isEqualTo(State.REJECTED);
        f.physical.set(f.originalPhysical);refused(()->f.scope.business(f.issuer));f.scope.close();assertThat(f.scope.state()).isEqualTo(State.REJECTED);verify(f.connection).rollback();
    }
    @ParameterizedTest @ValueSource(strings={"ISSUER","THREAD","ISOLATION","READ_ONLY","AUTO_COMMIT","GUC","EXTRA_SYNC","ROLLBACK","SAVEPOINT"})
    void mismatchedIdentityThreadTransactionAndSynchronizationAreSticky(String fault)throws Exception {
        var f=new Fixture();refused(()->f.run(scope->{
            switch(fault){
                case "ISSUER"->{scope.business(new Object());return;}
                case "THREAD"->{var failure=new AtomicReference<Throwable>();var thread=new Thread(()->{try{scope.business(f.issuer);}catch(Throwable ex){failure.set(ex);}});try{thread.start();thread.join(5000);}catch(InterruptedException ex){Thread.currentThread().interrupt();throw rejected();}assertThat(thread.isAlive()).isFalse();assertThat(failure.get()).isInstanceOf(IllegalStateException.class);((ConnectionHolder)TransactionSynchronizationManager.getResource(f.source)).resetRollbackOnly();refused(()->scope.business(f.issuer));throw rejected();}
                case "ISOLATION"->f.isolation.set(Connection.TRANSACTION_REPEATABLE_READ);case "READ_ONLY"->f.readOnly.set(true);case "AUTO_COMMIT"->f.auto.set(true);
                case "GUC"->f.wrongTenant=true;case "EXTRA_SYNC"->TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){});
                case "ROLLBACK"->((ConnectionHolder)TransactionSynchronizationManager.getResource(f.source)).setRollbackOnly();
                case "SAVEPOINT"->{for(var sync:TransactionSynchronizationManager.getSynchronizations())sync.savepointRollback(new Object());}
            }
            scope.business(f.issuer);
        }));assertThat(f.scope.state()).isEqualTo(State.REJECTED);verify(f.connection,never()).commit();
    }
    @Test void anUnknownCommitKeyCannotAuthorizeAFinishedBody()throws Exception {
        var f=new Fixture();refused(()->f.run(scope->{scope.finish(f.issuer);scope.commitBoundary(f.issuer,new Object());}));assertThat(f.scope.state()).isEqualTo(State.REJECTED);
    }
    @ParameterizedTest @ValueSource(strings={"EXECUTE_SQL","NEXT_SQL","CLOSE_SQL"})
    void queryAndCleanupErrorsCarryNoPrivateDriverDetails(String fault)throws Exception {
        var f=new Fixture();refused(()->f.run(scope->{f.fault=fault;scope.business(f.issuer);}));assertThat(f.scope.state()).isEqualTo(State.REJECTED);verify(f.connection).rollback();
    }
    static final class Fixture {
        final DataSource source=mock(DataSource.class);final Connection connection=mock(Connection.class,withSettings().extraInterfaces(ConnectionProxy.class));
        final Connection originalPhysical=mock(Connection.class);final AtomicReference<Connection> physical=new AtomicReference<>(originalPhysical);
        final AtomicBoolean auto=new AtomicBoolean(true),readOnly=new AtomicBoolean(false);final AtomicInteger isolation=new AtomicInteger(Connection.TRANSACTION_READ_COMMITTED);
        final Object issuer=new Object(),commitKey=new Object();final UUID tenant=new UUID(1,1),actor=new UUID(2,2);final AuthorizationWriteGuard guard=mock(AuthorizationWriteGuard.class);
        String fault="";boolean wrongTenant,drifted;RiskChangeCaptureScope scope;
        Fixture()throws Exception {
            when(source.getConnection()).thenReturn(connection);when(((ConnectionProxy)connection).getTargetConnection()).thenAnswer(c->physical.get());
            when(connection.getAutoCommit()).thenAnswer(c->auto.get());doAnswer(c->{auto.set(c.getArgument(0));return null;}).when(connection).setAutoCommit(anyBoolean());
            when(connection.isReadOnly()).thenAnswer(c->readOnly.get());doAnswer(c->{readOnly.set(c.getArgument(0));return null;}).when(connection).setReadOnly(anyBoolean());
            when(connection.getTransactionIsolation()).thenAnswer(c->isolation.get());doAnswer(c->{isolation.set(c.getArgument(0));return null;}).when(connection).setTransactionIsolation(anyInt());
            when(connection.prepareStatement(TENANT_SQL)).thenAnswer(c->{
                var statement=mock(PreparedStatement.class);when(statement.executeQuery()).thenAnswer(ex->{if(fault.equals("EXECUTE_SQL"))throw new SQLException("private-sql-credential");drift("EXECUTE");
                    var rows=mock(ResultSet.class);var metadata=mock(ResultSetMetaData.class);when(metadata.getColumnCount()).thenReturn(1);when(rows.getMetaData()).thenReturn(metadata);var index=new AtomicInteger();
                    when(rows.next()).thenAnswer(n->{if(fault.equals("NEXT_SQL"))throw new SQLException("private-sql-credential");drift("NEXT");return index.getAndIncrement()==0;});
                    when(rows.getObject(1)).thenAnswer(v->wrongTenant?new UUID(99,99).toString():tenant.toString());
                    doAnswer(close->{if(fault.equals("CLOSE_SQL"))throw new SQLException("private-sql-credential");drift("CLOSE");return null;}).when(rows).close();return rows;
                });return statement;
            });
        }
        void drift(String phase){if(drifted||!fault.endsWith(phase))return;drifted=true;
            if(fault.startsWith("HOLDER")){TransactionSynchronizationManager.unbindResource(source);TransactionSynchronizationManager.bindResource(source,new ConnectionHolder(mock(Connection.class)));}
            if(fault.startsWith("PHYSICAL"))physical.set(mock(Connection.class));
        }
        void run(java.util.function.Consumer<RiskChangeCaptureScope> body){
            var template=new TransactionTemplate(new JdbcTransactionManager(source));template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);template.setTimeout(10);
            template.execute(status->{var holder=(ConnectionHolder)TransactionSynchronizationManager.getResource(source);scope=new RiskChangeCaptureScope(issuer,commitKey,source,holder,tenant,actor,guard);
                var sync=new TransactionSynchronization(){@Override public void beforeCommit(boolean ro){scope.commitBoundary(issuer,commitKey);}@Override public void beforeCompletion(){scope.close();}@Override public void afterCompletion(int completion){scope.close();}@Override public void savepointRollback(Object savepoint){scope.reject();}};
                TransactionSynchronizationManager.registerSynchronization(sync);scope.open(issuer,TransactionSynchronizationManager.getSynchronizations());
                try{body.accept(scope);scope.finish(issuer);return null;}catch(RuntimeException|Error failure){scope.reject();throw rejected();}
            });
        }
    }
}
