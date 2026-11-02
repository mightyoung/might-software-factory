package com.mightyoung.softwarefactory;

import com.mightyoung.softwarefactory.authorization.AuthorizationWriteGuardException;
import com.mightyoung.softwarefactory.authorization.PostgresAuthorizationWriteGuard;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/** Opt-in advisory-lock/transaction acceptance only; this is not identity revocation acceptance.
 * Uses existing FORCE-RLS probe CRUD privileges and never creates database objects or grants.
 */
class AuthorizationWriteGuardPostgresIT {
    private HikariDataSource source;
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager manager;
    private PostgresAuthorizationWriteGuard guard;
    private ExecutorService executor;
    private final UUID tenant = UUID.randomUUID();
    private final String prefix = "auth-guard-" + UUID.randomUUID() + "-";

    @BeforeEach void openGatedAppRolePool() {
        var target = LanPostgresCompatibilityIT.DatabaseTarget.preflightFromEnvironment(System.getenv());
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(target.jdbcUrl());
        config.setUsername(required("LAN_PG_APP_USERNAME"));
        config.setPassword(required("LAN_PG_APP_PASSWORD"));
        config.setMaximumPoolSize(4);
        config.setMinimumIdle(0);
        config.setConnectionTimeout(5_000);
        config.setPoolName("authorization-guard-probe");
        source = new HikariDataSource(config);
        jdbc = new JdbcTemplate(source);
        manager = new DataSourceTransactionManager(source);
        guard = new PostgresAuthorizationWriteGuard(source);
        executor = Executors.newSingleThreadExecutor();
        assertThat(jdbc.queryForObject("""
                select relrowsecurity and relforcerowsecurity
                  from pg_class where oid = 'public.p0_tenant_probe'::regclass
                """, Boolean.class)).isTrue();
        transaction().executeWithoutResult(status -> {
            tenantContext();
            jdbc.update("insert into public.p0_tenant_probe(tenant_id, item_key) values (?, ?)",
                    tenant.toString(), prefix + "before");
        });
    }

    @AfterEach void closeAndRemoveOnlyOwnedRows() throws Exception {
        try {
            if (executor != null) {
                executor.shutdownNow();
                assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
            }
        } finally {
            if (source != null) {
                try {
                    if (manager != null) transaction().executeWithoutResult(status -> {
                        tenantContext();
                        jdbc.update("delete from public.p0_tenant_probe where tenant_id = ? and item_key like ?",
                                tenant.toString(), prefix + "%");
                    });
                } finally {
                    source.close();
                }
            }
        }
    }

    @Test void tenantWriterBlocksCommandAndCommandReadsCommittedFreshSnapshot() throws Exception {
        assertBlockingAndFreshRead(Mode.TENANT, Mode.COMMAND, false);
    }

    @Test void globalWriterBlocksCommandAndCommandReadsCommittedFreshSnapshot() throws Exception {
        assertBlockingAndFreshRead(Mode.GLOBAL, Mode.COMMAND, false);
    }

    @Test void commandBlocksTenantWriterUntilCommit() throws Exception {
        assertBlockingAndFreshRead(Mode.COMMAND, Mode.TENANT, false);
    }

    @Test void commandBlocksGlobalWriterUntilCommit() throws Exception {
        assertBlockingAndFreshRead(Mode.COMMAND, Mode.GLOBAL, false);
    }

    @Test void rollbackReleasesAdvisoryLocksAndDiscardsProbeChange() throws Exception {
        assertBlockingAndFreshRead(Mode.TENANT, Mode.COMMAND, true);
    }

