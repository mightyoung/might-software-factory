package com.mightyoung.softwarefactory.identity;

import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record4;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.tools.jdbc.MockConnection;
import org.jooq.tools.jdbc.MockDataProvider;
import org.jooq.tools.jdbc.MockResult;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JooqPrincipalStatusReadRepositoryTest {

    @Test
    void looksUpPrincipalByActorIdAndReturnsItsPersistedAccountKindAndStatus() {
        UUID actorId = UUID.fromString("10000000-0000-0000-0000-000000000001");
        UUID accountId = UUID.fromString("11000000-0000-0000-0000-000000000001");
        Object[] statement = new Object[1];
        DSLContext values = DSL.using(SQLDialect.POSTGRES);
        MockDataProvider provider = context -> {
            statement[0] = new Object[] {context.sql(), Arrays.asList(context.bindings())};
            Field<UUID> principalId = DSL.field("principal_id", UUID.class);
            Field<UUID> principalAccountId = DSL.field("principal_account_id", UUID.class);
            Field<String> kind = DSL.field("principal_kind", String.class);
            Field<String> status = DSL.field("principal_status", String.class);
            var result = values.newResult(principalId, principalAccountId, kind, status);
            Record4<UUID, UUID, String, String> row = values.newRecord(
                    principalId, principalAccountId, kind, status);
            row.set(principalId, actorId);
            row.set(principalAccountId, accountId);
            row.set(kind, "HUMAN");
            row.set(status, "ACTIVE");
            result.add(row);
            return new MockResult[] {new MockResult(result.size(), result)};
        };
        DSLContext dsl = DSL.using(new MockConnection(provider), SQLDialect.POSTGRES);

        var state = new JooqPrincipalStatusReadRepository(dsl).findByActorId(actorId);

        assertThat(state).contains(new PrincipalStatus(actorId, accountId, "HUMAN", "ACTIVE"));
        Object[] observed = (Object[]) statement[0];
        assertThat(((String) observed[0]).toLowerCase(java.util.Locale.ROOT))
                .contains("from public.principal", "where id = cast(? as uuid)");
        assertThat(observed[1]).isEqualTo(Arrays.asList(actorId));
    }
}
