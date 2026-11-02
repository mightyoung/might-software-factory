package com.mightyoung.softwarefactory.identity;

import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/** Reads a password hash only for a complete account + human-principal credential row. */
@Repository
public final class JooqAccountPasswordCredentialReadRepository implements AccountPasswordCredentialReadRepository {

    private static final String LOGIN_SQL = """
            select p.id as actor_id,
                   a.id as account_id,
                   a.status as account_status,
                   p.status as principal_status,
                   a.credential_version,
                   c.secret_hash
              from public.account a
              join public.credential c on c.account_id = a.id and c.kind = 'PASSWORD'
              join public.principal p on p.account_id = a.id and p.kind = 'HUMAN'
             where lower(btrim(a.login_key)) = ?
            """;

    private final DSLContext dsl;

    public JooqAccountPasswordCredentialReadRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public Optional<LocalPasswordCredential> findByLoginKey(String normalizedLoginKey) {
        Record row = dsl.fetchOne(LOGIN_SQL, normalizedLoginKey);
        if (row == null) {
            return Optional.empty();
        }
        return Optional.of(new LocalPasswordCredential(
                row.get("actor_id", java.util.UUID.class),
                row.get("account_id", java.util.UUID.class),
                row.get("account_status", String.class),
                row.get("principal_status", String.class),
                row.get("credential_version", Long.class),
                row.get("secret_hash", String.class)));
    }
}
