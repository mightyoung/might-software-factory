package com.mightyoung.softwarefactory.files;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import javax.sql.DataSource;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.jooq.impl.DefaultConnectionProvider;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Callback-scoped physical transaction identity; neither opens a transaction nor acquires authorization. */
public final class FileIngestionTxContext {
    private final Object ledgerOwner;
    private final DataSource source;
    private final ConnectionHolder holder;
    private final Connection connection;
    private final UUID tenantId;
    private final Thread owner=Thread.currentThread();
    private final DSLContext pinned;
    private boolean open=true;
    private boolean invalidated;
    private final TransactionSynchronization lifetime=new TransactionSynchronization(){
        @Override public void savepointRollback(Object savepoint){invalidate();}
        @Override public void suspend(){invalidate();}
        @Override public void beforeCommit(boolean readOnly){if(invalidated){holder.setRollbackOnly();throw inactive();}}
        @Override public void afterCompletion(int status){open=false;}
    };

    FileIngestionTxContext(DSLContext configured,DataSource source,UUID tenantId,Object ledgerOwner){
        FileIngestionContracts.required(configured,source,tenantId);this.source=source;this.tenantId=tenantId;this.ledgerOwner=ledgerOwner;
        Object bound=TransactionSynchronizationManager.getResource(source);
        if(!(bound instanceof ConnectionHolder current)||!current.isSynchronizedWithTransaction()
                ||current.isRollbackOnly()||!TransactionSynchronizationManager.isActualTransactionActive()
                ||!TransactionSynchronizationManager.isSynchronizationActive())throw inactive();
        holder=current;connection=DataSourceUtils.getTargetConnection(holder.getConnection());
        pinned=DSL.using(configured.configuration().derive(new DefaultConnectionProvider(connection)));
        TransactionSynchronizationManager.registerSynchronization(lifetime);
        requireOpen();
    }
    void requireOwner(Object owner){if(ledgerOwner!=owner){invalidate();throw inactive();}requireOpen();}
    UUID tenantId(){requireOpen();return tenantId;}
    DSLContext sql(){requireOpen();return pinned;}
    void close(){open=false;}
    void invalidate(){invalidated=true;holder.setRollbackOnly();}
    void requireOpen(){
        try{
            if(!open||invalidated||owner!=Thread.currentThread()
                    ||TransactionSynchronizationManager.getResource(source)!=holder
                    ||!holder.isSynchronizedWithTransaction()||holder.isRollbackOnly()
                    ||DataSourceUtils.getTargetConnection(holder.getConnection())!=connection
                    ||!TransactionSynchronizationManager.isActualTransactionActive()
                    ||!TransactionSynchronizationManager.isSynchronizationActive()
                    ||!TransactionSynchronizationManager.getSynchronizations().contains(lifetime)
                    ||TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                    ||!Integer.valueOf(TransactionDefinition.ISOLATION_READ_COMMITTED)
                       .equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())
                    ||connection.getAutoCommit()||connection.isReadOnly()
                    ||connection.getTransactionIsolation()!=Connection.TRANSACTION_READ_COMMITTED)throw inactive();
            try(var statement=connection.prepareStatement("select current_setting('app.tenant_id',true), current_setting('transaction_isolation'), current_setting('transaction_read_only')");
                var row=statement.executeQuery()){
                if(!row.next()||!tenantId.toString().equals(row.getString(1))
                        ||!"read committed".equals(row.getString(2))||!"off".equals(row.getString(3)))throw inactive();
            }
        }catch(SQLException|RuntimeException failure){
            invalidate();
            if(failure instanceof IllegalStateException state)throw state;
            throw new IllegalStateException("File ingestion transaction validation failed",failure);
        }
    }
    private static IllegalStateException inactive(){return new IllegalStateException("File ingestion transaction context is no longer active");}
}
