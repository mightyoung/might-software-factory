package com.mightyoung.softwarefactory.tenancy;

import com.mightyoung.softwarefactory.identity.TenantLoginContext;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record6;
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

class JooqTenantLoginContextResolverTest {

    @Test
    void selectsTenantByUntrustedCodeThenBindsThePersistedTenantAndAccountMembership() {
        UUID tenantId = UUID.fromString("30000000-0000-0000-0000-000000000001");
        UUID accountId = UUID.fromString("11000000-0000-0000-0000-000000000001");
        UUID memberId = UUID.fromString("20000000-0000-0000-0000-000000000001");
        List<String> statements = new ArrayList<>();
        List<List<Object>> bindings = new ArrayList<>();
        DSLContext values = DSL.using(SQLDialect.POSTGRES);
        MockDataProvider provider = context -> {
            String sql = context.sql();
            statements.add(sql);
            bindings.add(Arrays.asList(context.bindings()));
            if (sql.toLowerCase(java.util.Locale.ROOT).contains("set_config")) {
                String settingValue = (String) context.bindings()[1];
                return new MockResult[] {singleValue(values, "set_config", String.class, settingValue)};
            }
            if (sql.toLowerCase(java.util.Locale.ROOT).contains("select id from public.tenant")) {
                return new MockResult[] {singleValue(values, "id", UUID.class, tenantId)};
            }
            Field<UUID> tenant = DSL.field("tenant_id", UUID.class);
            Field<UUID> member = DSL.field("member_id", UUID.class);
            Field<String> tenantStatus = DSL.field("tenant_status", String.class);
            Field<String> memberStatus = DSL.field("member_status", String.class);
            Field<Long> tenantEpoch = DSL.field("tenant_auth_epoch", Long.class);
            Field<Long> memberEpoch = DSL.field("member_auth_epoch", Long.class);
            var result = values.newResult(tenant, member, tenantStatus, memberStatus, tenantEpoch, memberEpoch);
            Record6<UUID, UUID, String, String, Long, Long> row = values.newRecord(
                    tenant, member, tenantStatus, memberStatus, tenantEpoch, memberEpoch);
            row.set(tenant, tenantId);
            row.set(member, memberId);
            row.set(tenantStatus, "ACTIVE");
            row.set(memberStatus, "ACTIVE");
            row.set(tenantEpoch, 17L);
            row.set(memberEpoch, 23L);
            result.add(row);
            return new MockResult[] {new MockResult(result.size(), result)};
        };
        DSLContext dsl = DSL.using(new MockConnection(provider), SQLDialect.POSTGRES);

        var resolved = new JooqTenantLoginContextResolver(dsl).resolve(accountId, "north-plant");

        assertThat(resolved).contains(new TenantLoginContext(tenantId, memberId, "ACTIVE", "ACTIVE", 17L, 23L));
        assertThat(statements).hasSize(4);
        assertThat(bindings.get(0)).containsExactly("app.login_tenant_code", "north-plant");
        assertThat(statements.get(1).toLowerCase(java.util.Locale.ROOT))
                .contains("select id from public.tenant where code = ?");
        assertThat(bindings.get(1)).containsExactly("north-plant");
        assertThat(bindings.get(2)).containsExactly("app.tenant_id", tenantId.toString());
        assertThat(statements.get(3).toLowerCase(java.util.Locale.ROOT))
                .contains("join public.tenant_member tm", "tm.account_id = cast(? as uuid)");
        assertThat(bindings.get(3)).containsExactly(tenantId, accountId);
    }

    private static <T> MockResult singleValue(DSLContext values, String fieldName, Class<T> type, T value) {
        Field<T> field = DSL.field(fieldName, type);
        var result = values.newResult(field);
        var row = values.newRecord(field);
        row.set(field, value);
        result.add(row);
        return new MockResult(result.size(), result);
    }
}
