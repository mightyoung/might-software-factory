package com.mightyoung.softwarefactory.files;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.tools.jdbc.MockConnection;
import org.jooq.tools.jdbc.MockResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

class FileIngestionTxContextTest {
    final UUID tenant=UUID.randomUUID();
    final DataSource source=mock(DataSource.class);
    final Object owner=new Object();
    boolean jdbcReadOnly,jdbcAutoCommit;
    int jdbcIsolation=Connection.TRANSACTION_READ_COMMITTED,queries;
    String serverTenant=tenant.toString(),serverIsolation="read committed",serverReadOnly="off";
    boolean failValidation;
    final MockConnection jdbc=new MockConnection(context->{
        queries++;
        if(failValidation)throw new SQLException("synthetic validation failure");
        var a=DSL.field("tenant",String.class);var b=DSL.field("isolation",String.class);var c=DSL.field("readonly",String.class);
        var result=DSL.using(SQLDialect.POSTGRES).newResult(a,b,c);var row=DSL.using(SQLDialect.POSTGRES).newRecord(a,b,c);
        row.values(serverTenant,serverIsolation,serverReadOnly);result.add(row);return new MockResult[]{new MockResult(1,result)};
    }){
        @Override public boolean getAutoCommit(){return jdbcAutoCommit;}
        @Override public boolean isReadOnly(){return jdbcReadOnly;}
        @Override public int getTransactionIsolation(){return jdbcIsolation;}
        @Override public void commit(){throw new AssertionError("The files context must not commit its caller");}
        @Override public void rollback(){throw new AssertionError("The files context must only mark its caller rollback-only");}
    };
    final DSLContext configured=DSL.using(jdbc,SQLDialect.POSTGRES);
    MutableHolder holder;
    @BeforeEach void bind(){
        holder=new MutableHolder(jdbc);holder.setSynchronizedWithTransaction(true);
        TransactionSynchronizationManager.bindResource(source,holder);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
        TransactionSynchronizationManager.initSynchronization();
    }
    @AfterEach void clear(){
        if(TransactionSynchronizationManager.isSynchronizationActive())TransactionSynchronizationManager.clearSynchronization();
        TransactionSynchronizationManager.unbindResourceIfPossible(source);TransactionSynchronizationManager.clear();
    }
    FileIngestionTxContext context(){return new FileIngestionTxContext(configured,source,tenant,owner);}

