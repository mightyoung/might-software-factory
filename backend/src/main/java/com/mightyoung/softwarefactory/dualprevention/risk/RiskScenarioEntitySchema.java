package com.mightyoung.softwarefactory.dualprevention.risk;

import com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.EntitySchema;
import com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.FieldSchema;
import com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.FieldType;
import com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.FilterOperator;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.FilterOperator.*;

/** The sole immutable risk query-field publication. Relationships are never query fields. */
public final class RiskScenarioEntitySchema {
    public static final List<String> FIELD_KEYS = List.of("id", "organizationId", "code", "version", "description",
            "triggeringConditions", "exposureDescription", "consequence", "createdAt", "updatedAt", "assessmentStatus");
    public static final Set<String> SORTABLE_FIELDS = Set.of("code", "version", "createdAt", "updatedAt", "organizationId");
    private static final EntitySchema SCHEMA = build();
    private RiskScenarioEntitySchema() { }
    public static EntitySchema schema() { return SCHEMA; }

    private static EntitySchema build() {
        Map<String, FieldSchema> fields = new LinkedHashMap<>();
        for (String key : FIELD_KEYS) {
            FieldType type = switch (key) {
                case "id", "organizationId" -> FieldType.UUID;
                case "version" -> FieldType.NUMBER;
                case "createdAt", "updatedAt" -> FieldType.DATETIME;
                case "assessmentStatus" -> FieldType.ENUM;
                default -> FieldType.TEXT;
            };
            List<FilterOperator> allowed = switch (type) {
                case UUID, ENUM -> List.of(EQ, IN);
                case NUMBER, DATETIME -> List.of(EQ, GT, GTE, LT, LTE, BETWEEN);
                case TEXT -> List.of(EQ, CONTAINS, IS_EMPTY, IS_NOT_EMPTY);
                default -> throw new IllegalStateException("Unregistered risk field type");
            };
            int maximum = type == FieldType.TEXT ? (key.equals("code") ? 64 : RiskScenarioContracts.MAX_NARRATIVE_CODE_POINTS) : 0;
            Set<String> values = type == FieldType.ENUM ? Set.of("UNASSESSED") : Set.of();
            fields.put(key, new FieldSchema(type, false, true, SORTABLE_FIELDS.contains(key),
                    Collections.unmodifiableSet(new LinkedHashSet<>(allowed)), maximum, values));
        }
        return new EntitySchema(RiskScenarioContracts.ENTITY_KEY, RiskScenarioContracts.SCHEMA_VERSION,
                "id", List.of("id"), Collections.unmodifiableMap(fields));
    }
}
