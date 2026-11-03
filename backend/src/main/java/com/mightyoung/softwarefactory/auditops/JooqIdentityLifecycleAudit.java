package com.mightyoung.softwarefactory.auditops;

import com.mightyoung.softwarefactory.identity.IdentityLifecycleAudit;
import com.mightyoung.softwarefactory.identity.IdentityLifecycleSecurityTransaction;
import com.mightyoung.softwarefactory.identity.IdentityLifecycleException;
import static com.mightyoung.softwarefactory.identity.IdentityLifecycleContracts.*;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.conf.Settings;
import org.jooq.impl.DSL;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.time.ZoneOffset;
import java.util.UUID;

/** Capability provenance never reuses PASSWORD_CHANGE rows or invents an authenticated actor. */
@Repository
@ConditionalOnProperty(prefix="software-factory.identity.lifecycle",name="enabled",havingValue="true")
public final class JooqIdentityLifecycleAudit implements IdentityLifecycleAudit {
    private final DataSource source;
    private final IdentityLifecycleSecurityTransaction writer;
    private final TransactionTemplate failureTransaction;
    public JooqIdentityLifecycleAudit(DataSource source,IdentityLifecycleSecurityTransaction writer,PlatformTransactionManager manager) {
        this.source=source;this.writer=writer;failureTransaction=new TransactionTemplate(manager);
        failureTransaction.setReadOnly(false);failureTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);failureTransaction.setTimeout(5);
    }
    @Override public void success(IdentityLifecycleSecurityTransaction.Scope scope,LifecycleEvent event) {
        writer.requireActive(scope);
        if(scope.operation()!=event.operation()||!scope.challengeId().equals(event.challengeId())
                ||!java.util.Objects.equals(scope.targetTenantId(),event.tenantId()))throw unavailable();
        if(event.initiatorKind()==InitiatorKind.SESSION&&(scope.sourceSession()==null
                ||!event.sessionInitiator().accountId().equals(scope.sourceSession().context().accountId())
                ||!event.sessionInitiator().actorId().equals(scope.sourceSession().context().actorId())
                ||!event.sessionInitiator().memberId().equals(scope.sourceSession().context().memberId())))throw unavailable();
        var connection=DataSourceUtils.getConnection(source);
        try {
            writer.requireSqlConnection(scope,source,connection);
            DSLContext db=DSL.using(connection,SQLDialect.POSTGRES,new Settings().withExecuteLogging(false));insertEvent(db,event);
            if(event.initiatorKind()==InitiatorKind.SESSION){
                int rows=db.execute("""
                        insert into public.audit_event(tenant_id,id,actor_id,action,resource_type,resource_id,result,reason,
                         diff_redacted,trace_id,policy_version,occurred_at)
                        values(cast(? as uuid),cast(? as uuid),cast(? as uuid),?,'identity-invitation',cast(? as uuid),?,null,
                         '{}'::jsonb,?,'identity-lifecycle-v1',cast(? as timestamptz))
                        """,event.tenantId(),UUID.randomUUID(),event.sessionInitiator().actorId(),event.operation().name(),event.challengeId(),event.result(),event.traceId().toString(),event.occurredAt().atOffset(ZoneOffset.UTC));
                if(rows!=1)throw unavailable();
            }
            writer.requireSqlConnection(scope,source,connection);
        } catch(RuntimeException failure){if(TransactionSynchronizationManager.getResource(source) instanceof ConnectionHolder h)h.setRollbackOnly();throw failure;}
        finally {DataSourceUtils.releaseConnection(connection,source);}
    }
    @Override public void failure(LifecycleEvent event) {
        if(TransactionSynchronizationManager.isActualTransactionActive()||TransactionSynchronizationManager.isSynchronizationActive()
                ||TransactionSynchronizationManager.hasResource(source)||"SUCCESS".equals(event.result()))throw unavailable();
        failureTransaction.executeWithoutResult(status->{
            var connection=DataSourceUtils.getConnection(source);
            try {
                DSLContext db=DSL.using(connection,SQLDialect.POSTGRES,new Settings().withExecuteLogging(false));
                requireFailureConnection(connection,db);
                String tenant=event.tenantId()==null?"":event.tenantId().toString();
                if(!tenant.equals(db.fetchValue("select set_config('app.tenant_id',?,true)",tenant)))throw unavailable();
                insertEvent(db,event);
                requireFailureConnection(connection,db);
                if(!tenant.equals(db.fetchValue("select current_setting('app.tenant_id',true)")))throw unavailable();
            } finally {DataSourceUtils.releaseConnection(connection,source);}
        });
    }
    private void requireFailureConnection(java.sql.Connection connection,DSLContext db) {
        try {
            if(!(TransactionSynchronizationManager.getResource(source) instanceof ConnectionHolder holder)
                    ||holder.getConnection()!=connection||!holder.isSynchronizedWithTransaction()||holder.isRollbackOnly()
                    ||!TransactionSynchronizationManager.isActualTransactionActive()||!TransactionSynchronizationManager.isSynchronizationActive()
                    ||TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                    ||!Integer.valueOf(java.sql.Connection.TRANSACTION_READ_COMMITTED).equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())
                    ||connection.getAutoCommit()||connection.isReadOnly()||connection.getTransactionIsolation()!=java.sql.Connection.TRANSACTION_READ_COMMITTED
                    ||!"read committed".equals(db.fetchValue("select current_setting('transaction_isolation')"))
                    ||!"off".equals(db.fetchValue("select current_setting('transaction_read_only')")))throw unavailable();
        }catch(java.sql.SQLException invalid){throw unavailable();}
    }
    private static void insertEvent(DSLContext db,LifecycleEvent event) {
        IssuerBinding initiator=event.sessionInitiator();
        int rows=db.execute("""
                insert into public.identity_lifecycle_event(id,challenge_id,operation,result,reason,initiator_kind,tenant_id,
                 subject_account_id,subject_actor_id,initiator_account_id,initiator_actor_id,initiator_member_id,
                 trace_id,policy_version,occurred_at)
                values(cast(? as uuid),cast(? as uuid),?,?,?,?,cast(? as uuid),cast(? as uuid),cast(? as uuid),
                 cast(? as uuid),cast(? as uuid),cast(? as uuid),cast(? as uuid),'identity-lifecycle-v1',cast(? as timestamptz))
                """,event.id(),event.challengeId(),event.operation().name(),event.result(),event.reason(),event.initiatorKind().name(),event.tenantId(),event.accountId(),event.actorId(),
                    initiator==null?null:initiator.accountId(),initiator==null?null:initiator.actorId(),initiator==null?null:initiator.memberId(),event.traceId(),event.occurredAt().atOffset(ZoneOffset.UTC));
        if(rows!=1)throw unavailable();
    }
    private static IdentityLifecycleException unavailable(){return new IdentityLifecycleException(IdentityLifecycleException.Reason.UNAVAILABLE);}
}
