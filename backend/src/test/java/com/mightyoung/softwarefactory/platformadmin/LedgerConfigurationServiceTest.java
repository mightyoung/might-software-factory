package com.mightyoung.softwarefactory.platformadmin;

import com.mightyoung.softwarefactory.authorization.EffectivePermissions;
import com.mightyoung.softwarefactory.identity.*;
import com.mightyoung.softwarefactory.tenancy.*;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import java.util.*;
import java.util.function.Supplier;
import static com.mightyoung.softwarefactory.platformadmin.LedgerConfigurationModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class LedgerConfigurationServiceTest {
    final CurrentTenantContext context = new CurrentTenantContext(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 0, 0, 1);
    final SessionContextResolver resolver = mock(SessionContextResolver.class);
    final EffectivePermissions permissions = mock(EffectivePermissions.class);
    final LedgerConfigurationRepository repository = mock(LedgerConfigurationRepository.class);
    final TenantApplicationReadRepository applications = mock(TenantApplicationReadRepository.class);
    boolean guarded;
    final IdentitySecurityTransaction transaction = new IdentitySecurityTransaction() {
        public <T> T execute(CurrentTenantContext ctx, Supplier<T> work) { guarded = true; return work.get(); }
        public void requireActive(CurrentTenantContext ctx) { assertThat(guarded).isTrue(); }
    };
    final LedgerConfigurationService service = new LedgerConfigurationService(resolver, permissions, transaction, repository, applications);
    LedgerConfigurationServiceTest() {
        when(resolver.currentTenant()).thenReturn(context);
        when(applications.findCurrentInCaller(context.tenantId(), "platform")).thenAnswer(invocation -> {
            assertThat(guarded).isTrue();
            return Optional.of(new TenantApplicationState(true, 1));
        });
    }
    static Definition complete() { return new Definition("采购", List.of(new Field("quantity", "数量", "number", true)), List.of("quantity"), List.of("quantity")); }
    @Test void configurationManageDoesNotGrantPublishingAndPermissionManagementGrantsNeither() {
        when(permissions.has(context, LedgerConfigurationService.MANAGE)).thenReturn(true);
        assertThatThrownBy(() -> service.publish("purchase", new PublishRequest(1L, 1L, null))).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(repository);
        reset(permissions);
        when(permissions.has(context, "platform.permission.manage")).thenReturn(true);
        assertThatThrownBy(() -> service.save("purchase", new SaveRequest(UUID.randomUUID(), 0L, complete()))).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(repository);
    }
    @Test void publisherCanReadHistoryButCannotSaveOrRestore() {
        when(permissions.has(context, LedgerConfigurationService.PUBLISH)).thenReturn(true);
        when(repository.versions(context.tenantId(), "purchase")).thenReturn(List.of());
        service.versions("purchase");
        assertThatThrownBy(() -> service.restore("purchase", new RestoreRequest(UUID.randomUUID(), 1L, 1L))).isInstanceOf(AccessDeniedException.class);
        verify(repository, never()).restore(any(), any(), any(), any());
    }
    @Test void disabledApplicationDeniesDirectApiEvenWithActionPermission() {
        doReturn(Optional.of(new TenantApplicationState(false, 1))).when(applications).findCurrentInCaller(context.tenantId(), "platform");
        when(permissions.has(context, LedgerConfigurationService.MANAGE)).thenReturn(true);
        assertThatThrownBy(service::list).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.save("purchase", new SaveRequest(UUID.randomUUID(), 0L, complete()))).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(repository);
    }
    @Test void draftCanBeIncompleteButPublishRequiresFieldsListAndQuery() {
        Definition draft = new Definition("", List.of(), List.of(), List.of());
        assertThatCode(() -> LedgerConfigurationService.validDefinition(draft, false)).doesNotThrowAnyException();
        assertThatThrownBy(() -> LedgerConfigurationService.validDefinition(draft, true)).isInstanceOf(IllegalArgumentException.class);
        Definition noQuery = new Definition("采购", complete().fields(), List.of("quantity"), List.of());
        assertThatThrownBy(() -> LedgerConfigurationService.validDefinition(noQuery, true)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void invalidTypesDuplicateKeysAndUnknownColumnsAreRejected() {
        for (Definition definition : List.of(
                new Definition("采购", List.of(new Field("q", "数量", null, false)), List.of(), List.of()),
                new Definition("采购", List.of(new Field("q", "数量", "text", false), new Field("q", "重复", "text", false)), List.of(), List.of()),
                new Definition("采购", complete().fields(), List.of("secret"), List.of()))) {
            assertThatThrownBy(() -> LedgerConfigurationService.validDefinition(definition, false)).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
