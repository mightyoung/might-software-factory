package com.mightyoung.softwarefactory.applicationregistry.definition;

import com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.FieldType;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Pure startup catalog. It neither authorizes callers nor invokes registered paths. */
public final class EntityDefinitionCatalog {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final Map<Key, Publication> publications;

    public EntityDefinitionCatalog(Collection<? extends EntityDefinitionProvider> providers) {
        Map<Key, Publication> found = new HashMap<>();
        for (var provider : List.copyOf(providers)) {
            var commands = Set.copyOf(provider.commandBindings());
            var references = Set.copyOf(provider.referenceBindings());
            for (var definition : List.copyOf(provider.definitions())) {
                validate(definition, commands, references);
                var key = new Key(definition.appKey(), definition.entityKey(), definition.definitionVersion());
                if (found.putIfAbsent(key, new Publication(definition, checksum(definition))) != null)
                    throw new IllegalArgumentException("Duplicate entity publication");
            }
        }
        publications = Map.copyOf(found);
    }

    public Publication require(Key key) {
        var result = publications.get(Objects.requireNonNull(key));
        if (result == null) throw new IllegalArgumentException("Unknown entity publication");
        return result;
    }

    public record Key(String appKey, String entityKey, String definitionVersion) { }
    public record Publication(EntityDefinition definition, String checksum) { }

    /** Supplied from the owner's current authorization snapshot, never from request parameters. */
    public record Context(UUID accountId, UUID memberId, UUID tenantId, Map<String, String> authorityVersions) {
        public Context {
            Objects.requireNonNull(accountId); Objects.requireNonNull(memberId); Objects.requireNonNull(tenantId);
            authorityVersions = Map.copyOf(authorityVersions);
            if (authorityVersions.isEmpty()) throw new IllegalArgumentException("Missing authority versions");
            authorityVersions.forEach((key, value) -> { identifier(key); text(value, 128); });
        }
    }

    /**
     * A semantic cache validator only. The owner must authorize and project first on every request,
     * including conditional requests. Possession of this checksum grants no authority.
     */
    public static String semanticChecksum(EffectiveEntityDefinition effective, Context context) {
        return checksum(Map.of("definition", Objects.requireNonNull(effective),
                "context", Objects.requireNonNull(context)));
    }

