package com.mightyoung.softwarefactory.identity;

import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record1;
import org.jooq.Table;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

/** Identity-owned account status query. */
@Repository
public final class JooqAccountStatusReadRepository implements AccountStatusReadRepository {

    private static final Table<?> ACCOUNT = table(name("public", "account"));
    private static final Field<UUID> ACCOUNT_ID = field(name("id"), UUID.class);
    private static final Field<String> STATUS = field(name("status"), String.class);

    private final DSLContext dsl;

    public JooqAccountStatusReadRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public Optional<String> findStatus(UUID accountId) {
        Record1<String> row = dsl.select(STATUS)
                .from(ACCOUNT)
                .where(ACCOUNT_ID.eq(accountId))
                .fetchOne();
        return row == null ? Optional.empty() : Optional.ofNullable(row.value1());
    }
}
