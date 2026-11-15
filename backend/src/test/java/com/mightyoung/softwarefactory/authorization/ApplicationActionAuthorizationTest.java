package com.mightyoung.softwarefactory.authorization;

import com.mightyoung.softwarefactory.directory.*;
import com.mightyoung.softwarefactory.identity.*;
import com.mightyoung.softwarefactory.tenancy.*;
import java.sql.Connection;
import java.time.Instant;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ApplicationActionAuthorizationTest {
    final DataSource source=mock(DataSource.class);
    final Connection connection=mock(Connection.class);
    final HumanSessionAuthorityTransaction callers=mock(HumanSessionAuthorityTransaction.class);
    final CurrentHumanSessionAuthority human=mock(CurrentHumanSessionAuthority.class);
    final AuthorizationWriteGuard guard=mock(AuthorizationWriteGuard.class);
    final AccountStatusReadRepository accounts=mock(AccountStatusReadRepository.class);
    final PrincipalStatusReadRepository principals=mock(PrincipalStatusReadRepository.class);
    final TenantMembershipReadRepository memberships=mock(TenantMembershipReadRepository.class);
    final TenantApplicationReadRepository applications=mock(TenantApplicationReadRepository.class);
    final MemberDirectoryReadRepository directory=mock(MemberDirectoryReadRepository.class);
    final RoleScopeReadRepository roles=mock(RoleScopeReadRepository.class);
    final CurrentTenantContext context=new CurrentTenantContext(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),2,3,4);
    final UUID organization=UUID.randomUUID();
    final Instant time=Instant.parse("2026-11-15T03:00:00Z");
    final CapturedHumanRequest request=mock(CapturedHumanRequest.class);
    final HumanSessionAuthorityTransaction.ReadBoundary boundary=mock(HumanSessionAuthorityTransaction.ReadBoundary.class);
    final CheckedHumanSessionAuthority checked=mock(CheckedHumanSessionAuthority.class);
    final PersistentEntityActionAuthorization authority=new PersistentEntityActionAuthorization(source,callers,human,guard,accounts,principals,memberships,applications,directory,roles);
    @BeforeEach void setup() {
        var holder=new ConnectionHolder(connection);holder.setSynchronizedWithTransaction(true);
        TransactionSynchronizationManager.bindResource(source,holder);TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        when(request.context()).thenReturn(context);when(human.requireReadCheckedCurrent(request,boundary)).thenReturn(checked);
        when(checked.observedAt(request,boundary)).thenReturn(time);
        when(principals.findByActorId(context.actorId())).thenReturn(Optional.of(new PrincipalStatus(context.actorId(),context.accountId(),"HUMAN","ACTIVE")));
        when(accounts.findState(context.accountId())).thenReturn(Optional.of(new AccountSecurityState("ACTIVE",4)));
        when(memberships.findCurrentMembership(context.tenantId(),context.accountId(),context.memberId())).thenReturn(Optional.of(new TenantMembershipState("ACTIVE","ACTIVE",2,3)));
        when(applications.findCurrentInCaller(context.tenantId(),"tooling")).thenReturn(Optional.of(new TenantApplicationState(true,1)));
        when(directory.findCurrentMembershipsAt(context.tenantId(),context.memberId(),time.atOffset(java.time.ZoneOffset.UTC)))
                .thenReturn(new MemberDirectorySnapshot(Set.of(organization),Set.of(),Set.of()));
        when(roles.findActiveActionScopesAt(any(),any(),anySet(),anySet(),anySet(),eq("tool"),eq("read"),eq("tool.read"),any()))
                .thenReturn(List.of(new RoleScopeDescriptor(UUID.randomUUID(),"DEPT",null)));
        when(directory.findActiveOrganizationsInCaller(context.tenantId(),Set.of())).thenReturn(Set.of());
    }
    @AfterEach void cleanup() {TransactionSynchronizationManager.unbindResourceIfPossible(source);TransactionSynchronizationManager.clear();}
    @Test void neutralDecisionUsesRegisteredApplicationAndSingleCurrentOwnerTime() {
        var decision=authority.requireRead(request,boundary,new ApplicationEntityAction("tooling","tool","read"));
        assertThat(decision.organizationIds()).containsExactly(organization);assertThat(decision.observedAt()).isEqualTo(time);
        authority.requireSqlConnection(decision,source,connection);
        verify(applications).findCurrentInCaller(context.tenantId(),"tooling");
        verify(applications,never()).findCurrentInCaller(context.tenantId(),"safety");
        assertThatThrownBy(()->authority.requireSqlConnection(decision,source,mock(Connection.class))).isInstanceOf(AccessDeniedException.class);
        TransactionSynchronizationManager.unbindResource(source);
        assertThatThrownBy(decision::context).isInstanceOf(AccessDeniedException.class);
    }
    @Test void currentCredentialEntitlementAndScopeCannotFallBackToLegacyOrDefaultOff() {
        when(accounts.findState(context.accountId())).thenReturn(Optional.of(new AccountSecurityState("ACTIVE",5)));
        assertDenied();
        when(accounts.findState(context.accountId())).thenReturn(Optional.of(new AccountSecurityState("ACTIVE",4)));
        when(applications.findCurrentInCaller(context.tenantId(),"tooling")).thenReturn(Optional.empty());assertDenied();
        when(applications.findCurrentInCaller(context.tenantId(),"tooling")).thenReturn(Optional.of(new TenantApplicationState(true,1)));
        when(roles.findActiveActionScopesAt(any(),any(),anySet(),anySet(),anySet(),anyString(),anyString(),anyString(),any())).thenReturn(List.of());assertDenied();
        verify(human,times(3)).requireReadCheckedCurrent(request,boundary);
    }
    @Test void issuedDecisionIsThreadBoundAndIssuerBound() throws Exception {
        var decision=authority.requireRead(request,boundary,new ApplicationEntityAction("tooling","tool","read"));
        var another=new PersistentEntityActionAuthorization(source,callers,human,guard,accounts,principals,memberships,applications,directory,roles);
        assertThatThrownBy(()->another.requireSqlConnection(decision,source,connection)).isInstanceOf(AccessDeniedException.class);
        var error=new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var thread=new Thread(()->{try{decision.context();}catch(Throwable denied){error.set(denied);}});thread.start();thread.join();
        assertThat(error.get()).isInstanceOf(AccessDeniedException.class);
    }
    private void assertDenied(){assertThatThrownBy(()->authority.requireRead(request,boundary,new ApplicationEntityAction("tooling","tool","read"))).isInstanceOf(AccessDeniedException.class);}
}
