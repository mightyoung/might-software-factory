package com.mightyoung.softwarefactory.platformadmin.digitaltwin;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import static com.mightyoung.softwarefactory.platformadmin.digitaltwin.StudioPublicationPolicy.*;

/** Dormant decoder for walls, zones and primitive devices only. No tenant, transport or storage. */
public final class StudioDraftDecoder {
    private static final int MAX_BYTES = 256 * 1024;
    private static final Pattern ID = Pattern.compile("[a-z][a-z0-9_-]{0,63}");
    // ECMAScript String.trim matches the browser's label handling, including NBSP and BOM.
    private static final Pattern LABEL_TRIM = Pattern.compile("^[\\s\\u00a0\\u1680\\u2000-\\u200a\\u2028\\u2029\\u202f\\u205f\\u3000\\ufeff]+|[\\s\\u00a0\\u1680\\u2000-\\u200a\\u2028\\u2029\\u202f\\u205f\\u3000\\ufeff]+$");
    private static final JsonMapper JSON = JsonMapper.builder(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(8)
                    .maxTokenCount(24_000).maxStringLength(256).maxNameLength(24).maxNumberLength(48).build())
            .build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS, DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .build();

    private StudioDraftDecoder() {}

    public static final class Decoded {
        private final DraftFacts facts;
        private final byte[] bytes;
        private Decoded(DraftFacts facts, byte[] bytes) { this.facts = facts; this.bytes = bytes; }
        public DraftFacts facts() { return facts; }
        public byte[] canonicalBytes() { return bytes.clone(); }
    }

    public static Decoded decode(byte[] source) {
        if (source == null || source.length == 0 || source.length > MAX_BYTES) throw invalid();
        try {
            String input = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(source)).toString();
            return validate(JSON.readTree(input));
        } catch (CharacterCodingException | JacksonException | ArithmeticException malformed) {
            throw invalid();
        }
    }

    private static Decoded validate(JsonNode input) {
        fields(input, "snapshotVersion", "scene", "floorplan");
        integer(input.get("snapshotVersion"), 1, 1);
        JsonNode scene = input.get("scene"), floor = input.get("floorplan");
        fields(scene, "schemaVersion", "screenId", "devices");
        integer(scene.get("schemaVersion"), 1, 1);
        String screen = identifier(scene.get("screenId"));
        JsonNode devices = array(scene.get("devices"), 0, 500);
        ObjectNode canonical = JSON.createObjectNode().put("snapshotVersion", 1);
        var canonicalDevices = canonical.putObject("scene").put("schemaVersion", 1).put("screenId", screen).putArray("devices");
        var deviceIds = new HashSet<String>();
        for (var device : devices) {
            fields(device, "id", "label", "position", "state", "primitive", "size");
            String id = identifier(device.get("id"));
            if (!deviceIds.add(id)) throw invalid();
            String label = LABEL_TRIM.matcher(text(device.get("label"))).replaceAll("");
            if (label.isEmpty() || label.length() > 80 || !StandardCharsets.UTF_8.newEncoder().canEncode(label)) throw invalid();
            var target = canonicalDevices.addObject().put("id", id).put("label", label);
            vector(device.get("position"), target.putArray("position"), "-1000", "1000");
            target.put("state", choice(device.get("state"), Set.of("normal", "warning", "alarm")))
                    .put("primitive", choice(device.get("primitive"), Set.of("box", "cylinder")));
            vector(device.get("size"), target.putArray("size"), "0.05", "100");
        }
        fields(floor, "walls", "zones");
        var walls = new ArrayList<Wall>();
        var zones = new ArrayList<Zone>();
        var canonicalFloor = canonical.putObject("floorplan");
        var canonicalWalls = canonicalFloor.putArray("walls");
        for (var wall : array(floor.get("walls"), 0, 100)) {
            fields(wall, "id", "start", "end");
            int id = integer(wall.get("id"), 1, 10_000);
            var entry = canonicalWalls.addObject().put("id", id);
            walls.add(new Wall(id, point(wall.get("start"), entry.putArray("start")), point(wall.get("end"), entry.putArray("end"))));
        }
        var canonicalZones = canonicalFloor.putArray("zones");
        for (var zone : array(floor.get("zones"), 0, 20)) {
            fields(zone, "id", "points");
            int id = integer(zone.get("id"), 1, 10_000);
            var points = new ArrayList<Point>();
            var canonicalPoints = canonicalZones.addObject().put("id", id).putArray("points");
            for (var item : array(zone.get("points"), 3, 60)) points.add(point(item, canonicalPoints.addArray()));
            zones.add(new Zone(id, points));
        }

        var bytes = JSON.writeValueAsBytes(canonical);
        if (bytes.length > MAX_BYTES) throw invalid();
        var facts = new DraftFacts(screen, sha256(bytes), devices.size(), walls, zones);
        StudioPublicationPolicy.metrics(facts); // Same geometry rules as the eventual publication decision.
        return new Decoded(facts, bytes);
    }

    private static Point point(JsonNode node, ArrayNode target) {
        array(node, 2, 2);
        int x = integer(node.get(0), -100, 100), z = integer(node.get(1), -100, 100);
        target.add(x).add(z);
        return new Point(x, z);
    }

    private static void vector(JsonNode node, ArrayNode target, String minimum, String maximum) {
        var min = new BigDecimal(minimum);
        var max = new BigDecimal(maximum);
        for (var element : array(node, 3, 3)) {
            if (!element.isNumber()) throw invalid();
            var number = element.decimalValue().stripTrailingZeros();
            if (number.scale() > 24 || number.compareTo(min) < 0 || number.compareTo(max) > 0) throw invalid();
            target.add(number.scale() < 0 ? number.setScale(0) : number);
        }
    }

    private static void fields(JsonNode node, String... names) {
        if (node == null || !node.isObject() || node.size() != names.length) throw invalid();
        for (var name : names) if (!node.has(name)) throw invalid();
    }

    private static JsonNode array(JsonNode node, int min, int max) {
        if (!node.isArray() || node.size() < min || node.size() > max) throw invalid();
        return node;
    }

    private static int integer(JsonNode node, int min, int max) {
        if (!node.isIntegralNumber() || !node.canConvertToInt() || node.intValue() < min || node.intValue() > max) throw invalid();
        return node.intValue();
    }

    private static String identifier(JsonNode node) {
        var value = text(node);
        if (!ID.matcher(value).matches()) throw invalid();
        return value;
    }

    private static String choice(JsonNode node, Set<String> allowed) {
        var value = text(node);
        if (!allowed.contains(value)) throw invalid();
        return value;
    }

    private static String text(JsonNode node) {
        if (!node.isString()) throw invalid();
        return node.stringValue();
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 unavailable", unavailable);
        }
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Unsupported or invalid Studio v1 draft");
    }
}
