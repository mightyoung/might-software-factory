package com.mightyoung.softwarefactory.dualprevention.asset;

import com.mightyoung.softwarefactory.authorization.EntityActionAuthorization.ActionScope;
import com.mightyoung.softwarefactory.authorization.RegisteredEntityAction;
import com.mightyoung.softwarefactory.directory.DirectoryReferenceContracts;
import com.mightyoung.softwarefactory.directory.DirectoryReferenceReadPort;
import com.mightyoung.softwarefactory.identity.CapturedHumanRequest;
import com.mightyoung.softwarefactory.identity.CurrentTenantContext;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static com.mightyoung.softwarefactory.dualprevention.asset.AssetFoundationSelectorContracts.*;

/** Fixed routing/permission order fixtures. Does not manufacture a persisted HUMAN or execute PostgreSQL. */
class AssetFoundationSelectorServiceTest {
    private final AssetFoundationReadService reads = mock(AssetFoundationReadService.class);
    private final AssetFoundationReadService.ReadSnapshot snapshot = mock(AssetFoundationReadService.ReadSnapshot.class);
    private final AssetFoundationSelectorRepository repository = mock(AssetFoundationSelectorRepository.class);
    private final DirectoryReferenceReadPort directory = mock(DirectoryReferenceReadPort.class);
    private final CapturedHumanRequest request = mock(CapturedHumanRequest.class);
    private final UUID anchor = UUID.randomUUID(), otherOrganization = UUID.randomUUID(), parent = UUID.randomUUID();
    private final Instant at = Instant.parse("2026-11-04T08:00:00.123456Z");
    private final CurrentTenantContext context = new CurrentTenantContext(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1, 2, 3);
    private final AssetFoundationSelectorService service = new AssetFoundationSelectorService(reads, repository, directory);
    @BeforeEach @SuppressWarnings("unchecked") void callback() {
        when(reads.inReadSnapshot(eq(request), any())).thenAnswer(invocation -> ((Function<AssetFoundationReadService.ReadSnapshot, ?>) invocation.getArgument(1)).apply(snapshot));
    }
    @ParameterizedTest @EnumSource(Selector.class)
    void eachLiteralSelectorUsesOnlyItsRegisteredOwnerAndIndependentlyRequiredReference(Selector selector) {
        ActionScope owner = scope(selector.owner, Set.of(anchor));
        when(snapshot.authorize(selector.owner)).thenReturn(owner);
        var query = new Request("", null, 20, selector.anchoredCreate() || selector == Selector.BIND_POSITION ? anchor : null);
        var expected = new Page(List.of(new Item(UUID.randomUUID(), "CODE", "Name")), null);
        var directoryPage = new DirectoryReferenceContracts.Page(expected.items().stream().map(i -> new DirectoryReferenceContracts.Item(i.id(), i.code(), i.name())).toList(), null);
        when(directory.findOrganizationAt(context.tenantId(), anchor, at)).thenReturn(Optional.of(new DirectoryReferenceContracts.Organization(anchor, "ORG", "Org", true, at)));
        when(directory.queryOrganizationsAt(any(), anySet(), any(), any())).thenReturn(directoryPage);
        when(directory.queryPositionsAt(any(), any(), anySet(), any(), any())).thenReturn(directoryPage);
        Candidate parentKind = switch (selector) {
            case BIND_WORKSHOP -> Candidate.LINE; case BIND_EQUIPMENT -> Candidate.STAGE;
            case BIND_ORGANIZATION, BIND_POSITION -> Candidate.WORK_UNIT; default -> null;
        };
        UUID primary = selector == Selector.BIND_WORKSHOP ? UUID.randomUUID() : null;
        if (parentKind != null) when(repository.parent(parentKind, owner, parent)).thenReturn(new AssetFoundationSelectorRepository.Parent(parent, anchor, primary));
        ActionScope referenced = null;
        if (selector.referencedRead != null) {
            referenced = scope(selector.referencedRead, Set.of(otherOrganization));
            when(snapshot.authorize(selector.referencedRead)).thenReturn(referenced);
            when(repository.candidates(eq(selector.candidate), eq(referenced), eq(query), nullable(UUID.class))).thenReturn(expected);
        }
        assertThat(service.query(request, selector, selector.bind() ? parent : null, query)).isEqualTo(expected);
        var order = inOrder(snapshot, directory, repository);
        order.verify(snapshot).authorize(selector.owner);
        if (parentKind != null) order.verify(repository).parent(parentKind, owner, parent);
        if (selector.anchoredCreate()) order.verify(directory).findOrganizationAt(context.tenantId(), anchor, at);
        if (referenced != null) {
            order.verify(snapshot).authorize(selector.referencedRead);
            order.verify(repository).candidates(selector.candidate, referenced, query, primary);
        } else if (selector == Selector.BIND_POSITION) {
            order.verify(directory).findOrganizationAt(context.tenantId(), anchor, at);
            order.verify(directory).queryPositionsAt(context.tenantId(), anchor, Set.of(anchor), query.directoryQuery(), at);
        } else order.verify(directory).queryOrganizationsAt(context.tenantId(), Set.of(anchor), query.directoryQuery(), at);
        order.verify(snapshot).requireActive();
    }
    @Test void invalidOwnerAnchorStopsBeforeReferenceAuthorizationAndCandidateSql() {
        var owner = scope(RegisteredEntityAction.WORK_UNIT_CREATE, Set.of(anchor));
        when(snapshot.authorize(RegisteredEntityAction.WORK_UNIT_CREATE)).thenReturn(owner);
        assertThatThrownBy(() -> service.query(request, Selector.WORK_UNIT_WORKSHOP, null, new Request("", null, 20, otherOrganization)))
                .isInstanceOfSatisfying(AssetFoundationFailure.class, e -> assertThat(e.status()).isEqualTo(403));
        verify(snapshot, never()).authorize(RegisteredEntityAction.WORKSHOP_READ);
        verifyNoInteractions(repository, directory);
    }
    @Test void currentDenialStopsBeforeAnyDirectoryParentOrCandidateAccess() {
        when(snapshot.authorize(RegisteredEntityAction.LINE_WORKSHOP_BIND)).thenThrow(new org.springframework.security.access.AccessDeniedException("denied"));
        assertThatThrownBy(() -> service.query(request, Selector.BIND_WORKSHOP, parent, new Request("", null, 20, null)))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        verifyNoInteractions(repository, directory);
    }
    private ActionScope scope(RegisteredEntityAction action, Set<UUID> organizations) {
        var scope = mock(ActionScope.class);
        when(scope.action()).thenReturn(action); when(scope.context()).thenReturn(context);
        when(scope.organizationIds()).thenReturn(organizations); when(scope.observedAt()).thenReturn(at); return scope;
    }
}
