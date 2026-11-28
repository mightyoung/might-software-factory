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

/** Dormant allowlisted Studio v1 decoder. No tenant, transport or storage. */
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
        fields(input, Set.of("cameraTour", "hudWidgets"), "snapshotVersion", "scene", "floorplan");
        integer(input.get("snapshotVersion"), 1, 1);
        JsonNode scene = input.get("scene"), floor = input.get("floorplan");
        fields(scene, "schemaVersion", "screenId", "devices");
        integer(scene.get("schemaVersion"), 1, 1);
        String screen = identifier(scene.get("screenId"));
        JsonNode devices = array(scene.get("devices"), 0, 500);
        ObjectNode canonical = JSON.createObjectNode().put("snapshotVersion", 1);
        var canonicalDevices = canonical.putObject("scene").put("schemaVersion", 1).put("screenId", screen).putArray("devices");
        var deviceIds = new HashSet<String>();
        int parametric = 0;
        for (var device : devices) {
            boolean equipment = device.has("equipment");
            if (equipment) {
                fields(device, "id", "label", "position", "state", "equipment");
                if (++parametric > 24) throw invalid();
            } else fields(device, "id", "label", "position", "state", "primitive", "size");
            String id = identifier(device.get("id"));
            if (!deviceIds.add(id)) throw invalid();
            var target = canonicalDevices.addObject().put("id", id).put("label", label(device.get("label"), 80));
            vector(device.get("position"), target.putArray("position"), "-1000", "1000");
            target.put("state", choice(device.get("state"), Set.of("normal", "warning", "alarm")));
            if (equipment) equipment(device.get("equipment"), target.putObject("equipment"));
            else {
                target.put("primitive", choice(device.get("primitive"), Set.of("box", "cylinder")));
                vector(device.get("size"), target.putArray("size"), "0.05", "100");
            }
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

        if (input.has("cameraTour")) {
            var tour = canonical.putArray("cameraTour");
            var visited = new HashSet<String>();
            for (var point : array(input.get("cameraTour"), 0, 20)) {
                fields(point, "deviceId", "label", "dwellSeconds");
                String deviceId = identifier(point.get("deviceId"));
                if (!deviceIds.contains(deviceId) || !visited.add(deviceId)) throw invalid();
                tour.addObject().put("deviceId", deviceId).put("label", label(point.get("label"), 60))
                        .put("dwellSeconds", integer(point.get("dwellSeconds"), 1, 30));
            }
        }
        if (input.has("hudWidgets")) {
            var hud = canonical.putArray("hudWidgets");
            var shown = new HashSet<String>();
            for (var item : array(input.get("hudWidgets"), 0, 4)) {
                String key = choice(item, Set.of("devices", "alarms", "walls", "zones"));
                if (!shown.add(key)) throw invalid();
                hud.add(key);
            }
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

    private static void equipment(JsonNode value, ObjectNode target) {
        fields(value, Set.of("rotationYRad"), "kind", "dimensions");
        target.put("kind", choice(value.get("kind"), Set.of("CONVEYOR", "CORRIDOR", "ROBOT_ARM", "SPIRAL_CONVEYOR")));
        var dimensions = value.get("dimensions");
        fields(dimensions, "length", "width", "height");
        target.putObject("dimensions")
                .put("length", decimal(dimensions.get("length"), "0.2", "36"))
                .put("width", decimal(dimensions.get("width"), "0.2", "12"))
                .put("height", decimal(dimensions.get("height"), "0.2", "12"));
        target.put("rotationYRad", value.has("rotationYRad")
                ? decimal(value.get("rotationYRad"), "-6.283185307179586", "6.283185307179586") : BigDecimal.ZERO);
    }

    private static void vector(JsonNode node, ArrayNode target, String minimum, String maximum) {
        for (var element : array(node, 3, 3)) target.add(decimal(element, minimum, maximum));
    }

    private static BigDecimal decimal(JsonNode element, String minimum, String maximum) {
        if (!element.isNumber()) throw invalid();
        var number = element.decimalValue().stripTrailingZeros();
        if (number.scale() > 24 || number.compareTo(new BigDecimal(minimum)) < 0 ||
                number.compareTo(new BigDecimal(maximum)) > 0) throw invalid();
        return number.scale() < 0 ? number.setScale(0) : number;
    }

    private static String label(JsonNode node, int limit) {
        String label = LABEL_TRIM.matcher(text(node)).replaceAll("");
        if (label.isEmpty() || label.length() > limit || !StandardCharsets.UTF_8.newEncoder().canEncode(label)) throw invalid();
        return label;
    }

    private static void fields(JsonNode node, String... names) {
        fields(node, Set.of(), names);
    }

    private static void fields(JsonNode node, Set<String> optionals, String... names) {
        if (node == null || !node.isObject()) throw invalid();
        int count = names.length;
        for (var name : names) if (!node.has(name)) throw invalid();
        for (var optional : optionals) if (node.has(optional)) count++;
        if (node.size() != count) throw invalid();
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
