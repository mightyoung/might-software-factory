package com.mightyoung.softwarefactory.authorization;

import com.mightyoung.softwarefactory.identity.*;
import com.mightyoung.softwarefactory.tenancy.*;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.IdentityHashMap;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.datasource.ConnectionProxy;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronization;
import static com.mightyoung.softwarefactory.identity.SessionSensitiveContracts.Enforcement;

/** Borrows one existing RO/RR caller; no transaction, tenant-setting, bean or grant fallback. */
public final class PersistentTenantAuditReadAuthorization implements TenantAuditReadAuthorization {
    static final String CAPABILITY_SQL="""
            select exists(select 1 from public.role_grant rg
             join public."role" r on r.tenant_id=rg.tenant_id and r.id=rg.role_id and r.active=true
             join public.role_permission rp on rp.tenant_id=r.tenant_id and rp.role_id=r.id
             join public.permission p on p.id=rp.permission_id
             join public.scope_rule sr on sr.tenant_id=rp.tenant_id and sr.id=rp.scope_rule_id
             where rg.tenant_id=cast(? as uuid) and p.code='audit_event.read' and p.resource_type='audit_event' and p.action='read'
               and sr.kind='AUDIT_TENANT' and sr.definition='{}'::jsonb
               and not exists(select 1 from public.scope_org so where so.tenant_id=sr.tenant_id and so.scope_rule_id=sr.id)
               and rg.valid_from<=cast(? as timestamptz) and (rg.valid_to is null or rg.valid_to>cast(? as timestamptz))
               and ((rg.subject_kind='MEMBER' and rg.member_id=cast(? as uuid))
                or (rg.subject_kind='ORG' and exists(select 1 from public.member_org mo
                    join public.org_unit ou on ou.tenant_id=mo.tenant_id and ou.id=mo.org_id and ou.active=true
                    where mo.tenant_id=rg.tenant_id and mo.member_id=cast(? as uuid) and mo.org_id=rg.org_id
                      and mo.valid_from<=cast(? as timestamptz) and (mo.valid_to is null or mo.valid_to>cast(? as timestamptz))))
                or (rg.subject_kind='POSITION' and exists(select 1 from public.member_position mp
                    join public."position" pos on pos.tenant_id=mp.tenant_id and pos.id=mp.position_id and pos.active=true
                    where mp.tenant_id=rg.tenant_id and mp.member_id=cast(? as uuid) and mp.position_id=rg.position_id
                      and mp.valid_from<=cast(? as timestamptz) and (mp.valid_to is null or mp.valid_to>cast(? as timestamptz))))
                or (rg.subject_kind='GROUP' and exists(select 1 from public.group_member gm
                    join public.user_group g on g.tenant_id=gm.tenant_id and g.id=gm.group_id and g.active=true
                    where gm.tenant_id=rg.tenant_id and gm.member_id=cast(? as uuid) and gm.group_id=rg.group_id
                      and gm.valid_from<=cast(? as timestamptz) and (gm.valid_to is null or gm.valid_to>cast(? as timestamptz)))))) as authorized
            """;
    private final Object issuer=new Object();
    private final DataSource source;
    private final HumanSessionAuthorityTransaction callers;
    private final CurrentHumanSessionAuthority human;
    private final AccountStatusReadRepository accounts;
    private final PrincipalStatusReadRepository principals;
    private final TenantMembershipReadRepository members;
    private final TenantApplicationReadRepository applications;
    public PersistentTenantAuditReadAuthorization(DataSource source,HumanSessionAuthorityTransaction callers,
            CurrentHumanSessionAuthority human,AccountStatusReadRepository accounts,PrincipalStatusReadRepository principals,
            TenantMembershipReadRepository members,TenantApplicationReadRepository applications) {
        this.source=Objects.requireNonNull(source);this.callers=Objects.requireNonNull(callers);this.human=Objects.requireNonNull(human);
        this.accounts=Objects.requireNonNull(accounts);this.principals=Objects.requireNonNull(principals);
        this.members=Objects.requireNonNull(members);this.applications=Objects.requireNonNull(applications);
    }
    @Override public Scope requireRead(CapturedHumanRequest request,HumanSessionAuthorityTransaction.ReadBoundary boundary) {
        Binding binding=null;Scope scope=null;
        try {
            if(request==null||boundary==null)throw SessionSensitiveException.unavailable();
            request.requireCurrent(); binding=new Binding(request,boundary); binding.borrowed();
            CheckedHumanSessionAuthority checked=human.requireReadCheckedCurrent(request,boundary);
            if(checked==null||checked.enforcement()!=Enforcement.ENFORCING)throw SessionSensitiveException.unavailable();
            Instant at=checked.observedAt(request,boundary); if(at==null)throw SessionSensitiveException.unavailable();
            Binding owned=binding;
            scope=new Scope(issuer,binding.context.tenantId(),at,()->owned.validate(checked,at),
                    (expected,connection)->{if(expected!=source||connection!=owned.connection)throw SessionSensitiveException.unavailable();},binding::poison);
            Scope issued=scope;
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
                @Override public void beforeCommit(boolean readOnly){
                    if(!readOnly||issued.invalid()){issued.invalidate();throw SessionSensitiveException.unavailable();}
                }
                @Override public void suspend(){issued.invalidate();}
                @Override public void savepoint(Object savepoint){issued.invalidate();}
                @Override public void savepointRollback(Object savepoint){issued.invalidate();}
                @Override public void afterCompletion(int status){issued.invalidate();}
            });
            requireCurrent(scope);
            var context=binding.context;
            var account=accounts.findState(context.accountId()).orElseThrow(PersistentTenantAuditReadAuthorization::denied); requireCurrent(scope);
            var principal=principals.findByActorId(context.actorId()).orElseThrow(PersistentTenantAuditReadAuthorization::denied); requireCurrent(scope);
            var member=members.findCurrentMembership(context.tenantId(),context.accountId(),context.memberId()).orElseThrow(PersistentTenantAuditReadAuthorization::denied); requireCurrent(scope);
            if(!"ACTIVE".equals(account.status())||account.credentialVersion()!=context.credentialVersion()
                    ||!context.actorId().equals(principal.principalId())||!context.accountId().equals(principal.accountId())
                    ||!"HUMAN".equals(principal.kind())||!"ACTIVE".equals(principal.status())
                    ||!"ACTIVE".equals(member.tenantStatus())||!"ACTIVE".equals(member.memberStatus())
                    ||member.tenantAuthEpoch()!=context.tenantAuthEpoch()||member.memberAuthEpoch()!=context.memberAuthEpoch())throw denied();
            var application=applications.findCurrentInCaller(context.tenantId(),"system").orElseThrow(PersistentTenantAuditReadAuthorization::denied); requireCurrent(scope);
            if(!application.enabled())throw denied();
            boolean granted=capability(binding,at); requireCurrent(scope); if(!granted)throw denied();
            return scope;
        }catch(SessionSensitiveException|AccessDeniedException failure){if(scope!=null)scope.invalidate();else if(binding!=null)binding.poison();throw failure;}
        catch(RuntimeException failure){if(scope!=null)scope.invalidate();else if(binding!=null)binding.poison();throw SessionSensitiveException.unavailable();}
        catch(Error failure){if(scope!=null)scope.invalidate();else if(binding!=null)binding.poison();throw failure;}
    }
    @Override public void requireCurrent(Scope scope) {
        if(scope==null)throw SessionSensitiveException.unavailable();
        if(scope.issuer!=issuer){scope.invalidate();throw SessionSensitiveException.unavailable();}
        scope.require();
    }
    @Override public void requireSqlConnection(Scope scope,DataSource expected,Connection connection) {
        requireCurrent(scope);scope.requireConnection(expected,connection);
    }
    private boolean capability(Binding binding,Instant at) {
        var time=at.atOffset(ZoneOffset.UTC);var member=binding.context.memberId();
        Object[] parameters={binding.context.tenantId(),time,time,member,member,time,time,member,time,time,member,time,time};
        try(var statement=binding.connection.prepareStatement(CAPABILITY_SQL)){
            DataSourceUtils.applyTransactionTimeout(statement,source);
            for(int i=0;i<parameters.length;i++)statement.setObject(i+1,parameters[i]);
            try(var rows=statement.executeQuery()){
                var metadata=rows.getMetaData();
                if(metadata==null||metadata.getColumnCount()!=1||!rows.next())throw SessionSensitiveException.unavailable();
                Object value=rows.getObject(1);
                if(!(value instanceof Boolean granted)||rows.next())throw SessionSensitiveException.unavailable();
                return granted;
            }
        }catch(SQLException failure){throw SessionSensitiveException.unavailable();}
    }
    private final class Binding {
        final CapturedHumanRequest request;final HumanSessionAuthorityTransaction.ReadBoundary boundary;
        final Thread thread=Thread.currentThread();final ConnectionHolder holder;final Connection connection,physical;
        final CurrentTenantContext context;
        Binding(CapturedHumanRequest request,HumanSessionAuthorityTransaction.ReadBoundary boundary){
            this.request=request;this.boundary=boundary;context=request.context();
            if(!(TransactionSynchronizationManager.getResource(source) instanceof ConnectionHolder selected))throw SessionSensitiveException.unavailable();
            holder=selected;connection=holder.getConnection();physical=physical(connection);
        }
        void poison(){holder.setRollbackOnly();}
        void borrowed(){
            try {
                if(thread!=Thread.currentThread()||TransactionSynchronizationManager.getResource(source)!=holder||holder.getConnection()!=connection
                        ||holder.isRollbackOnly()||!holder.isSynchronizedWithTransaction()||!TransactionSynchronizationManager.isActualTransactionActive()
                        ||!TransactionSynchronizationManager.isSynchronizationActive()||!TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                        ||!Integer.valueOf(TransactionDefinition.ISOLATION_REPEATABLE_READ).equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())
                        ||physical(connection)!=physical||connection.getAutoCommit()||!connection.isReadOnly()
                        ||connection.getTransactionIsolation()!=Connection.TRANSACTION_REPEATABLE_READ)throw SessionSensitiveException.unavailable();
                request.requireCurrent();if(!context.equals(request.context()))throw SessionSensitiveException.unavailable();
                callers.requireRead(request,boundary,source,connection);
            }catch(SessionSensitiveException failure){poison();throw failure;}
            catch(SQLException|RuntimeException failure){poison();throw SessionSensitiveException.unavailable();}
        }
        void validate(CheckedHumanSessionAuthority checked,Instant at){
            borrowed();
            try {if(checked.enforcement()!=Enforcement.ENFORCING||!at.equals(checked.observedAt(request,boundary)))throw SessionSensitiveException.unavailable();}
            catch(SessionSensitiveException failure){poison();throw failure;}
            catch(RuntimeException failure){poison();throw SessionSensitiveException.unavailable();}
        }
    }
    private static Connection physical(Connection connection){
        try {
            var seen=new IdentityHashMap<Connection,Boolean>();Connection current=connection;
            for(int depth=0;depth<64;depth++){
                if(current==null||seen.put(current,Boolean.TRUE)!=null)throw SessionSensitiveException.unavailable();
                if(current instanceof ConnectionProxy proxy){current=proxy.getTargetConnection();continue;}
                Connection unwrapped=current.unwrap(Connection.class);if(unwrapped==current)return current;current=unwrapped;
            }
            throw SessionSensitiveException.unavailable();
        }catch(SQLException failure){throw SessionSensitiveException.unavailable();}
    }
    private static AccessDeniedException denied(){return new AccessDeniedException("Tenant audit query is not authorized");}
}
