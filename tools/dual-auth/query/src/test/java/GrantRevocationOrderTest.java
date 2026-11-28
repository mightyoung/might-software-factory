import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

class GrantRevocationOrderTest {
    @Test
    void twoConnectionsSerializeGrantRevocationAndRecordWriteInBothOrders() throws Exception {
        String schema = "authz_order_" + UUID.randomUUID().toString().replace("-", "");
        long guard = UUID.randomUUID().getMostSignificantBits();
        try (Connection setup = connect(); Connection writer = connect(); Connection revoker = connect()) {
            DSLContext admin = DSL.using(setup, SQLDialect.POSTGRES);
            admin.execute("create schema " + schema);
            try {
                admin.execute("create table " + schema + ".records (id text primary key, tenant text not null, owner text not null, department text not null, revision integer not null)");
                admin.execute("create table " + schema + ".grants (record_id text primary key)");
                admin.execute("insert into " + schema + ".records values ('peer', 'factory', 'bob', 'quality', 0)");
                admin.execute("insert into " + schema + ".grants values ('peer')");
                writer.setAutoCommit(false);
                revoker.setAutoCommit(false);
                writer.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
                revoker.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
                DSLContext writes = DSL.using(writer, SQLDialect.POSTGRES);
                DSLContext revokes = DSL.using(revoker, SQLDialect.POSTGRES);

                // Writer acquires the shared guard first and uses the committed grant.
                lock(writes, guard);
                assertEquals(1, writeWithCurrentPlan(writes, schema));
                assertFalse(tryLock(revokes, guard), "Revocation must wait for guarded write commit");
                writer.commit();
                lock(revokes, guard);
                assertEquals(1, revokes.execute("delete from " + schema + ".grants where record_id = 'peer'"));
                revoker.commit();
                assertEquals(1, revision(admin, schema));
                lock(writes, guard);
                assertEquals(0, writeWithCurrentPlan(writes, schema), "Old request after committed revocation");
                writer.commit();
                assertEquals(1, revision(admin, schema));

                // Reset only this test's private fixture, then let revocation acquire first.
                admin.execute("update " + schema + ".records set revision = 0");
                admin.execute("insert into " + schema + ".grants values ('peer')");
                lock(revokes, guard);
                assertEquals(1, revokes.execute("delete from " + schema + ".grants where record_id = 'peer'"));
                assertFalse(tryLock(writes, guard), "Writer must wait for guarded revocation commit");
                revoker.commit();
                lock(writes, guard);
                assertEquals(0, writeWithCurrentPlan(writes, schema), "Next statement observes committed revocation");
                writer.commit();
                assertEquals(0, revision(admin, schema));
                System.out.println("Two-connection revocation: write-first committed 1; revoke-first committed 0; both guards blocked the competing transaction");
            } finally {
                writer.rollback();
                revoker.rollback();
                admin.execute("drop schema " + schema + " cascade");
            }
        }
    }

    private static int writeWithCurrentPlan(DSLContext db, String schema) throws Exception {
        var ids = db.select(DSL.field("record_id", String.class))
            .from(DSL.table(DSL.name(schema, "grants"))).fetch(0, String.class);
        var principal = Map.of("id", "alice", "roles", new String[]{"collaborator"}, "attr",
            Map.of("tenant", "factory", "department", "quality", "temporary_ids", ids));
        var payload = Map.of("requestId", "two-connection-order", "principal", principal,
            "action", "edit", "resource", Map.of("kind", "ledger"));
        var mapper = new JsonMapper();
        var request = HttpRequest.newBuilder(URI.create(env("CERBOS_URL") + "/api/plan/resources"))
            .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload))).build();
        var response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        var scope = PlanCondition.from(mapper.readTree(response.body()).required("filter"));
        var revision = DSL.field(DSL.name("revision"), Integer.class);
        return db.update(DSL.table(DSL.name(schema, "records"))).set(revision, revision.plus(1))
            .where(scope.and(DSL.field(DSL.name("id"), String.class).eq("peer"))).execute();
    }

    private static int revision(DSLContext db, String schema) {
        var revision = DSL.field(DSL.name("revision"), Integer.class);
        return db.select(revision).from(DSL.table(DSL.name(schema, "records")))
            .where(DSL.field(DSL.name("id"), String.class).eq("peer")).fetchSingle(revision);
    }

    private static void lock(DSLContext db, long guard) {
        db.fetch("select pg_advisory_xact_lock(?)", guard);
    }

    private static boolean tryLock(DSLContext db, long guard) {
        return Boolean.TRUE.equals(db.fetchValue("select pg_try_advisory_xact_lock(?)", guard));
    }

    private static Connection connect() throws Exception {
        return DriverManager.getConnection(env("AUTHZ_PG_URL"), env("AUTHZ_PG_USER"), env("AUTHZ_PG_PASSWORD"));
    }

    private static String env(String name) {
        return Objects.requireNonNull(System.getenv(name), name + " is required");
    }
}
