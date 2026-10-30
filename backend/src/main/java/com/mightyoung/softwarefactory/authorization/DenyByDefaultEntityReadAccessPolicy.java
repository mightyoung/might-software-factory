package com.mightyoung.softwarefactory.authorization;

import com.mightyoung.softwarefactory.identity.CurrentTenantContext;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Authorization storage and membership scope calculation are not yet configured. Until they are,
 * every entity read is denied rather than treating a valid session as tenant-wide access.
 */
@Component
public final class DenyByDefaultEntityReadAccessPolicy implements EntityReadAccessPolicy {

    @Override
    public Optional<EntityReadGrant> resolveEntityReadGrant(CurrentTenantContext context, String entityKey) {
        return Optional.empty();
    }
}
