package com.mightyoung.softwarefactory.dualprevention;

import com.mightyoung.softwarefactory.applicationregistry.definition.EntityDefinition.Use;
import com.mightyoung.softwarefactory.applicationregistry.reference.ReferenceQueryContract;
import com.mightyoung.softwarefactory.authorization.FindingReadAuthorizationSnapshot;
import com.mightyoung.softwarefactory.authorization.FindingWorkflowAuthorizationRepository;
import com.mightyoung.softwarefactory.authorization.PersistentEntityReadAuthorizationRepository;
import com.mightyoung.softwarefactory.identity.AccountStatusReadRepository;
import com.mightyoung.softwarefactory.identity.CurrentTenantContext;
import com.mightyoung.softwarefactory.identity.SessionContextResolver;
import com.mightyoung.softwarefactory.tenancy.TenantApplicationReadRepository;
import com.mightyoung.softwarefactory.tenancy.TenantApplicationState;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import javax.sql.DataSource;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.conf.Settings;
import org.jooq.impl.DSL;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** No write/advisory locks: authority and bounded organization hints observe the same RR snapshot. */
@Repository
public final class JooqFindingDefinitionReadRepository implements FindingDefinitionReadRepository {
    private final DataSource source;
    private final DSLContext managedSql;
    private final SessionContextResolver sessions;
    private final TenantApplicationReadRepository applications;
    private final AccountStatusReadRepository accounts;
    private final FindingReadAuthorizer readAuthorizer;
    private final PersistentEntityReadAuthorizationRepository readAuthorization;
    private final FindingWorkflowAuthorizationRepository workflowAuthorization;
    private final TransactionTemplate transaction;

    public JooqFindingDefinitionReadRepository(DataSource source, DSLContext managedSql,
            PlatformTransactionManager manager, SessionContextResolver sessions,
            TenantApplicationReadRepository applications, AccountStatusReadRepository accounts,
            FindingReadAuthorizer readAuthorizer, PersistentEntityReadAuthorizationRepository readAuthorization,
            FindingWorkflowAuthorizationRepository workflowAuthorization) {
        this.source = Objects.requireNonNull(source); this.managedSql = Objects.requireNonNull(managedSql);
        this.sessions = Objects.requireNonNull(sessions); this.applications = Objects.requireNonNull(applications);
        this.accounts = Objects.requireNonNull(accounts); this.readAuthorizer = Objects.requireNonNull(readAuthorizer);
        this.readAuthorization = Objects.requireNonNull(readAuthorization);
        this.workflowAuthorization = Objects.requireNonNull(workflowAuthorization);
        transaction = new TransactionTemplate(Objects.requireNonNull(manager));
        transaction.setReadOnly(true);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        transaction.setTimeout(5);
    }

    @Override public <T> T inSnapshot(CurrentTenantContext context, Function<Snapshot, T> work) {
        Objects.requireNonNull(context); Objects.requireNonNull(work);
        if (TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isSynchronizationActive()
                || !TransactionSynchronizationManager.getResourceMap().isEmpty()) throw unavailable();
        return transaction.execute(status -> {
            if (!(TransactionSynchronizationManager.getResource(source) instanceof ConnectionHolder holder)) throw unavailable();
            var snapshot = new TenantSnapshot(context, holder);
            TransactionSynchronizationManager.registerSynchronization(snapshot);
            try {
                snapshot.applyContext();
                snapshot.requireActive();
                T result = work.apply(snapshot);
                snapshot.requireActive();
                return result;
            } finally { snapshot.open = false; }
        });
    }

    private final class TenantSnapshot implements Snapshot, TransactionSynchronization {
        private final CurrentTenantContext context;
        private final ConnectionHolder holder;
        private final Connection connection;
        private final Connection physical;
        private final DSLContext sql;
        private final Thread owner = Thread.currentThread();
        private boolean open = true;
        private Authority read;
        private Authority create;

        TenantSnapshot(CurrentTenantContext context, ConnectionHolder holder) {
            this.context = context; this.holder = holder; connection = holder.getConnection();
            physical = DataSourceUtils.getTargetConnection(connection);
            sql = DSL.using(connection, SQLDialect.POSTGRES, new Settings().withExecuteLogging(false));
        }
        private void applyContext() {
            if (!context.tenantId().toString().equals(sql.fetchValue("select set_config('app.tenant_id', ?, true)", context.tenantId().toString()))
                    || sql.fetchValue("select set_config('statement_timeout', '2000', true)") == null
                    || sql.fetchValue("select set_config('lock_timeout', '2000', true)") == null) throw unavailable();
        }
        @Override public void afterCompletion(int status) { open = false; }
        @Override public void savepoint(Object savepoint) { open = false; }
        @Override public void savepointRollback(Object savepoint) { open = false; }

