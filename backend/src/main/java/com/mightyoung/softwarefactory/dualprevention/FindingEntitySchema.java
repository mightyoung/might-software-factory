package com.mightyoung.softwarefactory.dualprevention;

import com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.EntitySchema;
import com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.FieldSchema;
import com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.FieldType;
import com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.FilterOperator;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.FilterOperator.BETWEEN;
import static com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.FilterOperator.CONTAINS;
import static com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.FilterOperator.EQ;
import static com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.FilterOperator.GT;
import static com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.FilterOperator.GTE;
import static com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.FilterOperator.IN;
import static com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.FilterOperator.IS_NOT_NULL;
import static com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.FilterOperator.IS_NULL;
import static com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.FilterOperator.LT;
import static com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.FilterOperator.LTE;

/** Finding v4 field allowlist shared by schema discovery, query validation, and SQL projection. */
@Component
public final class FindingEntitySchema {

    public static final String SCHEMA_VERSION = "4";
    public static final List<String> DEFAULT_FIELDS =
            List.of("id", "code", "organizationId", "organizationName", "description", "discoveredAt",
                    "sourceCategory", "urgency", "location", "interimMeasure", "backfill",
                    "sourceEventId", "riskId", "relatedFindingId", "grade", "state", "roundNo", "createdAt");

    private final EntitySchema schema;

    public FindingEntitySchema() {
        Map<String, FieldSchema> fields = new LinkedHashMap<>();
        fields.put("id", field(FieldType.UUID, false, true, 0, List.of(EQ, IN), List.of()));
        fields.put("code", field(FieldType.TEXT, false, true, 64, List.of(EQ, CONTAINS), List.of()));
        fields.put("organizationId", field(FieldType.UUID, false, false, 0, List.of(), List.of()));
        fields.put("organizationName", field(FieldType.TEXT, true, false, 0, List.of(), List.of()));
        fields.put("description", field(FieldType.TEXT, false, false, 0, List.of(), List.of()));
        fields.put("discoveredAt", field(FieldType.DATETIME, false, true, 0,
                List.of(EQ, GT, GTE, LT, LTE, BETWEEN), List.of()));
        fields.put("sourceCategory", field(FieldType.ENUM, true, false, 0,
                List.of(EQ, IN, IS_NULL, IS_NOT_NULL), List.of(
                        "FIELD_OBSERVATION", "INSPECTION", "PERSON_REPORT")));
        fields.put("urgency", field(FieldType.ENUM, true, false, 0,
                List.of(EQ, IN, IS_NULL, IS_NOT_NULL), List.of("NORMAL", "URGENT")));
        fields.put("location", field(FieldType.TEXT, true, false, 0, List.of(), List.of()));
        fields.put("interimMeasure", field(FieldType.TEXT, true, false, 0, List.of(), List.of()));
        fields.put("backfill", field(FieldType.BOOLEAN, true, false, 0,
                List.of(EQ, IS_NULL, IS_NOT_NULL), List.of()));
        fields.put("sourceEventId", field(FieldType.UUID, true, false, 0,
                List.of(EQ, IN, IS_NULL, IS_NOT_NULL), List.of()));
        fields.put("riskId", field(FieldType.UUID, true, false, 0,
                List.of(EQ, IN, IS_NULL, IS_NOT_NULL), List.of()));
        fields.put("relatedFindingId", field(FieldType.UUID, true, false, 0,
                List.of(EQ, IN, IS_NULL, IS_NOT_NULL), List.of()));
        fields.put("grade", field(FieldType.ENUM, false, true, 0, List.of(EQ, IN),
                List.of("UNKNOWN", "GENERAL", "MAJOR")));
        fields.put("state", field(FieldType.ENUM, false, true, 0, List.of(EQ, IN),
                List.of("DISCOVERED", "PERSON_CONFIRM_PENDING", "SAFETY_CONFIRM_PENDING",
                        "SAFETY_DISPATCH_PENDING", "RECTIFYING", "ACCEPTANCE_PENDING", "NOT_ESTABLISHED", "CLOSED")));
        fields.put("roundNo", field(FieldType.NUMBER, false, true, 0,
                List.of(EQ, GT, GTE, LT, LTE, BETWEEN), List.of()));
        fields.put("version", field(FieldType.NUMBER, false, true, 0,
                List.of(EQ, GT, GTE, LT, LTE, BETWEEN), List.of()));
        fields.put("createdAt", field(FieldType.DATETIME, false, true, 0,
                List.of(EQ, GT, GTE, LT, LTE, BETWEEN), List.of()));
        fields.put("updatedAt", field(FieldType.DATETIME, false, true, 0,
                List.of(EQ, GT, GTE, LT, LTE, BETWEEN), List.of()));
        this.schema = new EntitySchema("finding", SCHEMA_VERSION, "id", DEFAULT_FIELDS,
                Collections.unmodifiableMap(fields));
    }

    public EntitySchema schema() {
        return schema;
    }

    private static FieldSchema field(
            FieldType type,
            boolean nullable,
            boolean sortable,
            int maxLength,
            List<FilterOperator> operators,
            List<String> enumValues) {
        Set<FilterOperator> operatorSet = Collections.unmodifiableSet(new LinkedHashSet<>(operators));
        Set<String> enumSet = Collections.unmodifiableSet(new LinkedHashSet<>(enumValues));
        return new FieldSchema(type, nullable, true, sortable, operatorSet, maxLength, enumSet);
    }
}
