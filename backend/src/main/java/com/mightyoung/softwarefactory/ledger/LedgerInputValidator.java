package com.mightyoung.softwarefactory.ledger;

import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** Server-side standard input validation, independent of selectable/query fields and UI layout. */
public final class LedgerInputValidator {
    public static final Set<String> CORE_PROTECTED = Set.of("tenantId", "tenant_id", "id", "version", "actorId", "actor_id",
            "accountId", "memberId", "createdAt", "updatedAt", "createdByActorId", "updatedByActorId",
            "created_at", "updated_at", "created_by_actor_id", "updated_by_actor_id", "commandId", "eventId");
    private static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;

    public Map<String,Object> validate(LedgerBinding binding, LedgerDefinition.Use use,
                                      String definitionVersion, Map<String,JsonNode> supplied) {
        if (use == null || use == LedgerDefinition.Use.READ) throw invalid("values", "invalid_use");
        if (!binding.definition().definitionVersion().equals(definitionVersion)) throw invalid("definitionVersion", "definition_conflict");
        if (supplied == null || supplied.size() > 64) throw invalid("values", "invalid_object");
        var accepted = binding.definitionFor(use).inputFields();
        for (String key : supplied.keySet()) {
            if (key == null) throw invalid("values", "unknown_field");
            if (CORE_PROTECTED.contains(key) || binding.storage().protectedFields().contains(key)
                    || binding.storage().derivedFields().contains(key) || binding.storage().createDefaults().containsKey(key)) {
                throw invalid(key, "protected_field");
            }
            if (!accepted.containsKey(key) || !accepted.get(key).uses().contains(use)) throw invalid(key, "unknown_field");
        }
        Map<String,Object> values = new LinkedHashMap<>();
        for (var entry : accepted.entrySet()) {
            var field = entry.getValue();
            if (!field.uses().contains(use)) continue;
            JsonNode value = supplied.get(entry.getKey());
            if (value == null || value.isNull()) {
                if (field.required()) throw invalid(entry.getKey(), "required");
                values.put(entry.getKey(), null);
            } else values.put(entry.getKey(), convert(entry.getKey(), field, value));
        }
        return Collections.unmodifiableMap(values);
    }

    private Object convert(String key, LedgerDefinition.InputField field, JsonNode value) {
        try {
            return switch (field.type()) {
                case TEXT, MULTILINE_TEXT, ENUM -> text(key, field, value);
                case UUID, REFERENCE -> {
                    String text = rawText(key, value); UUID id = UUID.fromString(text);
                    if (!id.toString().equalsIgnoreCase(text)) throw invalid(key,"invalid_uuid");
                    yield id;
                }
                case INTEGER -> {
                    if (!value.isIntegralNumber() || !value.canConvertToLong()) throw invalid(key,"invalid_integer");
                    long number=value.longValue();
                    if (number < -MAX_SAFE_INTEGER || number > MAX_SAFE_INTEGER) throw invalid(key,"integer_range");
                    yield number;
                }
                case DATE_TIME -> {
                    var time=OffsetDateTime.parse(rawText(key,value));
                    if (time.getNano()%1000!=0) throw invalid(key,"timestamp_precision");
                    yield time.toInstant();
                }
            };
        } catch (LedgerInputException rejected) { throw rejected; }
        catch (IllegalArgumentException | java.time.DateTimeException invalid) { throw invalid(key,"invalid_type"); }
    }
    private static String text(String key, LedgerDefinition.InputField field, JsonNode value) {
        String text=rawText(key,value).strip();
        if (text.indexOf('\0')>=0 || text.codePoints().anyMatch(cp -> cp>=0xD800 && cp<=0xDFFF)) throw invalid(key,"invalid_text");
        if (field.required() && text.isBlank()) throw invalid(key,"required");
        if (field.maxLength()>0 && text.codePointCount(0,text.length())>field.maxLength()) throw invalid(key,"max_length");
        if (!field.allowedValues().isEmpty() && !field.allowedValues().contains(text)) throw invalid(key,"invalid_enum");
        return text;
    }
    private static String rawText(String key, JsonNode value) {
        if (!value.isString()) throw invalid(key,"invalid_string");
        return value.stringValue();
    }
    private static LedgerInputException invalid(String field, String reason) { return new LedgerInputException(field,reason); }
}
