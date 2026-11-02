package com.mightyoung.softwarefactory.applicationregistry.navigation;

import com.mightyoung.softwarefactory.authorization.*;
import com.mightyoung.softwarefactory.identity.*;
import com.mightyoung.softwarefactory.tenancy.*;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class NavigationServiceTest {
    private final UUID organization = UUID.randomUUID();
    private final SessionContextResolver resolver = mock(SessionContextResolver.class);
    private final CurrentSessionService sessions = mock(CurrentSessionService.class);
    private final TenantApplicationReadRepository applications = mock(TenantApplicationReadRepository.class);
    private final EntityReadAccessPolicy reads = mock(EntityReadAccessPolicy.class);
    private final FindingWorkflowAuthorizationRepository actions = mock(FindingWorkflowAuthorizationRepository.class);
    private final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
    private final NavigationService service = new NavigationService(resolver, sessions, applications, reads, actions, manager);

    private CurrentTenantContext session() {
        CurrentTenantContext context = new CurrentTenantContext(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), 2, 3, 1);
        when(resolver.currentTenant()).thenReturn(context);
        when(sessions.current()).thenReturn(new CurrentSessionDetails(context.actorId(),context.accountId(),
                context.tenantId(), "tenant", "Tenant", context.memberId(), "Member"));
        doAnswer(invocation -> {
            org.springframework.transaction.TransactionDefinition definition = invocation.getArgument(0);
            assertThat(definition.isReadOnly()).isTrue();
            assertThat(definition.getIsolationLevel()).isEqualTo(
                    org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
            return new SimpleTransactionStatus();
        }).when(manager).getTransaction(any());
        when(applications.findCurrent(context.tenantId(), "safety")).thenReturn(Optional.empty());
        when(reads.resolveEntityReadGrant(context, "finding")).thenReturn(Optional.empty());
        when(actions.loadActionSnapshot(context, "finding.create", "create")).thenReturn(Optional.empty());
        return context;
    }

    @Test void differentTenantsOnlyReceiveTheirEntitledAndScopedApplication() {
        var first = session();
        when(applications.findCurrent(first.tenantId(),"safety")).thenReturn(Optional.of(new TenantApplicationState(true, 4)));
        when(reads.resolveEntityReadGrant(first,"finding")).thenReturn(Optional.of(new EntityReadGrant(first.tenantId(),Set.of(organization))));
        assertThat(service.bootstrap().applications()).extracting(NavigationService.ApplicationEntry::id).containsExactly("safety");
        assertThat(service.bootstrap().permissions()).containsExactly("finding.read");
        var second = session();
        assertThat(second.tenantId()).isNotEqualTo(first.tenantId());
        assertThat(service.bootstrap().applications()).isEmpty();
        assertThatThrownBy(() -> service.menu("safety")).isInstanceOf(AccessDeniedException.class);
    }

    @Test void everyCallRechecksEntitlementVersionAndRevocationWithoutCache() {
        var context = session();
        when(reads.resolveEntityReadGrant(context,"finding")).thenReturn(Optional.of(new EntityReadGrant(context.tenantId(),Set.of(organization))));
        when(applications.findCurrent(context.tenantId(),"safety")).thenReturn(
                Optional.of(new TenantApplicationState(true,4)), Optional.of(new TenantApplicationState(false,5)));
        assertThat(service.bootstrap().authorization().applicationVersion()).isEqualTo(4);
        var revoked = service.bootstrap();
        assertThat(revoked.authorization().applicationVersion()).isEqualTo(5);
        assertThat(revoked.applications()).isEmpty();
        verify(applications,times(2)).findCurrent(context.tenantId(),"safety");
    }

    @Test void emptyScopeAndCrossTenantGrantFailClosed() {
        var context = session();
        when(applications.findCurrent(context.tenantId(),"safety")).thenReturn(Optional.of(new TenantApplicationState(true,1)));
        when(reads.resolveEntityReadGrant(context,"finding")).thenReturn(Optional.of(new EntityReadGrant(context.tenantId(),Set.of())),
                Optional.of(new EntityReadGrant(UUID.randomUUID(),Set.of(organization))));
        assertThat(service.bootstrap().permissions()).isEmpty();
        assertThat(service.bootstrap().permissions()).isEmpty();
    }

    @Test void createRequiresActiveMatchingIdentityAndNonemptyEffectiveScope() {
        var context = session();
        when(applications.findCurrent(context.tenantId(),"safety")).thenReturn(Optional.of(new TenantApplicationState(true,1)));
        when(reads.resolveEntityReadGrant(context,"finding")).thenReturn(Optional.of(new EntityReadGrant(context.tenantId(),Set.of(organization))));
        when(actions.loadActionSnapshot(context,"finding.create","create")).thenReturn(
                Optional.of(snapshot(context,null,3)), Optional.of(snapshot(context,Set.of(organization),4)),
                Optional.of(snapshot(context,Set.of(organization),3)), Optional.empty());
        assertThat(service.bootstrap().permissions()).containsExactly("finding.read");
        assertThat(service.bootstrap().permissions()).containsExactly("finding.read");
        assertThat(service.bootstrap().permissions()).containsExactly("finding.read","finding.create");
        assertThat(service.bootstrap().permissions()).containsExactly("finding.read");
    }

    @Test void unknownApplicationsAreDeniedWithoutLookingUpAnyClientTenant() {
        assertThatThrownBy(() -> service.menu("equipment")).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.menu(null)).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(applications,resolver,sessions);
    }

    private FindingReadAuthorizationSnapshot snapshot(CurrentTenantContext c, Set<UUID> scope,long memberEpoch) {
        return new FindingReadAuthorizationSnapshot(c.actorId(),c.accountId(),"HUMAN","ACTIVE","ACTIVE","ACTIVE","ACTIVE",2,memberEpoch,scope);
    }
}
