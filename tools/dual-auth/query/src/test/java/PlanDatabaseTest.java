import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import org.jooq.*;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

class PlanDatabaseTest {
    private static final Table<?> ROWS = DSL.table(DSL.name("authz_probe_records"));
    private static final Field<String> ID = DSL.field(DSL.name("id"), String.class);

    @Test
    void realCheckResultsMatchSql() throws Exception {
        JsonNode evidence = new JsonMapper().readTree(Path.of(env("AUTHZ_PLAN_FILE")).toFile());
        assertFalse(evidence.required("records").isEmpty());
        assertFalse(evidence.required("cases").isEmpty());
        try (var connection = DriverManager.getConnection(env("AUTHZ_PG_URL"), env("AUTHZ_PG_USER"), env("AUTHZ_PG_PASSWORD"))) {
            connection.setAutoCommit(false);
            DSLContext db = DSL.using(connection, SQLDialect.POSTGRES);
            db.execute("create temporary table authz_probe_records (id text primary key, tenant text not null, owner text, department text, quantity int not null, price int not null) on commit drop");
            db.execute("create temporary table receipt_links (order_id text, receipt_id text, primary key(order_id,receipt_id)) on commit drop");
            db.execute("insert into receipt_links values ('A','own'), ('A','other-dept'), ('B','peer'), ('B','other-tenant')");
            insertRecords(db, evidence.required("records"));
            for (JsonNode sample : evidence.get("cases")) compare(db, sample);
            verifyProjectionAndTabs(db, evidence.get("cases"));

            // The secondary fixture uses the same SQL compiler and prepared statements,
            // but never changes baseline projection, link or role expectations.
            db.deleteFrom(ROWS).execute();
            insertRecords(db, evidence.required("nullableRecords"));
            assertEquals(2, evidence.required("nullableCases").size());
            for (JsonNode sample : evidence.get("nullableCases")) compare(db, sample);
            connection.rollback();
        }
    }

    private static void insertRecords(DSLContext db, JsonNode records) {
        for (JsonNode row : records) {
            db.execute("insert into authz_probe_records values (?, ?, ?, ?, ?, ?)", row.required("id").asString(),
                    row.required("tenant").asString(), optionalText(row.get("owner")), optionalText(row.get("department")),
                    row.required("quantity").asInt(), row.required("price").asInt());
        }
    }

    private static String optionalText(JsonNode field) {
        if (field == null || field.isNull()) return null;
        if (!field.isString()) throw new IllegalArgumentException("Malformed nullable fixture attribute");
        return field.asString();
    }

    private static void verifyProjectionAndTabs(DSLContext db, JsonNode cases) {
        Condition visible = findPlan(cases, "combined-read");
        Field<Integer> quantity = DSL.field(DSL.name("quantity"), Integer.class);
        Field<Integer> price = DSL.field(DSL.name("price"), Integer.class);
        var rows = db.select(ID, DSL.when(findPlan(cases, "combined-quantity"), quantity),
                DSL.when(findPlan(cases, "combined-price"), price))
            .from(ROWS).where(visible).orderBy(ID).fetch();
        assertEquals(2, rows.size());
        assertEquals("own", rows.get(0).value1());
        assertEquals(2, rows.get(0).value2());
        assertEquals(10, rows.get(0).value3());
        assertEquals("peer", rows.get(1).value1());
        assertEquals(3, rows.get(1).value2());
        assertNull(rows.get(1).value3());

        Field<String> receipt = DSL.field(DSL.name("receipt_id"), String.class);
        Field<String> order = DSL.field(DSL.name("order_id"), String.class);
        for (String parent : List.of("A", "B")) {
            Condition linked = ID.in(db.select(receipt).from(DSL.table(DSL.name("receipt_links"))).where(order.eq(parent)));
            assertEquals(1, db.fetchCount(ROWS, visible.and(linked)));
            assertEquals(List.of(parent.equals("A") ? "own" : "peer"),
                db.select(ID).from(ROWS).where(visible.and(linked)).fetch(ID));
        }
    }

    private static Condition findPlan(JsonNode cases, String name) {
        for (JsonNode sample : cases) {
            if (name.equals(sample.required("name").asString())) return PlanCondition.from(sample.required("filter"));
        }
        throw new IllegalArgumentException("Missing real Cerbos case: " + name);
    }

    private static void compare(DSLContext db, JsonNode sample) {
        String name = sample.required("name").asString() + "/" + sample.required("action").asString();
        Condition scope = PlanCondition.from(sample.required("filter"));
        List<String> expected = new ArrayList<>();
        for (JsonNode id : sample.required("expectedIds")) expected.add(id.asString());
        Collections.sort(expected);
        int count = db.fetchCount(ROWS, scope);
        assertEquals(expected.size(), count, name);
        List<String> pages = new ArrayList<>();
        for (int offset = 0; offset <= count; offset++) {
            pages.addAll(db.select(ID).from(ROWS).where(scope).orderBy(ID).limit(1).offset(offset).fetch(ID));
        }
        assertEquals(expected, pages, name);
        System.out.println(name + " matched SQL count and pages: " + count);
    }

    private static String env(String name) {
        return Objects.requireNonNull(System.getenv(name), name + " is required");
    }
}
