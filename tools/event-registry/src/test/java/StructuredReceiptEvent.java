import java.net.URI;
import java.util.Map;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

// A test-only bridge for these two synthetic CloudEvents types, not an ingress API.
record StructuredReceiptEvent(Identity identity, Map<String, String> flowableFields) {
    private static final ObjectMapper JSON = new ObjectMapper();

    record Identity(String source, String id) {}

    static StructuredReceiptEvent parse(String json) {
        JsonNode event;
        try {
            event = JSON.readTree(json);
        } catch (JacksonException error) {
            throw new IllegalArgumentException("Expected a structured JSON event", error);
        }
        if (event == null || !event.isObject() || !"1.0".equals(text(event, "specversion"))) {
            throw new IllegalArgumentException("Expected CloudEvents specversion 1.0");
        }
        String type = text(event, "type");
        String source = text(event, "source");
        URI.create(source); // Validate the URI reference without changing the identity string.
        var identity = new Identity(source, text(event, "id"));
        var data = event.get("data");
        if (data == null || !data.isObject()) throw new IllegalArgumentException("Expected object data");
        return new StructuredReceiptEvent(identity, switch (type) {
            case "com.mightyoung.probe.receipt.requested" ->
                    Map.of("type", "receiptRequested", "batch", text(data, "batch"));
            case "com.mightyoung.probe.batch.approved" ->
                    Map.of("type", "batchApproved", "batch", text(data, "batch"), "instance", text(data, "instance"));
            default -> throw new IllegalArgumentException("Unsupported fixture event type");
        });
    }

    private static String text(JsonNode object, String key) {
        var field = object.get(key);
        if (field == null || !field.isString() || field.stringValue().isBlank()) {
            throw new IllegalArgumentException("Expected non-blank text: " + key);
        }
        return field.stringValue();
    }
}
