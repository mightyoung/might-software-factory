package com.mightyoung.softwarefactory.applicationregistry.definition;

import com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.EntitySchema;
import com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.FieldSchema;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Publication data, never a permission decision or dynamic command dispatcher. */
public record EntityDefinition(
        String appKey, String entityKey, String definitionVersion, String title,
        EntitySchema querySchema, Map<String, Field> fields, Layout layout,
        Map<String, Action> actions, Map<String, Reference> references) {
    public EntityDefinition {
        Objects.requireNonNull(querySchema);
        Map<String, FieldSchema> queryFields = new LinkedHashMap<>();
        querySchema.fields().forEach((key, value) -> queryFields.put(key, new FieldSchema(
                value.type(), value.nullable(), value.selectable(), value.sortable(),
                Collections.unmodifiableSet(new LinkedHashSet<>(value.operators())), value.maxLength(),
                Collections.unmodifiableSet(new LinkedHashSet<>(value.enumValues())))));
        querySchema = new EntitySchema(querySchema.key(), querySchema.schemaVersion(), querySchema.idField(),
                List.copyOf(querySchema.defaultFields()), Collections.unmodifiableMap(queryFields));
        fields = Map.copyOf(fields);
        Objects.requireNonNull(layout);
        actions = Map.copyOf(actions);
        references = Map.copyOf(references);
    }

    public enum Use { READ, CREATE }
    public enum UiKind { TEXT, MULTILINE_TEXT, DATE_TIME, BOOLEAN, ENUM, REFERENCE, STATUS, NUMBER }
    public enum History { CURRENT, SNAPSHOT }
    public enum LengthUnit { UTF16_CODE_UNITS, UNICODE_CODE_POINTS }
    public enum Position { CREATE }
    public enum Method { POST }

    public record Field(String label, UiKind uiKind, History history, String referenceKey,
                        Map<String, String> valueLabels, Edit edit) {
        public Field { valueLabels = Map.copyOf(valueLabels); }
    }

    /**
     * Accepted input constraints, including fields not rendered by this desktop form.
     * Layout.create alone selects controls; these hints do not authorize writes or replace validators.
     */
    public record Edit(boolean required, int maxLength, LengthUnit lengthUnit, List<String> allowedValues) {
        public Edit { allowedValues = List.copyOf(allowedValues); }
    }

    /** Ordered visible fields; create is a presentation subset of the command's accepted transport. */
    public record Layout(List<String> list, List<String> detail, List<String> create, List<String> query) {
        public Layout {
            list = List.copyOf(list); detail = List.copyOf(detail);
            create = List.copyOf(create); query = List.copyOf(query);
        }
    }

    public record CommandBinding(Method method, String permission, String path) { }
    /** inputFields names the aggregate's accepted transport, not a promise of rendered controls. */
    public record Action(String label, Position position, CommandBinding binding, boolean expectedVersionRequired,
                         boolean idempotencyKeyRequired, List<String> inputFields) {
        public Action { inputFields = List.copyOf(inputFields); }
    }

    public record ReferenceBinding(String resourceKey, Method method, String permission, String path) { }
    public record Reference(ReferenceBinding binding, String entityKey, History history) { }
}
