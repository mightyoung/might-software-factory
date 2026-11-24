import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EarlyReceiptPostgresStorageTest {
    private static final Instant ACCEPTED = Instant.parse("2026-11-25T00:00:00Z");
    private static final Duration TTL = Duration.ofSeconds(30);
    private static final String BODY = StructuredReceiptEventTest.APPROVED;

    @Test
    void restoresCommittedDeliveryAfterReconnectWithoutOverwritingItsBodyOrDeadline() throws Exception {
        String url = System.getenv("EVENT_PG_URL");
        assertNotNull(url, "Set EVENT_PG_URL to a dedicated PostgreSQL verification database");
        String user = System.getenv("EVENT_PG_USER");
        String password = System.getenv("EVENT_PG_PASSWORD");
        String schema = "early_storage_probe_" + UUID.randomUUID().toString().replace("-", "");
        var original = EarlyReceiptContract.accept("tenant-a", "sub-v1", BODY, ACCEPTED, TTL);
        var uncommitted = EarlyReceiptContract.accept("tenant-a", "sub-v2", BODY, ACCEPTED, TTL);
        var otherTenant = EarlyReceiptContract.accept("tenant-b", "sub-v1",
                BODY.replace("instance-a", "instance-b"), ACCEPTED, TTL);
        var retry = EarlyReceiptContract.accept("tenant-a", "sub-v1", BODY, ACCEPTED.plusSeconds(20), TTL);
        var mutation = EarlyReceiptContract.accept("tenant-a", "sub-v1",
                BODY.replace("\"batch\":\"A\"", "\"batch\":\"B\""), ACCEPTED, TTL);
        assertEquals(original.key(), mutation.key());
        assertTrue(retry.expiresAt().isAfter(original.expiresAt()));

        try (var admin = DriverManager.getConnection(url, user, password);
             var ddl = admin.createStatement()) {
            ddl.execute("create schema " + schema);
            try {
                var inbox = new EarlyReceiptStorageFixture(schema);
                inbox.create(admin);
                try (var writer = DriverManager.getConnection(url, user, password)) {
                    writer.setAutoCommit(false);
                    assertEquals(original, inbox.accept(writer, original));
                    assertEquals(otherTenant, inbox.accept(writer, otherTenant));
                    writer.commit();
                    assertEquals(uncommitted, inbox.accept(writer, uncommitted));
                    writer.rollback();
                }
                try (var reader = DriverManager.getConnection(url, user, password)) {
                    reader.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
                    reader.setAutoCommit(false);
                    assertNull(inbox.find(reader, uncommitted.key()));
                    assertEquals(original, inbox.find(reader, original.key()));
                    assertEquals(otherTenant, inbox.find(reader, otherTenant.key()));
                    assertEquals(original, inbox.accept(reader, retry));
                    assertThrows(IllegalArgumentException.class, () -> inbox.accept(reader, mutation));
                    reader.rollback();
                    assertEquals(original, inbox.find(reader, original.key()));
                    assertEquals(EarlyReceiptContract.Decision.EXPIRE,
                            inbox.find(reader, original.key()).evaluate(original.expiresAt(), true));
                    reader.rollback();
                }
            } finally {
                ddl.execute("drop schema " + schema + " cascade");
            }
        }
    }
}
