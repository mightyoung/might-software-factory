package com.mightyoung.softwarefactory.authorization;

import com.mightyoung.softwarefactory.identity.CurrentTenantContext;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

@Component
public final class PersistentEffectivePermissions implements EffectivePermissions {
    private final PersistentEntityReadAuthorizationRepository repository;

    public PersistentEffectivePermissions(PersistentEntityReadAuthorizationRepository repository) {
        this.repository = repository;
    }

    @Override
    public boolean has(CurrentTenantContext context, String permissionCode) {
        return !readableOrganizations(context, permissionCode).isEmpty();
    }

    @Override
    public Set<UUID> readableOrganizations(CurrentTenantContext context, String permissionCode) {
        if (context == null || permissionCode == null || context.actorId() == null || context.accountId() == null
                || context.tenantId() == null || context.memberId() == null) {
            return Set.of();
        }
        FindingReadAuthorizationSnapshot snapshot = repository.loadFindingReadSnapshot(context, permissionCode)
                .orElse(null);
        boolean valid = snapshot != null
                && context.actorId().equals(snapshot.principalId())
                && context.accountId().equals(snapshot.principalAccountId())
                && "HUMAN".equals(snapshot.principalKind())
                && "ACTIVE".equals(snapshot.principalStatus())
                && "ACTIVE".equals(snapshot.accountStatus())
                && "ACTIVE".equals(snapshot.tenantStatus())
                && "ACTIVE".equals(snapshot.memberStatus())
                && snapshot.tenantAuthEpoch() == context.tenantAuthEpoch()
                && snapshot.memberAuthEpoch() == context.memberAuthEpoch()
                && snapshot.organizationIds() != null;
        return valid ? Set.copyOf(snapshot.organizationIds()) : Set.of();
    }

    @Override
    public Set<String> granted(CurrentTenantContext context, Collection<String> permissionCodes) {
        Set<String> granted = new LinkedHashSet<>();
        for (String code : permissionCodes) {
            if (has(context, code)) granted.add(code);
        }
        return Set.copyOf(granted);
    }
}