        @Override public void requireActive() {
            if (!open || Thread.currentThread() != owner || !context.equals(sessions.currentTenant())
                    || TransactionSynchronizationManager.getResourceMap().size() != 1
                    || TransactionSynchronizationManager.getResource(source) != holder
                    || holder.getConnection() != connection || !holder.isSynchronizedWithTransaction() || holder.isRollbackOnly()
                    || !TransactionSynchronizationManager.isActualTransactionActive()
                    || !TransactionSynchronizationManager.isSynchronizationActive()
                    || !TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                    || !Integer.valueOf(TransactionDefinition.ISOLATION_REPEATABLE_READ)
                        .equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())
                    || TransactionSynchronizationManager.getSynchronizations().stream().noneMatch(item -> item == this)) throw unavailable();
            try {
                if (physical.getAutoCommit() || !physical.isReadOnly()
                        || physical.getTransactionIsolation() != Connection.TRANSACTION_REPEATABLE_READ) throw unavailable();
                // All injected public read adapters use this Spring-managed DSL bean. Fail closed on a miswired provider.
                if (!Boolean.TRUE.equals(managedSql.connectionResult(current ->
                        DataSourceUtils.getTargetConnection(current) == physical))) throw unavailable();
                var state = sql.fetchOne("""
                        select current_setting('app.tenant_id',true) tenant,
                         current_setting('transaction_isolation') isolation,
                         current_setting('transaction_read_only') readonly,
                         current_setting('statement_timeout')::interval = interval '2 seconds' statement_timeout_valid,
                         current_setting('lock_timeout')::interval = interval '2 seconds' lock_timeout_valid
                        """);
                if (state == null || !context.tenantId().toString().equals(state.get("tenant", String.class))
                        || !"repeatable read".equals(state.get("isolation", String.class))
                        || !"on".equals(state.get("readonly", String.class))
                        || !Boolean.TRUE.equals(state.get("statement_timeout_valid", Boolean.class))
                        || !Boolean.TRUE.equals(state.get("lock_timeout_valid", Boolean.class))) throw unavailable();
            } catch (SQLException error) { throw unavailable(); }
        }

        @Override public Authority authorize(Use use) {
            Objects.requireNonNull(use); requireActive();
            TenantApplicationState application = applications.findCurrent(context.tenantId(), "safety")
                    .filter(TenantApplicationState::enabled).orElseThrow(JooqFindingDefinitionReadRepository::denied);
            requireActive();
            var account = accounts.findState(context.accountId()).orElseThrow(JooqFindingDefinitionReadRepository::denied);
            if (!"ACTIVE".equals(account.status()) || account.credentialVersion() != context.credentialVersion()) throw denied();
            requireActive();
            FindingReadAuthorizationSnapshot snapshot;
            if (use == Use.READ) {
                var scope = readAuthorizer.requireReadScope(context);
                requireActive();
                snapshot = readAuthorization.loadFindingReadSnapshot(context, "finding.read")
                        .orElseThrow(JooqFindingDefinitionReadRepository::denied);
                if (!context.actorId().equals(scope.actorId()) || !context.tenantId().equals(scope.tenantId())
                        || !context.memberId().equals(scope.memberId())
                        || !scope.organizationIds().equals(snapshot.organizationIds())) throw denied();
            } else {
                snapshot = workflowAuthorization.loadActionSnapshot(context, "finding.create", "create")
                        .orElseThrow(JooqFindingDefinitionReadRepository::denied);
            }
            requireActive();
            if (!isCurrentHuman(context, snapshot)) throw denied();
            Authority authority = new Authority(use, context, snapshot.organizationIds(), application.version());
            if (use == Use.READ) read = authority; else create = authority;
            return authority;
        }

        @Override public ReferenceQueryContract.Page organizations(Authority authority, ReferenceQueryContract.Request request) {
            Objects.requireNonNull(request); requireActive();
            if (authority == null || authority != create || authority.use() != Use.CREATE) throw unavailable();
            String pattern = "%" + request.query().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
            var conditions = DSL.field("tenant_id", UUID.class).eq(context.tenantId())
                    .and(DSL.field("active", Boolean.class).isTrue())
                    .and(DSL.field("id", UUID.class).in(authority.organizationIds()))
                    .and(DSL.condition("(code ilike ? escape '\\' or name ilike ? escape '\\')", pattern, pattern));
            if (request.afterId() != null) conditions = conditions.and(DSL.field("id", UUID.class).gt(request.afterId()));
            List<ReferenceQueryContract.Item> rows = sql.select(DSL.field("id", UUID.class), DSL.field("code", String.class), DSL.field("name", String.class))
                    .from(DSL.table(DSL.name("public", "org_unit"))).where(conditions)
                    .orderBy(DSL.field("id", UUID.class).asc()).limit(request.limit() + 1)
                    .fetch(row -> new ReferenceQueryContract.Item(row.get("id", UUID.class), row.get("code", String.class), row.get("name", String.class)));
            requireActive();
            boolean more = rows.size() > request.limit();
            var items = List.copyOf(rows.subList(0, Math.min(rows.size(), request.limit())));
            return new ReferenceQueryContract.Page(items, more ? items.getLast().id() : null);
        }
    }

    private static boolean isCurrentHuman(CurrentTenantContext context, FindingReadAuthorizationSnapshot snapshot) {
        return context.actorId().equals(snapshot.principalId()) && context.accountId().equals(snapshot.principalAccountId())
                && "HUMAN".equals(snapshot.principalKind()) && "ACTIVE".equals(snapshot.principalStatus())
                && "ACTIVE".equals(snapshot.accountStatus()) && "ACTIVE".equals(snapshot.tenantStatus())
                && "ACTIVE".equals(snapshot.memberStatus()) && context.tenantAuthEpoch() == snapshot.tenantAuthEpoch()
                && context.memberAuthEpoch() == snapshot.memberAuthEpoch()
                && snapshot.organizationIds() != null && !snapshot.organizationIds().isEmpty();
    }
    private static AccessDeniedException denied() { return new AccessDeniedException("Current Finding authority is required"); }
    private static FindingDefinitionUnavailableException unavailable() { return new FindingDefinitionUnavailableException(); }
}
