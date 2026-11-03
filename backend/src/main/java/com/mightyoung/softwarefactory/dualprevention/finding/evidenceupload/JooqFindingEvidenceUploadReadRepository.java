package com.mightyoung.softwarefactory.dualprevention.finding.evidenceupload;

import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.Objects;
import javax.sql.DataSource;
import java.util.UUID;
import java.util.function.Function;

/** Bounded point-in-time status, including current authorization and public engine task reads. */
@Repository
public final class JooqFindingEvidenceUploadReadRepository implements FindingEvidenceUploadReadRepository {
    private final DSLContext dsl;
    private final DataSource source;
    private final TransactionTemplate transaction;
    public JooqFindingEvidenceUploadReadRepository(DSLContext dsl,PlatformTransactionManager manager,DataSource source) {
        this.dsl=Objects.requireNonNull(dsl); this.source=Objects.requireNonNull(source); transaction=new TransactionTemplate(manager);
        transaction.setReadOnly(true); transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        transaction.setTimeout(5);
    }
    @Override public <T> T inTenantReadSnapshot(UUID tenantId,Function<Snapshot,T> work) {
        FindingEvidenceUploadService.requireIndependentTransaction(); Objects.requireNonNull(tenantId); Objects.requireNonNull(work);
        return transaction.execute(status -> {
            var snapshot=new JooqFindingEvidenceUploadSnapshot(dsl,tenantId,false,source,null);
            try {
                configure(snapshot.dsl,tenantId);
                snapshot.requireOpen();
                return work.apply(snapshot);
            } finally { snapshot.close(); }
        });
    }
    static void configure(DSLContext dsl,UUID tenantId) {
        String applied=(String)dsl.fetchValue("select set_config('app.tenant_id', ?, true)",tenantId.toString());
        if (!tenantId.toString().equals(applied)) throw new IllegalStateException("Upload tenant context unavailable");
        dsl.fetchValue("select set_config('statement_timeout', '2000ms', true)");
        dsl.fetchValue("select set_config('lock_timeout', '2000ms', true)");
    }
}
