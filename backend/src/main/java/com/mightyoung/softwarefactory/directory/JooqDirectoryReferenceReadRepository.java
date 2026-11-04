package com.mightyoung.softwarefactory.directory;

import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.conf.Settings;
import org.jooq.impl.DSL;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import static com.mightyoung.softwarefactory.directory.DirectoryReferenceContracts.*;

/** Current directory rows and SQL-scoped keyset pages in the caller's exact physical boundary. */
@Repository
public final class JooqDirectoryReferenceReadRepository implements DirectoryReferenceReadPort {
    private static final String ORGANIZATION_SQL = """
                select id,code,name,active from public.org_unit
                 where tenant_id=cast(? as uuid) and id=cast(? as uuid)
                """;
    private static final String POSITION_SQL = """
                select id,code,name,org_id,active from public."position"
                 where tenant_id=cast(? as uuid) and id=cast(? as uuid)
                """;
    private static final org.jooq.Field<String> TENANT_GUC = DSL.field("current_setting('app.tenant_id',true)",String.class);
    public static java.util.Map<String,Integer> commandReadSql() {
        String tenantCheck=DSL.using(SQLDialect.POSTGRES,new Settings().withExecuteLogging(false).withFetchWarnings(false))
                .select(TENANT_GUC).getSQL(org.jooq.conf.ParamType.INDEXED);
        return java.util.Map.of(ORGANIZATION_SQL,2,POSITION_SQL,2,tenantCheck,0);
    }
    private final DataSource source;
    public JooqDirectoryReferenceReadRepository(DataSource source) { this.source = Objects.requireNonNull(source); }

    @Override public Optional<Organization> findOrganizationAt(UUID tenantId, UUID id, Instant observedAt) {
        Objects.requireNonNull(id); Objects.requireNonNull(observedAt);
        return within(tenantId, db -> Optional.ofNullable(db.resultQuery(ORGANIZATION_SQL, tenantId, id).coerce(DSL.field("id",UUID.class),DSL.field("code",String.class),
                        DSL.field("name",String.class),DSL.field("active",Boolean.class)).fetchOne()).map(r -> new Organization(r.get("id", UUID.class), r.get("code", String.class),
                    r.get("name", String.class), Boolean.TRUE.equals(r.get("active", Boolean.class)), observedAt)));
    }
    @Override public Optional<Position> findPositionAt(UUID tenantId, UUID id, Instant observedAt) {
        Objects.requireNonNull(id); Objects.requireNonNull(observedAt);
        return within(tenantId, db -> Optional.ofNullable(db.resultQuery(POSITION_SQL, tenantId, id).coerce(DSL.field("id",UUID.class),DSL.field("code",String.class),
                        DSL.field("name",String.class),DSL.field("org_id",UUID.class),DSL.field("active",Boolean.class)).fetchOne()).map(r -> new Position(r.get("id", UUID.class), r.get("code", String.class),
                    r.get("name", String.class), r.get("org_id", UUID.class),
                    Boolean.TRUE.equals(r.get("active", Boolean.class)), observedAt)));
    }
    @Override public Page queryOrganizationsAt(UUID tenantId, Set<UUID> allowedOrgIds, Query query, Instant observedAt) {
        return page(tenantId, null, allowedOrgIds, query, observedAt, false);
    }
    @Override public Page queryPositionsAt(UUID tenantId, UUID orgId, Set<UUID> allowedOrgIds, Query query, Instant observedAt) {
        return page(tenantId, Objects.requireNonNull(orgId), allowedOrgIds, query, observedAt, true);
    }
    private Page page(UUID tenantId, UUID orgId, Set<UUID> allowed, Query query, Instant observedAt, boolean positions) {
        Set<UUID> scope = Set.copyOf(allowed);
        Objects.requireNonNull(query); Objects.requireNonNull(observedAt);
        return within(tenantId, db -> {
            if (scope.isEmpty() || positions && !scope.contains(orgId)) return new Page(java.util.List.of(), null);
            var bindings = new ArrayList<Object>(); bindings.add(tenantId);
            String sql;
            if (positions) {
                sql = "select p.id,p.code,p.name from public.\"position\" p where p.tenant_id=cast(? as uuid) and p.active=true "
                    + "and (p.org_id is null or p.org_id=cast(? as uuid)) and exists (select 1 from public.org_unit o "
                    + "where o.tenant_id=p.tenant_id and o.id=cast(? as uuid) and o.active=true)";
                bindings.add(orgId); bindings.add(orgId);
            } else {
                sql = "select p.id,p.code,p.name from public.org_unit p where p.tenant_id=cast(? as uuid) and p.active=true and p.id in ("
                    + String.join(",", java.util.Collections.nCopies(scope.size(), "cast(? as uuid)")) + ")";
                bindings.addAll(scope.stream().sorted(java.util.Comparator.comparing(UUID::toString)).toList());
            }
            String literal = "%" + query.query().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
            sql += " and (p.code ilike ? escape '\\' or p.name ilike ? escape '\\')";
            bindings.add(literal); bindings.add(literal);
            if (query.afterId() != null) { sql += " and p.id>cast(? as uuid)"; bindings.add(query.afterId()); }
            sql += " order by p.id asc limit ?"; bindings.add(query.limit() + 1);
            var rows = db.fetch(sql, bindings.toArray()).map(r -> new Item(r.get("id", UUID.class), r.get("code", String.class), r.get("name", String.class)));
            boolean more = rows.size() > query.limit();
            var items = java.util.List.copyOf(rows.subList(0, Math.min(rows.size(), query.limit())));
            return new Page(items, more ? items.getLast().id() : null);
        });
    }
    private <T> T within(UUID tenantId, Function<DSLContext,T> work) {
        Objects.requireNonNull(tenantId);
        if (!(TransactionSynchronizationManager.getResource(source) instanceof ConnectionHolder holder)) throw unavailable();
        Connection connection = DataSourceUtils.getConnection(source);
        try {
            require(holder, connection);
            DSLContext db = DSL.using(connection, SQLDialect.POSTGRES, new Settings().withExecuteLogging(false).withFetchWarnings(false));
            if (!tenantId.toString().equals(db.select(TENANT_GUC).fetchSingle().value1())) throw unavailable();
            T result = work.apply(db);
            require(holder, connection);
            if (!tenantId.toString().equals(db.select(TENANT_GUC).fetchSingle().value1())) throw unavailable();
            return result;
        } catch (RuntimeException failure) { holder.setRollbackOnly(); throw failure; }
        finally { DataSourceUtils.releaseConnection(connection, source); }
    }
    private void require(ConnectionHolder holder, Connection connection) {
        try {
            if (TransactionSynchronizationManager.getResource(source) != holder || holder.getConnection() != connection
                    || !holder.isSynchronizedWithTransaction() || holder.isRollbackOnly()
                    || !TransactionSynchronizationManager.isActualTransactionActive()
                    || !TransactionSynchronizationManager.isSynchronizationActive() || connection.getAutoCommit()) throw unavailable();
            int isolation = connection.getTransactionIsolation();
            if (isolation != (TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                    ? Connection.TRANSACTION_REPEATABLE_READ : Connection.TRANSACTION_READ_COMMITTED)
                    || connection.isReadOnly() != TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                    || !Integer.valueOf(isolation).equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())) throw unavailable();
        } catch (SQLException failure) { throw unavailable(); }
    }
    private static IllegalStateException unavailable() { return new IllegalStateException("Directory reference boundary is unavailable"); }
}
