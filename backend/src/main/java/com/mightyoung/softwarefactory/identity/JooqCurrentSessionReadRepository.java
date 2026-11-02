package com.mightyoung.softwarefactory.identity;

import org.jooq.Record;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;

/** Revalidates the verified session and reads only its own active tenant membership. */
@Repository
public final class JooqCurrentSessionReadRepository implements CurrentSessionReadRepository {

    private final DSLContext dsl;
    private final TransactionTemplate readTransaction;

    public JooqCurrentSessionReadRepository(DSLContext dsl, PlatformTransactionManager transactionManager) {
        this.dsl = dsl;
        this.readTransaction = new TransactionTemplate(transactionManager);
        this.readTransaction.setReadOnly(true);
        this.readTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    @Override
    public Optional<CurrentSessionDetails> findCurrent(CurrentTenantContext context) {
        return readTransaction.execute(status -> {
            String applied = (String) dsl.fetchValue("select set_config(?, ?, true)",
                    "app.tenant_id", context.tenantId().toString());
            if (!context.tenantId().toString().equals(applied)) {
                throw new IllegalStateException("Unable to apply the current session tenant context");
            }
            Record row = dsl.fetchOne("""
                    select p.id as actor_id, a.id as account_id, t.id as tenant_id,
                           t.code as tenant_code, t.name as tenant_name,
                           tm.id as member_id, tm.display_name as display_name
                      from public.principal p
                      join public.account a on a.id = p.account_id
                      join public.tenant t on t.id = cast(? as uuid)
                      join public.tenant_member tm
                        on tm.tenant_id = t.id and tm.account_id = a.id and tm.id = cast(? as uuid)
                     where p.id = cast(? as uuid)
                       and a.id = cast(? as uuid)
                       and p.kind = 'HUMAN' and p.status = 'ACTIVE'
                       and a.status = 'ACTIVE'
                       and a.credential_version = ?
                       and t.status = 'ACTIVE' and tm.status = 'ACTIVE'
                       and t.auth_epoch = ?
                       and tm.auth_epoch = ?
                    """, context.tenantId(), context.memberId(), context.actorId(), context.accountId(),
                    context.credentialVersion(), context.tenantAuthEpoch(), context.memberAuthEpoch());
            if (row == null) return Optional.empty();
            return Optional.of(new CurrentSessionDetails(
                    row.get("actor_id", java.util.UUID.class),
                    row.get("account_id", java.util.UUID.class),
                    row.get("tenant_id", java.util.UUID.class),
                    row.get("tenant_code", String.class),
                    row.get("tenant_name", String.class),
                    row.get("member_id", java.util.UUID.class),
                    row.get("display_name", String.class)));
        });
    }
}
