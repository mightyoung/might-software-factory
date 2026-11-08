package com.mightyoung.softwarefactory.authorization;

import com.mightyoung.softwarefactory.identity.IdentityLifecycleSecurityTransaction;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.datasource.ConnectionProxy;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.function.Supplier;

/** Owns the single global/sorted-tenant transaction and the irreversible source→target phase. */
@Component
public final class ManagedIdentityLifecycleSecurityTransaction implements IdentityLifecycleSecurityTransaction {
    private final DataSource dataSource;
    private final PostgresAuthorizationWriteGuard guard;
    private final TransactionTemplate transaction;
    public ManagedIdentityLifecycleSecurityTransaction(DataSource dataSource,PlatformTransactionManager manager,
                                                       PostgresAuthorizationWriteGuard guard) {
        this.dataSource=Objects.requireNonNull(dataSource);this.guard=Objects.requireNonNull(guard);
        guard.requireDataSource(dataSource);
        transaction=new TransactionTemplate(Objects.requireNonNull(manager));
        transaction.setReadOnly(false);transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setTimeout(10);
    }
    @Override public <T> T execute(Scope scope,Supplier<T> work) {
        Objects.requireNonNull(scope);Objects.requireNonNull(work);
        if(TransactionSynchronizationManager.isActualTransactionActive()||TransactionSynchronizationManager.isSynchronizationActive()
                ||TransactionSynchronizationManager.hasResource(dataSource))throw failure("Lifecycle writes require an independent transaction");
        return transaction.execute(status->{
            guard.forGlobalSecurityChange(scope.affectedTenants());
            ConnectionHolder holder=holder();Connection connection=holder.getConnection();
            Marker marker=new Marker(scope,holder,connection,physical(connection));
            TransactionSynchronizationManager.registerSynchronization(marker);
            try {
                if(scope.sourceSession()!=null) {
                    setTenant(connection,scope.sourceSession().context().tenantId().toString());
                    marker.expectedTenant=scope.sourceSession().context().tenantId().toString();
                    marker.validate(false);
                    scope.sourceSession().beginSourceValidation();
                    marker.validate(false);
                    scope.sourceSession().closeSourcePhase();
                }
                String target=scope.targetTenantId()==null?"":scope.targetTenantId().toString();
                setTenant(connection,target);marker.expectedTenant=target;marker.targetPhase=true;
                marker.validate(true);
                return work.get();
            } catch(RuntimeException error){marker.invalidated=true;holder.setRollbackOnly();throw error;}
        });
    }
    @Override public void requireActive(Scope scope) {
        Marker matching=null;
        try {
            for(var synchronization:TransactionSynchronizationManager.getSynchronizations())
                if(synchronization instanceof Marker marker&&marker.owner()==this&&marker.scope==scope){matching=marker;break;}
            if(matching!=null){matching.validate(true);return;}
            throw failure("A matching lifecycle transaction is required");
        } catch(RuntimeException error){
            if(matching!=null){matching.invalidated=true;matching.originalHolder.setRollbackOnly();}
            if(TransactionSynchronizationManager.getResource(dataSource) instanceof ConnectionHolder bound)bound.setRollbackOnly();
            throw error;
        }
    }
    @Override public void requireSqlConnection(Scope scope,DataSource source,Connection connection) {
        requireActive(scope);ConnectionHolder holder=holder();
        try {
            if(source!=dataSource||connection!=holder.getConnection())throw failure("Lifecycle SQL connection changed");
        } catch(RuntimeException error){
            holder.setRollbackOnly();
            for(var synchronization:TransactionSynchronizationManager.getSynchronizations())
                if(synchronization instanceof Marker marker&&marker.owner()==this)marker.invalidated=true;
            throw error;
        }
    }
    private ConnectionHolder holder() {
        Object resource=TransactionSynchronizationManager.getResource(dataSource);
        if(!(resource instanceof ConnectionHolder holder)||!holder.isSynchronizedWithTransaction()||holder.isRollbackOnly()
                ||!TransactionSynchronizationManager.isActualTransactionActive()||!TransactionSynchronizationManager.isSynchronizationActive())
            throw failure("A managed lifecycle datasource transaction is required");
        return holder;
    }
    private final class Marker implements TransactionSynchronization {
        private final Scope scope;private final ConnectionHolder originalHolder;private final Connection connection,physical;
        private final Thread thread=Thread.currentThread();private boolean targetPhase,invalidated,completed;
        private String expectedTenant;
        private Marker(Scope scope,ConnectionHolder holder,Connection connection,Connection physical){this.scope=scope;this.originalHolder=holder;this.connection=connection;this.physical=physical;}
        private ManagedIdentityLifecycleSecurityTransaction owner(){return ManagedIdentityLifecycleSecurityTransaction.this;}
        private void validate(boolean requireTarget) {
            try {
                if(invalidated||completed||thread!=Thread.currentThread()||holder()!=originalHolder
                        ||connection!=originalHolder.getConnection()||physical(connection)!=physical
                        ||(requireTarget&&!targetPhase)||!TransactionSynchronizationManager.getSynchronizations().contains(this))
                    throw failure("Lifecycle transaction identity or phase changed");
                guard.requireGlobalSecurityChange(scope.affectedTenants());
                if(!Objects.equals(expectedTenant,scalar(connection,"select current_setting('app.tenant_id', true)")))
                    throw failure("Lifecycle tenant scope changed");
                if(scope.sourceSession()!=null)scope.sourceSession().requireRequestCurrent();
            } catch(SQLException|RuntimeException error){invalidated=true;originalHolder.setRollbackOnly();if(error instanceof AuthorizationWriteGuardException known)throw known;throw failure("Cannot validate lifecycle transaction");}
        }
        @Override public void suspend(){invalidated=true;originalHolder.setRollbackOnly();}
        @Override public void savepointRollback(Object savepoint){invalidated=true;originalHolder.setRollbackOnly();}
        @Override public void beforeCommit(boolean readOnly){validate(true);}
        @Override public void afterCompletion(int status){completed=true;invalidated=true;}
    }
    private static Connection physical(Connection connection) {
        try {
            var visited=new IdentityHashMap<Connection,Boolean>();Connection current=connection;
            while(true){if(visited.put(current,Boolean.TRUE)!=null)throw failure("Cyclic datasource connection wrapper");
                if(current instanceof ConnectionProxy proxy){current=proxy.getTargetConnection();continue;}
                Connection unwrapped=current.unwrap(Connection.class);if(unwrapped==current)return current;if(unwrapped==null)throw failure("Missing physical connection");current=unwrapped;}
        }catch(SQLException error){throw failure("Cannot identify lifecycle physical connection");}
    }
    private static void setTenant(Connection connection,String tenant) {
        try(var statement=connection.prepareStatement("select set_config('app.tenant_id', ?, true)")){
            statement.setString(1,tenant);try(var result=statement.executeQuery()){if(!result.next()||!tenant.equals(result.getString(1)))throw failure("Cannot establish lifecycle tenant");}
        }catch(SQLException error){throw failure("Cannot establish lifecycle tenant");}
    }
    private static String scalar(Connection connection,String sql)throws SQLException{try(var statement=connection.prepareStatement(sql);var rows=statement.executeQuery()){if(!rows.next())throw new SQLException("Missing transaction setting");return rows.getString(1);}}
    private static AuthorizationWriteGuardException failure(String message){return new AuthorizationWriteGuardException(message);}
}
