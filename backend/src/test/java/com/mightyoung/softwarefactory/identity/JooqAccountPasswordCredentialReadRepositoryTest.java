package com.mightyoung.softwarefactory.identity;

import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record5;
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

class JooqAccountPasswordCredentialReadRepositoryTest {

    @Test
    void joinsPasswordAccountAndHumanPrincipalByNormalizedLoginKey() {
        UUID actorId = UUID.fromString("10000000-0000-0000-0000-000000000001");
        UUID accountId = UUID.fromString("11000000-0000-0000-0000-000000000001");
        List<String> statements = new ArrayList<>();
        List<List<Object>> bindings = new ArrayList<>();
        DSLContext values = DSL.using(SQLDialect.POSTGRES);
        MockDataProvider provider = context -> {
            statements.add(context.sql());
            bindings.add(Arrays.asList(context.bindings()));
            Field<UUID> actor = DSL.field("actor_id", UUID.class);
            Field<UUID> account = DSL.field("account_id", UUID.class);
            Field<String> accountStatus = DSL.field("account_status", String.class);
            Field<String> principalStatus = DSL.field("principal_status", String.class);
            Field<String> secretHash = DSL.field("secret_hash", String.class);
            var result = values.newResult(actor, account, accountStatus, principalStatus, secretHash);
            Record5<UUID, UUID, String, String, String> row = values.newRecord(
                    actor, account, accountStatus, principalStatus, secretHash);
            row.set(actor, actorId);
            row.set(account, accountId);
            row.set(accountStatus, "ACTIVE");
            row.set(principalStatus, "ACTIVE");
            row.set(secretHash, "{bcrypt}$2a$10$synthetic-hash-value");
            result.add(row);
            return new MockResult[] {new MockResult(result.size(), result)};
        };
        DSLContext dsl = DSL.using(new MockConnection(provider), SQLDialect.POSTGRES);

        var credential = new JooqAccountPasswordCredentialReadRepository(dsl).findByLoginKey("operator-1");

        assertThat(credential).contains(new LocalPasswordCredential(
                actorId, accountId, "ACTIVE", "ACTIVE", "{bcrypt}$2a$10$synthetic-hash-value"));
        assertThat(statements).hasSize(1);
        assertThat(statements.getFirst().toLowerCase(java.util.Locale.ROOT))
                .contains("from public.account a", "join public.credential", "c.kind = 'password'",
                        "join public.principal", "p.kind = 'human'", "where lower(btrim(a.login_key)) = ?");
        assertThat(bindings.getFirst()).containsExactly("operator-1");
    }
}
