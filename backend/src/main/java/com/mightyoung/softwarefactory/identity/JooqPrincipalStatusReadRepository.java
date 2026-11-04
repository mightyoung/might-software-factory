package com.mightyoung.softwarefactory.identity;

import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/** Reads the current principal row by its stable actor ID. */
@Repository
public final class JooqPrincipalStatusReadRepository implements PrincipalStatusReadRepository {

    private static final String PRINCIPAL_SQL = """
                select id as principal_id,
                       account_id as principal_account_id,
                       kind as principal_kind,
                       status as principal_status
                  from public.principal
                 where id = cast(? as uuid)
                """;

    public static java.util.Map<String,Integer> commandReadSql() { return java.util.Map.of(PRINCIPAL_SQL,1); }

    private final DSLContext dsl;

    public JooqPrincipalStatusReadRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public Optional<PrincipalStatus> findByActorId(UUID actorId) {
        Record row = dsl.resultQuery(PRINCIPAL_SQL, actorId).coerce(
                        org.jooq.impl.DSL.field("principal_id", UUID.class),
                        org.jooq.impl.DSL.field("principal_account_id", UUID.class),
                        org.jooq.impl.DSL.field("principal_kind", String.class),
                        org.jooq.impl.DSL.field("principal_status", String.class)).fetchOne();
        if (row == null) {
            return Optional.empty();
        }
        return Optional.of(new PrincipalStatus(
                row.get("principal_id", UUID.class),
                row.get("principal_account_id", UUID.class),
                row.get("principal_kind", String.class),
                row.get("principal_status", String.class)));
    }
}
