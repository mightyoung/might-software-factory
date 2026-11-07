package com.mightyoung.softwarefactory.tenancy;

import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.datasource.ConnectionProxy;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Actual Spring transaction/holder management over controlled JDBC; not PostgreSQL/RLS or SERVICE authorization proof. */
class JdbcTenantLifecycleReadRepositoryTest {
    private static final UUID TENANT=UUID.fromString("11111111-2222-3333-4444-555555555555");
    @AfterEach void noOwnedTransactionLeaks(){assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();}
    @Test void borrowsNoLeaseAndReadsOnlyTheTenantOnTheExistingWritableReadCommittedConnection()throws Exception {
        var f=new Fixture();var value=f.run(()->{
            var state=f.reader.findCurrentInCaller(TENANT);assertThat(state).contains(new TenantLifecycleState(TENANT,"ACTIVE",0));
            verify(f.connection,never()).commit();verify(f.connection,never()).close();return state;
        });
        assertThat(value).isPresent();verify(f.source,times(1)).getConnection();verify(f.connection).commit();verify(f.connection).close();verify(f.connection,never()).rollback();
        assertThat(f.sql).containsExactly(JdbcTenantLifecycleReadRepository.TENANT_CONTEXT_SQL,JdbcTenantLifecycleReadRepository.TENANT_SQL,JdbcTenantLifecycleReadRepository.TENANT_CONTEXT_SQL);
        assertThat(f.bindings).containsExactly(List.of(),List.of(TENANT),List.of());
        for(var statement:f.statements){verify(statement).setMaxRows(2);verify(statement).setQueryTimeout(intThat(valueSeconds->valueSeconds>0&&valueSeconds<=10));verify(statement).close();}
        for(var rows:f.results)verify(rows).close();
    }
    @Test void suspendedAndMissingTenantsAreFactsAndMissingStillRevalidatesTheCaller()throws Exception {
        for(boolean missing:new boolean[]{false,true}){var f=new Fixture();f.fault=missing?"MISSING":"SUSPENDED";
            var value=f.run(()->f.reader.findCurrentInCaller(TENANT));
            if(missing)assertThat(value).isEmpty();else assertThat(value).contains(new TenantLifecycleState(TENANT,"SUSPENDED",0));
            assertThat(f.sql).hasSize(3);verify(f.connection).commit();verify(f.source).getConnection();}
    }
    @Test void missingForeignAndUnmanagedHoldersRejectBeforeAnyReaderSqlOrBorrow()throws Exception {
        var f=new Fixture();refused(()->f.reader.findCurrentInCaller(TENANT));verify(f.source,never()).getConnection();assertThat(f.sql).isEmpty();
        var foreign=mock(DataSource.class);f.run(()->{refused(()->new JdbcTenantLifecycleReadRepository(foreign).findCurrentInCaller(TENANT));verify(foreign,never()).getConnection();return null;});
        var g=new Fixture();var unmanaged=new ConnectionHolder(g.connection);TransactionSynchronizationManager.bindResource(g.source,unmanaged);
        try{refused(()->g.reader.findCurrentInCaller(TENANT));assertThat(unmanaged.isRollbackOnly()).isTrue();assertThat(g.sql).isEmpty();verify(g.source,never()).getConnection();verify(g.connection,never()).close();}
        finally{TransactionSynchronizationManager.unbindResource(g.source);}
    }
    @Test void readonlyWrongIsolationAndAlreadyRollbackOnlyCallersAreRejectedBeforeSql()throws Exception {
        for(String fault:List.of("READ_ONLY","RR","ROLLBACK_ONLY","NULL_ID")){
            var f=new Fixture();if(fault.equals("READ_ONLY"))f.transaction.setReadOnly(true);if(fault.equals("RR"))f.transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
            refused(()->f.run(()->{if(fault.equals("ROLLBACK_ONLY"))f.holder().setRollbackOnly();return f.reader.findCurrentInCaller(fault.equals("NULL_ID")?null:TENANT);}));
            assertThat(f.sql).isEmpty();verify(f.source).getConnection();verify(f.connection).rollback();verify(f.connection,never()).commit();
        }
    }
    @ParameterizedTest @ValueSource(strings={"DUPLICATE","WRONG_TENANT","ID_STRING","NULL_ID","NULL_STATUS","UNKNOWN_STATUS","NULL_EPOCH","INTEGER_EPOCH","NEGATIVE_EPOCH","NULL_METADATA","WRONG_WIDTH","WRONG_LABEL","SQL","RESULT_CLOSE","STATEMENT_CLOSE"})
    void malformedStorageAndJdbcFailuresRollBackTheOriginalManagedHolder(String fault)throws Exception {
        var f=new Fixture();f.fault=fault;refused(()->f.run(()->{
            try{return f.reader.findCurrentInCaller(TENANT);}catch(IllegalStateException rejected){assertThat(f.holder().isRollbackOnly()).isTrue();throw rejected;}
        }));verify(f.connection).rollback();verify(f.connection).close();verify(f.connection,never()).commit();verify(f.source).getConnection();
    }
    @ParameterizedTest @ValueSource(strings={"MISSING_GUC","WRONG_GUC","STRING_CASE_GUC","NULL_GUC","TWO_GUC","ZERO_GUC","BAD_GUC_LABEL","GUC_SQL"})
    void tenantContextMustMatchExactlyBeforeReadingTheTenant(String fault)throws Exception {
        var f=new Fixture();f.fault=fault;refused(()->f.run(()->f.reader.findCurrentInCaller(TENANT)));assertThat(f.sql).doesNotContain(JdbcTenantLifecycleReadRepository.TENANT_SQL);verify(f.connection).rollback();
    }
    @ParameterizedTest @ValueSource(strings={"PHYSICAL","HOLDER","GUC_DRIFT","AUTOCOMMIT","READONLY","ISOLATION","TX_FLAG","SYNC_FLAG"})
    void driftAtSqlBoundaryRejectsEvenAfterAValidRowAndMarksTheOriginalHolder(String fault)throws Exception {
        var f=new Fixture();f.fault=fault;refused(()->f.run(()->{
            var original=f.holder();
            try{return f.reader.findCurrentInCaller(TENANT);}
            catch(IllegalStateException rejected){assertThat(original.isRollbackOnly()).isTrue();throw rejected;}
            finally{if(TransactionSynchronizationManager.getResource(f.source)!=original){TransactionSynchronizationManager.unbindResource(f.source);TransactionSynchronizationManager.bindResource(f.source,original);}TransactionSynchronizationManager.setActualTransactionActive(true);}
        }));verify(f.connection).rollback();verify(f.source).getConnection();
    }
    @Test void closedCallerCannotBeReusedAndNoSecondConnectionIsLeased()throws Exception {
        var f=new Fixture();assertThat(f.run(()->f.reader.findCurrentInCaller(TENANT))).isPresent();refused(()->f.reader.findCurrentInCaller(TENANT));verify(f.source,times(1)).getConnection();assertThat(f.sql).hasSize(3);
    }
    @Test void transactionTimeoutAppliesBeforeAnyQueryAndStillMarksRollbackOnly()throws Exception {
        var f=new Fixture();refused(()->f.run(()->{f.holder().setTimeoutInMillis(-1);return f.reader.findCurrentInCaller(TENANT);}));assertThat(f.executed.get()).isZero();verify(f.connection).rollback();verify(f.connection).close();
    }
    private static void refused(org.assertj.core.api.ThrowableAssert.ThrowingCallable callback){assertThatThrownBy(callback).isExactlyInstanceOf(IllegalStateException.class).hasMessage("Caller tenant lifecycle boundary is unavailable").hasNoCause().satisfies(error->assertThat(error.getSuppressed()).isEmpty());}
    private static final class Fixture {
        final DataSource source=mock(DataSource.class);
        final Connection connection=mock(Connection.class,withSettings().extraInterfaces(ConnectionProxy.class));
        final AtomicReference<Connection> physical=new AtomicReference<>(mock(Connection.class));
        final AtomicBoolean autoCommit=new AtomicBoolean(true),readOnly=new AtomicBoolean(false);final AtomicInteger isolation=new AtomicInteger(Connection.TRANSACTION_READ_COMMITTED),executed=new AtomicInteger();
        final List<String> sql=new ArrayList<>();final List<List<Object>> bindings=new ArrayList<>();final List<PreparedStatement> statements=new ArrayList<>();final List<ResultSet> results=new ArrayList<>();
        final JdbcTenantLifecycleReadRepository reader=new JdbcTenantLifecycleReadRepository(source);final TransactionTemplate transaction;
        String fault="";int contexts;
        Fixture()throws Exception {
            when(source.getConnection()).thenReturn(connection);when(((ConnectionProxy)connection).getTargetConnection()).thenAnswer(call->physical.get());
            when(connection.getAutoCommit()).thenAnswer(call->autoCommit.get());doAnswer(call->{autoCommit.set(call.getArgument(0));return null;}).when(connection).setAutoCommit(anyBoolean());
            when(connection.isReadOnly()).thenAnswer(call->readOnly.get());doAnswer(call->{readOnly.set(call.getArgument(0));return null;}).when(connection).setReadOnly(anyBoolean());
            when(connection.getTransactionIsolation()).thenAnswer(call->isolation.get());doAnswer(call->{isolation.set(call.getArgument(0));return null;}).when(connection).setTransactionIsolation(anyInt());
            doAnswer(call->{assertThat(TransactionSynchronizationManager.hasResource(source)).isFalse();return null;}).when(connection).close();
            when(connection.prepareStatement(anyString())).thenAnswer(call->{String query=call.getArgument(0);sql.add(query);var values=new ArrayList<>();bindings.add(values);var statement=mock(PreparedStatement.class);statements.add(statement);
                doAnswer(bind->{assertThat(bind.<Integer>getArgument(0)).isEqualTo(1);values.add(bind.getArgument(1));return null;}).when(statement).setObject(anyInt(),any());
                when(statement.executeQuery()).thenAnswer(execute->{executed.incrementAndGet();assertThat(holder().getConnection()).isSameAs(connection);return rows(query);});
                if(fault.equals("STATEMENT_CLOSE")&&query.equals(JdbcTenantLifecycleReadRepository.TENANT_SQL))doThrow(new SQLException("synthetic private close")).when(statement).close();
                return statement;
            });
            transaction=new TransactionTemplate(new DataSourceTransactionManager(source));transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);transaction.setTimeout(10);
        }
        <T>T run(Supplier<T> work){return transaction.execute(status->work.get());}
        ConnectionHolder holder(){return (ConnectionHolder)TransactionSynchronizationManager.getResource(source);}
        ResultSet rows(String query)throws Exception {
            boolean context=query.equals(JdbcTenantLifecycleReadRepository.TENANT_CONTEXT_SQL);if(!context&&!query.equals(JdbcTenantLifecycleReadRepository.TENANT_SQL))throw new AssertionError("Unexpected lifecycle SQL");
            if(context&&fault.equals("GUC_SQL")||!context&&fault.equals("SQL"))throw new SQLException("synthetic private SQL tenant value");
            List<Object[]> values=new ArrayList<>();String[] labels=context?new String[]{"tenant_context"}:new String[]{"id","status","auth_epoch"};
            if(context){contexts++;Object value=TENANT.toString();
                if(fault.equals("MISSING_GUC"))value="";if(fault.equals("WRONG_GUC")||fault.equals("GUC_DRIFT")&&contexts>1)value=UUID.randomUUID().toString();
                if(fault.equals("STRING_CASE_GUC"))value=TENANT.toString().replace('a','A')+" ";if(fault.equals("NULL_GUC"))value=null;
                if(!fault.equals("ZERO_GUC"))values.add(new Object[]{value});if(fault.equals("TWO_GUC"))values.add(new Object[]{value});if(fault.equals("BAD_GUC_LABEL"))labels[0]="wrong";
            }else{
                Object id=TENANT,state=fault.equals("SUSPENDED")?"SUSPENDED":"ACTIVE",epoch=0L;
                switch(fault){case "WRONG_TENANT"->id=UUID.randomUUID();case "ID_STRING"->id=TENANT.toString();case "NULL_ID"->id=null;case "NULL_STATUS"->state=null;case "UNKNOWN_STATUS"->state="UNKNOWN";case "NULL_EPOCH"->epoch=null;case "INTEGER_EPOCH"->epoch=0;case "NEGATIVE_EPOCH"->epoch=-1L;default->{}}
                if(!fault.equals("MISSING"))values.add(new Object[]{id,state,epoch});if(fault.equals("DUPLICATE"))values.add(values.getFirst().clone());
                if(fault.equals("WRONG_LABEL"))labels[0]="unexpected";
                switch(fault){case "PHYSICAL"->physical.set(mock(Connection.class));case "HOLDER"->{TransactionSynchronizationManager.unbindResource(source);TransactionSynchronizationManager.bindResource(source,new ConnectionHolder(connection));}
                    case "AUTOCOMMIT"->autoCommit.set(true);case "READONLY"->readOnly.set(true);case "ISOLATION"->isolation.set(Connection.TRANSACTION_REPEATABLE_READ);case "TX_FLAG"->TransactionSynchronizationManager.setActualTransactionActive(false);case "SYNC_FLAG"->holder().setSynchronizedWithTransaction(false);default->{}}
            }
            var rows=mock(ResultSet.class);results.add(rows);var metadata=mock(ResultSetMetaData.class);when(rows.getMetaData()).thenReturn(!context&&fault.equals("NULL_METADATA")?null:metadata);
            when(metadata.getColumnCount()).thenReturn(!context&&fault.equals("WRONG_WIDTH")?4:labels.length);when(metadata.getColumnLabel(anyInt())).thenAnswer(call->labels[call.<Integer>getArgument(0)-1]);
            var index=new AtomicInteger(-1);when(rows.next()).thenAnswer(call->index.incrementAndGet()<values.size());when(rows.getObject(anyInt())).thenAnswer(call->values.get(index.get())[call.<Integer>getArgument(0)-1]);
            if(!context&&fault.equals("RESULT_CLOSE"))doThrow(new SQLException("synthetic private result close")).when(rows).close();return rows;
        }
    }
}
