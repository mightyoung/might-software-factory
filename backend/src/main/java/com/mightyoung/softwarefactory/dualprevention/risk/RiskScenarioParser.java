package com.mightyoung.softwarefactory.dualprevention.risk;

import com.mightyoung.softwarefactory.applicationregistry.definition.EntityDefinition.Use;
import com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.Direction;
import com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.FieldSchema;
import com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.FilterOperator;
import com.mightyoung.softwarefactory.dualprevention.asset.AssetFoundationFailure;
import com.mightyoung.softwarefactory.dualprevention.asset.AssetFoundationRequestParser;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import static com.mightyoung.softwarefactory.dualprevention.risk.RiskScenarioContracts.*;

/** Strict transport parsing only. Identity, scope, cursor authority and SQL remain owner responsibilities. */
public final class RiskScenarioParser {
    private final ObjectMapper json;
    public RiskScenarioParser(ObjectMapper json) { this.json = Objects.requireNonNull(json); }

    public Create create(HttpServletRequest request) {
        var input = body(request, COMMAND_BODY_BYTES);
        fields(input, Set.of("code", "organizationId", "description", "triggeringConditions", "exposureDescription", "consequence", "hazardIds"), Set.of());
        return new Create(string(input.get("code")), uuid(string(input.get("organizationId"))), string(input.get("description")),
                string(input.get("triggeringConditions")), string(input.get("exposureDescription")), string(input.get("consequence")), hazards(input.get("hazardIds")));
    }
    public Update update(HttpServletRequest request) {
        var input = body(request, COMMAND_BODY_BYTES);
        fields(input, Set.of("expectedVersion", "description", "triggeringConditions", "exposureDescription", "consequence", "hazardIds"), Set.of());
        return new Update(safeVersion(input.get("expectedVersion")), string(input.get("description")), string(input.get("triggeringConditions")),
                string(input.get("exposureDescription")), string(input.get("consequence")), hazards(input.get("hazardIds")));
    }
    public UUID idempotencyKey(HttpServletRequest request) {
        var values = Collections.list(request.getHeaders("Idempotency-Key"));
        if (values.size() != 1) throw invalid();
        return uuid(values.getFirst());
    }
    public static UUID uuid(String value) {
        try { return AssetFoundationRequestParser.uuid(value); }
        catch (AssetFoundationFailure failure) { throw invalid(); }
    }
    public static long pathVersion(String value) {
        if (value == null || !value.matches("[1-9][0-9]{0,15}")) throw invalid();
        try { return version(Long.parseLong(value)); } catch (NumberFormatException failure) { throw invalid(); }
    }
    public QueryRequest query(HttpServletRequest request) {
        var input = body(request, QUERY_BODY_BYTES);
        fields(input, Set.of("page", "size"), Set.of("filterAST", "sort", "fields"));
        int size = integer(input.get("size"), 20, 100);
        if (size != 20 && size != 50 && size != 100) throw invalid();
        var page = input.get("page"); fields(page, Set.of(), Set.of("number", "cursor"));
        boolean numbered = page.hasNonNull("number"), cursor = page.hasNonNull("cursor");
        if (numbered == cursor) throw invalid();
        Integer number = numbered ? integer(page.get("number"), 1, 501) : null;
        if (number != null && (long) (number - 1) * size > 10_000) throw invalid();
        String after = cursor ? string(page.get("cursor")) : null;
        if (after != null && (after.isBlank() || after.codePointCount(0, after.length()) > 2048)) throw invalid();
        var schema = RiskScenarioEntitySchema.schema();
        List<String> projection = new ArrayList<>();
        var selected = input.get("fields");
        if (selected == null || selected.isNull()) projection.addAll(schema.defaultFields());
        else {
            if (!selected.isArray() || selected.size() > 50) throw invalid();
            for (var entry : selected) {
                String key = string(entry); var field = schema.fields().get(key);
                if (field == null || !field.selectable() || projection.contains(key)) throw invalid();
                projection.add(key);
            }
        }
        List<Sort> sort = new ArrayList<>(); var ordering = input.get("sort");
        if (ordering != null && !ordering.isNull()) {
            if (!ordering.isArray() || ordering.size() > 3) throw invalid();
            Set<String> seen = new HashSet<>();
            for (var entry : ordering) {
                fields(entry, Set.of("field", "direction"), Set.of());
                String key = string(entry.get("field")); var field = schema.fields().get(key);
                if (field == null || !field.sortable() || !seen.add(key)) throw invalid();
                sort.add(new Sort(key, choice(Direction.class, string(entry.get("direction")))));
            }
        }
        sort.add(new Sort("id", Direction.ASC));
        List<Filter> filters = new ArrayList<>(); var group = input.get("filterAST");
        if (group != null && !group.isNull()) {
            fields(group, Set.of("operator", "conditions"), Set.of());
            if (!string(group.get("operator")).equals("AND") || !group.get("conditions").isArray()
                    || group.get("conditions").size() > 50) throw invalid();
            for (var condition : group.get("conditions")) {
                fields(condition, Set.of("field", "operator"), Set.of("value"));
                String key = string(condition.get("field")); FieldSchema field = schema.fields().get(key);
                var operator = choice(FilterOperator.class, string(condition.get("operator")));
                if (field == null || !field.operators().contains(operator)) throw invalid();
                boolean unary = switch (operator) { case IS_EMPTY, IS_NOT_EMPTY, IS_NULL, IS_NOT_NULL -> true; default -> false; };
                if (unary) {
                    if (condition.has("value")) throw invalid();
                    filters.add(new Filter(key, operator, List.of())); continue;
                }
                var value = condition.get("value"); List<Object> values = new ArrayList<>();
                if (operator == FilterOperator.IN || operator == FilterOperator.BETWEEN) {
                    if (value == null || !value.isArray() || value.isEmpty()
                            || value.size() > (operator == FilterOperator.IN ? 100 : 2)
                            || operator == FilterOperator.BETWEEN && value.size() != 2) throw invalid();
                    for (var element : value) values.add(scalar(field, element));
                } else values.add(scalar(field, value));
                if (operator == FilterOperator.BETWEEN && compare(values.get(0), values.get(1)) > 0) throw invalid();
                filters.add(new Filter(key, operator, values));
            }
        }
        return new QueryRequest(filters, sort, projection, size, number, after);
    }
    public SelectorRequest eligibleHazards(HttpServletRequest request) {
        var input = body(request, SELECTOR_BODY_BYTES);
        fields(input, Set.of("use", "query"), Set.of("organizationId", "scenarioId", "afterId", "limit"));
        var use = choice(SelectorUse.class, string(input.get("use")));
        String anchor = use == SelectorUse.CREATE ? "organizationId" : "scenarioId";
        String forbidden = use == SelectorUse.CREATE ? "scenarioId" : "organizationId";
        if (!input.has(anchor) || input.has(forbidden)) throw invalid();
        UUID id = uuid(string(input.get(anchor)));
        UUID after = input.hasNonNull("afterId") ? uuid(string(input.get("afterId"))) : null;
        int limit = input.has("limit") ? integer(input.get("limit"), 1, 50) : 20;
        return new SelectorRequest(use, use == SelectorUse.CREATE ? id : null, use == SelectorUse.UPDATE ? id : null,
                string(input.get("query")), after, limit);
    }
    public HistoryRequest history(HttpServletRequest request) {
        var parameters = parameters(request, Set.of("pageSize", "afterOccurredAt", "afterCommandId"));
        int size = parameters.containsKey("pageSize") ? boundedDecimal(parameters.get("pageSize"), 1, 50) : 20;
        boolean time = parameters.containsKey("afterOccurredAt"), command = parameters.containsKey("afterCommandId");
        if (time != command) throw invalid();
        return new HistoryRequest(time ? new HistoryCursor(time(parameters.get("afterOccurredAt")), uuid(parameters.get("afterCommandId"))) : null, size);
    }
    public Use definitionUse(HttpServletRequest request) {
        var parameters = parameters(request, Set.of("use"));
        if (!parameters.containsKey("use")) throw invalid();
        return choice(Use.class, parameters.get("use"));
    }
    public void noParameters(HttpServletRequest request) {
        if (request.getQueryString() != null || !parameters(request, Set.of()).isEmpty()) throw invalid();
    }

