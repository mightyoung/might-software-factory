package com.mightyoung.softwarefactory.tenancy;

import com.mightyoung.softwarefactory.identity.IdentityLifecycleSecurityTransaction;
import com.mightyoung.softwarefactory.identity.IdentityLifecycleException;
import com.mightyoung.softwarefactory.identity.InvitationMembershipPort;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.conf.Settings;
import org.jooq.impl.DSL;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import javax.sql.DataSource;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/** Creates a plain membership only: no role, organization, entitlement, or reactivation. */
@Repository
@ConditionalOnProperty(prefix="software-factory.identity.lifecycle",name="enabled",havingValue="true")
public final class JooqInvitationMembershipAdapter implements InvitationMembershipPort {
    private final DataSource source;
    private final IdentityLifecycleSecurityTransaction writer;
    public JooqInvitationMembershipAdapter(DataSource source,IdentityLifecycleSecurityTransaction writer){this.source=source;this.writer=writer;}
    @Override public void requireActiveTarget(IdentityLifecycleSecurityTransaction.Scope scope) {
        sql(scope,db->{if(scope.targetTenantId()==null||!Boolean.TRUE.equals(db.fetchValue(
                "select exists(select 1 from public.tenant where id=cast(? as uuid) and status='ACTIVE')",scope.targetTenantId())))throw rejected();return null;});
    }
    @Override public Optional<Membership> find(IdentityLifecycleSecurityTransaction.Scope scope,UUID accountId) {
        return sql(scope,db->{var row=db.fetchOne("select id,account_id,status,auth_epoch from public.tenant_member where tenant_id=cast(? as uuid) and account_id=cast(? as uuid) for update",scope.targetTenantId(),accountId);
            return row==null?Optional.empty():Optional.of(new Membership(row.get("id",UUID.class),row.get("account_id",UUID.class),row.get("status",String.class),row.get("auth_epoch",Long.class)));});
    }
    @Override public void create(IdentityLifecycleSecurityTransaction.Scope scope,UUID accountId,UUID memberId,String displayName) {
        if(scope.operation()!=com.mightyoung.softwarefactory.identity.IdentityLifecycleContracts.Operation.CONSUME_INVITATION
                ||scope.targetTenantId()==null||accountId==null||memberId==null||displayName==null||displayName.isBlank()
                ||displayName.codePointCount(0,displayName.length())>200||displayName.indexOf('\0')>=0
                ||displayName.codePoints().anyMatch(cp->cp>=0xD800&&cp<=0xDFFF))throw rejected();
        requireActiveTarget(scope);
        if(find(scope,accountId).isPresent())throw rejected();
        sql(scope,db->{int rows=db.execute("""
                insert into public.tenant_member(tenant_id,id,account_id,status,auth_epoch,display_name)
                 values(cast(? as uuid),cast(? as uuid),cast(? as uuid),'ACTIVE',0,?)
                """,scope.targetTenantId(),memberId,accountId,displayName);
            if(rows!=1)throw rejected();return null;});
    }
    private <T>T sql(IdentityLifecycleSecurityTransaction.Scope scope,Function<DSLContext,T> operation) {
        writer.requireActive(scope);var connection=DataSourceUtils.getConnection(source);
        try {writer.requireSqlConnection(scope,source,connection);T result=operation.apply(DSL.using(connection,SQLDialect.POSTGRES,new Settings().withExecuteLogging(false)));writer.requireSqlConnection(scope,source,connection);return result;}
        catch(RuntimeException failure){if(TransactionSynchronizationManager.getResource(source) instanceof ConnectionHolder h)h.setRollbackOnly();throw failure;}
        finally {DataSourceUtils.releaseConnection(connection,source);}
    }
    private static IdentityLifecycleException rejected(){return new IdentityLifecycleException(IdentityLifecycleException.Reason.CAPABILITY_REJECTED);}
}
