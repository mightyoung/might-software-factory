package com.mightyoung.softwarefactory.tenancy;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Private composition supplies the same canonical source as the caller transaction. No bean or fallback lease. */
public final class JdbcTenantLifecycleReadRepository implements TenantLifecycleReadRepository {
    static final String TENANT_CONTEXT_SQL="select pg_catalog.current_setting('app.tenant_id',true) as tenant_context";
    static final String TENANT_SQL="select id,status,auth_epoch from public.tenant where id=cast(? as pg_catalog.uuid)";
    private final DataSource callerSource;
    public JdbcTenantLifecycleReadRepository(DataSource callerSource){this.callerSource=Objects.requireNonNull(callerSource);}

    @Override public Optional<TenantLifecycleState> findCurrentInCaller(UUID tenantId){
        ConnectionHolder holder=null;
        try {
            if(!(TransactionSynchronizationManager.getResource(callerSource) instanceof ConnectionHolder bound))throw unavailable();
            holder=bound;Connection connection=holder.getConnection();Connection physical=DataSourceUtils.getTargetConnection(connection);
            requireCaller(holder,connection,physical,tenantId);
            Optional<TenantLifecycleState> result;
            try(var statement=connection.prepareStatement(TENANT_SQL)){
                DataSourceUtils.applyTransactionTimeout(statement,callerSource);statement.setMaxRows(2);statement.setObject(1,tenantId);
                try(var rows=statement.executeQuery()){
                    shape(rows,"id","status","auth_epoch");
                    if(!rows.next())result=Optional.empty();
                    else {
                        Object id=rows.getObject(1),status=rows.getObject(2),epoch=rows.getObject(3);
                        if(!(id instanceof UUID actual)||!tenantId.equals(actual)||!(status instanceof String state)||!(epoch instanceof Long version))throw unavailable();
                        var value=new TenantLifecycleState(actual,state,version);if(rows.next())throw unavailable();result=Optional.of(value);
                    }
                }
            }
            requireCaller(holder,connection,physical,tenantId);return result;
        }catch(SQLException|RuntimeException|Error failure){if(holder!=null)holder.setRollbackOnly();throw unavailable();}
    }
    private void requireCaller(ConnectionHolder holder,Connection connection,Connection physical,UUID tenantId)throws SQLException {
        requirePins(holder,connection,physical,tenantId);
        try(var statement=connection.prepareStatement(TENANT_CONTEXT_SQL)){
            DataSourceUtils.applyTransactionTimeout(statement,callerSource);statement.setMaxRows(2);
            try(var rows=statement.executeQuery()){
                shape(rows,"tenant_context");
                if(!rows.next()||!(rows.getObject(1) instanceof String context)||!tenantId.toString().equals(context)||rows.next())throw unavailable();
            }
        }
        requirePins(holder,connection,physical,tenantId);
    }
    private void requirePins(ConnectionHolder holder,Connection connection,Connection physical,UUID tenantId)throws SQLException {
        if(tenantId==null||connection==null||physical==null||TransactionSynchronizationManager.getResource(callerSource)!=holder
                ||holder.getConnection()!=connection||DataSourceUtils.getTargetConnection(connection)!=physical||holder.isRollbackOnly()||!holder.isSynchronizedWithTransaction()
                ||!TransactionSynchronizationManager.isActualTransactionActive()||!TransactionSynchronizationManager.isSynchronizationActive()
                ||TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                ||!Integer.valueOf(Connection.TRANSACTION_READ_COMMITTED).equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())
                ||connection.getAutoCommit()||connection.isReadOnly()||connection.getTransactionIsolation()!=Connection.TRANSACTION_READ_COMMITTED)throw unavailable();
    }
    private static void shape(ResultSet rows,String... labels)throws SQLException {
        var metadata=rows.getMetaData();if(metadata==null||metadata.getColumnCount()!=labels.length)throw unavailable();
        for(int i=0;i<labels.length;i++)if(!labels[i].equals(metadata.getColumnLabel(i+1)))throw unavailable();
    }
    private static IllegalStateException unavailable(){return new IllegalStateException("Caller tenant lifecycle boundary is unavailable");}
}
