package com.mightyoung.softwarefactory.dualprevention.asset;

import com.mightyoung.softwarefactory.applicationregistry.definition.EntityDefinition.Use;
import com.mightyoung.softwarefactory.authorization.EntityActionAuthorization.ActionScope;
import com.mightyoung.softwarefactory.authorization.RegisteredEntityAction;
import com.mightyoung.softwarefactory.identity.CapturedHumanRequest;
import com.mightyoung.softwarefactory.identity.CurrentTenantContext;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static com.mightyoung.softwarefactory.dualprevention.asset.AssetFoundationEntitySchemas.Root.WORKSHOP;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Semantic cache fixtures; these mocks do not prove persisted HUMAN or PostgreSQL acceptance. */
class AssetFoundationDefinitionServiceTest {
    private final AssetFoundationReadService reads = mock(AssetFoundationReadService.class);
    private final AssetFoundationReadService.ReadSnapshot snapshot = mock(AssetFoundationReadService.ReadSnapshot.class);
    private final CapturedHumanRequest request = mock(CapturedHumanRequest.class);
    private final ActionScope scope = mock(ActionScope.class);
    private final CurrentTenantContext context = new CurrentTenantContext(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1, 2, 3);
    private final AssetFoundationDefinitionService service = new AssetFoundationDefinitionService(reads, new AssetFoundationDefinitionProvider());
    @BeforeEach @SuppressWarnings("unchecked") void callback() {
        when(reads.inReadSnapshot(eq(request), any())).thenAnswer(call -> ((Function<AssetFoundationReadService.ReadSnapshot, ?>) call.getArgument(1)).apply(snapshot));
        when(scope.context()).thenReturn(context);
        when(scope.organizationIds()).thenReturn(Set.of(UUID.fromString("10000000-0000-0000-0000-000000000001")));
        when(snapshot.authorize(any())).thenReturn(scope);
        when(snapshot.applicationVersion(scope)).thenReturn(7L);
    }
    @Test void definitionUsesOnlyTheSelectedOwnerActionAndSameSnapshotApplicationVersion() {
        service.definition(request, WORKSHOP, Use.CREATE);
        var order = inOrder(snapshot);
        order.verify(snapshot).authorize(RegisteredEntityAction.WORKSHOP_CREATE);
        order.verify(snapshot).applicationVersion(scope);
        order.verify(snapshot).requireActive();
        verify(snapshot, never()).authorize(RegisteredEntityAction.WORKSHOP_READ);
    }
    @Test void semanticValidatorTracksUseCurrentScopeAndApplicationVersion() {
        var original = service.definition(request, WORKSHOP, Use.READ).etag();
        assertThat(service.definition(request, WORKSHOP, Use.READ).etag()).isEqualTo(original);
        assertThat(service.definition(request, WORKSHOP, Use.CREATE).etag()).isNotEqualTo(original);
        when(snapshot.applicationVersion(scope)).thenReturn(8L);
        var upgraded = service.definition(request, WORKSHOP, Use.READ).etag();
        assertThat(upgraded).isNotEqualTo(original);
        when(scope.organizationIds()).thenReturn(Set.of(UUID.fromString("20000000-0000-0000-0000-000000000001")));
        assertThat(service.definition(request, WORKSHOP, Use.READ).etag()).isNotEqualTo(upgraded);
    }
    @Test void deniedCurrentActionNeverReadsApplicationVersionOrReturnsCachedMetadata() {
        when(snapshot.authorize(RegisteredEntityAction.WORKSHOP_READ)).thenThrow(new org.springframework.security.access.AccessDeniedException("revoked"));
        assertThatThrownBy(() -> service.definition(request, WORKSHOP, Use.READ)).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        verify(snapshot, never()).applicationVersion(any());
    }
    @Test void schemaReadUsesTheSharedImmutableRootAndNoApplicationVersionLookup() {
        assertThat(service.schema(request, WORKSHOP)).isSameAs(AssetFoundationEntitySchemas.schema(WORKSHOP));
        verify(snapshot).authorize(RegisteredEntityAction.WORKSHOP_READ);
        verify(snapshot).requireActive();
        verify(snapshot, never()).applicationVersion(any());
    }
}
