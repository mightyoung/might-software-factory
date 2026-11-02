package com.mightyoung.softwarefactory.tenancy;

import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.Optional;
import java.util.UUID;

@Repository
public final class JooqTenantApplicationReadRepository implements TenantApplicationReadRepository {
    private final DSLContext dsl;
    private final TransactionTemplate transaction;

    public JooqTenantApplicationReadRepository(DSLContext dsl, PlatformTransactionManager manager) {
        this.dsl = dsl;
        this.transaction = new TransactionTemplate(manager);
        this.transaction.setReadOnly(true);
        this.transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    @Override
    public Optional<TenantApplicationState> findCurrent(UUID tenantId, String applicationKey) {
        if (tenantId == null || applicationKey == null || applicationKey.isBlank()) return Optional.empty();
        return transaction.execute(status -> {
            Object applied = dsl.fetchValue("select set_config(?, ?, true)", "app.tenant_id", tenantId.toString());
            if (!tenantId.toString().equals(applied)) {
                throw new IllegalStateException("Unable to apply application entitlement tenant context");
            }
            Record row = dsl.fetchOne("""
                    select enabled, version from public.tenant_application
                     where tenant_id = cast(? as uuid) and application_key = ?
                    """, tenantId, applicationKey);
            if (row == null) return Optional.empty();
            Boolean enabled = row.get("enabled", Boolean.class);
            Long version = row.get("version", Long.class);
            if (enabled == null || version == null || version < 1) return Optional.empty();
            return Optional.of(new TenantApplicationState(enabled, version));
        });
    }
}