    private Map<String, String> parameters(HttpServletRequest request, Set<String> allowed) {
        try { if (request.getInputStream().readNBytes(1).length != 0) throw invalid(); }
        catch (java.io.IOException failure) { throw invalid(); }
        var result = new LinkedHashMap<String, String>();
        request.getParameterMap().forEach((name, values) -> {
            if (!allowed.contains(name) || values == null || values.length != 1) throw invalid();
            result.put(name, unicode(values[0]));
        });
        return result;
    }
    private JsonNode body(HttpServletRequest request, int maximum) {
        if (request.getQueryString() != null) throw invalid();
        var types = Collections.list(request.getHeaders("Content-Type"));
        if (types.size() != 1 || !types.getFirst().matches("(?i)application/json(?:[ \\t]*;[ \\t]*charset[ \\t]*=[ \\t]*(?:UTF-8|\"UTF-8\"))?[ \\t]*"))
            throw new Failure(415);
        try {
            byte[] bytes = request.getInputStream().readNBytes(maximum + 1);
            if (bytes.length > maximum) throw new Failure(413);
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            if (text.startsWith("\uFEFF")) throw invalid();
            try (var parser = json.createParser(text)) {
                var containers = new ArrayDeque<Set<String>>(); int depth = 0, roots = 0; JsonToken token;
                while ((token = parser.nextToken()) != null) {
                    if (token == JsonToken.START_OBJECT || token == JsonToken.START_ARRAY) {
                        if (depth == 0 && ++roots > 1 || ++depth > 8) throw invalid();
                        containers.push(token == JsonToken.START_OBJECT ? new HashSet<>() : Collections.emptySet());
                    } else if (token == JsonToken.END_OBJECT || token == JsonToken.END_ARRAY) {
                        if (containers.isEmpty()) throw invalid(); containers.pop(); depth--;
                    } else if (token == JsonToken.PROPERTY_NAME) {
                        if (containers.isEmpty() || !containers.peek().add(parser.currentName())) throw invalid(); unicode(parser.currentName());
                    } else if (token == JsonToken.VALUE_STRING) {
                        if (depth == 0) throw invalid(); unicode(parser.getString());
                    } else if (token == JsonToken.VALUE_NUMBER_FLOAT || token == JsonToken.VALUE_NUMBER_INT) {
                        if (depth == 0 || !parser.getString().matches("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?")) throw invalid();
                    } else if (depth == 0) throw invalid();
                }
            }
            JsonNode input = json.reader(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readTree(text);
            if (input == null || !input.isObject()) throw invalid();
            return input;
        } catch (java.io.IOException | tools.jackson.core.JacksonException failure) { throw invalid(); }
    }
    private static List<UUID> hazards(JsonNode value) {
        if (value == null || !value.isArray() || value.isEmpty() || value.size() > MAX_HAZARDS) throw invalid();
        List<UUID> ids = new ArrayList<>(); for (var item : value) ids.add(uuid(string(item)));
        return canonicalHazards(ids);
    }
    private static long safeVersion(JsonNode value) {
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) throw invalid();
        return version(value.longValue());
    }
    private static int integer(JsonNode value, int minimum, int maximum) {
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) throw invalid();
        int result = value.intValue(); if (result < minimum || result > maximum) throw invalid(); return result;
    }
    private static int boundedDecimal(String value, int minimum, int maximum) {
        if (value == null || !value.matches("[1-9][0-9]*") || value.length() > 9) throw invalid();
        try { int result = Integer.parseInt(value); if (result < minimum || result > maximum) throw invalid(); return result; }
        catch (NumberFormatException failure) { throw invalid(); }
    }
    private static String string(JsonNode value) {
        if (value == null || !value.isString()) throw invalid(); return unicode(value.stringValue());
    }
    private static void fields(JsonNode input, Set<String> required, Set<String> optional) {
        if (input == null || !input.isObject()) throw invalid();
        var found = new HashSet<String>();
        input.properties().forEach(entry -> {
            if (!required.contains(entry.getKey()) && !optional.contains(entry.getKey())) throw invalid(); found.add(entry.getKey());
        });
        if (!found.containsAll(required)) throw invalid();
    }
    private static Instant time(String value) {
        if (value == null || !value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\\.[0-9]{1,6})?(?:Z|[+-][0-9]{2}:[0-9]{2})")) throw invalid();
        try { return instant(OffsetDateTime.parse(value).toInstant()); }
        catch (java.time.DateTimeException failure) { throw invalid(); }
    }
    private static Object scalar(FieldSchema field, JsonNode value) {
        if (value == null || value.isNull()) throw invalid();
        return switch (field.type()) {
            case UUID -> uuid(string(value));
            case TEXT -> {
                String result = string(value);
                if (result.codePointCount(0, result.length()) > field.maxLength()) throw invalid(); yield result;
            }
            case ENUM -> {
                String result = string(value); if (!field.enumValues().contains(result)) throw invalid(); yield result;
            }
            case DATETIME -> time(string(value)).atOffset(ZoneOffset.UTC);
            case NUMBER -> {
                if (!value.isNumber()) throw invalid();
                try {
                    BigDecimal number = value.decimalValue();
                    if (number.abs().compareTo(BigDecimal.valueOf(MAX_VERSION)) > 0) throw invalid();
                    yield number.signum() == 0 ? BigDecimal.ZERO : number.stripTrailingZeros();
                } catch (NumberFormatException | ArithmeticException failure) { throw invalid(); }
            }
            default -> throw invalid();
        };
    }
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static int compare(Object left, Object right) { return ((Comparable) left).compareTo(right); }
    private static <T extends Enum<T>> T choice(Class<T> type, String value) {
        try { return Enum.valueOf(type, value); } catch (IllegalArgumentException | NullPointerException failure) { throw invalid(); }
    }
}
