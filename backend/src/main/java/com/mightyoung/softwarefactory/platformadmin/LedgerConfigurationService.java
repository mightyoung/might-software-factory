package com.mightyoung.softwarefactory.platformadmin;

import com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.ValidationException;
import com.mightyoung.softwarefactory.authorization.EffectivePermissions;
import com.mightyoung.softwarefactory.identity.CurrentTenantContext;
import com.mightyoung.softwarefactory.identity.IdentitySecurityTransaction;
import com.mightyoung.softwarefactory.identity.SessionContextResolver;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import com.mightyoung.softwarefactory.tenancy.TenantApplicationReadRepository;
import com.mightyoung.softwarefactory.tenancy.TenantApplicationState;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import static com.mightyoung.softwarefactory.platformadmin.LedgerConfigurationModels.*;

@Service
public final class LedgerConfigurationService {
    static final String READ = "ledger.configuration.read";
    static final String MANAGE = "ledger.configuration.manage";
    static final String PUBLISH = "ledger.configuration.publish";
    private final SessionContextResolver resolver;
    private final EffectivePermissions permissions;
    private final IdentitySecurityTransaction transaction;
    private final LedgerConfigurationRepository repository;
    private final TenantApplicationReadRepository applications;

    public LedgerConfigurationService(SessionContextResolver resolver, EffectivePermissions permissions,
                                      IdentitySecurityTransaction transaction, LedgerConfigurationRepository repository,
                                      TenantApplicationReadRepository applications) {
        this.resolver = resolver;
        this.permissions = permissions;
        this.transaction = transaction;
        this.repository = repository;
        this.applications = applications;
    }

    public Items list() {
        return reading(context -> new Items(repository.list(context.tenantId())));
    }
    public Versions versions(String key) {
        validKey(key);
        return reading(context -> new Versions(repository.versions(context.tenantId(), key)));
    }
    public Snapshot latest(String key) {
        return versions(key).versions().getFirst();
    }
    public Snapshot save(String key, SaveRequest request) {
        validKey(key);
        if (request == null || request.requestId() == null) invalid("requestId", "required");
        validExpected(request.expectedVersion());
        validDefinition(request.definition(), false);
        return write(MANAGE, context -> repository.save(context.tenantId(), context.memberId(), key, request));
    }
    public Snapshot restore(String key, RestoreRequest request) {
        validKey(key);
        if (request == null || request.requestId() == null) invalid("requestId", "required");
        validExpected(request.expectedVersion());
        if (request.sourceVersion() == null || request.sourceVersion() < 1) invalid("sourceVersion", "invalid");
        return write(MANAGE, context -> repository.restore(context.tenantId(), context.memberId(), key, request));
    }
    public Snapshot publish(String key, PublishRequest request) {
        validKey(key);
        if (request == null) invalid("request", "required");
        validExpected(request.expectedVersion());
        if (request.version() == null || request.version() < 1) invalid("version", "invalid");
        return write(PUBLISH, context -> repository.publish(context.tenantId(), context.memberId(), key, request));
    }
    private Snapshot write(String permission, Function<CurrentTenantContext, Snapshot> work) {
        CurrentTenantContext context = resolver.currentTenant();
        return transaction.execute(context, () -> {
            requireApplication(context);
            if (!permissions.has(context, permission)) throw new AccessDeniedException("Ledger configuration unavailable");
            return work.apply(context);
        });
    }
    private <T> T reading(Function<CurrentTenantContext, T> work) {
        CurrentTenantContext context = resolver.currentTenant();
        // Keep application, authorization and tenant-scoped reads in the same guarded transaction.
        return transaction.execute(context, () -> {
            requireApplication(context);
            if (!permissions.has(context, READ) && !permissions.has(context, MANAGE) && !permissions.has(context, PUBLISH)) {
                throw new AccessDeniedException("Ledger configuration unavailable");
            }
            return work.apply(context);
        });
    }
    private void requireApplication(CurrentTenantContext context) {
        if (applications.findCurrentInCaller(context.tenantId(), "platform").filter(TenantApplicationState::enabled).isEmpty()) {
            throw new AccessDeniedException("Ledger configuration unavailable");
        }
    }
    static void validDefinition(Definition definition, boolean publishing) {
        if (definition == null) invalid("definition", "required");
        if (definition.name() == null || definition.name().length() > 200) invalid("definition.name", "invalid");
        if (definition.fields() == null || definition.fields().size() > 100) invalid("definition.fields", "invalid");
        Set<String> keys = new HashSet<>();
        for (Field field : definition.fields()) {
            if (field == null) invalid("definition.fields", "invalid");
            validKey(field.key());
            if (!keys.add(field.key())) invalid("definition.fields", "duplicate_key");
            if (field.label() == null || field.label().isBlank() || field.label().length() > 200) invalid("definition.fields", "invalid_label");
            if (field.type() == null || !Set.of("text", "number", "date", "boolean").contains(field.type())) invalid("definition.fields", "invalid_type");
        }
        validColumns(definition.listFields(), keys, "definition.listFields");
        validColumns(definition.queryFields(), keys, "definition.queryFields");
        if (publishing && (definition.name().isBlank() || keys.isEmpty() || definition.listFields().isEmpty() || definition.queryFields().isEmpty())) {
            invalid("definition", "incomplete_configuration");
        }
    }
    private static void validColumns(List<String> columns, Set<String> keys, String path) {
        if (columns == null || columns.size() > 100) invalid(path, "invalid");
        if (new HashSet<>(columns).size() != columns.size() || !keys.containsAll(columns)) invalid(path, "unknown_or_duplicate_field");
    }
    private static void validExpected(Long version) {
        if (version == null || version < 0 || version == Long.MAX_VALUE) invalid("expectedVersion", "invalid");
    }
    private static void validKey(String key) {
        if (key == null || !key.matches("[a-z][a-z0-9_]{0,63}")) invalid("key", "invalid");
    }
    private static void invalid(String field, String code) {
        throw new ValidationException(field, code, "Invalid ledger configuration");
    }
}
