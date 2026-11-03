package com.mightyoung.softwarefactory.authorization;

import com.mightyoung.softwarefactory.identity.CapturedHumanRequest;
import com.mightyoung.softwarefactory.identity.SessionSensitiveSecurityTransaction;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.datasource.ConnectionProxy;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/** Source-only G008 boundary. Deliberately not auto-enabled before all authority consumers land. */
public final class ManagedSessionSensitiveSecurityTransaction implements SessionSensitiveSecurityTransaction {
    private final DataSource source;
    private final PostgresAuthorizationWriteGuard guard;
    private final TransactionTemplate writer,reader;
    public ManagedSessionSensitiveSecurityTransaction(DataSource source,PlatformTransactionManager manager,PostgresAuthorizationWriteGuard guard) {
        this.source=Objects.requireNonNull(source);this.guard=Objects.requireNonNull(guard);guard.requireDataSource(source);
        writer=template(manager,false,TransactionDefinition.ISOLATION_READ_COMMITTED);
        reader=template(manager,true,TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }
    private static TransactionTemplate template(PlatformTransactionManager manager,boolean readOnly,int isolation) {
        TransactionTemplate result=new TransactionTemplate(Objects.requireNonNull(manager));
        result.setReadOnly(readOnly);result.setIsolationLevel(isolation);result.setTimeout(10);return result;
    }
    @Override public <T> T execute(Scope scope,Function<GuardedBoundary,T> work) {
        Objects.requireNonNull(scope);Objects.requireNonNull(work);outside();scope.request().requireCurrent();
        return writer.execute(status->{
            guard.forGlobalSecurityChange(List.of(scope.request().context().tenantId()));
            Marker marker=start(scope,scope.request(),false);
            GuardedProof proof=new GuardedProof(marker);marker.proof=proof;
            return run(marker,()->work.apply(proof));
        });
    }
    @Override public <T> T read(CapturedHumanRequest request,Function<ReadSnapshotBoundary,T> work) {
        Objects.requireNonNull(request);Objects.requireNonNull(work);outside();request.requireCurrent();
        return reader.execute(status->{
            Marker marker=start(null,request,true);
            SnapshotProof proof=new SnapshotProof(marker);marker.proof=proof;
            return run(marker,()->work.apply(proof));
        });
    }
    private <T> T run(Marker marker,java.util.function.Supplier<T> work) {
        try {marker.validate();T value=work.get();marker.validate();return value;}
        catch(RuntimeException error){marker.poison();throw error;}
    }
    private Marker start(Scope scope,CapturedHumanRequest request,boolean readOnly) {
        ConnectionHolder holder=holder();Connection connection=holder.getConnection();
        String tenant=request.context().tenantId().toString();
        try(var statement=connection.prepareStatement("select set_config('app.tenant_id', ?, true)")){
            statement.setString(1,tenant);try(var rows=statement.executeQuery()){
                if(!rows.next()||!tenant.equals(rows.getString(1)))throw failure();
            }
        }catch(SQLException error){holder.setRollbackOnly();throw failure();}
        Marker marker=new Marker(scope,request,holder,connection,physical(connection),tenant,readOnly);
        TransactionSynchronizationManager.registerSynchronization(marker);
        marker.validate();
        // The writer already owns its guard; a snapshot uses the transaction's one fixed time.
        String sql=readOnly?"select transaction_timestamp()":"select statement_timestamp()";
        try(var statement=connection.prepareStatement(sql);var rows=statement.executeQuery()){
            if(!rows.next())throw failure();marker.time=rows.getObject(1,OffsetDateTime.class).toInstant();
            if(rows.next())throw failure();
        }catch(SQLException|RuntimeException error){marker.poison();throw failure();}
        marker.validate();return marker;
    }
    @Override public void requireGuarded(Scope scope,GuardedBoundary boundary,DataSource expected,Connection connection) {
        Marker marker=boundary instanceof GuardedProof proof?proof.marker:null;
        assertMarker(marker,scope,scope==null?null:scope.request(),boundary,expected,connection,false);
    }
    @Override public void requireSnapshot(CapturedHumanRequest request,ReadSnapshotBoundary boundary,DataSource expected,Connection connection) {
        Marker marker=boundary instanceof SnapshotProof proof?proof.marker:null;
        assertMarker(marker,null,request,boundary,expected,connection,true);
    }
    private void assertMarker(Marker marker,Scope scope,CapturedHumanRequest request,Object proof,DataSource expected,Connection connection,boolean readOnly) {
        try {
            if(marker==null||marker.owner()!=this||marker.scope!=scope||marker.request!=request||marker.proof!=proof
                    ||marker.readOnly!=readOnly||expected!=source||connection!=marker.connection)throw failure();
            marker.validate();
        }catch(RuntimeException error){
            if(marker!=null&&marker.owner()==this)marker.poison();
            if(TransactionSynchronizationManager.getResource(source) instanceof ConnectionHolder holder)holder.setRollbackOnly();
            throw failure();
        }
    }
    private void outside(){
        if(TransactionSynchronizationManager.isActualTransactionActive()||TransactionSynchronizationManager.isSynchronizationActive()
                ||TransactionSynchronizationManager.hasResource(source))throw failure();
    }
    private ConnectionHolder holder(){
        Object resource=TransactionSynchronizationManager.getResource(source);
        if(!(resource instanceof ConnectionHolder holder)||!holder.isSynchronizedWithTransaction()||holder.isRollbackOnly()
                ||!TransactionSynchronizationManager.isActualTransactionActive()||!TransactionSynchronizationManager.isSynchronizationActive())throw failure();
        return holder;
    }
    private final class Marker implements TransactionSynchronization {
        final Scope scope;final CapturedHumanRequest request;final ConnectionHolder originalHolder;
        final Connection connection,physical;final String tenant;final boolean readOnly;final Thread thread=Thread.currentThread();
        boolean invalidated,completed;Instant time;Object proof;
        Marker(Scope scope,CapturedHumanRequest request,ConnectionHolder holder,Connection connection,Connection physical,String tenant,boolean readOnly){
            this.scope=scope;this.request=request;originalHolder=holder;this.connection=connection;this.physical=physical;this.tenant=tenant;this.readOnly=readOnly;
        }
        ManagedSessionSensitiveSecurityTransaction owner(){return ManagedSessionSensitiveSecurityTransaction.this;}
        void poison(){invalidated=true;originalHolder.setRollbackOnly();}
        void validate(){
            try {
                int isolation=readOnly?Connection.TRANSACTION_REPEATABLE_READ:Connection.TRANSACTION_READ_COMMITTED;
                if(invalidated||completed||thread!=Thread.currentThread()||holder()!=originalHolder
                        ||connection!=originalHolder.getConnection()||physical(connection)!=physical
                        ||!TransactionSynchronizationManager.getSynchronizations().contains(this)
                        ||connection.getAutoCommit()||connection.isReadOnly()!=readOnly||connection.getTransactionIsolation()!=isolation
                        ||TransactionSynchronizationManager.isCurrentTransactionReadOnly()!=readOnly
                        ||!Integer.valueOf(isolation).equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())
                        ||!(readOnly?"repeatable read":"read committed").equals(scalar(connection,"select current_setting('transaction_isolation')"))
                        ||!(readOnly?"on":"off").equals(scalar(connection,"select current_setting('transaction_read_only')"))
                        ||!tenant.equals(scalar(connection,"select current_setting('app.tenant_id',true)")))throw failure();
                request.requireCurrent();
                if(!readOnly)guard.requireGlobalSecurityChange(List.of(request.context().tenantId()));
            }catch(SQLException|RuntimeException error){poison();throw failure();}
        }
        @Override public void suspend(){poison();}
        @Override public void savepointRollback(Object savepoint){poison();}
        @Override public void beforeCommit(boolean mode){if(mode!=readOnly){poison();throw failure();}validate();}
        @Override public void afterCompletion(int status){completed=true;invalidated=true;}
    }
    private final class GuardedProof implements GuardedBoundary {
        final Marker marker;GuardedProof(Marker marker){this.marker=marker;}
        @Override public Instant observedAt(){marker.validate();return Objects.requireNonNull(marker.time);}
        @Override public String toString(){return "GuardedSessionBoundary[REDACTED]";}
    }
    private final class SnapshotProof implements ReadSnapshotBoundary {
        final Marker marker;SnapshotProof(Marker marker){this.marker=marker;}
        @Override public Instant observedAt(){marker.validate();return Objects.requireNonNull(marker.time);}
        @Override public String toString(){return "SessionReadSnapshot[REDACTED]";}
    }
    private static Connection physical(Connection connection){
        try {
            IdentityHashMap<Connection,Boolean> seen=new IdentityHashMap<>();Connection current=connection;
            while(true){
                if(seen.put(current,Boolean.TRUE)!=null)throw failure();
                if(current instanceof ConnectionProxy proxy){current=proxy.getTargetConnection();continue;}
                Connection unwrapped=current.unwrap(Connection.class);if(unwrapped==current)return current;
                if(unwrapped==null)throw failure();current=unwrapped;
            }
        }catch(SQLException error){throw failure();}
    }
    private static String scalar(Connection connection,String sql)throws SQLException{
        try(var statement=connection.prepareStatement(sql);var rows=statement.executeQuery()){
            if(!rows.next())throw new SQLException("Missing transaction state");return rows.getString(1);
        }
    }
    private static AuthorizationWriteGuardException failure(){return new AuthorizationWriteGuardException("Session security transaction is unavailable");}
}
