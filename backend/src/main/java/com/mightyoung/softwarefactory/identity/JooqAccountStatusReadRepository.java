package com.mightyoung.softwarefactory.identity;

import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record2;
import org.jooq.Table;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

/** Identity-owned account status and credential-generation query. */
@Repository
public final class JooqAccountStatusReadRepository implements AccountStatusReadRepository {

    private static final Table<?> ACCOUNT = table(name("public", "account"));
    private static final Field<UUID> ACCOUNT_ID = field(name("id"), UUID.class);
    private static final Field<String> STATUS = field(name("status"), String.class);
    private static final Field<Long> CREDENTIAL_VERSION = field(name("credential_version"), Long.class);

    private final DSLContext dsl;

    public JooqAccountStatusReadRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public Optional<AccountSecurityState> findState(UUID accountId) {
        Record2<String, Long> row = dsl.select(STATUS, CREDENTIAL_VERSION)
                .from(ACCOUNT)
                .where(ACCOUNT_ID.eq(accountId))
                .fetchOne();
        return row == null ? Optional.empty() : Optional.of(new AccountSecurityState(row.value1(), row.value2()));
    }
}
