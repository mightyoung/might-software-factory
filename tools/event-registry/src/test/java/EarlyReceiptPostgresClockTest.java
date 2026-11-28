import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EarlyReceiptPostgresClockTest {
    @Test
    @Timeout(30)
    void waitingForDeliveryLockCanOutliveTheOriginalDeadline() throws Exception {
        String url = System.getenv("EVENT_PG_URL");
        assertNotNull(url, "Set EVENT_PG_URL to a dedicated PostgreSQL verification database");
        String user = System.getenv("EVENT_PG_USER");
        String password = System.getenv("EVENT_PG_PASSWORD");
        String schema = "early_clock_probe_" + UUID.randomUUID().toString().replace("-", "");
        String table = schema + ".delivery";
        try (var owner = DriverManager.getConnection(url, user, password);
             var waiter = DriverManager.getConnection(url, user, password)) {
            execute(owner, "create schema " + schema);
            try {
                execute(owner, "create table " + table
                        + " (id integer primary key, expires_at timestamptz not null)");
                int waiterPid = scalar(waiter, "select pg_backend_pid()", Integer.class);
                try (var pool = Executors.newSingleThreadExecutor()) {
                    try {
                        // Start the deadline only after connections and the table are ready.
                        Instant expiresAt = timestamp(owner, "insert into " + table
                                + " values (1, clock_timestamp() + interval '5 seconds') returning expires_at");
                        owner.setAutoCommit(false);
                        timestamp(owner, "select expires_at from " + table + " where id = 1 for update");
                        waiter.setAutoCommit(false);
                        execute(waiter, "set local statement_timeout = '12s'");
                        Instant transactionStart = timestamp(waiter, "select transaction_timestamp()");
                        assertTrue(transactionStart.isBefore(expiresAt), "waiter starts before the deadline");
                        var afterLock = pool.submit(() -> {
                            Instant stored = timestamp(waiter, "select expires_at from " + table + " where id = 1 for update");
                            // Separate statement: do not evaluate the wall clock before the lock is acquired.
                            Instant observed = timestamp(waiter, "select clock_timestamp()");
                            return new Observation(stored, observed);
                        });
                        while (!scalar(owner, "select cardinality(pg_blocking_pids(" + waiterPid + ")) > 0", Boolean.class)) {
                            assertTrue(timestamp(owner, "select clock_timestamp()").isBefore(expiresAt),
                                    "waiter must be observed blocked before expiry");
                            Thread.sleep(10);
                        }
                        assertTrue(timestamp(owner, "select clock_timestamp()").isBefore(expiresAt),
                                "lock contention must be confirmed before expiry");
                        while (timestamp(owner, "select clock_timestamp()").isBefore(expiresAt)) {
                            Thread.sleep(10);
                        }
                        owner.commit();
                        var observed = afterLock.get(10, TimeUnit.SECONDS);
                        assertEquals(expiresAt, observed.storedDeadline());
                        assertTrue(!observed.wallClock().isBefore(expiresAt), "lock was acquired after expiry");
                        var original = EarlyReceiptContract.accept("tenant-a", "subscription-v1",
                                StructuredReceiptEventTest.APPROVED, expiresAt.minusSeconds(5),
                                Duration.ofSeconds(5));
                        assertEquals(EarlyReceiptContract.Decision.DELIVER, original.evaluate(transactionStart, true));
                        assertEquals(EarlyReceiptContract.Decision.EXPIRE, original.evaluate(observed.wallClock(), true));
                    } finally {
                        // Always release the row lock before joining the worker, including assertion failures.
                        if (!owner.getAutoCommit()) owner.rollback();
                    }
                }
            } finally {
                try {
                    if (!waiter.getAutoCommit()) waiter.rollback();
                } finally {
                    owner.setAutoCommit(true);
                    execute(owner, "drop schema " + schema + " cascade");
                }
            }
        }
    }

    private record Observation(Instant storedDeadline, Instant wallClock) {}

    private static Instant timestamp(Connection db, String sql) throws SQLException {
        return scalar(db, sql, OffsetDateTime.class).toInstant();
    }

    private static <T> T scalar(Connection db, String sql, Class<T> type) throws SQLException {
        try (var statement = db.createStatement(); var rows = statement.executeQuery(sql)) {
            assertTrue(rows.next(), "expected one fixture row");
            return rows.getObject(1, type);
        }
    }

    private static void execute(Connection db, String sql) throws SQLException {
        try (var statement = db.createStatement()) {
            statement.execute(sql);
        }
    }
}
