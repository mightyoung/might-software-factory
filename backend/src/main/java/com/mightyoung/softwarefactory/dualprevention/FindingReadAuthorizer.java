package com.mightyoung.softwarefactory.dualprevention;

import com.mightyoung.softwarefactory.authorization.EntityReadAccessPolicy;
import com.mightyoung.softwarefactory.authorization.EntityReadGrant;
import com.mightyoung.softwarefactory.dualprevention.finding.FindingReadScope;
import com.mightyoung.softwarefactory.identity.CurrentTenantContext;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

/** Converts a server-side permission/scope decision into the only scope accepted by finding reads. */
@Component
public final class FindingReadAuthorizer {

    private final EntityReadAccessPolicy accessPolicy;

    public FindingReadAuthorizer(EntityReadAccessPolicy accessPolicy) {
        this.accessPolicy = accessPolicy;
    }

    public FindingReadScope requireReadScope(CurrentTenantContext context) {
        EntityReadGrant grant = accessPolicy.resolveEntityReadGrant(context, "finding")
                .orElseThrow(() -> new AccessDeniedException("Finding read permission is required"));
        if (!context.tenantId().equals(grant.tenantId()) || grant.organizationIds().isEmpty()) {
            throw new AccessDeniedException("Finding read scope is not available in this session");
        }
        return new FindingReadScope(
                context.actorId(), context.tenantId(), context.memberId(), grant.organizationIds());
    }
}