    private void assertBlockingAndFreshRead(Mode holderMode, Mode waiterMode, boolean rollback) throws Exception {
        CompletableFuture<Integer> waiterPid = new CompletableFuture<>();
        CompletableFuture<Future<String>> pending = new CompletableFuture<>();
        transaction().executeWithoutResult(status -> {
            tenantContext();
            lock(holderMode);
            int holderPid = pid();
            renameProbe();
            Future<String> waiting = executor.submit(() -> transaction().execute(waiterStatus -> {
                tenantContext();
                // A statement before the lock sees the old committed value. The statement
                // after the lock must get a fresh READ COMMITTED snapshot.
                assertThat(readProbe()).isEqualTo(prefix + "before");
                waiterPid.complete(pid());
                lock(waiterMode);
                return readProbe();
            }));
            pending.complete(waiting);
            try {
                observeAdvisoryWait(holderPid, waiterPid.get(3, TimeUnit.SECONDS), waiting);
            } catch (Exception failure) {
                throw new IllegalStateException("Unable to observe actual advisory lock wait", failure);
            }
            if (rollback) status.setRollbackOnly();
        });
        assertThat(pending.get(1, TimeUnit.SECONDS).get(7, TimeUnit.SECONDS))
                .isEqualTo(prefix + (rollback ? "before" : "after"));
    }

    @Test void strictTimeoutIsRestoredAndTimeoutRollsBackAllPriorWrites() throws Exception {
        transaction().executeWithoutResult(status -> {
            tenantContext();
            jdbc.queryForObject("select set_config('lock_timeout', '125ms', true)", String.class);
            guard.forFindingCommand(tenant);
            assertThat(jdbc.queryForObject("show lock_timeout", String.class)).isEqualTo("125ms");
        });
        CompletableFuture<Future<Throwable>> pending = new CompletableFuture<>();
        transaction().executeWithoutResult(status -> {
            tenantContext();
            guard.forTenantSecurityChange(List.of(tenant));
            pending.complete(executor.submit(() -> catchThrowable(() -> transaction().executeWithoutResult(waiter -> {
                tenantContext();
                jdbc.update("insert into public.p0_tenant_probe(tenant_id, item_key) values (?, ?)",
                        tenant.toString(), prefix + "timeout-write");
                jdbc.queryForObject("select set_config('lock_timeout', '125ms', true)", String.class);
                guard.forFindingCommand(tenant);
            }))));
            try {
                // A bounded completion deadline proves 125ms was not widened to the default 5s.
                Throwable failure = pending.get(1, TimeUnit.SECONDS).get(3, TimeUnit.SECONDS);
                assertThat(failure).isInstanceOf(AuthorizationWriteGuardException.class);
                assertThat(sqlCause(failure).getSQLState()).isEqualTo("55P03");
            } catch (Exception failure) {
                throw new IllegalStateException("Strict lock_timeout did not abort the waiting transaction", failure);
            }
        });
        transaction().executeWithoutResult(status -> {
            tenantContext();
            assertThat(jdbc.queryForObject("select count(*) from public.p0_tenant_probe where tenant_id = ? and item_key = ?",
                    Integer.class, tenant.toString(), prefix + "timeout-write")).isZero();
            guard.forGlobalSecurityChange(); // exclusive root proves failed waiter's shared root lock was released
            assertThat(readProbe()).isEqualTo(prefix + "before");
        });
    }

    @Test void requiredInnerTransactionCannotHideRepeatableReadOrReadOnlyOuterTransaction() {
        for (boolean readOnly : List.of(false, true)) {
            TransactionTemplate outer = transaction();
            outer.setReadOnly(readOnly);
            if (!readOnly) outer.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
            assertThatThrownBy(() -> outer.executeWithoutResult(status -> {
                tenantContext();
                // REQUIRED joins the actual outer transaction despite the template's RC declaration.
                transaction().executeWithoutResult(inner -> guard.forFindingCommand(tenant));
            })).isInstanceOf(AuthorizationWriteGuardException.class);
        }
    }

