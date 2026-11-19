import java.nio.file.Path;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import org.casbin.jcasbin.main.Enforcer;
import org.casbin.jcasbin.model.Model;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.SQLDialect;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.assertEquals;

class CombinedGrantTest {
    private static final Table<?> RECORDS = DSL.table(DSL.name("authz_combined_records"));
    private static final Field<String> ID = DSL.field(DSL.name("id"), String.class);
    private static final Field<Integer> REVISION = DSL.field(DSL.name("revision"), Integer.class);
    private static final String MODEL = """
        [request_definition]
        r = sub, dom, obj, act
        [policy_definition]
        p = sub, dom, obj, act
        [role_definition]
        g = _, _, _
        [policy_effect]
        e = some(where (p.eft == allow))
        [matchers]
        m = g(r.sub, p.sub, r.dom) && r.dom == p.dom && r.obj == p.obj && r.act == p.act
        """;

    @Test
    void oldRequestCannotWriteAfterEitherGrantIsRevoked() throws Exception {
        JsonNode evidence = new JsonMapper().readTree(Path.of(env("AUTHZ_PLAN_FILE")).toFile());
        var model = new Model();
        model.loadModelFromText(MODEL);
        var functions = new Enforcer(model);
        functions.addPolicy("collaborator", "factory", "ledger:inspection", "edit");

        // Reuse the record identity from the originally opened form for each submission.
        String openedRecordId = "peer";
        try (var connection = DriverManager.getConnection(env("AUTHZ_PG_URL"), env("AUTHZ_PG_USER"), env("AUTHZ_PG_PASSWORD"))) {
            connection.setAutoCommit(false);
            try {
                DSLContext db = DSL.using(connection, SQLDialect.POSTGRES);
                db.execute("create temporary table authz_combined_records (id text primary key, tenant text not null, owner text not null, department text not null, revision integer not null default 0) on commit drop");
                db.execute("create temporary table authz_combined_grants (record_id text primary key) on commit drop");
                db.execute("insert into authz_combined_grants values (?)", openedRecordId);
                for (JsonNode row : evidence.required("records")) {
                    db.execute("insert into authz_combined_records (id, tenant, owner, department) values (?, ?, ?, ?)",
                        row.required("id").asString(), row.required("tenant").asString(),
                        row.required("owner").asString(), row.required("department").asString());
                }

                assertEquals(0, submit(db, functions, openedRecordId), "Data grant alone cannot edit");
                functions.addGroupingPolicy("alice", "collaborator", "factory");
                assertEquals(1, submit(db, functions, openedRecordId), "Both grants permit edit");
                assertEquals(1, revision(db, openedRecordId));

                functions.removeGroupingPolicy("alice", "collaborator", "factory");
                assertEquals(0, submit(db, functions, openedRecordId), "Old form after functional revocation");
                assertEquals(1, revision(db, openedRecordId));
                assertEquals(1, db.fetchCount(DSL.table("authz_combined_grants")), "Functional revocation preserves data grant");

                functions.addGroupingPolicy("alice", "collaborator", "factory");
                db.execute("delete from authz_combined_grants where record_id = ?", openedRecordId);
                assertEquals(0, submit(db, functions, openedRecordId), "Old form after data revocation");
                assertEquals(1, revision(db, openedRecordId));
                assertEquals(true, functions.enforce("alice", "factory", "ledger:inspection", "edit"), "Data revocation preserves functional role");

                db.execute("insert into authz_combined_grants values (?)", openedRecordId);
                assertEquals(0, submit(db, functions, "own"), "Grant only covers peer record");
                assertEquals(0, submit(db, functions, "other-tenant"), "Cross-tenant record denied");
                assertEquals(0, revision(db, "own"));
                assertEquals(0, revision(db, "other-tenant"));
                System.out.println("Combined grants: allowed edit persisted; either revocation blocked old request; unrelated and cross-tenant records unchanged");
            } finally {
                connection.rollback();
            }
        }
    }

    private static int submit(DSLContext db, Enforcer functions, String recordId) throws Exception {
        // Read current grant facts inside the same transaction that will update the record.
        var temporaryIds = db.select(DSL.field("record_id", String.class))
            .from(DSL.table("authz_combined_grants")).fetch(0, String.class);
        var principal = Map.of("id", "alice", "roles", new String[]{"collaborator"}, "attr",
            Map.of("tenant", "factory", "department", "quality", "temporary_ids", temporaryIds));
        var request = Map.of("requestId", "combined-old-form", "principal", principal,
            "action", "edit", "resource", Map.of("kind", "ledger"));
        var mapper = new JsonMapper();
        var call = HttpRequest.newBuilder(URI.create(env("CERBOS_URL") + "/api/plan/resources"))
            .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(request))).build();
        var response = HttpClient.newHttpClient().send(call, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), "Current Cerbos plan must be available");
        Condition dataScope = PlanCondition.from(mapper.readTree(response.body()).required("filter"));
        boolean functionAllowed = functions.enforce("alice", "factory", "ledger:inspection", "edit");
        return db.update(RECORDS).set(REVISION, REVISION.plus(1))
            .where(DSL.condition(functionAllowed).and(dataScope).and(ID.eq(recordId))).execute();
    }

    private static int revision(DSLContext db, String recordId) {
        return db.select(REVISION).from(RECORDS).where(ID.eq(recordId)).fetchSingle(REVISION);
    }

    private static String env(String name) {
        return Objects.requireNonNull(System.getenv(name), name + " is required");
    }
}
