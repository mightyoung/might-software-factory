package com.mightyoung.softwarefactory.tenancy;

import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import org.jooq.SQLDialect;
import org.jooq.conf.Settings;
import org.jooq.impl.DSL;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Repository
public final class JooqTenantApplicationReadRepository implements TenantApplicationReadRepository {
    private static final org.jooq.Field<Boolean> ENABLED = DSL.field(DSL.name("enabled"),Boolean.class);
    private static final org.jooq.Field<Long> VERSION = DSL.field(DSL.name("version"),Long.class);
    private static final org.jooq.Field<String> TENANT_GUC = DSL.field("current_setting('app.tenant_id',true)",String.class);

    public static java.util.Map<String,Integer> commandReadSql() {
        var renderer=DSL.using(SQLDialect.POSTGRES,callerSettings());
        return java.util.Map.of(applicationQuery(renderer,new UUID(0,0),"dual-prevention").getSQL(org.jooq.conf.ParamType.INDEXED),2,
                renderer.select(TENANT_GUC).getSQL(org.jooq.conf.ParamType.INDEXED),0);
    }

    private static Settings callerSettings() { return new Settings().withExecuteLogging(false).withFetchWarnings(false); }
    private static org.jooq.SelectConditionStep<org.jooq.Record2<Boolean,Long>> applicationQuery(
            DSLContext db,UUID tenantId,String applicationKey) {
        return db.select(ENABLED,VERSION).from(DSL.table(DSL.name("public","tenant_application")))
                .where(DSL.field(DSL.name("tenant_id"),UUID.class).eq(tenantId))
                .and(DSL.field(DSL.name("application_key"),String.class).eq(applicationKey));
    }
    private final DSLContext dsl;
    private final TransactionTemplate transaction;
    private final DataSource callerSource;

    public JooqTenantApplicationReadRepository(DSLContext dsl, PlatformTransactionManager manager) {
        this(dsl, manager, null);
    }

    @Autowired
    public JooqTenantApplicationReadRepository(DSLContext dsl, PlatformTransactionManager manager, DataSource callerSource) {
        this.dsl = dsl;
        this.callerSource = callerSource;
        this.transaction = new TransactionTemplate(manager);
        this.transaction.setReadOnly(true);
        this.transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    @Override public Optional<TenantApplicationState> findCurrentInCaller(UUID tenantId, String applicationKey) {
        Objects.requireNonNull(tenantId);
        if (callerSource == null || applicationKey == null || applicationKey.isBlank()
                || !(TransactionSynchronizationManager.getResource(callerSource) instanceof ConnectionHolder holder)) throw unavailable();
        Connection connection = DataSourceUtils.getConnection(callerSource);
        try {
            requireCaller(holder, connection, tenantId);
            var caller = DSL.using(connection, SQLDialect.POSTGRES, callerSettings());
            var row = applicationQuery(caller,tenantId,applicationKey).fetchOne();
            requireCaller(holder, connection, tenantId);
            if (row == null) return Optional.empty();
            Boolean active = row.get(ENABLED); Long revision = row.get(VERSION);
            if (active == null || revision == null || revision < 1) throw unavailable();
            return Optional.of(new TenantApplicationState(active, revision));
        } catch (RuntimeException failure) { holder.setRollbackOnly(); throw failure; }
        finally { DataSourceUtils.releaseConnection(connection, callerSource); }
    }

    private void requireCaller(ConnectionHolder holder, Connection connection, UUID tenantId) {
        try {
            boolean readOnly = TransactionSynchronizationManager.isCurrentTransactionReadOnly();
            int expectedIsolation = readOnly ? Connection.TRANSACTION_REPEATABLE_READ : Connection.TRANSACTION_READ_COMMITTED;
            if (TransactionSynchronizationManager.getResource(callerSource) != holder || holder.getConnection() != connection
                    || holder.isRollbackOnly() || !holder.isSynchronizedWithTransaction()
                    || !TransactionSynchronizationManager.isActualTransactionActive() || !TransactionSynchronizationManager.isSynchronizationActive()
                    || connection.getAutoCommit() || connection.isReadOnly() != readOnly || connection.getTransactionIsolation() != expectedIsolation
                    || !Integer.valueOf(expectedIsolation).equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())) throw unavailable();
            var caller = DSL.using(connection, SQLDialect.POSTGRES, callerSettings());
            String tenant = caller.select(TENANT_GUC).fetchSingle().value1();
            if (!tenantId.toString().equals(tenant)) throw unavailable();
        } catch (SQLException failure) { throw unavailable(); }
    }

    private static IllegalStateException unavailable() { return new IllegalStateException("Caller application entitlement boundary is unavailable"); }

    @Override
    public Optional<TenantApplicationState> findCurrent(UUID tenantId, String applicationKey) {
        if (tenantId == null || applicationKey == null || applicationKey.isBlank()) return Optional.empty();
        return transaction.execute(status -> {
            Object applied = dsl.fetchValue("select set_config(?, ?, true)", "app.tenant_id", tenantId.toString());
            if (!tenantId.toString().equals(applied)) {
                throw new IllegalStateException("Unable to apply application entitlement tenant context");
            }
            Record row = dsl.fetchOne("""
                    select enabled, version from public.tenant_application
                     where tenant_id = cast(? as uuid) and application_key = ?
                    """, tenantId, applicationKey);
            if (row == null) return Optional.empty();
            Boolean enabled = row.get("enabled", Boolean.class);
            Long version = row.get("version", Long.class);
            if (enabled == null || version == null || version < 1) return Optional.empty();
            return Optional.of(new TenantApplicationState(enabled, version));
        });
    }
}
