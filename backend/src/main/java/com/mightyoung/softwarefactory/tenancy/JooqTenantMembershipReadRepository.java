package com.mightyoung.softwarefactory.tenancy;

import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/** Tenancy-owned query validates the current tenant/member/account binding. */
@Repository
public final class JooqTenantMembershipReadRepository implements TenantMembershipReadRepository {

    private static final String MEMBERSHIP_SQL = """
            select t.status as tenant_status,
                   tm.status as member_status,
                   t.auth_epoch as tenant_auth_epoch,
                   tm.auth_epoch as member_auth_epoch
              from public.tenant t
              join public.tenant_member tm on tm.tenant_id = t.id
             where t.id = cast(? as uuid)
               and tm.account_id = cast(? as uuid)
               and tm.id = cast(? as uuid)
            """;

    private final DSLContext dsl;

    public JooqTenantMembershipReadRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public Optional<TenantMembershipState> findCurrentMembership(UUID tenantId, UUID accountId, UUID memberId) {
        Record row = dsl.fetchOne(MEMBERSHIP_SQL, tenantId, accountId, memberId);
        if (row == null) {
            return Optional.empty();
        }
        return Optional.of(new TenantMembershipState(
                row.get("tenant_status", String.class),
                row.get("member_status", String.class),
                row.get("tenant_auth_epoch", Long.class),
                row.get("member_auth_epoch", Long.class)));
    }
}
