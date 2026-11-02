package com.mightyoung.softwarefactory;

import com.mightyoung.softwarefactory.authorization.AuthorizationWriteGuardException;
import com.mightyoung.softwarefactory.authorization.PostgresAuthorizationWriteGuard;
import com.mightyoung.softwarefactory.workflow.WorkflowBindingTransactionLock;
import com.mightyoung.softwarefactory.workflow.WorkflowEngineUnavailableException;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.SQLException;
import java.time.Duration;
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

/** Opt-in managed-transaction advisory lock acceptance; performs no table writes or fixture DDL. */
class WorkflowBindingLockPostgresIT {
    private static final int BINDING_NAMESPACE = 0x4D534657;
    private HikariDataSource source;
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager manager;
    private PostgresAuthorizationWriteGuard authorization;
    private WorkflowBindingTransactionLock bindingLock;
    private ExecutorService executor;
    private final UUID tenant = UUID.randomUUID();
    private final UUID business = UUID.randomUUID();

    @BeforeEach void openExplicitlyGatedRuntimePool() {
        var accepted = IdentityAcceptanceTarget.validateInputs(System.getenv());
        if (!("I_CONFIRM_AUTHORIZATION_CONCURRENCY_WRITES:" + accepted.databaseName())
                .equals(System.getenv("LAN_PG_AUTHORIZATION_CONCURRENCY_ACK"))) {
            throw new IllegalStateException("Workflow binding acceptance requires exact database-specific confirmation");
        }
        var target = IdentityAcceptanceTarget.preflightFromEnvironment(System.getenv());
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(target.jdbcUrl());
        config.setUsername(required("LAN_PG_APP_USERNAME"));
        config.setPassword(required("LAN_PG_APP_PASSWORD"));
        config.setMaximumPoolSize(3);
        config.setMinimumIdle(0);
        config.setConnectionTimeout(5_000);
        config.setPoolName("workflow-binding-lock-acceptance");
        source = new HikariDataSource(config);
        jdbc = new JdbcTemplate(source);
        manager = new DataSourceTransactionManager(source);
        authorization = new PostgresAuthorizationWriteGuard(source);
        bindingLock = new WorkflowBindingTransactionLock(source, authorization);
        executor = Executors.newSingleThreadExecutor();
    }

    @AfterEach void closeOnlyOwnedConnectionsAndExecutor() throws Exception {
        try {
            if (executor != null) {
                executor.shutdownNow();
                assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
            }
        } finally {
            if (source != null) source.close();
        }
    }

    @Test void sameBindingWaitsDespiteCompatibleAuthorizationSharedLocksThenCommitReleases() throws Exception {
        sameBindingWaits(false);
    }

    @Test void sameBindingWaitsThenRollbackReleases() throws Exception {
        sameBindingWaits(true);
    }

    private void sameBindingWaits(boolean rollback) throws Exception {
        CompletableFuture<Integer> sharedAuthorizationAcquired = new CompletableFuture<>();
        CompletableFuture<Future<Integer>> pending = new CompletableFuture<>();
        transaction().executeWithoutResult(status -> {
            setTenant(tenant);
            bindingLock.acquire(tenant, "FINDING", business);
            int holderPid = pid();
            Future<Integer> waiting = executor.submit(() -> transaction().execute(waiter -> {
                setTenant(tenant);
                authorization.forFindingCommand(tenant);
                // This completion proves auth S/S was compatible before binding X waits.
                sharedAuthorizationAcquired.complete(pid());
                bindingLock.acquire(tenant, "FINDING", business);
                return jdbc.queryForObject("select 41 + 1", Integer.class);
            }));
            pending.complete(waiting);
            int waiterPid = await(sharedAuthorizationAcquired);
            observeBindingWait(holderPid, waiterPid, waiting);
            assertThat(jdbc.queryForObject("""
                    select count(*) from pg_locks where pid=? and locktype='advisory'
                       and granted and mode='ShareLock'
                    """, Integer.class, waiterPid)).isGreaterThanOrEqualTo(2);
            if (rollback) status.setRollbackOnly();
        });
        assertThat(pending.get(1, TimeUnit.SECONDS).get(7, TimeUnit.SECONDS)).isEqualTo(42);
        assertNoHeldLockAfterTransaction();
    }

    @Test void independentBindingAcquiresWhileFirstBindingRemainsHeld() throws Exception {
        transaction().executeWithoutResult(holder -> {
            setTenant(tenant);
            bindingLock.acquire(tenant, "FINDING", business);
            Future<Integer> independent = executor.submit(() -> transaction().execute(waiter -> {
                setTenant(tenant);
                bindingLock.acquire(tenant, "FINDING", UUID.randomUUID());
                return jdbc.queryForObject("select 42", Integer.class);
            }));
            try { assertThat(independent.get(3, TimeUnit.SECONDS)).isEqualTo(42); }
            catch (Exception failure) { throw new IllegalStateException("Independent binding blocked", failure); }
        });
    }

