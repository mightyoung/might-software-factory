package com.mightyoung.softwarefactory.authorization;

import com.mightyoung.softwarefactory.identity.AccountStatusReadRepository;
import com.mightyoung.softwarefactory.identity.PrincipalStatusReadRepository;
import com.mightyoung.softwarefactory.identity.IdentityLifecycleAuthorization;
import com.mightyoung.softwarefactory.identity.IdentityLifecycleSecurityTransaction;
import com.mightyoung.softwarefactory.identity.IdentityLifecycleException;
import com.mightyoung.softwarefactory.identity.IdentityLifecycleContracts.IssuerBinding;
import com.mightyoung.softwarefactory.tenancy.TenantApplicationReadRepository;
import com.mightyoung.softwarefactory.tenancy.TenantMembershipReadRepository;
import org.jooq.SQLDialect;
import org.jooq.conf.Settings;
import org.jooq.impl.DSL;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import javax.sql.DataSource;

/** Exact tenant invitation capability; NULL and every organization-style scope remain denied. */
@Repository
@ConditionalOnProperty(prefix="software-factory.identity.lifecycle",name="enabled",havingValue="true")
public final class PersistentIdentityLifecycleAuthorization implements IdentityLifecycleAuthorization {
    static final String TENANT_CAPABILITY_SQL="""
            select exists(select 1 from public.role_grant rg
             join public."role" r on r.tenant_id=rg.tenant_id and r.id=rg.role_id and r.active=true
             join public.role_permission rp on rp.tenant_id=r.tenant_id and rp.role_id=r.id
             join public.permission p on p.id=rp.permission_id
             join public.scope_rule sr on sr.tenant_id=rp.tenant_id and sr.id=rp.scope_rule_id
             where rg.tenant_id=cast(? as uuid) and p.code=? and p.resource_type='account-invitation' and p.action=?
               and sr.kind='IDENTITY_TENANT' and sr.definition='{}'::jsonb
               and not exists(select 1 from public.scope_org so where so.tenant_id=sr.tenant_id and so.scope_rule_id=sr.id)
               and rg.valid_from<=statement_timestamp() and (rg.valid_to is null or rg.valid_to>statement_timestamp())
               and ((rg.subject_kind='MEMBER' and rg.member_id=cast(? as uuid))
                or (rg.subject_kind='ORG' and exists(select 1 from public.member_org mo
                    join public.org_unit ou on ou.tenant_id=mo.tenant_id and ou.id=mo.org_id and ou.active=true
                    where mo.tenant_id=rg.tenant_id and mo.member_id=cast(? as uuid) and mo.org_id=rg.org_id
                      and mo.valid_from<=statement_timestamp() and (mo.valid_to is null or mo.valid_to>statement_timestamp())))
                or (rg.subject_kind='POSITION' and exists(select 1 from public.member_position mp
                    join public."position" pos on pos.tenant_id=mp.tenant_id and pos.id=mp.position_id and pos.active=true
                    where mp.tenant_id=rg.tenant_id and mp.member_id=cast(? as uuid) and mp.position_id=rg.position_id
                      and mp.valid_from<=statement_timestamp() and (mp.valid_to is null or mp.valid_to>statement_timestamp())))
                or (rg.subject_kind='GROUP' and exists(select 1 from public.group_member gm
                    join public.user_group g on g.tenant_id=gm.tenant_id and g.id=gm.group_id and g.active=true
                    where gm.tenant_id=rg.tenant_id and gm.member_id=cast(? as uuid) and gm.group_id=rg.group_id
                      and gm.valid_from<=statement_timestamp() and (gm.valid_to is null or gm.valid_to>statement_timestamp())))))
            """;
    private final DataSource source;
    private final IdentityLifecycleSecurityTransaction writer;
    private final AccountStatusReadRepository accounts;
    private final PrincipalStatusReadRepository principals;
    private final TenantMembershipReadRepository memberships;
    private final TenantApplicationReadRepository applications;
    public PersistentIdentityLifecycleAuthorization(DataSource source,IdentityLifecycleSecurityTransaction writer,
            AccountStatusReadRepository accounts,PrincipalStatusReadRepository principals,
            TenantMembershipReadRepository memberships,TenantApplicationReadRepository applications) {
        this.source=source;this.writer=writer;this.accounts=accounts;this.principals=principals;
        this.memberships=memberships;this.applications=applications;
    }
    @Override public void requireInvitationAction(IdentityLifecycleSecurityTransaction.Scope scope,IssuerBinding issuer,Action action) {
        writer.requireActive(scope);
        if(scope.sourceSession()==null||issuer==null||action==null)throw denied();
        var current=scope.sourceSession().context();
        if(!issuer.accountId().equals(current.accountId())||!issuer.actorId().equals(current.actorId())
                ||!issuer.tenantId().equals(current.tenantId())||!issuer.memberId().equals(current.memberId())
                ||issuer.credentialVersion()!=current.credentialVersion()||issuer.tenantAuthEpoch()!=current.tenantAuthEpoch()
                ||issuer.memberAuthEpoch()!=current.memberAuthEpoch())throw denied();
        require(scope,issuer,action,true);
    }
    @Override public void requireIssuerStillAuthorized(IdentityLifecycleSecurityTransaction.Scope scope,IssuerBinding issuer,Action action) {
        // The issued versions are provenance, not a reconstructed or impersonated live session.
        require(scope,issuer,action,false);
    }
    private void require(IdentityLifecycleSecurityTransaction.Scope scope,IssuerBinding issuer,Action action,boolean session) {
        writer.requireActive(scope);
        if(issuer==null||action==null||!issuer.tenantId().equals(scope.targetTenantId()))throw denied();
        var connection=DataSourceUtils.getConnection(source);
        try {
            writer.requireSqlConnection(scope,source,connection);
            var account=accounts.findState(issuer.accountId()).orElseThrow(PersistentIdentityLifecycleAuthorization::denied);
            var principal=principals.findByActorId(issuer.actorId()).orElseThrow(PersistentIdentityLifecycleAuthorization::denied);
            var membership=memberships.findCurrentMembership(issuer.tenantId(),issuer.accountId(),issuer.memberId())
                    .orElseThrow(PersistentIdentityLifecycleAuthorization::denied);
            var application=applications.findCurrent(issuer.tenantId(),"system").orElseThrow(PersistentIdentityLifecycleAuthorization::denied);
            if(!"ACTIVE".equals(account.status())||!"HUMAN".equals(principal.kind())||!"ACTIVE".equals(principal.status())
                    ||!issuer.actorId().equals(principal.principalId())||!issuer.accountId().equals(principal.accountId())
                    ||!"ACTIVE".equals(membership.tenantStatus())||!"ACTIVE".equals(membership.memberStatus())||!application.enabled()
                    ||session&&(account.credentialVersion()!=issuer.credentialVersion()
                        ||membership.tenantAuthEpoch()!=issuer.tenantAuthEpoch()||membership.memberAuthEpoch()!=issuer.memberAuthEpoch()))throw denied();
            writer.requireSqlConnection(scope,source,connection);
            String verb=action==Action.CREATE?"create":"revoke";
            Object granted=DSL.using(connection,SQLDialect.POSTGRES,new Settings().withExecuteLogging(false)).fetchValue(
                    TENANT_CAPABILITY_SQL,issuer.tenantId(),"identity.invitation."+verb,verb,issuer.memberId(),issuer.memberId(),issuer.memberId(),issuer.memberId());
            if(!Boolean.TRUE.equals(granted))throw denied();
            writer.requireSqlConnection(scope,source,connection);
        }catch(RuntimeException failure){if(TransactionSynchronizationManager.getResource(source) instanceof ConnectionHolder h)h.setRollbackOnly();throw failure;}
        finally {DataSourceUtils.releaseConnection(connection,source);}
    }
    private static IdentityLifecycleException denied(){return new IdentityLifecycleException(IdentityLifecycleException.Reason.CAPABILITY_REJECTED);}
}
