import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

// Isolated single-delivery storage experiment; never used by application runtime.
final class EarlyReceiptStorageFixture {
    private final String table;

    EarlyReceiptStorageFixture(String schema) {
        if (!schema.matches("(early_storage_probe_|event_probe_)[a-f0-9]{32}")) {
            throw new IllegalArgumentException("Expected a dedicated test schema");
        }
        table = schema + ".delivery";
    }

    void create(Connection db) throws SQLException {
        try (var sql = db.createStatement()) {
            sql.execute("create table " + table + """
                     (
                        tenant_id text not null,
                        source text not null,
                        event_id text not null,
                        subscription_version text not null,
                        instance_id text not null,
                        batch_id text not null,
                        original_body text not null,
                        expires_at timestamptz not null,
                        primary key (tenant_id, source, event_id, subscription_version)
                    )
                    """);
        }
    }

    EarlyReceiptContract accept(Connection db, EarlyReceiptContract candidate) throws SQLException {
        try (var sql = db.prepareStatement("insert into " + table + """
                     (tenant_id, source, event_id, subscription_version, instance_id, batch_id, original_body, expires_at)
                    values (?, ?, ?, ?, ?, ?, ?, ?)
                    on conflict (tenant_id, source, event_id, subscription_version) do nothing
                    """)) {
            key(sql, candidate.key());
            sql.setString(5, candidate.target().instance());
            sql.setString(6, candidate.target().batch());
            sql.setString(7, candidate.originalBody());
            sql.setObject(8, candidate.expiresAt().atOffset(ZoneOffset.UTC));
            if (sql.executeUpdate() == 1) return candidate;
        }
        // A separate READ COMMITTED statement sees the winning committed row after INSERT waited.
        var stored = find(db, candidate.key());
        if (stored == null) throw new IllegalStateException("Expected an existing fixture delivery");
        return stored.replay(candidate);
    }

    EarlyReceiptContract find(Connection db, EarlyReceiptContract.DeliveryKey key) throws SQLException {
        try (var sql = db.prepareStatement(
                "select instance_id, batch_id, original_body, expires_at from " + table + """
                         where tenant_id = ? and source = ? and event_id = ? and subscription_version = ?
                        """)) {
            key(sql, key);
            try (var row = sql.executeQuery()) {
                if (!row.next()) return null;
                return new EarlyReceiptContract(key, new EarlyReceiptContract.Target(row.getString(1), row.getString(2)),
                        row.getString(3), row.getObject(4, OffsetDateTime.class).toInstant());
            }
        }
    }

    private static void key(PreparedStatement sql, EarlyReceiptContract.DeliveryKey key) throws SQLException {
        sql.setString(1, key.transport().tenant());
        sql.setString(2, key.transport().identity().source());
        sql.setString(3, key.transport().identity().id());
        sql.setString(4, key.subscriptionVersion());
    }
}
