package com.mightyoung.softwarefactory.dualprevention.risk;

import com.mightyoung.softwarefactory.authorization.AuthorizationWriteGuard;
import com.mightyoung.softwarefactory.authorization.PostgresAuthorizationWriteGuard;
import com.mightyoung.softwarefactory.identity.*;
import com.mightyoung.softwarefactory.tenancy.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.jooq.SQLDialect;
import org.jooq.conf.Settings;
import org.jooq.impl.DSL;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.*;
import static com.mightyoung.softwarefactory.dualprevention.risk.RiskChangeCaptureScope.*;

/** Explicit private graph for one durable metadata cause. No bean, listener, HUMAN authority or activation fallback. */
public final class RiskChangeCaptureService {
    static final String SET_TENANT_SQL="select pg_catalog.set_config('app.tenant_id',?,true)";
    public record Capture(UUID tenantId,UUID commandId,int consumerVersion,Instant recordedAt,
            UUID recordedByServiceActorId,boolean replay) { }
    private record Eligibility(PrincipalStatus principal,TenantLifecycleState tenant,TenantApplicationState application) { }
    private final DataSource source;
    private final UUID serviceActor;
    private final Set<UUID> tenants;
    private final AuthorizationWriteGuard guard;
    private final PrincipalStatusReadRepository principals;
    private final TenantLifecycleReadRepository lifecycle;
    private final TenantApplicationReadRepository applications;
    private final TransactionTemplate transaction;
    private final Object issuer=new Object(),commitKey=new Object();
    private final RiskChangeCauseRepository repository;