    @Test void strictTimeoutRestoresOnSuccessAndCaughtFailureForcesWholeRollbackAndReleasesLocks() throws Exception {
        transaction().executeWithoutResult(status -> {
            setTenant(tenant);
            jdbc.queryForObject("select set_config('lock_timeout','125ms',true)", String.class);
            bindingLock.acquire(tenant, "FINDING", business);
            assertThat(jdbc.queryForObject("show lock_timeout", String.class)).isEqualTo("125ms");
        });
        CompletableFuture<Throwable> timeoutFailure = new CompletableFuture<>();
        transaction().executeWithoutResult(holder -> {
            setTenant(tenant);
            bindingLock.acquire(tenant, "FINDING", business);
            Future<Throwable> waiting = executor.submit(() -> catchThrowable(() -> transaction().executeWithoutResult(status -> {
                setTenant(tenant);
                jdbc.queryForObject("select set_config('lock_timeout','125ms',true)", String.class);
                try {
                    bindingLock.acquire(tenant, "FINDING", business);
                    throw new AssertionError("Expected binding lock timeout");
                } catch (WorkflowEngineUnavailableException failure) {
                    timeoutFailure.complete(failure);
                    assertThat(((ConnectionHolder) TransactionSynchronizationManager.getResource(source)).isRollbackOnly()).isTrue();
                    // Deliberately swallow it: commit must still roll back the entire managed transaction.
                }
            })));
            try {
                assertThat(waiting.get(3, TimeUnit.SECONDS)).isInstanceOf(UnexpectedRollbackException.class);
                assertThat(sqlCause(timeoutFailure.get(1, TimeUnit.SECONDS)).getSQLState()).isEqualTo("55P03");
            } catch (Exception failure) { throw new IllegalStateException("125ms timeout was not preserved", failure); }
        });
        assertNoHeldLockAfterTransaction();
    }

    @Test void refusesWithoutManagedTransaction() {
        assertThatThrownBy(() -> bindingLock.acquire(tenant, "FINDING", business))
                .isInstanceOf(AuthorizationWriteGuardException.class);
    }

    @Test void wrongTenantGucMarksWholeTransactionRollbackOnlyEvenIfCaught() {
        assertThatThrownBy(() -> transaction().executeWithoutResult(status -> {
            setTenant(UUID.randomUUID());
            assertThatThrownBy(() -> bindingLock.acquire(tenant, "FINDING", business))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("tenant context mismatch");
        })).isInstanceOf(UnexpectedRollbackException.class);
        assertNoHeldLockAfterTransaction();
    }

    @Test void sameTransactionCannotChangeBindingAndCaughtViolationStillRollsBack() {
        assertThatThrownBy(() -> transaction().executeWithoutResult(status -> {
            setTenant(tenant);
            bindingLock.acquire(tenant, "FINDING", business);
            bindingLock.acquire(tenant, "FINDING", business); // same binding is reentrant
            assertThatThrownBy(() -> bindingLock.acquire(tenant, "FINDING", UUID.randomUUID()))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("single workflow binding");
        })).isInstanceOf(UnexpectedRollbackException.class);
        assertNoHeldLockAfterTransaction();
    }

    private void assertNoHeldLockAfterTransaction() {
        // Exclusive root proves previous shared auth locks released; a later distinct transaction
        // then reacquires the binding, proving both lock lifetimes ended on completion.
        transaction().executeWithoutResult(status -> authorization.forGlobalSecurityChange());
        transaction().executeWithoutResult(status -> {
            setTenant(tenant);
            bindingLock.acquire(tenant, "FINDING", business);
            assertThat(jdbc.queryForObject("select 42", Integer.class)).isEqualTo(42);
        });
    }

    private void observeBindingWait(int holderPid, int waiterPid, Future<?> pending) {
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (System.nanoTime() < deadline) {
            if (Boolean.TRUE.equals(jdbc.queryForObject("""
                    select ?=any(pg_blocking_pids(?)) and exists (
                       select 1 from pg_locks where pid=? and locktype='advisory'
                          and classid::bigint=? and mode='ExclusiveLock' and not granted)
                    """, Boolean.class, holderPid, waiterPid, waiterPid, BINDING_NAMESPACE))) {
                assertThat(pending.isDone()).isFalse(); return;
            }
            assertThat(pending.isDone()).as("Waiter completed before actual binding lock wait").isFalse();
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
        }
        throw new AssertionError("No matching actual PostgreSQL binding namespace wait observed");
    }

    private TransactionTemplate transaction() {
        var transaction = new TransactionTemplate(manager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setTimeout(15);
        return transaction;
    }
    private void setTenant(UUID value) { jdbc.queryForObject("select set_config('app.tenant_id',?,true)", String.class, value.toString()); }
    private int pid() { return jdbc.queryForObject("select pg_backend_pid()", Integer.class); }
    private static int await(CompletableFuture<Integer> value) {
        try { return value.get(3, TimeUnit.SECONDS); }
        catch (Exception failure) { throw new IllegalStateException("Shared authorization was not acquired", failure); }
    }
    private static SQLException sqlCause(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) if (cause instanceof SQLException sql) return sql;
        throw new AssertionError("Missing PostgreSQL timeout SQLState", failure);
    }
    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("Missing " + name);
        return value;
    }
}
