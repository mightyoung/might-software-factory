package com.mightyoung.softwarefactory.auditops;

import com.mightyoung.softwarefactory.identity.SessionSensitiveAudit;
import com.mightyoung.softwarefactory.identity.LogoutOwnership;
import com.mightyoung.softwarefactory.identity.SessionSensitiveException;
import com.mightyoung.softwarefactory.identity.SessionSensitiveSecurityTransaction;
import static com.mightyoung.softwarefactory.identity.SessionSensitiveContracts.*;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.conf.Settings;
import org.jooq.impl.DSL;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.sql.Connection;
import java.time.ZoneOffset;
import java.util.Objects;

/** Owning audit adapter; no password-change overloading, arbitrary JSON or capability logging. */
public final class JooqSessionSensitiveAudit implements SessionSensitiveAudit {
    private final DataSource source;
    private final SessionSensitiveSecurityTransaction boundaries;
    private final TransactionTemplate failureWriter;
    public JooqSessionSensitiveAudit(DataSource source,SessionSensitiveSecurityTransaction boundaries,PlatformTransactionManager manager){
        this.source=Objects.requireNonNull(source);this.boundaries=Objects.requireNonNull(boundaries);
        failureWriter=new TransactionTemplate(Objects.requireNonNull(manager));failureWriter.setReadOnly(false);
        failureWriter.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);failureWriter.setTimeout(5);
    }
    @Override public void success(SessionSensitiveSecurityTransaction.Scope scope,SessionSensitiveSecurityTransaction.GuardedBoundary boundary,SensitiveEvent event){
        var connection=DataSourceUtils.getConnection(source);
        try {
            boundaries.requireGuarded(scope,boundary,source,connection);
            if(scope.operation()!=event.operation()||!"SUCCESS".equals(event.result())
                    ||!scope.request().context().accountId().equals(event.accountId())
                    ||!scope.request().context().actorId().equals(event.actorId())
                    ||!boundary.observedAt().equals(event.occurredAt()))throw SessionSensitiveException.unavailable();
            insert(DSL.using(connection,SQLDialect.POSTGRES,new Settings().withExecuteLogging(false)),event);
            boundaries.requireGuarded(scope,boundary,source,connection);
        }catch(RuntimeException failure){poison();throw SessionSensitiveException.unavailable();}
        finally{DataSourceUtils.releaseConnection(connection,source);}
    }
    @Override public void logoutSuccess(SessionSensitiveSecurityTransaction.LogoutScope scope,SessionSensitiveSecurityTransaction.LogoutBoundary boundary,
                                        LogoutOwnership ownership,SensitiveEvent event){
        var connection=DataSourceUtils.getConnection(source);
        try {
            boundaries.requireLogout(scope,boundary,source,connection);ownership.requireOwned(scope,boundary,source,connection);
            if(event.operation()!=Operation.LOGOUT||!"SUCCESS".equals(event.result())||event.provenance()!=Provenance.KNOWN_LINEAGE
                    ||!ownership.accountId().equals(event.accountId())||!Objects.equals(ownership.actorId(),event.actorId())
                    ||!ownership.handle().equals(event.source())||!ownership.handle().equals(event.target())
                    ||!boundary.observedAt().equals(event.occurredAt()))throw SessionSensitiveException.unavailable();
            insert(DSL.using(connection,SQLDialect.POSTGRES,new Settings().withExecuteLogging(false)),event);
            ownership.requireOwned(scope,boundary,source,connection);boundaries.requireLogout(scope,boundary,source,connection);
        }catch(RuntimeException failure){poison();throw SessionSensitiveException.unavailable();}
        finally{DataSourceUtils.releaseConnection(connection,source);}
    }
    @Override public void failure(SensitiveEvent event){
        if(!java.util.List.of("REJECTED","UNKNOWN").contains(event.result())
                ||TransactionSynchronizationManager.isActualTransactionActive()||TransactionSynchronizationManager.isSynchronizationActive()
                ||TransactionSynchronizationManager.hasResource(source))throw SessionSensitiveException.unavailable();
        failureWriter.executeWithoutResult(status->{
            var connection=DataSourceUtils.getConnection(source);
            try {
                DSLContext db=DSL.using(connection,SQLDialect.POSTGRES,new Settings().withExecuteLogging(false));
                requireFailure(connection,db);
                if(!"".equals(db.fetchValue("select set_config('app.tenant_id','',true)")))throw SessionSensitiveException.unavailable();
                insert(db,event);requireFailure(connection,db);
                if(!"".equals(db.fetchValue("select current_setting('app.tenant_id',true)")))throw SessionSensitiveException.unavailable();
            }catch(RuntimeException failure){poison();throw SessionSensitiveException.unavailable();}
            finally{DataSourceUtils.releaseConnection(connection,source);}
        });
    }
    private void requireFailure(Connection connection,DSLContext db){
        try {
            if(!(TransactionSynchronizationManager.getResource(source) instanceof ConnectionHolder holder)
                    ||holder.getConnection()!=connection||!holder.isSynchronizedWithTransaction()||holder.isRollbackOnly()
                    ||!TransactionSynchronizationManager.isActualTransactionActive()||!TransactionSynchronizationManager.isSynchronizationActive()
                    ||TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                    ||!Integer.valueOf(Connection.TRANSACTION_READ_COMMITTED).equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())
                    ||connection.getAutoCommit()||connection.isReadOnly()||connection.getTransactionIsolation()!=Connection.TRANSACTION_READ_COMMITTED
                    ||!"read committed".equals(db.fetchValue("select current_setting('transaction_isolation')"))
                    ||!"off".equals(db.fetchValue("select current_setting('transaction_read_only')")))throw SessionSensitiveException.unavailable();
        }catch(java.sql.SQLException error){throw SessionSensitiveException.unavailable();}
    }
    private void poison(){if(TransactionSynchronizationManager.getResource(source) instanceof ConnectionHolder holder)holder.setRollbackOnly();}
    private static void insert(DSLContext db,SensitiveEvent event){
        int changed=db.execute("""
                insert into public.identity_sensitive_event(id,operation,result,reason,provenance,subject_account_id,actor_id,
                 source_primary_id,target_primary_id,proof_id,challenge_id,trace_id,policy_version,occurred_at)
                values(cast(? as uuid),?,?,?,?,cast(? as uuid),cast(? as uuid),?,?,cast(? as uuid),cast(? as uuid),cast(? as uuid),
                 'session-sensitive-v1',cast(? as timestamptz))
                """,event.id(),event.operation().name(),event.result(),event.reason(),event.provenance().name(),event.accountId(),event.actorId(),
                event.source()==null?null:event.source().toString(),event.target()==null?null:event.target().toString(),event.proofId(),event.challengeId(),event.traceId(),event.occurredAt().atOffset(ZoneOffset.UTC));
        if(changed!=1)throw SessionSensitiveException.unavailable();
    }
}