    private static void validate(EntityDefinition definition, Set<EntityDefinition.CommandBinding> commands,
                                 Set<EntityDefinition.ReferenceBinding> referenceBindings) {
        identifier(definition.appKey()); identifier(definition.entityKey()); version(definition.definitionVersion());
        text(definition.title(), 200);
        var query = definition.querySchema();
        if (!definition.entityKey().equals(query.key())) invalid();
        version(query.schemaVersion());
        if (query.fields().isEmpty() || !definition.fields().keySet().equals(query.fields().keySet())) invalid();
        var id = query.fields().get(query.idField());
        if (id == null || id.nullable() || !id.selectable()) invalid();
        keys(query.defaultFields(), query.fields().keySet());
        for (var key : query.defaultFields()) if (!query.fields().get(key).selectable()) invalid();
        query.fields().forEach((key, field) -> {
            identifier(key); Objects.requireNonNull(field.type());
            if (field.maxLength() < 0 || field.maxLength() > 100_000) invalid();
            if (field.type() != FieldType.ENUM && !field.enumValues().isEmpty()) invalid();
        });
        var layout = definition.layout();
        keys(layout.list(), definition.fields().keySet()); keys(layout.detail(), definition.fields().keySet());
        keys(layout.create(), definition.fields().keySet()); keys(layout.query(), definition.fields().keySet());
        definition.fields().forEach((key, field) -> {
            text(field.label(), 200); Objects.requireNonNull(field.uiKind()); Objects.requireNonNull(field.history());
            var queryField = query.fields().get(key);
            if (!queryField.enumValues().containsAll(field.valueLabels().keySet())) invalid();
            field.valueLabels().values().forEach(label -> text(label, 200));
            if (field.referenceKey() != null && !definition.references().containsKey(field.referenceKey())) invalid();
            if (field.uiKind() == EntityDefinition.UiKind.REFERENCE && field.referenceKey() == null) invalid();
            if (field.edit() != null) {
                var edit = field.edit();
                if (edit.maxLength() < 0 || edit.maxLength() > 100_000) invalid();
                Objects.requireNonNull(edit.lengthUnit());
                if (new HashSet<>(edit.allowedValues()).size() != edit.allowedValues().size()
                        || !queryField.enumValues().containsAll(edit.allowedValues())) invalid();
            } else if (layout.create().contains(key)) invalid();
        });
        for (var key : layout.query()) if (query.fields().get(key).operators().isEmpty()) invalid();
        definition.actions().forEach((key, action) -> {
            qualifiedKey(key); text(action.label(), 200); Objects.requireNonNull(action.position());
            Objects.requireNonNull(action.binding()); qualifiedKey(action.binding().permission()); path(action.binding().path());
            Objects.requireNonNull(action.binding().method());
            if (!commands.contains(action.binding()) || !key.equals(action.binding().permission())) invalid();
            keys(action.inputFields(), definition.fields().keySet());
            for (var input : action.inputFields()) if (definition.fields().get(input).edit() == null) invalid();
            if (action.inputFields().isEmpty() || !action.idempotencyKeyRequired()) invalid();
        });
        definition.references().forEach((key, reference) -> {
            identifier(key); Objects.requireNonNull(reference.binding()); Objects.requireNonNull(reference.history());
            identifier(reference.entityKey()); identifier(reference.binding().resourceKey());
            Objects.requireNonNull(reference.binding().method());
            qualifiedKey(reference.binding().permission()); path(reference.binding().path());
            if (!key.equals(reference.binding().resourceKey()) || !referenceBindings.contains(reference.binding())) invalid();
        });
    }

    private static void keys(List<String> values, Set<String> allowed) {
        if (new HashSet<>(values).size() != values.size() || !allowed.containsAll(values)) invalid();
    }
    private static void identifier(String value) {
        if (value == null || !value.matches("[A-Za-z][A-Za-z0-9_-]{0,63}")) invalid();
    }
    private static void qualifiedKey(String value) {
        if (value == null || !value.matches("[a-z][A-Za-z0-9_-]*(?:\\.[A-Za-z][A-Za-z0-9_-]*)+")) invalid();
    }
    private static void version(String value) { if (value == null || !value.matches("[1-9][0-9]{0,8}")) invalid(); }
    private static void path(String value) {
        if (value == null || !value.matches("/api/v1/[A-Za-z0-9/_-]+")) invalid();
    }
    private static void text(String value, int max) { if (value == null || value.isBlank() || value.length() > max) invalid(); }
    private static void invalid() { throw new IllegalArgumentException("Invalid entity publication"); }

    private static String checksum(Object value) {
        StringBuilder canonical = new StringBuilder();
        canonical(JSON.valueToTree(value), "", canonical);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static void canonical(JsonNode value, String property, StringBuilder out) {
        if (value.isObject()) {
            out.append('{'); List<String> names = new ArrayList<>(); value.propertyNames().forEach(names::add);
            names.sort(Comparator.naturalOrder());
            for (int index = 0; index < names.size(); index++) {
                if (index > 0) out.append(','); String name = names.get(index);
                out.append(JSON.writeValueAsString(name)).append(':'); canonical(value.get(name), name, out);
            }
            out.append('}');
        } else if (value.isArray()) {
            List<JsonNode> items = new ArrayList<>(); value.forEach(items::add);
            if (property.equals("operators") || property.equals("enumValues")) items.sort(Comparator.comparing(JsonNode::toString));
            out.append('[');
            for (int index = 0; index < items.size(); index++) {
                if (index > 0) out.append(','); canonical(items.get(index), "", out);
            }
            out.append(']');
        } else out.append(value);
    }
}
