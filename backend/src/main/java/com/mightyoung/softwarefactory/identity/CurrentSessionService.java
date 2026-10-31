package com.mightyoung.softwarefactory.identity;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.util.Objects;

/** Resolves the current user only when session, account, tenant, and membership are still active. */
@Service
public final class CurrentSessionService {

    private final SessionContextResolver sessionContextResolver;
    private final CurrentSessionReadRepository repository;

    public CurrentSessionService(
            SessionContextResolver sessionContextResolver,
            CurrentSessionReadRepository repository) {
        this.sessionContextResolver = Objects.requireNonNull(sessionContextResolver, "sessionContextResolver");
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    public CurrentSessionDetails current() {
        CurrentTenantContext context = sessionContextResolver.currentTenant();
        CurrentSessionDetails current = repository.findCurrent(context)
                .orElseThrow(() -> new AccessDeniedException("The current session is no longer active"));
        if (!context.actorId().equals(current.actorId())
                || !context.accountId().equals(current.accountId())
                || !context.tenantId().equals(current.tenantId())
                || !context.memberId().equals(current.memberId())) {
            throw new AccessDeniedException("The current session identity is no longer active");
        }
        return current;
    }
}
