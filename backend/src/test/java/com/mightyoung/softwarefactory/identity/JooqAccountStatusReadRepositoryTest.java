package com.mightyoung.softwarefactory.identity;

import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record2;
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

class JooqAccountStatusReadRepositoryTest {

    @Test
    void readsStatusAndCredentialVersionForTheRequestedAccountId() {
        UUID accountId = UUID.fromString("10000000-0000-0000-0000-000000000001");
        List<String> statements = new ArrayList<>();
        List<List<Object>> bindings = new ArrayList<>();
        DSLContext values = DSL.using(SQLDialect.POSTGRES);
        MockDataProvider provider = context -> {
            statements.add(context.sql());
            bindings.add(Arrays.asList(context.bindings()));
            Field<String> status = DSL.field("status", String.class);
            Field<Long> version = DSL.field("credential_version", Long.class);
            var result = values.newResult(status, version);
            Record2<String, Long> row = values.newRecord(status, version);
            row.set(status, "ACTIVE");
            row.set(version, 7L);
            result.add(row);
            return new MockResult[] {new MockResult(result.size(), result)};
        };
        DSLContext dsl = DSL.using(new MockConnection(provider), SQLDialect.POSTGRES);

        var status = new JooqAccountStatusReadRepository(dsl).findState(accountId);

        assertThat(status).contains(new AccountSecurityState("ACTIVE", 7L));
        assertThat(statements).hasSize(1);
        assertThat(statements.getFirst().toLowerCase(java.util.Locale.ROOT))
                .contains("from \"public\".\"account\"", "\"id\"", "\"credential_version\"");
        assertThat(bindings.getFirst()).containsExactly(accountId);
    }
}
