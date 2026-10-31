package com.mightyoung.softwarefactory.dualprevention;

import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Table;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

/** Tenant-scoped organization labels for a pre-authorized set of reportable organization IDs. */
@Repository
public final class JooqReportableOrganizationReadRepository implements ReportableOrganizationReadRepository {

    private static final Table<?> ORG_UNIT = table(name("public", "org_unit"));
    private static final Field<UUID> TENANT_ID = field(name("tenant_id"), UUID.class);
    private static final Field<UUID> ID = field(name("id"), UUID.class);
    private static final Field<String> CODE = field(name("code"), String.class);
    private static final Field<String> NAME = field(name("name"), String.class);
    private static final Field<Boolean> ACTIVE = field(name("active"), Boolean.class);

    private final DSLContext dsl;
    private final TransactionTemplate readTransaction;

    public JooqReportableOrganizationReadRepository(
            DSLContext dsl, PlatformTransactionManager transactionManager) {
        this.dsl = dsl;
        this.readTransaction = new TransactionTemplate(transactionManager);
        this.readTransaction.setReadOnly(true);
        this.readTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    @Override
    public List<ReportableOrganization> findActive(UUID tenantId, Set<UUID> authorizedOrganizationIds) {
        if (tenantId == null || authorizedOrganizationIds == null || authorizedOrganizationIds.isEmpty()) {
            return List.of();
        }
        return readTransaction.execute(status -> {
            String applied = (String) dsl.fetchValue("select set_config(?, ?, true)",
                    "app.tenant_id", tenantId.toString());
            if (!tenantId.toString().equals(applied)) {
                throw new IllegalStateException("Unable to apply the organization tenant context");
            }
            return dsl.select(ID, CODE, NAME)
                    .from(ORG_UNIT)
                    .where(TENANT_ID.eq(tenantId).and(ACTIVE.isTrue()).and(ID.in(authorizedOrganizationIds)))
                    .orderBy(NAME.asc(), CODE.asc(), ID.asc())
                    .fetch(row -> toOrganization(row));
        });
    }

    private static ReportableOrganization toOrganization(Record row) {
        return new ReportableOrganization(row.get(ID), row.get(CODE), row.get(NAME));
    }
}
