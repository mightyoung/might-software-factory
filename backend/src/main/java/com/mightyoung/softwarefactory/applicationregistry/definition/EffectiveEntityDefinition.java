package com.mightyoung.softwarefactory.applicationregistry.definition;

import com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.FieldSchema;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;

/** Owner-projected wire data; CREATE does not implicitly publish the full ledger schema. */
public record EffectiveEntityDefinition(
        String appKey, String entityKey, String definitionVersion, String querySchemaVersion,
        EntityDefinition.Use use, String title, String publicationChecksum,
        Map<String, Field> fields, EntityDefinition.Layout layout,
        Map<String, EntityDefinition.Action> actions, Map<String, EntityDefinition.Reference> references) {
    public EffectiveEntityDefinition {
        Objects.requireNonNull(use); Objects.requireNonNull(layout);
        fields = Map.copyOf(fields); actions = Map.copyOf(actions); references = Map.copyOf(references);
    }
    public record Field(FieldSchema query, EntityDefinition.Field presentation) {
        public Field {
            Objects.requireNonNull(query); Objects.requireNonNull(presentation);
            query = new FieldSchema(query.type(), query.nullable(), query.selectable(), query.sortable(),
                    Collections.unmodifiableSet(new LinkedHashSet<>(query.operators())), query.maxLength(),
                    Collections.unmodifiableSet(new LinkedHashSet<>(query.enumValues())));
        }
    }
}
