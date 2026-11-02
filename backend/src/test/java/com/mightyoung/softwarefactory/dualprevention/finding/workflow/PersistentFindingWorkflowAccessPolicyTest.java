package com.mightyoung.softwarefactory.dualprevention.finding.workflow;

import com.mightyoung.softwarefactory.authorization.*;
import com.mightyoung.softwarefactory.identity.CurrentTenantContext;
import com.mightyoung.softwarefactory.tenancy.*;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PersistentFindingWorkflowAccessPolicyTest {
    private final CurrentTenantContext context = new CurrentTenantContext(UUID.randomUUID(),UUID.randomUUID(),
            UUID.randomUUID(),UUID.randomUUID(),2,3,1);
    private final UUID organization = UUID.randomUUID();
    private final UUID assignee = UUID.randomUUID();
    private final FindingWorkflowAuthorizationRepository repository = mock(FindingWorkflowAuthorizationRepository.class);
    private final AtomicReference<Optional<TenantApplicationState>> state = new AtomicReference<>(Optional.of(new TenantApplicationState(true,1)));
    private final PersistentFindingWorkflowAccessPolicy policy = new PersistentFindingWorkflowAccessPolicy(repository, (tenant, app) -> {
        assertThat(tenant).isEqualTo(context.tenantId()); assertThat(app).isEqualTo("safety"); return state.get();
    });

    @Test void actionAndAssignmentRecheckEntitlementAfterRevocation() {
        when(repository.loadActionSnapshot(context,"finding.create","create")).thenReturn(Optional.of(snapshot(Set.of(organization))));
        when(repository.isAssignableMemberAuthorizedForAction(context.tenantId(),assignee,organization,"finding.remediate")).thenReturn(true);
        policy.requireAction(context,"finding.create",organization);
        policy.requireAssignableMember(context,assignee,organization,"finding.remediate");
        clearInvocations(repository);
        for (Optional<TenantApplicationState> entitlement : List.of(Optional.of(new TenantApplicationState(false,2)), Optional.<TenantApplicationState>empty())) {
            state.set(entitlement);
            assertThatThrownBy(() -> policy.requireAction(context,"finding.create",organization)).isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> policy.requireAssignableMember(context,assignee,organization,"finding.remediate")).isInstanceOf(AccessDeniedException.class);
        }
        verifyNoInteractions(repository);
    }

    @Test void enabledEntitlementDoesNotBypassEmptyOrMissingActionScope() {
        when(repository.loadActionSnapshot(context,"finding.create","create")).thenReturn(
                Optional.of(snapshot(null)), Optional.of(snapshot(Set.of())), Optional.empty());
        for (int i=0; i<3; i++) {
            assertThatThrownBy(() -> policy.requireAction(context,"finding.create",organization)).isInstanceOf(AccessDeniedException.class);
        }
        assertThatThrownBy(() -> policy.requireAssignableMember(context,assignee,organization,"finding.remediate"))
                .isInstanceOf(AccessDeniedException.class);
    }

    private FindingReadAuthorizationSnapshot snapshot(Set<UUID> organizations) {
        return new FindingReadAuthorizationSnapshot(context.actorId(),context.accountId(),"HUMAN","ACTIVE","ACTIVE",
                "ACTIVE","ACTIVE",2,3,organizations);
    }
}