    /** Constructs the exact real collaborators over one canonical source without borrowing a connection. */
    public static RiskChangeCaptureService create(DataSource source,UUID serviceActor,Set<UUID> tenants){
        try {
            Objects.requireNonNull(source);var manager=new JdbcTransactionManager(source);
            var dsl=DSL.using(new TransactionAwareDataSourceProxy(source),SQLDialect.POSTGRES,
                    new Settings().withExecuteLogging(false).withFetchWarnings(false));
            return new RiskChangeCaptureService(source,serviceActor,tenants,manager,new PostgresAuthorizationWriteGuard(source),
                    new JooqPrincipalStatusReadRepository(dsl),new JdbcTenantLifecycleReadRepository(source),
                    new JooqTenantApplicationReadRepository(dsl,manager,source));
        }catch(RuntimeException|Error failure){throw rejected();}
    }
    RiskChangeCaptureService(DataSource source,UUID serviceActor,Set<UUID> tenants,JdbcTransactionManager manager,
            AuthorizationWriteGuard guard,PrincipalStatusReadRepository principals,TenantLifecycleReadRepository lifecycle,
            TenantApplicationReadRepository applications){
        try {
            this.source=Objects.requireNonNull(source);this.serviceActor=Objects.requireNonNull(serviceActor);
            this.tenants=Set.copyOf(tenants);if(this.tenants.isEmpty()||manager.getDataSource()!=source)throw rejected();
            this.guard=Objects.requireNonNull(guard);this.principals=Objects.requireNonNull(principals);
            this.lifecycle=Objects.requireNonNull(lifecycle);this.applications=Objects.requireNonNull(applications);
            this.transaction=new TransactionTemplate(manager);transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);transaction.setTimeout(10);
            repository=new RiskChangeCauseRepository(source,issuer);
        }catch(RuntimeException|Error failure){throw rejected();}
    }
    /** Static consumer domain only; this is not proof of current SERVICE eligibility or HUMAN authorization. */
    public boolean acceptsTenant(UUID tenant){return tenant!=null&&tenants.contains(tenant);}

    public Capture capture(RiskScenarioDraftChangedEvent event){
        var active=new AtomicReference<RiskChangeCaptureScope>();
        try {
            if(event==null||!acceptsTenant(event.tenantId())||TransactionSynchronizationManager.isActualTransactionActive()
                    ||TransactionSynchronizationManager.isSynchronizationActive()||!TransactionSynchronizationManager.getResourceMap().isEmpty())throw rejected();
            Capture result=transaction.execute(status->{
                ConnectionHolder holder=null;
                try {
                    if(!(TransactionSynchronizationManager.getResource(source) instanceof ConnectionHolder current))throw rejected();holder=current;
                    setTenant(holder,event.tenantId());guard.forBusinessCommand(event.tenantId());
                    var scope=new RiskChangeCaptureScope(issuer,commitKey,source,holder,event.tenantId(),serviceActor,guard);active.set(scope);
                    var initial=new AtomicReference<Eligibility>();
                    var synchronization=new TransactionSynchronization(){
                        @Override public void beforeCommit(boolean readOnly){
                            try {
                                if(readOnly)throw rejected();scope.commitBoundary(issuer,commitKey);
                                if(!Objects.equals(initial.get(),eligibility(event.tenantId(),()->scope.commitBoundary(issuer,commitKey))))throw rejected();
                                scope.commitBoundary(issuer,commitKey);
                            }catch(RuntimeException|Error failure){scope.reject();throw rejected();}
                        }
                        @Override public void savepointRollback(Object savepoint){scope.reject();}
                        @Override public void beforeCompletion(){scope.close();}
                        @Override public void afterCompletion(int completion){scope.close();}
                    };
                    TransactionSynchronizationManager.registerSynchronization(synchronization);
                    var expected=TransactionSynchronizationManager.getSynchronizations();
                    initial.set(eligibility(event.tenantId(),()->scope.createdBoundary(issuer,commitKey,expected)));scope.open(issuer,expected);
                    repository.requireSource(scope,event);var stored=repository.store(scope,event);scope.finish(issuer);
                    var cause=stored.cause();return new Capture(cause.tenantId(),cause.commandId(),cause.consumerVersion(),
                            cause.recordedAt(),cause.recordedByServiceActorId(),stored.replay());
                }catch(RuntimeException|Error failure){if(holder!=null)holder.setRollbackOnly();var scope=active.get();if(scope!=null)scope.reject();status.setRollbackOnly();throw rejected();}
            });
            if(result==null)throw rejected();return result;
        }catch(RuntimeException|Error failure){throw rejected();}
        finally {var scope=active.get();if(scope!=null)scope.close();}
    }
    private Eligibility eligibility(UUID tenant,Runnable boundary){
        boundary.run();
        var principal=principals.findByActorId(serviceActor).orElseThrow(RiskChangeCaptureScope::rejected);boundary.run();
        if(!serviceActor.equals(principal.principalId())||principal.accountId()!=null||!"SERVICE".equals(principal.kind())||!"ACTIVE".equals(principal.status()))throw rejected();
        var current=lifecycle.findCurrentInCaller(tenant).orElseThrow(RiskChangeCaptureScope::rejected);boundary.run();
        if(!tenant.equals(current.tenantId())||!"ACTIVE".equals(current.status())||current.authEpoch()<0)throw rejected();
        var app=applications.findCurrentInCaller(tenant,"safety").orElseThrow(RiskChangeCaptureScope::rejected);boundary.run();
        if(!app.enabled()||app.version()<1)throw rejected();boundary.run();
        return new Eligibility(principal,current,app);
    }
    private void setTenant(ConnectionHolder holder,UUID tenant){
        try {
            Connection connection=holder.getConnection(),physical=DataSourceUtils.getTargetConnection(connection);Thread thread=Thread.currentThread();
            setupPins(holder,connection,physical,thread);var metadata=connection.getMetaData();
            if(metadata==null||!"PostgreSQL".equals(metadata.getDatabaseProductName())||metadata.getDatabaseMajorVersion()!=18)throw rejected();
            setupPins(holder,connection,physical,thread);
            try(var statement=connection.prepareStatement(SET_TENANT_SQL)){
                timeout(statement,holder);statement.setMaxRows(2);statement.setString(1,tenant.toString());setupPins(holder,connection,physical,thread);
                try(var rows=statement.executeQuery()){
                    width(rows,1);if(!rows.next()||!tenant.toString().equals(rows.getObject(1))||rows.next())throw rejected();
                }
            }
            setupPins(holder,connection,physical,thread);
        }catch(SQLException|RuntimeException|Error failure){holder.setRollbackOnly();throw rejected();}
    }
    private void setupPins(ConnectionHolder holder,Connection connection,Connection physical,Thread thread)throws SQLException {
        if(!TransactionSynchronizationManager.getSynchronizations().isEmpty()||Thread.currentThread()!=thread||TransactionSynchronizationManager.getResource(source)!=holder||holder.getConnection()!=connection
                ||physical==null||DataSourceUtils.getTargetConnection(connection)!=physical||!holder.isSynchronizedWithTransaction()||holder.isRollbackOnly()
                ||!TransactionSynchronizationManager.isActualTransactionActive()||!TransactionSynchronizationManager.isSynchronizationActive()
                ||TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                ||!Integer.valueOf(Connection.TRANSACTION_READ_COMMITTED).equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())
                ||connection.getAutoCommit()||connection.isReadOnly()||connection.getTransactionIsolation()!=Connection.TRANSACTION_READ_COMMITTED)throw rejected();
    }
}
