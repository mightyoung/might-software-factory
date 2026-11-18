package com.mightyoung.softwarefactory.ledger;

import com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.EntitySchema;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashMap;
import com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.FieldSchema;

/** Immutable publication data only. SQL identifiers and policy objects never cross the wire. */
public record LedgerDefinition(String appKey, String entityKey, String definitionVersion, String title,
        Map<String, Field> fields, Map<String, InputField> inputFields, EntitySchema querySchema,
        Layout layout, Actions actions, Map<String, Reference> references) {
    public LedgerDefinition {
        fields = Map.copyOf(fields); inputFields = Map.copyOf(inputFields); references = Map.copyOf(references);
        Map<String,FieldSchema> queryFields=new LinkedHashMap<>();
        querySchema.fields().forEach((key,value)->queryFields.put(key,new FieldSchema(value.type(),value.nullable(),value.selectable(),
                value.sortable(),Set.copyOf(value.operators()),value.maxLength(),Set.copyOf(value.enumValues()))));
        querySchema=new EntitySchema(querySchema.key(),querySchema.schemaVersion(),querySchema.idField(),List.copyOf(querySchema.defaultFields()),Map.copyOf(queryFields));
    }
    public enum Use { READ, CREATE, EDIT }
    public enum Type { TEXT, MULTILINE_TEXT, UUID, INTEGER, DATE_TIME, ENUM, REFERENCE }
    public record Field(String label, Type type, boolean nullable, Map<String,String> valueLabels) {
        public Field { valueLabels = Map.copyOf(valueLabels); }
    }
    /** Accepted input is independent of selectable/query fields. Required applies to the full create/update form. */
    public record InputField(String label, Type type, boolean required, int maxLength,
            List<String> allowedValues, String referenceKey, Set<Use> uses) {
        public InputField { allowedValues = List.copyOf(allowedValues); uses = Set.copyOf(uses); }
    }
    public record Layout(List<String> list, List<String> detail, List<String> create, List<String> edit, List<String> query) {
        public Layout { list=List.copyOf(list); detail=List.copyOf(detail); create=List.copyOf(create); edit=List.copyOf(edit); query=List.copyOf(query); }
    }
    public record Action(String label, String permission) { }
    public record Actions(Action read, Action create, Action update) { }
    public record Reference(String label) { }
}
