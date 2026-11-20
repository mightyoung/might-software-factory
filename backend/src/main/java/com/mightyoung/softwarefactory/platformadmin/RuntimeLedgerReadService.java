package com.mightyoung.softwarefactory.platformadmin;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import com.mightyoung.softwarefactory.authorization.EffectivePermissions;
import com.mightyoung.softwarefactory.identity.CurrentTenantContext;
import com.mightyoung.softwarefactory.identity.IdentitySecurityTransaction;
import com.mightyoung.softwarefactory.identity.SessionContextResolver;
import com.mightyoung.softwarefactory.tenancy.TenantApplicationReadRepository;
import com.mightyoung.softwarefactory.tenancy.TenantApplicationState;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import static com.mightyoung.softwarefactory.platformadmin.RuntimeLedgerReadModels.*;

@Service
public final class RuntimeLedgerReadService {
    private final SessionContextResolver resolver;
    private final IdentitySecurityTransaction transaction;
    private final EffectivePermissions permissions;
    private final TenantApplicationReadRepository applications;
    private final RuntimeLedgerReadRepository repository;

    public RuntimeLedgerReadService(SessionContextResolver resolver, IdentitySecurityTransaction transaction,
            EffectivePermissions permissions, TenantApplicationReadRepository applications, RuntimeLedgerReadRepository repository) {
        this.resolver = resolver;
        this.transaction = transaction;
        this.permissions = permissions;
        this.applications = applications;
        this.repository = repository;
    }
    public Items catalog() {
        return guarded(context -> new Items(repository.published(context.tenantId()).stream()
                .map(published -> item(context, published, capabilities(context, published.key())))
                .filter(item -> item.capabilities().read() || item.capabilities().create()).toList()));
    }
    public Item definition(String key) {
        return guarded(context -> {
            Published published = repository.published(context.tenantId(), key);
            Capabilities capabilities = capabilities(context, key);
            if (!capabilities.read() && !capabilities.create()) throw denied();
            return item(context, published, capabilities);
        });
    }
    public Page query(String key, Query query) {
        return guarded(context -> {
            Published published = repository.published(context.tenantId(), key);
            if (!permissions.has(context, code(key, "read"))) throw denied();
            Set<UUID> scope = permissions.readableOrganizations(context, code(key, "read"));
            return repository.query(context.tenantId(), published, scope, query);
        });
    }
    public Detail detail(String key, UUID id) {
        return guarded(context -> {
            repository.published(context.tenantId(), key);
            if (!permissions.has(context, code(key, "read"))) throw denied();
            Capabilities capabilities = capabilities(context, key);
            Detail detail = repository.detail(context.tenantId(), key, id,
                    permissions.readableOrganizations(context, code(key, "read")), capabilities);
            UUID organization = detail.record().organizationId();
            return new Detail(detail.record(), detail.definition(), new Capabilities(true, capabilities.create(),
                    permissions.readableOrganizations(context, code(key, "update")).contains(organization),
                    permissions.readableOrganizations(context, code(key, "delete")).contains(organization)));
        });
    }
    private Item item(CurrentTenantContext context, Published published, Capabilities capabilities) {
        return new Item(published.key(), published.definitionVersion(), published.definition(), capabilities,
                repository.organizations(context.tenantId(), permissions.readableOrganizations(context, code(published.key(), "create"))));
    }
    private Capabilities capabilities(CurrentTenantContext context, String key) {
        return new Capabilities(permissions.has(context, code(key, "read")), permissions.has(context, code(key, "create")),
                permissions.has(context, code(key, "update")), permissions.has(context, code(key, "delete")));
    }
    static String code(String key, String action) { return "ledger.record." + key + "." + action; }
    private <T> T guarded(Function<CurrentTenantContext, T> work) {
        CurrentTenantContext context = resolver.currentTenant();
        return transaction.execute(context, () -> {
            if (applications.findCurrentInCaller(context.tenantId(), "platform").filter(TenantApplicationState::enabled).isEmpty()) throw denied();
            return work.apply(context);
        });
    }
    private static AccessDeniedException denied() { return new AccessDeniedException("Ledger unavailable"); }
}