    @Test void guardFirstAcquiredInsideSpringSavepointCannotBeReusedAfterNestedRollback() {
        Throwable completion = catchThrowable(() -> transaction().executeWithoutResult(outer -> {
            tenantContext();
            int outerPid = pid();
            TransactionTemplate nested = transaction();
            nested.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
            assertThatThrownBy(() -> nested.executeWithoutResult(inner -> {
                guard.forFindingCommand(tenant);
                jdbc.update("insert into public.p0_tenant_probe(tenant_id, item_key) values (?, ?)",
                        tenant.toString(), prefix + "nested-write");
                throw new NestedProbeRollback();
            })).isExactlyInstanceOf(NestedProbeRollback.class);

            // A separate physical connection must take exclusive root while the outer TX is still open:
            // savepoint rollback released the shared locks acquired by the first nested guard.
            Future<Integer> exclusiveRoot = executor.submit(() -> transaction().execute(other -> {
                jdbc.queryForObject("select set_config('lock_timeout', '1000ms', true)", String.class);
                guard.forGlobalSecurityChange();
                return pid();
            }));
            try {
                assertThat(exclusiveRoot.get(3, TimeUnit.SECONDS)).isNotEqualTo(outerPid);
            } catch (Exception failure) {
                exclusiveRoot.cancel(true);
                throw new IllegalStateException("Exclusive root was not available after nested rollback", failure);
            }

            // Spring clears ConnectionHolder.rollbackOnly during JDBC savepoint rollback. The guard's
            // own invalidated scope must still reject reuse and poison the entire outer transaction.
            assertThatThrownBy(() -> guard.forFindingCommand(tenant))
                    .isExactlyInstanceOf(AuthorizationWriteGuardException.class);
            jdbc.update("insert into public.p0_tenant_probe(tenant_id, item_key) values (?, ?)",
                    tenant.toString(), prefix + "outer-after-nested");
        }));
        assertThat(completion).isInstanceOf(UnexpectedRollbackException.class);
        transaction().executeWithoutResult(status -> {
            tenantContext();
            assertThat(jdbc.queryForObject("""
                    select count(*) from public.p0_tenant_probe
                     where tenant_id = ? and item_key in (?, ?)
                    """, Integer.class, tenant.toString(), prefix + "nested-write", prefix + "outer-after-nested"))
                    .isZero();
            assertThat(readProbe()).isEqualTo(prefix + "before");
        });
    }

    private static final class NestedProbeRollback extends RuntimeException { }

    private void observeAdvisoryWait(int holderPid, int waiterPid, Future<?> waiting) {
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (System.nanoTime() < deadline) {
            Boolean observed = jdbc.queryForObject("""
                    select ? = any(pg_blocking_pids(?)) and exists (
                        select 1 from pg_locks where pid = ? and locktype = 'advisory' and not granted)
                    """, Boolean.class, holderPid, waiterPid, waiterPid);
            if (Boolean.TRUE.equals(observed)) {
                assertThat(waiting.isDone()).isFalse();
                return;
            }
            assertThat(waiting.isDone()).as("waiter completed before an actual advisory wait was observed").isFalse();
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
        }
        throw new AssertionError("No matching pg_blocking_pids/pg_locks advisory wait within deadline");
    }

    private TransactionTemplate transaction() {
        TransactionTemplate template = new TransactionTemplate(manager);
        template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        template.setTimeout(15);
        return template;
    }

    private void tenantContext() {
        jdbc.queryForObject("select set_config('app.tenant_id', ?, true)", String.class, tenant.toString());
    }

    private int pid() { return jdbc.queryForObject("select pg_backend_pid()", Integer.class); }

    private String readProbe() {
        return jdbc.queryForObject("select item_key from public.p0_tenant_probe where tenant_id = ? and item_key in (?, ?)",
                String.class, tenant.toString(), prefix + "before", prefix + "after");
    }

    private void renameProbe() {
        assertThat(jdbc.update("update public.p0_tenant_probe set item_key = ? where tenant_id = ? and item_key = ?",
                prefix + "after", tenant.toString(), prefix + "before")).isEqualTo(1);
    }

    private void lock(Mode mode) {
        switch (mode) {
            case COMMAND -> guard.forFindingCommand(tenant);
            case TENANT -> guard.forTenantSecurityChange(List.of(tenant));
            case GLOBAL -> guard.forGlobalSecurityChange();
        }
    }

    private static SQLException sqlCause(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql) return sql;
        }
        throw new AssertionError("No SQL cause", failure);
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("Required LAN PostgreSQL test input missing: " + name);
        return value;
    }

    private enum Mode { COMMAND, TENANT, GLOBAL }
}
