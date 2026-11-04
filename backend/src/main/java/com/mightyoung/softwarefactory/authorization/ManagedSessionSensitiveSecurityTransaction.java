package com.mightyoung.softwarefactory.authorization;

import com.mightyoung.softwarefactory.identity.CapturedHumanRequest;
import com.mightyoung.softwarefactory.identity.KnownLineageLogoutCapture;
import com.mightyoung.softwarefactory.identity.HumanSessionAuthorityTransaction;
import com.mightyoung.softwarefactory.identity.SessionAuthorityControlTransaction;
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
import java.util.UUID;
import java.util.function.Function;

/** Source-only boundary: sensitive operations own a transaction; HUMAN assertions borrow one. */
public final class ManagedSessionSensitiveSecurityTransaction implements SessionSensitiveSecurityTransaction,HumanSessionAuthorityTransaction,SessionAuthorityControlTransaction {
    private static final String COMMAND_CLOCK_SQL="select statement_timestamp()";
    /** The same statement clock is sampled afresh at each borrowed command boundary, including final validation. */
    public static java.util.Map<String,Integer> commandReadSql(){return java.util.Map.of(COMMAND_CLOCK_SQL,0);}
    private final DataSource source;
    private final PostgresAuthorizationWriteGuard guard;
    private final TransactionTemplate writer,reader;
    private final Object controlScope=new Object();
    public ManagedSessionSensitiveSecurityTransaction(DataSource source,PlatformTransactionManager manager,PostgresAuthorizationWriteGuard guard) {
        this.source=Objects.requireNonNull(source);this.guard=Objects.requireNonNull(guard);guard.requireDataSource(source);
        writer=template(manager,false,TransactionDefinition.ISOLATION_READ_COMMITTED);
        reader=template(manager,true,TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }
    private static TransactionTemplate template(PlatformTransactionManager manager,boolean readOnly,int isolation) {
        TransactionTemplate result=new TransactionTemplate(Objects.requireNonNull(manager));
        result.setReadOnly(readOnly);result.setIsolationLevel(isolation);result.setTimeout(10);return result;
    }
    @Override public <T>T execute(Scope scope,Function<SessionSensitiveSecurityTransaction.GuardedBoundary,T> work) {
        Objects.requireNonNull(scope);Objects.requireNonNull(work);outside();scope.request().requireCurrent();
        return writer.execute(status->{
            var tenants=List.of(scope.request().context().tenantId());guard.forGlobalSecurityChange(tenants);
            Marker marker=start(scope,scope.request(),null,false,Guard.GLOBAL_SECURITY,tenants,true);
            GuardedProof proof=new GuardedProof(marker);marker.proof=proof;return run(marker,()->work.apply(proof));
        });
    }
    @Override public <T>T executeLogout(LogoutScope scope,Function<LogoutBoundary,T> work) {
        Objects.requireNonNull(scope);Objects.requireNonNull(work);outside();scope.request().requireUnchanged();
        return writer.execute(status->{
            // All logout-owned tables are global identity tables; no fabricated tenant is needed.
            guard.forGlobalSecurityChange();
            Marker marker=start(scope,null,scope.request(),false,Guard.GLOBAL_SECURITY,List.of(),true);
            LogoutProof proof=new LogoutProof(marker);marker.proof=proof;return run(marker,()->work.apply(proof));
        });
    }
    @Override public <T>T read(CapturedHumanRequest request,Function<ReadSnapshotBoundary,T> work) {
        Objects.requireNonNull(request);Objects.requireNonNull(work);outside();request.requireCurrent();
        return reader.execute(status->{
            Marker marker=start(null,request,null,true,null,List.of(),true);
            SnapshotProof proof=new SnapshotProof(marker);marker.proof=proof;return run(marker,()->work.apply(proof));
        });
    }
    @Override public <T>T readControl(Function<SessionAuthorityControlTransaction.Boundary,T> work){
        Objects.requireNonNull(work);outside();
        return reader.execute(status->{
            Marker marker=start(controlScope,null,null,true,null,List.of(),true);
            ControlProof proof=new ControlProof(marker);marker.proof=proof;return run(marker,()->work.apply(proof));
        });
    }
    @Override public void requireControl(SessionAuthorityControlTransaction.Boundary boundary,DataSource expected,Connection connection){
        assertMarker(boundary instanceof ControlProof proof?proof.marker:null,controlScope,null,null,boundary,expected,connection,true);
    }
    @Override public <T>T withGuarded(WriteScope scope,Function<HumanSessionAuthorityTransaction.GuardedBoundary,T> work) {
        Objects.requireNonNull(scope);Objects.requireNonNull(work);scope.request().requireCurrent();
        // Verification only: never acquire a guard, open a transaction or alter caller RLS.
        Marker marker=start(scope,scope.request(),null,false,scope.guard(),scope.declaredTenants(),false);
        CallerGuardedProof proof=new CallerGuardedProof(marker);marker.proof=proof;return run(marker,()->work.apply(proof));
    }
    @Override public <T>T withRead(CapturedHumanRequest request,Function<ReadBoundary,T> work) {
        Objects.requireNonNull(request);Objects.requireNonNull(work);request.requireCurrent();
        Marker marker=start(request,request,null,true,null,List.of(),false);
        CallerReadProof proof=new CallerReadProof(marker);marker.proof=proof;return run(marker,()->work.apply(proof));
    }
    private <T>T run(Marker marker,java.util.function.Supplier<T> work) {
        try {marker.validate();T value=work.get();marker.validate();return value;}
        catch(RuntimeException error){marker.poison();throw error;}
        finally{marker.callbackComplete=true;}
    }
    private Marker start(Object scope,CapturedHumanRequest request,KnownLineageLogoutCapture logout,boolean readOnly,
                         Guard guardMode,List<UUID> tenants,boolean bindTenant) {
        ConnectionHolder holder=holder();Connection connection=holder.getConnection();
        String tenant=request==null?"":request.context().tenantId().toString();
        if(bindTenant){
            try(var statement=connection.prepareStatement("select set_config('app.tenant_id', ?, true)")){
                statement.setString(1,tenant);try(var rows=statement.executeQuery()){
                    if(!rows.next()||!tenant.equals(rows.getString(1)))throw failure();
                }
            }catch(SQLException error){holder.setRollbackOnly();throw failure();}
        }
        Marker marker=new Marker(scope,request,logout,holder,connection,physical(connection),tenant,readOnly,guardMode,tenants,!bindTenant);
        TransactionSynchronizationManager.registerSynchronization(marker);marker.validate();
        String sql=readOnly?"select transaction_timestamp()":COMMAND_CLOCK_SQL;
        try(var statement=connection.prepareStatement(sql);var rows=statement.executeQuery()){
            if(!rows.next())throw failure();marker.time=rows.getObject(1,OffsetDateTime.class).toInstant();
            if(rows.next())throw failure();
        }catch(SQLException|RuntimeException error){marker.poison();throw failure();}
        marker.validate();return marker;
    }
    @Override public void requireGuarded(Scope scope,SessionSensitiveSecurityTransaction.GuardedBoundary boundary,DataSource expected,Connection connection) {
        assertMarker(boundary instanceof GuardedProof proof?proof.marker:null,scope,scope==null?null:scope.request(),null,boundary,expected,connection,false);
    }
    @Override public void requireLogout(LogoutScope scope,LogoutBoundary boundary,DataSource expected,Connection connection) {
        assertMarker(boundary instanceof LogoutProof proof?proof.marker:null,scope,null,scope==null?null:scope.request(),boundary,expected,connection,false);
    }
    @Override public void requireSnapshot(CapturedHumanRequest request,ReadSnapshotBoundary boundary,DataSource expected,Connection connection) {
        assertMarker(boundary instanceof SnapshotProof proof?proof.marker:null,null,request,null,boundary,expected,connection,true);
    }
    @Override public void requireGuarded(WriteScope scope,HumanSessionAuthorityTransaction.GuardedBoundary boundary,DataSource expected,Connection connection) {
        assertMarker(boundary instanceof CallerGuardedProof proof?proof.marker:null,scope,scope==null?null:scope.request(),null,boundary,expected,connection,false);
    }
    @Override public void requireRead(CapturedHumanRequest request,ReadBoundary boundary,DataSource expected,Connection connection) {
        assertMarker(boundary instanceof CallerReadProof proof?proof.marker:null,request,request,null,boundary,expected,connection,true);
    }
    private void assertMarker(Marker marker,Object scope,CapturedHumanRequest request,KnownLineageLogoutCapture logout,Object proof,
                              DataSource expected,Connection connection,boolean readOnly) {
        try {
            if(marker==null||marker.owner()!=this||marker.scope!=scope||marker.request!=request||marker.logout!=logout||marker.proof!=proof
                    ||marker.readOnly!=readOnly||expected!=source||connection!=marker.connection||marker.callbackComplete)throw failure();
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
        final Object scope;final CapturedHumanRequest request;final KnownLineageLogoutCapture logout;
        final ConnectionHolder originalHolder;final Connection connection,physical;final String tenant;
        final boolean readOnly,borrowed;final Guard guardMode;final List<UUID> tenants;final Thread thread=Thread.currentThread();
        boolean invalidated,completed,callbackComplete;Instant time;Object proof;
        Marker(Object scope,CapturedHumanRequest request,KnownLineageLogoutCapture logout,ConnectionHolder holder,Connection connection,
               Connection physical,String tenant,boolean readOnly,Guard guardMode,List<UUID> tenants,boolean borrowed){
            this.scope=scope;this.request=request;this.logout=logout;originalHolder=holder;this.connection=connection;this.physical=physical;
            this.tenant=tenant;this.readOnly=readOnly;this.borrowed=borrowed;this.guardMode=guardMode;this.tenants=List.copyOf(tenants);
        }
        ManagedSessionSensitiveSecurityTransaction owner(){return ManagedSessionSensitiveSecurityTransaction.this;}
        void poison(){invalidated=true;originalHolder.setRollbackOnly();}
        void validate(){
            try {
                int isolation=readOnly?Connection.TRANSACTION_REPEATABLE_READ:Connection.TRANSACTION_READ_COMMITTED;
                validateJavaState(isolation);
                if(!(readOnly?"repeatable read":"read committed").equals(scalar(connection,"select current_setting('transaction_isolation')"))
                        ||!(readOnly?"on":"off").equals(scalar(connection,"select current_setting('transaction_read_only')"))
                        ||!tenant.equals(scalar(connection,"select current_setting('app.tenant_id',true)")))throw failure();
                if(request!=null)request.requireCurrent();else if(logout!=null)logout.requireUnchanged();else if(scope!=controlScope||!readOnly)throw failure();
                if(!readOnly){
                    if(guardMode==Guard.FINDING_COMMAND)guard.requireFindingCommand(request.context().tenantId());
                    else if(guardMode==Guard.GLOBAL_SECURITY)guard.requireGlobalSecurityChange(tenants);
                    else throw failure();
                }
            }catch(SQLException|RuntimeException error){poison();throw failure();}
        }
        void validateJavaState(int isolation)throws SQLException {
            if(invalidated||completed||thread!=Thread.currentThread()||holder()!=originalHolder
                    ||connection!=originalHolder.getConnection()||physical(connection)!=physical
                    ||!TransactionSynchronizationManager.getSynchronizations().contains(this)
                    ||connection.getAutoCommit()||connection.isReadOnly()!=readOnly||connection.getTransactionIsolation()!=isolation
                    ||TransactionSynchronizationManager.isCurrentTransactionReadOnly()!=readOnly
                    ||!Integer.valueOf(isolation).equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel()))throw failure();
        }
        void validateClosedCaller(){
            try {
                // The caller may have irreversibly moved from SOURCE_SESSION to target RLS.
                // Its callback proof is already closed: do not re-enter source SQL at commit.
                validateJavaState(readOnly?Connection.TRANSACTION_REPEATABLE_READ:Connection.TRANSACTION_READ_COMMITTED);
                request.requireCurrent();
            }catch(SQLException|RuntimeException error){poison();throw failure();}
        }
        void requireCallback(){if(callbackComplete){poison();throw failure();}validate();}
        @Override public void suspend(){poison();}
        @Override public void savepoint(Object savepoint){poison();}
        @Override public void savepointRollback(Object savepoint){poison();}
        @Override public void beforeCommit(boolean mode){if(mode!=readOnly){poison();throw failure();}
            if(borrowed&&callbackComplete)validateClosedCaller();else validate();}
        @Override public void afterCompletion(int status){completed=true;invalidated=true;}
    }
    private final class GuardedProof implements SessionSensitiveSecurityTransaction.GuardedBoundary {
        final Marker marker;GuardedProof(Marker marker){this.marker=marker;}
        @Override public Instant observedAt(){marker.requireCallback();return Objects.requireNonNull(marker.time);}
        @Override public String toString(){return "GuardedSessionBoundary[REDACTED]";}
    }
    private final class LogoutProof implements LogoutBoundary {
        final Marker marker;LogoutProof(Marker marker){this.marker=marker;}
        @Override public Instant observedAt(){marker.requireCallback();return Objects.requireNonNull(marker.time);}
        @Override public String toString(){return "LogoutSessionBoundary[REDACTED]";}
    }
    private final class SnapshotProof implements ReadSnapshotBoundary {
        final Marker marker;SnapshotProof(Marker marker){this.marker=marker;}
        @Override public Instant observedAt(){marker.requireCallback();return Objects.requireNonNull(marker.time);}
        @Override public String toString(){return "SessionReadSnapshot[REDACTED]";}
    }
    private final class ControlProof implements SessionAuthorityControlTransaction.Boundary {
        final Marker marker;ControlProof(Marker marker){this.marker=marker;}
        @Override public Instant observedAt(){marker.requireCallback();return Objects.requireNonNull(marker.time);}
        @Override public String toString(){return "SessionAuthorityControlBoundary[REDACTED]";}
    }
    private final class CallerGuardedProof implements HumanSessionAuthorityTransaction.GuardedBoundary {
        final Marker marker;CallerGuardedProof(Marker marker){this.marker=marker;}
        @Override public Instant observedAt(){marker.requireCallback();return Objects.requireNonNull(marker.time);}
        @Override public String toString(){return "CallerGuardedSessionBoundary[REDACTED]";}
    }
    private final class CallerReadProof implements ReadBoundary {
        final Marker marker;CallerReadProof(Marker marker){this.marker=marker;}
        @Override public Instant observedAt(){marker.requireCallback();return Objects.requireNonNull(marker.time);}
        @Override public String toString(){return "CallerSessionReadBoundary[REDACTED]";}
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
