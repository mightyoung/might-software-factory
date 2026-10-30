package com.mightyoung.softwarefactory.identity;

import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record1;
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
    void readsStatusForTheRequestedAccountId() {
        UUID accountId = UUID.fromString("10000000-0000-0000-0000-000000000001");
        List<String> statements = new ArrayList<>();
        List<List<Object>> bindings = new ArrayList<>();
        DSLContext values = DSL.using(SQLDialect.POSTGRES);
        MockDataProvider provider = context -> {
            statements.add(context.sql());
            bindings.add(Arrays.asList(context.bindings()));
            Field<String> status = DSL.field("status", String.class);
            var result = values.newResult(status);
            Record1<String> row = values.newRecord(status);
            row.set(status, "ACTIVE");
            result.add(row);
            return new MockResult[] {new MockResult(result.size(), result)};
        };
        DSLContext dsl = DSL.using(new MockConnection(provider), SQLDialect.POSTGRES);

        var status = new JooqAccountStatusReadRepository(dsl).findStatus(accountId);

        assertThat(status).contains("ACTIVE");
        assertThat(statements).hasSize(1);
        assertThat(statements.getFirst().toLowerCase(java.util.Locale.ROOT))
                .contains("from \"public\".\"account\"", "\"id\"");
        assertThat(bindings.getFirst()).containsExactly(accountId);
    }
}
