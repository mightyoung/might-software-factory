package com.mightyoung.softwarefactory.auditops;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import static com.mightyoung.softwarefactory.auditops.TenantAuditQueryContracts.*;

/** Strict request parsing only; the service supplies the authorized observedAt and effective window. */
public final class TenantAuditQueryParser {
    private static final Set<String> FIELDS = Set.of("from", "to", "actorId", "resourceType", "resourceId", "action", "result", "pageSize", "cursor");
    // A protocol-owned default mapper prevents application-wide permissive JSON options changing this grammar.
    private final ObjectMapper json = JsonMapper.builder().build();

    public Query query(HttpServletRequest request) {
        if (request == null || request.getQueryString() != null) throw invalid();
        media(request);
        JsonNode input = body(request);
        fields(input, Set.of(), FIELDS);
        Cursor cursor = null;
        if (input.has("cursor")) {
            var value = input.get("cursor");
            fields(value, Set.of("occurredAt", "id", "queryFingerprint"), Set.of());
            cursor = new Cursor(instant(string(value.get("occurredAt"))), uuid(string(value.get("id"))), string(value.get("queryFingerprint")));
        }
        Result result = null;
        if (input.has("result")) {
            try { result = Result.valueOf(string(input.get("result"))); }
            catch (IllegalArgumentException failure) { throw invalid(); }
        }
        return new Query(input.has("from") ? instant(string(input.get("from"))) : null,
                input.has("to") ? instant(string(input.get("to"))) : null,
                input.has("actorId") ? uuid(string(input.get("actorId"))) : null,
                input.has("resourceType") ? string(input.get("resourceType")) : null,
                input.has("resourceId") ? uuid(string(input.get("resourceId"))) : null,
                input.has("action") ? string(input.get("action")) : null,
                result, input.has("pageSize") ? pageSize(input.get("pageSize")) : 20, cursor);
    }

    private static void media(HttpServletRequest request) {
        var types = request.getHeaders("Content-Type");
        if (types == null || !types.hasMoreElements()) throw new Failure(Code.UNSUPPORTED_MEDIA_TYPE);
        String type = types.nextElement();
        if (types.hasMoreElements() || type == null || type.length() > 128
                || !type.matches("(?i)application/json(?:[ \\t]*;[ \\t]*charset[ \\t]*=[ \\t]*(?:UTF-8|\"UTF-8\"))?[ \\t]*"))
            throw new Failure(Code.UNSUPPORTED_MEDIA_TYPE);
        var encodings = request.getHeaders("Content-Encoding");
        if (encodings != null && encodings.hasMoreElements()) throw new Failure(Code.UNSUPPORTED_MEDIA_TYPE);
    }
    private JsonNode body(HttpServletRequest request) {
        try {
            byte[] bytes = request.getInputStream().readNBytes(REQUEST_BYTES + 1);
            if (bytes.length > REQUEST_BYTES) throw new Failure(Code.REQUEST_TOO_LARGE);
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            if (text.startsWith("\uFEFF")) throw invalid();
            try (var parser = json.createParser(text)) {
                var containers = new ArrayDeque<Set<String>>(); int depth = 0; int roots = 0; JsonToken token;
                while ((token = parser.nextToken()) != null) {
                    if (token == JsonToken.START_OBJECT || token == JsonToken.START_ARRAY) {
                        if (depth == 0 && ++roots > 1 || ++depth > 4) throw invalid();
                        containers.push(token == JsonToken.START_OBJECT ? new HashSet<>() : Collections.emptySet());
                    } else if (token == JsonToken.END_OBJECT || token == JsonToken.END_ARRAY) {
                        if (containers.isEmpty()) throw invalid(); containers.pop(); depth--;
                    } else if (token == JsonToken.PROPERTY_NAME) {
                        if (containers.isEmpty() || !containers.peek().add(parser.currentName())) throw invalid();
                        unicode(parser.currentName());
                    } else if (token == JsonToken.VALUE_STRING) {
                        if (depth == 0) throw invalid(); unicode(parser.getString());
                    } else if (depth == 0) throw invalid();
                }
                if (roots != 1 || depth != 0) throw invalid();
            }
            JsonNode value = json.readTree(text);
            if (value == null || !value.isObject()) throw invalid();
            return value;
        } catch (IOException | tools.jackson.core.JacksonException failure) { throw invalid(); }
    }
    private static int pageSize(JsonNode value) {
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) throw invalid();
        int size = value.intValue(); if (size < 1 || size > MAX_PAGE_SIZE) throw invalid(); return size;
    }
    private static UUID uuid(String value) {
        if (!value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) throw invalid();
        try { UUID result = UUID.fromString(value); if (!result.toString().equals(value)) throw invalid(); return result; }
        catch (IllegalArgumentException failure) { throw invalid(); }
    }
    /** UTC only: uppercase Z or +00:00, seconds with optional 1–6 fractional digits, years 0001–9999. */
    private static Instant instant(String value) {
        if (!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\\.[0-9]{1,6})?(?:Z|\\+00:00)")) throw invalid();
        try { return time(OffsetDateTime.parse(value).toInstant()); }
        catch (java.time.DateTimeException failure) { throw invalid(); }
    }
    private static String string(JsonNode value) {
        if (value == null || !value.isString()) throw invalid(); return unicode(value.stringValue());
    }
    private static String unicode(String value) {
        if (value == null) throw invalid();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == 0 || Character.isLowSurrogate(c)) throw invalid();
            if (Character.isHighSurrogate(c)) {
                if (i + 1 == value.length() || !Character.isLowSurrogate(value.charAt(i + 1))) throw invalid();
                i++;
            }
        }
        return value;
    }
    private static void fields(JsonNode value, Set<String> required, Set<String> optional) {
        if (value == null || !value.isObject()) throw invalid();
        var found = new HashSet<String>();
        for (var entry : value.properties()) {
            if (!required.contains(entry.getKey()) && !optional.contains(entry.getKey())) throw invalid();
            found.add(entry.getKey());
        }
        if (!found.containsAll(required)) throw invalid();
    }
}