    @Test void validContextPinsCallerAndCannotEscapeClosedCallback(){
        var context=context();context.requireOwner(owner);context.requireOpen();
        assertThat(context.tenantId()).isEqualTo(tenant);assertThat(holder.isRollbackOnly()).isFalse();
        context.close();assertThatThrownBy(context::requireOpen).isInstanceOf(IllegalStateException.class);
        assertThat(holder.isRollbackOnly()).isTrue();
    }
    @Test void absentTransactionAndWrongDatasourceRejectBeforeAnySql(){
        int before=queries;
        assertThatThrownBy(()->new FileIngestionTxContext(configured,mock(DataSource.class),tenant,owner)).isInstanceOf(IllegalStateException.class);
        assertThat(queries).isEqualTo(before);
        TransactionSynchronizationManager.setActualTransactionActive(false);
        assertThatThrownBy(this::context).isInstanceOf(IllegalStateException.class);
        assertThat(queries).isEqualTo(before);
    }
    @Test void originalLedgerOwnerIsRequiredAndFailurePermanentlyInvalidates(){
        var context=context();assertThatThrownBy(()->context.requireOwner(new Object())).isInstanceOf(IllegalStateException.class);
        holder.resetRollbackOnly();assertThatThrownBy(context::requireOpen).isInstanceOf(IllegalStateException.class);
        assertThat(holder.isRollbackOnly()).isTrue();
    }
    @Test void crossThreadAccessInvalidatesTheOriginalContext() throws Exception {
        var context=context();var failure=new AtomicReference<Throwable>();
        Thread thread=new Thread(()->{try{context.requireOpen();}catch(Throwable e){failure.set(e);}});thread.start();thread.join(2000);
        assertThat(thread.isAlive()).isFalse();assertThat(failure.get()).isInstanceOf(IllegalStateException.class);
        holder.resetRollbackOnly();assertThatThrownBy(context::requireOpen).isInstanceOf(IllegalStateException.class);
    }
    @Test void replacingHolderCannotBorrowTheOriginalContextEvenAfterRestoration(){
        var context=context();TransactionSynchronizationManager.unbindResource(source);
        var replacement=new ConnectionHolder(jdbc);replacement.setSynchronizedWithTransaction(true);
        TransactionSynchronizationManager.bindResource(source,replacement);
        assertThatThrownBy(context::requireOpen).isInstanceOf(IllegalStateException.class);
        TransactionSynchronizationManager.unbindResource(source);TransactionSynchronizationManager.bindResource(source,holder);
        holder.resetRollbackOnly();assertThatThrownBy(context::requireOpen).isInstanceOf(IllegalStateException.class);
    }
    @Test void replacingPhysicalConnectionCannotBorrowTheOriginalHolder(){
        var context=context();holder.replace(new MockConnection(c->new MockResult[0]));
        assertThatThrownBy(context::requireOpen).isInstanceOf(IllegalStateException.class);
        holder.replace(jdbc);holder.resetRollbackOnly();assertThatThrownBy(context::requireOpen).isInstanceOf(IllegalStateException.class);
    }
    @Test void losingSynchronizationInvalidatesOtherwiseIdenticalPhysicalTransaction(){
        var context=context();TransactionSynchronizationManager.clearSynchronization();TransactionSynchronizationManager.initSynchronization();
        assertThatThrownBy(context::requireOpen).isInstanceOf(IllegalStateException.class);assertThat(holder.isRollbackOnly()).isTrue();
    }
    @Test void savepointRollbackAndSuspensionCannotBeReversedByResettingHolderFlags(){
        var context=context();var sync=TransactionSynchronizationManager.getSynchronizations().getLast();
        sync.savepointRollback(new Object());holder.resetRollbackOnly();
        assertThatThrownBy(context::requireOpen).isInstanceOf(IllegalStateException.class);
        holder.resetRollbackOnly();assertThatThrownBy(()->sync.beforeCommit(false)).isInstanceOf(IllegalStateException.class);
    }
    @Test void suspensionPermanentlyInvalidatesTheContext(){
        var context=context();TransactionSynchronizationManager.getSynchronizations().getLast().suspend();holder.resetRollbackOnly();
        assertThatThrownBy(context::requireOpen).isInstanceOf(IllegalStateException.class);
    }
    @Test void springAndJdbcModeMustIndependentlyBeReadCommittedReadWrite(){
        for(int candidate=0;candidate<5;candidate++){
            int mismatch=candidate;
            var context=context();
            if(mismatch==0)TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
            if(mismatch==1)TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(Connection.TRANSACTION_REPEATABLE_READ);
            if(mismatch==2)jdbcReadOnly=true;
            if(mismatch==3)jdbcIsolation=Connection.TRANSACTION_REPEATABLE_READ;
            if(mismatch==4)jdbcAutoCommit=true;
            assertThatThrownBy(context::requireOpen).isInstanceOf(IllegalStateException.class);
            assertThat(holder.isRollbackOnly()).isTrue();holder.resetRollbackOnly();
            TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
            TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(Connection.TRANSACTION_READ_COMMITTED);
            jdbcReadOnly=false;jdbcAutoCommit=false;jdbcIsolation=Connection.TRANSACTION_READ_COMMITTED;
        }
    }
    @Test void serverTenantIsolationAndReadOnlyMustMatchTheActualPinnedContext(){
        for(int candidate=0;candidate<3;candidate++){
            int mismatch=candidate;var context=context();
            if(mismatch==0)serverTenant=UUID.randomUUID().toString();
            if(mismatch==1)serverIsolation="repeatable read";
            if(mismatch==2)serverReadOnly="on";
            assertThatThrownBy(context::requireOpen).isInstanceOf(IllegalStateException.class);assertThat(holder.isRollbackOnly()).isTrue();
            holder.resetRollbackOnly();serverTenant=tenant.toString();serverIsolation="read committed";serverReadOnly="off";
        }
    }
    @Test void jdbcValidationFailureMarksCallerRollbackOnlyAndDoesNotCommit(){
        var context=context();failValidation=true;
        assertThatThrownBy(context::requireOpen).isInstanceOf(IllegalStateException.class).hasMessage("File ingestion transaction validation failed");
        assertThat(holder.isRollbackOnly()).isTrue();
    }
    static final class MutableHolder extends ConnectionHolder {
        MutableHolder(Connection c){super(c);}void replace(Connection c){setConnection(c);}
    }
}
