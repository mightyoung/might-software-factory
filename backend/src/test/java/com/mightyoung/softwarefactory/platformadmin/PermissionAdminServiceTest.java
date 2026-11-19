package com.mightyoung.softwarefactory.platformadmin;

import com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.ValidationException;
import com.mightyoung.softwarefactory.authorization.EffectivePermissions;
import com.mightyoung.softwarefactory.identity.CurrentTenantContext;
import com.mightyoung.softwarefactory.identity.IdentitySecurityTransaction;
import com.mightyoung.softwarefactory.identity.SessionContextResolver;
import com.mightyoung.softwarefactory.platformadmin.PermissionAdminModels.ReplaceRequest;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PermissionAdminServiceTest {
    private final SessionContextResolver resolver = mock(SessionContextResolver.class);
    private final EffectivePermissions permissions = mock(EffectivePermissions.class);
    private final PermissionAdminRepository repository = mock(PermissionAdminRepository.class);
    private final IdentitySecurityTransaction transaction = new IdentitySecurityTransaction() {
        @Override public <T> T execute(CurrentTenantContext context, Supplier<T> work) { return work.get(); }
        @Override public void requireActive(CurrentTenantContext context) {}
    };
    private final PermissionAdminService service = new PermissionAdminService(resolver, permissions, transaction, repository);
    private final CurrentTenantContext context = new CurrentTenantContext(UUID.randomUUID(), UUID.randomUUID(),
            UUID.randomUUID(), UUID.randomUUID(), 1, 1, 1);

    @Test void readingRequiresReadOrManagePermission() {
        when(resolver.currentTenant()).thenReturn(context);
        assertThatThrownBy(service::roles).isInstanceOf(AccessDeniedException.class);
        when(permissions.has(context, PermissionAdminService.MANAGE)).thenReturn(true);
        service.roles();
        verify(repository).roles(context.tenantId());
    }

    @Test void writingRequiresManageEvenWhenReadIsHeld() {
        when(resolver.currentTenant()).thenReturn(context);
        when(permissions.has(context, PermissionAdminService.READ)).thenReturn(true);
        var request = new ReplaceRequest(List.of("finding.read"), "abc");
        assertThatThrownBy(() -> service.replace(UUID.randomUUID(), request)).isInstanceOf(AccessDeniedException.class);
        verify(repository, never()).replaceRolePermissions(any(), any(), any(), any(), any(), any());
    }

    @Test void writeUsesTrustedMemberAndDeduplicatedCodes() {
        when(resolver.currentTenant()).thenReturn(context);
        when(permissions.has(context, PermissionAdminService.MANAGE)).thenReturn(true);
        UUID role = UUID.randomUUID();
        service.replace(role, new ReplaceRequest(List.of("finding.read", "finding.read"), "abc"));
        verify(repository).replaceRolePermissions(any(), any(), any(), any(), any(), any());
    }

    @Test void requestValidationRejectsMalformedInput() {
        assertThatThrownBy(() -> PermissionAdminService.validate(new ReplaceRequest(null, "v"))).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> PermissionAdminService.validate(new ReplaceRequest(List.of(""), "v"))).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> PermissionAdminService.validate(new ReplaceRequest(List.of("a"), " "))).isInstanceOf(ValidationException.class);
        assertThat(PermissionAdminService.validate(new ReplaceRequest(List.of("a", "a"), "v"))).isEqualTo(Set.of("a"));
    }

    @Test void versionIsOrderIndependentAndChangesWithContent() {
        assertThat(JooqPermissionAdminRepository.version(Set.of("a", "b"))).isEqualTo(JooqPermissionAdminRepository.version(Set.of("b", "a")));
        assertThat(JooqPermissionAdminRepository.version(Set.of("a"))).isNotEqualTo(JooqPermissionAdminRepository.version(Set.of("a", "b")));
    }
}
