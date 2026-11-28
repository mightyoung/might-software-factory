import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StructuredReceiptEventTest {
    static final String REQUESTED = """
            {
              "specversion": "1.0",
              "type": "com.mightyoung.probe.receipt.requested",
              "source": "urn:fixture:purchase",
              "id": "req-a",
              "data": {"batch":"A","tenant":"not-an-authority","type":"not-a-flowable-key"}
            }
            """;

    static final String APPROVED = """
            {
              "specversion": "1.0",
              "type": "com.mightyoung.probe.batch.approved",
              "source": "urn:fixture:inspection",
              "id": "approval-1",
              "data": {"instance":"instance-a","batch":"A","operator":"unused"}
            }
            """;

    @Test
    void mapsOnlyFixtureBusinessFieldsAndPreservesEventIdentity() {
        var requested = StructuredReceiptEvent.parse(REQUESTED);
        assertEquals(new StructuredReceiptEvent.Identity("urn:fixture:purchase", "req-a"), requested.identity());
        assertEquals(Map.of("type", "receiptRequested", "batch", "A"), requested.flowableFields());

        var approved = StructuredReceiptEvent.parse(APPROVED);
        assertEquals(new StructuredReceiptEvent.Identity("urn:fixture:inspection", "approval-1"), approved.identity());
        assertEquals(Map.of("type", "batchApproved", "instance", "instance-a", "batch", "A"), approved.flowableFields());
        var sameIdOtherSource = StructuredReceiptEvent.parse(APPROVED.replace("fixture:inspection", "fixture:other"));
        assertNotEquals(approved.identity(), sameIdOtherSource.identity());
    }

    @Test
    void rejectsInvalidStructuredEnvelopesAndCorrelators() {
        for (String invalid : new String[] {
                "{ malformed",
                "[]",
                REQUESTED.replace("\"1.0\"", "\"0.3\""),
                REQUESTED.replace("\"specversion\"", "\"missingVersion\""),
                REQUESTED.replace("com.mightyoung.probe.receipt.requested", "receiptRequested"),
                REQUESTED.replace("urn:fixture:purchase", "not a URI"),
                REQUESTED.replace("urn:fixture:purchase", ""),
                REQUESTED.replace("\"source\"", "\"missingSource\""),
                REQUESTED.replace("\"req-a\"", "42"),
                REQUESTED.replace("\"req-a\"", "\" \""),
                REQUESTED.replace("\"batch\":\"A\"", "\"batch\":42"),
                REQUESTED.replace("\"data\"", "\"otherData\""),
                APPROVED.replace("\"instance-a\"", "null"),
                APPROVED.replace("\"instance\"", "\"wrongKey\""),
        }) {
            assertThrows(IllegalArgumentException.class, () -> StructuredReceiptEvent.parse(invalid), invalid);
        }
    }
}
