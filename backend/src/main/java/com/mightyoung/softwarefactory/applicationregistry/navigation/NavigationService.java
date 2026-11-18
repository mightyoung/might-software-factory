package com.mightyoung.softwarefactory.applicationregistry.navigation;

import com.mightyoung.softwarefactory.authorization.EffectivePermissions;
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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Presentation projection only: business endpoints must independently enforce entitlements and actions. */
@Service
public final class NavigationService {
    private record AppDef(String id, String name) {}
    private record NodeDef(String application, String id, String label, String path, String permission) {}
    /** Capability permissions surfaced for presentation only (e.g. enabling edit controls). */
    private static final String FINDING_CREATE = "finding.create";
    private static final List<String> PLATFORM_EXTRAS = List.of("platform.permission.manage");

    /** Presentation catalog only. Which nodes a member sees is decided by persisted role permissions. */
    private static final List<AppDef> APPS = List.of(new AppDef("safety", "生产安全"),
            new AppDef("equipment", "设备管理"), new AppDef("platform", "系统设置"));
    private static final List<NodeDef> NODES = List.of(
            new NodeDef("safety", "findings", "隐患管理", "safety/findings", "finding.read"),
            new NodeDef("equipment", "overview", "设备管理", "equipment/overview", "equipment.read"),
            new NodeDef("platform", "permissions", "权限配置", "platform/permissions", "platform.permission.read"));
    private final SessionContextResolver resolver;
    private final CurrentSessionService sessions;
    private final TenantApplicationReadRepository applications;
    private final EntityReadAccessPolicy reads;
    private final FindingWorkflowAuthorizationRepository actions;
    private final EffectivePermissions permissions;
    private final TransactionTemplate transaction;

    public NavigationService(SessionContextResolver resolver, CurrentSessionService sessions,
            TenantApplicationReadRepository applications, EntityReadAccessPolicy reads,
            FindingWorkflowAuthorizationRepository actions, EffectivePermissions permissions,
            PlatformTransactionManager manager) {
        this.resolver = resolver;
        this.sessions = sessions;
        this.applications = applications;
        this.reads = reads;
        this.actions = actions;
        this.permissions = permissions;
        this.transaction = new TransactionTemplate(manager);
        this.transaction.setReadOnly(true);
        this.transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    public Bootstrap bootstrap() {
        return transaction.execute(status -> load());
    }

    public Menu menu(String applicationId) {
        AppDef app = APPS.stream().filter(candidate -> candidate.id().equals(applicationId)).findFirst()
                .orElseThrow(NavigationService::denied);
        return transaction.execute(status -> {
            Bootstrap current = load();
            ApplicationEntry entry = current.applications().stream()
                    .filter(candidate -> candidate.id().equals(app.id())).findFirst().orElseThrow(NavigationService::denied);
            List<MenuNode> nodes = NODES.stream().filter(node -> node.application().equals(app.id()))
                    .map(node -> new MenuNode(node.id(), node.label(), node.path(), menuPermission(node, current.permissions())))
                    .filter(node -> node.permission() != null).toList();
            return new Menu(1, app.id(), entry.name(), nodes, current.permissions(), current.authorization());
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
        Long safetyVersion = null;
        boolean canCreate = false;
        Set<String> granted = new LinkedHashSet<>();
        Map<String, AppDef> visible = new LinkedHashMap<>();
        for (AppDef app : APPS) {
            var entitlement = applications.findCurrent(context.tenantId(), app.id()).orElse(null);
            if ("safety".equals(app.id()) && entitlement != null) safetyVersion = entitlement.version();
            if (entitlement == null || !entitlement.enabled()) continue;
            for (NodeDef node : NODES) {
                if (node.application().equals(app.id()) && hasNodePermission(context, node.permission())) {
                    granted.add(node.permission());
                    visible.putIfAbsent(app.id(), app);
                }
            }
            if ("safety".equals(app.id())) {
                // A member who may only report still enters the findings page; it never advertises read.
                canCreate = effectiveAction(context, actions.loadActionSnapshot(context, FINDING_CREATE, "create").orElse(null));
                if (canCreate) visible.putIfAbsent(app.id(), app);
            }
            if ("platform".equals(app.id())) {
                for (String extra : PLATFORM_EXTRAS) {
                    if (permissions.has(context, extra)) {
                        granted.add(extra);
                        if ("platform.permission.manage".equals(extra)) {
                            // Managing permissions implies the right to open the page.
                            granted.add("platform.permission.read");
                            visible.putIfAbsent(app.id(), app);
                        }
                    }
                }
            }
        }
        AuthorizationVersion version = new AuthorizationVersion(context.tenantAuthEpoch(),
                context.memberAuthEpoch(), safetyVersion);
        if (visible.isEmpty()) return new Bootstrap(1, identity, List.of(), List.of(), version);
        List<String> codes = new ArrayList<>(granted);
        if (canCreate) codes.add(FINDING_CREATE);
        List<ApplicationEntry> entries = visible.values().stream().map(app -> new ApplicationEntry(app.id(), app.name(),
                NODES.stream().filter(node -> node.application().equals(app.id())
                        && menuPermission(node, codes) != null).findFirst().orElseThrow().path())).toList();
        return new Bootstrap(1, identity, entries, List.copyOf(codes), version);
    }

    /** The permission a node is shown under, or null when hidden. Findings also opens for create-only members. */
    private static String menuPermission(NodeDef node, List<String> held) {
        if (held.contains(node.permission())) return node.permission();
        return "findings".equals(node.id()) && held.contains(FINDING_CREATE) ? FINDING_CREATE : null;
    }

    private boolean hasNodePermission(CurrentTenantContext context, String permission) {
        if ("finding.read".equals(permission)) {
            var grant = reads.resolveEntityReadGrant(context, "finding").orElse(null);
            return grant != null && context.tenantId().equals(grant.tenantId()) && !grant.organizationIds().isEmpty();
        }
        return permissions.has(context, permission);
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
