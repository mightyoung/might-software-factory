package com.mightyoung.softwarefactory.platformadmin;

import com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.ValidationException;
import com.mightyoung.softwarefactory.authorization.EffectivePermissions;
import com.mightyoung.softwarefactory.identity.CurrentTenantContext;
import com.mightyoung.softwarefactory.identity.IdentitySecurityTransaction;
import com.mightyoung.softwarefactory.identity.SessionContextResolver;
import com.mightyoung.softwarefactory.platformadmin.PermissionAdminModels.CatalogResponse;
import com.mightyoung.softwarefactory.platformadmin.PermissionAdminModels.ReplaceRequest;
import com.mightyoung.softwarefactory.platformadmin.PermissionAdminModels.RoleView;
import com.mightyoung.softwarefactory.platformadmin.PermissionAdminModels.RolesResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

@Service
public final class PermissionAdminService {
    static final String READ = "platform.permission.read";
    static final String MANAGE = "platform.permission.manage";
    private static final int MAX_CODES = 200;
    private static final int MAX_CODE_LENGTH = 128;

    private static final Logger LOG = LoggerFactory.getLogger(PermissionAdminService.class);

    private final SessionContextResolver resolver;
    private final EffectivePermissions permissions;
    private final IdentitySecurityTransaction transaction;
    private final PermissionAdminRepository repository;

    public PermissionAdminService(SessionContextResolver resolver, EffectivePermissions permissions,
                                  IdentitySecurityTransaction transaction, PermissionAdminRepository repository) {
        this.resolver = resolver;
        this.permissions = permissions;
        this.transaction = transaction;
        this.repository = repository;
    }

    public CatalogResponse catalog() {
        CurrentTenantContext context = resolver.currentTenant();
        requireReader(context);
        return new CatalogResponse(repository.catalog(context.tenantId()));
    }

    public RolesResponse roles() {
        CurrentTenantContext context = resolver.currentTenant();
        requireReader(context);
        return new RolesResponse(repository.roles(context.tenantId()));
    }

    public RoleView replace(UUID roleId, ReplaceRequest request) {
        CurrentTenantContext context = resolver.currentTenant();
        Set<String> desired = validate(request);
        UUID trace = UUID.randomUUID();
        try {
            return transaction.execute(context, () -> {
                if (!permissions.has(context, MANAGE)) throw denied();
                return repository.replaceRolePermissions(context.tenantId(), roleId, desired,
                        request.expectedVersion(), context.memberId(), trace);
            });
        } catch (AccessDeniedException | PermissionAdminException expected) {
            throw expected;
        } catch (RuntimeException failure) {
            // Operators need the cause; the HTTP layer deliberately reports only a generic unavailable error.
            LOG.error("Permission change failed trace={} tenant={} role={}", trace, context.tenantId(), roleId, failure);
            throw failure;
        }
    }

    private void requireReader(CurrentTenantContext context) {
        if (!permissions.has(context, READ) && !permissions.has(context, MANAGE)) throw denied();
    }

    static Set<String> validate(ReplaceRequest request) {
        if (request == null || request.permissionCodes() == null) {
            throw new ValidationException("permissionCodes", "required", "permissionCodes is required");
        }
        if (request.expectedVersion() == null || request.expectedVersion().isBlank()
                || request.expectedVersion().length() > 64) {
            throw new ValidationException("expectedVersion", "invalid", "expectedVersion is required");
        }
        if (request.permissionCodes().size() > MAX_CODES) {
            throw new ValidationException("permissionCodes", "too_many", "Too many permission codes");
        }
        Set<String> codes = new LinkedHashSet<>();
        for (String code : request.permissionCodes()) {
            if (code == null || code.isBlank() || code.length() > MAX_CODE_LENGTH) {
                throw new ValidationException("permissionCodes", "invalid", "A permission code is invalid");
            }
            codes.add(code);
        }
        return Set.copyOf(codes);
    }

    private static AccessDeniedException denied() { return new AccessDeniedException("Permission configuration unavailable"); }
}
