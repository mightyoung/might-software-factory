package com.mightyoung.softwarefactory.tenancy;

import com.mightyoung.softwarefactory.identity.TenantLoginContext;
import com.mightyoung.softwarefactory.identity.TenantLoginContextResolver;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * Resolves tenant code and account membership in one transaction. Tenant code is only a lookup
 * selector; RLS and the account binding are both checked before a context is returned.
 */
@Repository
public final class JooqTenantLoginContextResolver implements TenantLoginContextResolver {

    private final DSLContext dsl;

    public JooqTenantLoginContextResolver(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public Optional<TenantLoginContext> resolve(UUID accountId, String tenantCode) {
        String appliedCode = (String) dsl.fetchValue("select set_config(?, ?, true)",
                "app.login_tenant_code", tenantCode);
        if (!tenantCode.equals(appliedCode)) {
            throw new IllegalStateException("Unable to apply the tenant login selector");
        }
        UUID tenantId = (UUID) dsl.fetchValue("select id from public.tenant where code = ?", tenantCode);
        if (tenantId == null) {
            return Optional.empty();
        }
        String appliedTenantId = (String) dsl.fetchValue("select set_config(?, ?, true)",
                "app.tenant_id", tenantId.toString());
        if (!tenantId.toString().equals(appliedTenantId)) {
            throw new IllegalStateException("Unable to apply the resolved tenant context");
        }
        Record row = dsl.fetchOne("""
                select t.id as tenant_id,
                       tm.id as member_id,
                       t.status as tenant_status,
                       tm.status as member_status,
                       t.auth_epoch as tenant_auth_epoch,
                       tm.auth_epoch as member_auth_epoch
                  from public.tenant t
                  join public.tenant_member tm on tm.tenant_id = t.id
                 where t.id = cast(? as uuid)
                   and tm.account_id = cast(? as uuid)
                """, tenantId, accountId);
        if (row == null) {
            return Optional.empty();
        }
        return Optional.of(new TenantLoginContext(
                row.get("tenant_id", UUID.class),
                row.get("member_id", UUID.class),
                row.get("tenant_status", String.class),
                row.get("member_status", String.class),
                row.get("tenant_auth_epoch", Long.class),
                row.get("member_auth_epoch", Long.class)));
    }
}
