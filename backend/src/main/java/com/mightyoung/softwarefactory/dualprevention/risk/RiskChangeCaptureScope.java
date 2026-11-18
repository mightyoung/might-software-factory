package com.mightyoung.softwarefactory.dualprevention.risk;

import com.mightyoung.softwarefactory.authorization.AuthorizationWriteGuard;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Module-private transaction witness. No caller can use a finished body as commit authority. */
final class RiskChangeCaptureScope {
    static final String TENANT_SQL="select pg_catalog.current_setting('app.tenant_id',true)";
    enum State { CREATED, BODY_OPEN, BODY_FINISHED, CLOSED, REJECTED }
    private final Object issuer,commitKey;
    private final DataSource source;
    private final ConnectionHolder holder;
    private final Connection connection,physical;
    private final UUID tenant,actor;
    private final Thread thread=Thread.currentThread();
    private final AuthorizationWriteGuard guard;
    private List<TransactionSynchronization> synchronizations;
    private final AtomicReference<State> state=new AtomicReference<>(State.CREATED);

    RiskChangeCaptureScope(Object issuer,Object commitKey,DataSource source,ConnectionHolder holder,
            UUID tenant,UUID actor,AuthorizationWriteGuard guard) {
        this.issuer=Objects.requireNonNull(issuer);this.commitKey=Objects.requireNonNull(commitKey);
        this.source=Objects.requireNonNull(source);this.holder=Objects.requireNonNull(holder);
        this.connection=holder.getConnection();this.physical=DataSourceUtils.getTargetConnection(connection);this.tenant=Objects.requireNonNull(tenant);
        this.actor=Objects.requireNonNull(actor);this.guard=Objects.requireNonNull(guard);
    }
    void createdBoundary(Object owner,Object key,List<TransactionSynchronization> expected){
        try {
            if(owner!=issuer||key!=commitKey||state.get()!=State.CREATED)throw rejected();
            if(synchronizations==null)synchronizations=List.copyOf(expected);
            boundary();if(state.get()!=State.CREATED)throw rejected();
        }catch(RuntimeException|Error failure){reject();throw rejected();}
    }
    void open(Object owner,List<TransactionSynchronization> expected){
        try { if(owner!=issuer||state.get()!=State.CREATED)throw rejected();synchronizations=List.copyOf(expected);boundary();if(!state.compareAndSet(State.CREATED,State.BODY_OPEN))throw rejected(); }
        catch(RuntimeException|Error failure){reject();throw rejected();}
    }
    UUID tenant(Object owner){business(owner);return tenant;}
    UUID actor(Object owner){business(owner);return actor;}
    Connection business(Object owner){
        try {if(owner!=issuer||state.get()!=State.BODY_OPEN)throw rejected();boundary();if(state.get()!=State.BODY_OPEN)throw rejected();return connection;}
        catch(RuntimeException|Error failure){reject();throw rejected();}
    }
    void finish(Object owner){
        business(owner);if(!state.compareAndSet(State.BODY_OPEN,State.BODY_FINISHED)){reject();throw rejected();}
    }
    void commitBoundary(Object owner,Object key){
        try {if(owner!=issuer||key!=commitKey||state.get()!=State.BODY_FINISHED)throw rejected();boundary();if(state.get()!=State.BODY_FINISHED)throw rejected();}
        catch(RuntimeException|Error failure){reject();throw rejected();}
    }
    void close(){state.updateAndGet(current->current==State.REJECTED?State.REJECTED:State.CLOSED);}
    void reject(){holder.setRollbackOnly();state.set(State.REJECTED);}
    State state(){return state.get();}
    private void boundary(){
        try {
            pins();guard.requireBusinessCommand(tenant);pins();
            try(var statement=connection.prepareStatement(TENANT_SQL)){
                timeout(statement,holder);statement.setMaxRows(2);pins();
                try(var rows=statement.executeQuery()){
                    width(rows,1);if(!rows.next()||!tenant.toString().equals(rows.getObject(1))||rows.next())throw rejected();
                }
            }
            pins();guard.requireBusinessCommand(tenant);pins();
        }catch(SQLException failure){throw rejected();}
    }
    private void pins()throws SQLException {
        if(Thread.currentThread()!=thread||TransactionSynchronizationManager.getResource(source)!=holder
                ||holder.getConnection()!=connection||physical==null||DataSourceUtils.getTargetConnection(connection)!=physical||!holder.isSynchronizedWithTransaction()||holder.isRollbackOnly()
                ||!TransactionSynchronizationManager.isActualTransactionActive()||!TransactionSynchronizationManager.isSynchronizationActive()
                ||TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                ||!Integer.valueOf(Connection.TRANSACTION_READ_COMMITTED).equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())
                ||connection.getAutoCommit()||connection.isReadOnly()||connection.getTransactionIsolation()!=Connection.TRANSACTION_READ_COMMITTED)throw rejected();
        var actual=TransactionSynchronizationManager.getSynchronizations();
        if(synchronizations==null||actual.size()!=synchronizations.size())throw rejected();
        for(int i=0;i<actual.size();i++)if(actual.get(i)!=synchronizations.get(i))throw rejected();
    }
    static void timeout(PreparedStatement statement,ConnectionHolder holder)throws SQLException {
        int seconds=holder.hasTimeout()?Math.min(2,holder.getTimeToLiveInSeconds()):2;
        if(seconds<1)throw rejected();statement.setQueryTimeout(seconds);
    }
    static void width(ResultSet rows,int expected)throws SQLException {
        var metadata=rows.getMetaData();if(metadata==null||metadata.getColumnCount()!=expected)throw rejected();
    }
    static IllegalStateException rejected(){return new IllegalStateException("Risk change capture is unavailable");}
}
