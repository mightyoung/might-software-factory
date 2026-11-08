package com.mightyoung.softwarefactory.dualprevention.finding.workflow;

import com.mightyoung.softwarefactory.dualprevention.finding.FindingReadScope;
import org.jooq.*;
import org.jooq.impl.DSL;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import javax.sql.DataSource;
import java.sql.*;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.function.Function;

/** Own bounded RR snapshot. Every query uses the original managed physical connection. */
@Repository
public final class JooqFindingReviewHistoryRepository implements FindingReviewHistoryRepository {
    private final DataSource dataSource;
    private final TransactionTemplate transaction;
    public JooqFindingReviewHistoryRepository(DataSource dataSource, PlatformTransactionManager manager) {
        this.dataSource=dataSource;
        transaction=new TransactionTemplate(manager); transaction.setReadOnly(true);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ); transaction.setTimeout(5);
    }
    @Override public <T> T inTenantReadSnapshot(UUID tenantId, Function<Snapshot,T> work) {
        Objects.requireNonNull(tenantId); Objects.requireNonNull(work);
        if(TransactionSynchronizationManager.isActualTransactionActive() || TransactionSynchronizationManager.isSynchronizationActive()
                || !TransactionSynchronizationManager.getResourceMap().isEmpty()) throw unavailable("History requires its own snapshot");
        return transaction.execute(status -> {
            Object resource=TransactionSynchronizationManager.getResource(dataSource);
            if(!(resource instanceof ConnectionHolder holder)) throw unavailable("Missing history connection");
            var snapshot=new TenantSnapshot(tenantId,holder);
            snapshot.sql.fetchValue("select set_config('app.tenant_id', ?, true)",tenantId.toString());
            snapshot.sql.fetchValue("select set_config('statement_timeout', '2000', true)");
            snapshot.sql.fetchValue("select set_config('lock_timeout', '2000', true)");
            TransactionSynchronizationManager.registerSynchronization(snapshot);
            try { snapshot.requireOpen(); return work.apply(snapshot); }
            finally { snapshot.open=false; }
        });
    }
    private final class TenantSnapshot implements Snapshot, TransactionSynchronization {
        final UUID tenant; final ConnectionHolder holder; final Connection connection; final DSLContext sql;
        final Thread owner=Thread.currentThread(); boolean open=true;
        TenantSnapshot(UUID tenant,ConnectionHolder holder) { this.tenant=tenant;this.holder=holder;connection=holder.getConnection();sql=DSL.using(connection,SQLDialect.POSTGRES); }
        @Override public void afterCompletion(int status) { open=false; }
        void requireOpen() {
            if(!open || owner!=Thread.currentThread() || TransactionSynchronizationManager.getResource(dataSource)!=holder
                    || holder.getConnection()!=connection || !holder.isSynchronizedWithTransaction() || holder.isRollbackOnly()
                    || !TransactionSynchronizationManager.isActualTransactionActive() || !TransactionSynchronizationManager.isSynchronizationActive()
                    || !TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                    || !Integer.valueOf(TransactionDefinition.ISOLATION_REPEATABLE_READ).equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())
                    || TransactionSynchronizationManager.getSynchronizations().stream().noneMatch(s -> s==this)) throw unavailable("History snapshot expired or changed");
            try {
                if(connection.getAutoCommit() || !connection.isReadOnly() || connection.getTransactionIsolation()!=Connection.TRANSACTION_REPEATABLE_READ)
                    throw unavailable("History physical transaction changed");
                var row=sql.fetchOne("select current_setting('app.tenant_id',true) tenant, current_setting('transaction_isolation') isolation, current_setting('transaction_read_only') readonly, current_setting('statement_timeout')::interval = interval '2 seconds' statement_timeout_valid, current_setting('lock_timeout')::interval = interval '2 seconds' lock_timeout_valid");
                if(row==null || !tenant.toString().equals(row.get("tenant",String.class)) || !"repeatable read".equals(row.get("isolation",String.class)) || !"on".equals(row.get("readonly",String.class)) || !Boolean.TRUE.equals(row.get("statement_timeout_valid",Boolean.class)) || !Boolean.TRUE.equals(row.get("lock_timeout_valid",Boolean.class)))
                    throw unavailable("History tenant transaction changed");
            } catch(SQLException error) { throw new com.mightyoung.softwarefactory.workflow.WorkflowEngineUnavailableException(error); }
        }
        org.jooq.Condition scope(FindingReadScope scope, UUID id) {
            requireOpen();
            if(!tenant.equals(scope.tenantId()) || scope.organizationIds().isEmpty()) throw new IllegalArgumentException("Invalid history scope");
            return DSL.field(DSL.name("f","tenant_id"),UUID.class).eq(tenant)
                .and(DSL.field(DSL.name("f","id"),UUID.class).eq(id))
                .and(DSL.field(DSL.name("f","organization_id"),UUID.class).in(scope.organizationIds()));
        }
        @Override public Optional<Current> find(FindingReadScope scope,UUID id) {
            return sql.select(DSL.field(DSL.name("f","id"),UUID.class),DSL.field(DSL.name("f","organization_id"),UUID.class),DSL.field(DSL.name("f","created_by_member_id"),UUID.class),DSL.field(DSL.name("f","state"),String.class),DSL.field(DSL.name("f","version"),Long.class),DSL.field(DSL.name("f","review_cycle"),Integer.class),DSL.field(DSL.name("f","review_protocol"),String.class))
                .from(DSL.table(DSL.name("public","finding")).as("f")).where(scope(scope,id)).fetchOptional(r -> new Current(r.get("id",UUID.class),r.get("organization_id",UUID.class),r.get("created_by_member_id",UUID.class),r.get("state",String.class),r.get("version",Long.class),r.get("review_cycle",Integer.class),FindingReviewProtocol.valueOf(r.get("review_protocol",String.class))));
        }
        @Override public List<Entry> entries(FindingReadScope scope,UUID id,Cursor after,int limit) {
            requireOpen(); if(limit<1||limit>101) throw new IllegalArgumentException("Invalid history limit");
            // Scope is applied before pagination; all source identifiers and values are bound.
            var history=DSL.table("("+HISTORY_SQL+") as h",tenant,id,tenant,id,tenant,id);
            var query=sql.select(DSL.field(DSL.name("h","id"),String.class), DSL.field(DSL.name("h","origin"),String.class),
                    DSL.field(DSL.name("h","review_cycle"),Integer.class), DSL.field(DSL.name("h","stage"),String.class),
                    DSL.field(DSL.name("h","decision"),String.class), DSL.field(DSL.name("h","actor_member_id"),UUID.class),
                    DSL.field(DSL.name("h","occurred_at"),OffsetDateTime.class), DSL.field(DSL.name("h","text"),String.class),
                    DSL.field(DSL.name("h","recorded_grade"),String.class), DSL.field(DSL.name("h","next_review_cycle"),Integer.class)).from(history).join(DSL.table(DSL.name("public","finding")).as("f"))
                .on(DSL.field("h.finding_id",UUID.class).eq(DSL.field("f.id",UUID.class))).where(scope(scope,id));
            if(after!=null) query.and(DSL.condition("(h.review_cycle,h.occurred_at,h.origin,h.id) > (?,cast(? as timestamptz),?,?)",after.reviewCycle(),after.occurredAt(),after.kind(),after.id()));
            return query.orderBy(DSL.field("h.review_cycle"),DSL.field("h.occurred_at"),DSL.field("h.origin"),DSL.field("h.id")).limit(limit)
                .fetch(r -> new Entry(r.get("id",String.class),r.get("origin",String.class),r.get("review_cycle",Integer.class),r.get("stage",String.class),r.get("decision",String.class),r.get("actor_member_id",UUID.class),r.get("occurred_at",OffsetDateTime.class).withOffsetSameInstant(java.time.ZoneOffset.UTC),r.get("text",String.class),r.get("recorded_grade",String.class),r.get("next_review_cycle",Integer.class)));
        }
        @Override public Optional<UUID> approvedPerson(UUID id,int cycle,boolean cycleProtocol) {
            requireOpen();
            String query=cycleProtocol ? "select actor_member_id from public.finding_review_decision where tenant_id=cast(? as uuid) and finding_id=cast(? as uuid) and stage='PERSON' and decision='APPROVE' and review_cycle=?" : "select actor_member_id from public.finding_confirmation where tenant_id=cast(? as uuid) and finding_id=cast(? as uuid) and stage='PERSON' and decision='APPROVE' and 1=?";
            return sql.fetchOptional(query,tenant,id,cycle).map(r -> r.get("actor_member_id",UUID.class));
        }
    }
    private static com.mightyoung.softwarefactory.workflow.WorkflowEngineUnavailableException unavailable(String reason) {
        return new com.mightyoung.softwarefactory.workflow.WorkflowEngineUnavailableException(new IllegalStateException(reason));
    }
    static final String HISTORY_SQL="""
        select finding_id, 'legacy-confirmation:'||finding_id||':'||stage id, 'LEGACY_CONFIRMATION' origin,
          1 review_cycle, stage, decision, actor_member_id, decided_at occurred_at, reason text,
          null::varchar recorded_grade, null::integer next_review_cycle
          from public.finding_confirmation where tenant_id=cast(? as uuid) and finding_id=cast(? as uuid)
        union all
        select finding_id, 'review-decision:'||finding_id||':'||review_cycle||':'||stage, 'REVIEW_DECISION',
          review_cycle, stage, decision, actor_member_id, decided_at, reason, confirmed_grade, null::integer
          from public.finding_review_decision where tenant_id=cast(? as uuid) and finding_id=cast(? as uuid)
        union all
        select finding_id, 'reporter-supplement:'||id, 'REPORTER_SUPPLEMENT', review_cycle, null::varchar,
          null::varchar, reporter_member_id, submitted_at, statement, null::varchar, next_review_cycle
          from public.finding_report_supplement where tenant_id=cast(? as uuid) and finding_id=cast(? as uuid)
        """;
}
