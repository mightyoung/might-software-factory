package com.mightyoung.softwarefactory.authorization;

import com.mightyoung.softwarefactory.directory.MemberDirectoryReadRepository;
import com.mightyoung.softwarefactory.identity.*;
import com.mightyoung.softwarefactory.tenancy.*;
import javax.sql.DataSource;
import java.sql.Connection;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Explicit construction requires real identity control/key-aware authority; no default-OFF implementation. */
public final class PersistentEntityActionAuthorization implements EntityActionAuthorization {
    private final Object issuer=new Object();
    private final DataSource source;
    private final HumanSessionAuthorityTransaction callers;
    private final CurrentHumanSessionAuthority human;
    private final AuthorizationWriteGuard guard;
    private final AccountStatusReadRepository accounts;
    private final PrincipalStatusReadRepository principals;
    private final TenantMembershipReadRepository memberships;
    private final TenantApplicationReadRepository applications;
    private final MemberDirectoryReadRepository directory;
    private final RoleScopeReadRepository roles;
    public PersistentEntityActionAuthorization(DataSource source, HumanSessionAuthorityTransaction callers,
            CurrentHumanSessionAuthority human, AuthorizationWriteGuard guard, AccountStatusReadRepository accounts,
            PrincipalStatusReadRepository principals, TenantMembershipReadRepository memberships,
            TenantApplicationReadRepository applications, MemberDirectoryReadRepository directory, RoleScopeReadRepository roles) {
        this.source=Objects.requireNonNull(source); this.callers=Objects.requireNonNull(callers); this.human=Objects.requireNonNull(human);
        this.guard=Objects.requireNonNull(guard); this.accounts=Objects.requireNonNull(accounts); this.principals=Objects.requireNonNull(principals);
        this.memberships=Objects.requireNonNull(memberships); this.applications=Objects.requireNonNull(applications);
        this.directory=Objects.requireNonNull(directory); this.roles=Objects.requireNonNull(roles);
    }
    @Override public ActionScope requireRead(CapturedHumanRequest request, HumanSessionAuthorityTransaction.ReadBoundary boundary,
            RegisteredEntityAction action) {
        // Selector reads resolve their fixed owning CREATE/BIND action inside a read boundary too.
        Objects.requireNonNull(action);
        var binding=binding();
        Runnable check=()->{ binding.require(); callers.requireRead(request,boundary,source,binding.connection); };
        check.run(); var checked=human.requireReadCheckedCurrent(request,boundary);
        Instant time=checked.observedAt(request,boundary);
        return resolve(request.context(),action,time,check);
    }
    @Override public ActionScope requireCommand(HumanSessionAuthorityTransaction.WriteScope scope,
            HumanSessionAuthorityTransaction.GuardedBoundary boundary, RegisteredEntityAction action) {
        if(Objects.requireNonNull(action).isRead()) throw denied();
        return requireFinalCommand(scope,boundary,Set.of(action)).require(action);
    }
    @Override public FinalActionScopes requireFinalCommand(HumanSessionAuthorityTransaction.WriteScope scope,
            HumanSessionAuthorityTransaction.GuardedBoundary boundary, Set<RegisteredEntityAction> required) {
        Set<RegisteredEntityAction> selected=Set.copyOf(required);
        if(selected.isEmpty()) throw denied();
        var binding=binding();
        Runnable check=()->{ binding.require(); callers.requireGuarded(scope,boundary,source,binding.connection);
            guard.requireBusinessCommand(scope.request().context().tenantId()); };
        check.run(); var checked=human.requireGuardedCheckedCurrent(scope,boundary);
        // Reuse the actual assertion's single owner cut; no second boundary clock getter.
        Instant time=checked.observedAt(scope,boundary);
        var result=new EnumMap<RegisteredEntityAction,ActionScope>(RegisteredEntityAction.class);
        for(var action: RegisteredEntityAction.values()) if(selected.contains(action))
            result.put(action,resolve(scope.request().context(),action,time,check));
        check.run(); return new FinalActionScopes(result,time);
    }
    private ActionScope resolve(CurrentTenantContext context, RegisteredEntityAction action, Instant time, Runnable check) {
        check.run();
        var principal=principals.findByActorId(context.actorId()).orElseThrow(PersistentEntityActionAuthorization::denied);
        var account=accounts.findState(context.accountId()).orElseThrow(PersistentEntityActionAuthorization::denied);
        var member=memberships.findCurrentMembership(context.tenantId(),context.accountId(),context.memberId())
                .orElseThrow(PersistentEntityActionAuthorization::denied);
        if(!context.actorId().equals(principal.principalId()) || !context.accountId().equals(principal.accountId())
                || !"HUMAN".equals(principal.kind()) || !"ACTIVE".equals(principal.status()) || !"ACTIVE".equals(account.status())
                || account.credentialVersion()!=context.credentialVersion() || !"ACTIVE".equals(member.tenantStatus())
                || !"ACTIVE".equals(member.memberStatus()) || member.tenantAuthEpoch()!=context.tenantAuthEpoch()
                || member.memberAuthEpoch()!=context.memberAuthEpoch()
                || applications.findCurrentInCaller(context.tenantId(),"safety").filter(TenantApplicationState::enabled).isEmpty()) throw denied();
        var at=time.atOffset(ZoneOffset.UTC);
        var current=directory.findCurrentMembershipsAt(context.tenantId(),context.memberId(),at);
        var grants=roles.findActiveActionScopesAt(context.tenantId(),context.memberId(),current.organizationIds(),
                current.positionIds(),current.groupIds(),action.resourceType(),action.action(),action.permissionCode(),at);
        Set<UUID> organizations=new LinkedHashSet<>(), explicit=new LinkedHashSet<>(); boolean tree=false;
        for(var grant:grants) switch(grant.kind()) {
            case "DEPT" -> organizations.addAll(current.organizationIds());
            case "DEPT_TREE" -> tree=true;
            case "EXPLICIT" -> { if(grant.explicitOrganizationId()!=null) explicit.add(grant.explicitOrganizationId()); }
            default -> { /* Unsupported SELF/RELATION never expands authority. */ }
        }
        if(tree) organizations.addAll(directory.expandDepartmentTreeAt(context.tenantId(),context.memberId(),at));
        organizations.addAll(directory.findActiveOrganizationsInCaller(context.tenantId(),explicit));
        if(organizations.isEmpty()) throw denied();
        check.run(); return new ActionScope(issuer,check,context,action,time,organizations);
    }
    @Override public void requireCurrent(ActionScope scope) {
        if(scope==null || scope.issuer!=issuer) throw denied();
        scope.validation.run();
    }
    @Override public void requireSqlConnection(ActionScope scope, DataSource expected, Connection connection) {
        requireCurrent(scope);
        if(expected!=source || binding().connection!=connection) throw denied();
    }
    private Binding binding() {
        if(!(TransactionSynchronizationManager.getResource(source) instanceof ConnectionHolder holder)) throw denied();
        var result=new Binding(holder,holder.getConnection(),Thread.currentThread()); result.require(); return result;
    }
    private final class Binding {
        final ConnectionHolder holder; final Connection connection; final Thread thread;
        Binding(ConnectionHolder holder,Connection connection,Thread thread){this.holder=holder;this.connection=connection;this.thread=thread;}
        void require() {
            if(thread!=Thread.currentThread() || TransactionSynchronizationManager.getResource(source)!=holder
                    || holder.getConnection()!=connection || holder.isRollbackOnly() || !holder.isSynchronizedWithTransaction()
                    || !TransactionSynchronizationManager.isActualTransactionActive() || !TransactionSynchronizationManager.isSynchronizationActive()) {
                holder.setRollbackOnly(); throw denied();
            }
        }
    }
    private static AccessDeniedException denied() { return new AccessDeniedException("Current entity action is not authorized"); }
}
