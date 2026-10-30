package com.mightyoung.softwarefactory.tenancy;

import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record4;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.tools.jdbc.MockConnection;
import org.jooq.tools.jdbc.MockDataProvider;
import org.jooq.tools.jdbc.MockResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JooqTenantMembershipReadRepositoryTest {

    @Test
    void bindsTenantAccountAndMemberToThePersistedMembershipLookup() {
        UUID tenantId = UUID.fromString("30000000-0000-0000-0000-000000000001");
        UUID accountId = UUID.fromString("11000000-0000-0000-0000-000000000001");
        UUID memberId = UUID.fromString("20000000-0000-0000-0000-000000000001");
        String[] statement = new String[1];
        List<List<Object>> bindings = new ArrayList<>();
        DSLContext values = DSL.using(SQLDialect.POSTGRES);
        MockDataProvider provider = context -> {
            statement[0] = context.sql();
            bindings.add(Arrays.asList(context.bindings()));
            Field<String> tenantStatus = DSL.field("tenant_status", String.class);
            Field<String> memberStatus = DSL.field("member_status", String.class);
            Field<Long> tenantEpoch = DSL.field("tenant_auth_epoch", Long.class);
            Field<Long> memberEpoch = DSL.field("member_auth_epoch", Long.class);
            var result = values.newResult(tenantStatus, memberStatus, tenantEpoch, memberEpoch);
            Record4<String, String, Long, Long> row = values.newRecord(
                    tenantStatus, memberStatus, tenantEpoch, memberEpoch);
            row.set(tenantStatus, "ACTIVE");
            row.set(memberStatus, "ACTIVE");
            row.set(tenantEpoch, 17L);
            row.set(memberEpoch, 23L);
            result.add(row);
            return new MockResult[] {new MockResult(result.size(), result)};
        };
        DSLContext dsl = DSL.using(new MockConnection(provider), SQLDialect.POSTGRES);

        var membership = new JooqTenantMembershipReadRepository(dsl)
                .findCurrentMembership(tenantId, accountId, memberId);

        assertThat(membership).contains(new TenantMembershipState("ACTIVE", "ACTIVE", 17L, 23L));
        assertThat(statement[0].toLowerCase(java.util.Locale.ROOT))
                .contains("from public.tenant t", "join public.tenant_member tm", "tm.account_id",
                        "tm.id", "t.auth_epoch", "tm.auth_epoch");
        assertThat(bindings.getFirst()).containsExactly(tenantId, accountId, memberId);
    }
}
