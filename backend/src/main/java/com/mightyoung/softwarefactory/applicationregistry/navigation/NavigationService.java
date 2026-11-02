package com.mightyoung.softwarefactory.applicationregistry.navigation;

import com.mightyoung.softwarefactory.authorization.EntityReadAccessPolicy;
import com.mightyoung.softwarefactory.authorization.FindingReadAuthorizationSnapshot;
import com.mightyoung.softwarefactory.authorization.FindingWorkflowAuthorizationRepository;
import com.mightyoung.softwarefactory.identity.CurrentSessionDetails;
import com.mightyoung.softwarefactory.identity.CurrentSessionService;
import com.mightyoung.softwarefactory.identity.CurrentTenantContext;
import com.mightyoung.softwarefactory.identity.SessionContextResolver;
import com.mightyoung.softwarefactory.tenancy.TenantApplicationReadRepository;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;

/** Presentation projection only: business endpoints must independently enforce entitlements and actions. */
@Service
public final class NavigationService {
    private static final String APPLICATION = "safety";
    private static final String ENTRY_PATH = "safety/findings";
    private final SessionContextResolver resolver;
    private final CurrentSessionService sessions;
    private final TenantApplicationReadRepository applications;
    private final EntityReadAccessPolicy reads;
    private final FindingWorkflowAuthorizationRepository actions;
    private final TransactionTemplate transaction;

    public NavigationService(SessionContextResolver resolver, CurrentSessionService sessions,
            TenantApplicationReadRepository applications, EntityReadAccessPolicy reads,
            FindingWorkflowAuthorizationRepository actions, PlatformTransactionManager manager) {
        this.resolver = resolver;
        this.sessions = sessions;
        this.applications = applications;
        this.reads = reads;
        this.actions = actions;
        this.transaction = new TransactionTemplate(manager);
        this.transaction.setReadOnly(true);
        this.transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    public Bootstrap bootstrap() {
        return transaction.execute(status -> load());
    }

    public Menu menu(String applicationId) {
        if (!APPLICATION.equals(applicationId)) throw denied();
        return transaction.execute(status -> {
            Bootstrap current = load();
            if (current.applications().isEmpty()) throw denied();
            return new Menu(1, APPLICATION, "生产安全", List.of(new MenuNode(
                    "findings", "隐患管理", ENTRY_PATH, "finding.read")), current.permissions(),
                    current.authorization());
        });
    }

    private Bootstrap load() {
        CurrentTenantContext context = resolver.currentTenant();
        CurrentSessionDetails identity = sessions.current();
        // Preserve a single trusted binding even when a custom session reader is substituted.
        if (!context.actorId().equals(identity.actorId()) || !context.accountId().equals(identity.accountId())
                || !context.tenantId().equals(identity.tenantId()) || !context.memberId().equals(identity.memberId())) {
            throw denied();
        }
        var entitlement = applications.findCurrent(context.tenantId(), APPLICATION).orElse(null);
        Long applicationVersion = entitlement == null ? null : entitlement.version();
        AuthorizationVersion version = new AuthorizationVersion(context.tenantAuthEpoch(),
                context.memberAuthEpoch(), applicationVersion);
        if (entitlement == null || !entitlement.enabled()) {
            return new Bootstrap(1, identity, List.of(), List.of(), version);
        }
        var grant = reads.resolveEntityReadGrant(context, "finding").orElse(null);
        if (grant == null || !context.tenantId().equals(grant.tenantId()) || grant.organizationIds().isEmpty()) {
            return new Bootstrap(1, identity, List.of(), List.of(), version);
        }
        List<String> permissions = new ArrayList<>(List.of("finding.read"));
        var create = actions.loadActionSnapshot(context, "finding.create", "create").orElse(null);
        if (effectiveAction(context, create)) permissions.add("finding.create");
        return new Bootstrap(1, identity, List.of(new ApplicationEntry(APPLICATION, "生产安全", ENTRY_PATH)),
                List.copyOf(permissions), version);
    }

    private static boolean effectiveAction(CurrentTenantContext context, FindingReadAuthorizationSnapshot snapshot) {
        return snapshot != null && context.actorId().equals(snapshot.principalId())
                && context.accountId().equals(snapshot.principalAccountId())
                && "HUMAN".equals(snapshot.principalKind()) && "ACTIVE".equals(snapshot.principalStatus())
                && "ACTIVE".equals(snapshot.accountStatus()) && "ACTIVE".equals(snapshot.tenantStatus())
                && "ACTIVE".equals(snapshot.memberStatus())
                && context.tenantAuthEpoch() == snapshot.tenantAuthEpoch()
                && context.memberAuthEpoch() == snapshot.memberAuthEpoch()
                && snapshot.organizationIds() != null && !snapshot.organizationIds().isEmpty();
    }

    private static AccessDeniedException denied() { return new AccessDeniedException("Application unavailable"); }

    public record ApplicationEntry(String id, String name, String entryPath) {}
    public record AuthorizationVersion(long tenantAuthEpoch, long memberAuthEpoch, Long applicationVersion) {}
    public record Bootstrap(int schemaVersion, CurrentSessionDetails identity, List<ApplicationEntry> applications,
                            List<String> permissions, AuthorizationVersion authorization) {}
    public record MenuNode(String id, String label, String path, String permission) {}
    public record Menu(int schemaVersion, String applicationId, String title, List<MenuNode> nodes,
                       List<String> permissions, AuthorizationVersion authorization) {}
}
