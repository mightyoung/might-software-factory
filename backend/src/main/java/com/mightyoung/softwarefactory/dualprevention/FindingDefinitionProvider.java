package com.mightyoung.softwarefactory.dualprevention;

import com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.EntitySchema;
import com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.FieldSchema;
import com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.FieldType;
import com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.FilterOperator;
import org.springframework.stereotype.Component;
import com.mightyoung.softwarefactory.applicationregistry.definition.EntityDefinition;
import com.mightyoung.softwarefactory.applicationregistry.definition.EntityDefinitionProvider;
import com.mightyoung.softwarefactory.applicationregistry.definition.EntityDefinitionCatalog;
import static com.mightyoung.softwarefactory.applicationregistry.definition.EntityDefinition.*;

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

/** Finding owns the published definition and the fixed real command/reference bindings. */
@Component
public final class FindingDefinitionProvider implements EntityDefinitionProvider {
    public static final String DEFINITION_VERSION = "1";
    public static final String REFERENCE_PATH = "/api/v1/entities/finding/references/report-organization/query";
    static final CommandBinding CREATE_BINDING = new CommandBinding(Method.POST, "finding.create", "/api/v1/findings");
    static final ReferenceBinding ORGANIZATION_BINDING = new ReferenceBinding("report-organization", Method.POST,
            "finding.create", REFERENCE_PATH);
    static final List<String> ACCEPTED_INPUTS = List.of("organizationId", "description", "discoveredAt", "sourceEventId",
            "riskId", "grade", "sourceCategory", "urgency", "location", "interimMeasure", "relatedFindingId");
    static final List<String> CREATE_CONTROLS = List.of("organizationId", "description", "discoveredAt",
            "sourceCategory", "urgency", "location", "interimMeasure");
    private final EntityDefinition definition;
    private final EntityDefinitionCatalog.Publication publication;

    public FindingDefinitionProvider() {
        EntitySchema query = querySchema();
        Map<String, EntityDefinition.Field> presentations = new LinkedHashMap<>();
        query.fields().forEach((key, field) -> presentations.put(key, presentation(key, field)));
        definition = new EntityDefinition("safety", "finding", DEFINITION_VERSION, "隐患台账", query,
                presentations, new Layout(List.of("code", "description", "location", "state", "createdAt"),
                List.copyOf(query.fields().keySet()), CREATE_CONTROLS, List.of("code", "state", "createdAt")),
                Map.of("finding.create", new Action("上报隐患", Position.CREATE, CREATE_BINDING, false, true, ACCEPTED_INPUTS)),
                Map.of("report-organization", new Reference(ORGANIZATION_BINDING, "organization", History.CURRENT)));
        publication = new EntityDefinitionCatalog(List.of(this)).require(new EntityDefinitionCatalog.Key("safety", "finding", DEFINITION_VERSION));
    }
    @Override public List<EntityDefinition> definitions() { return List.of(definition); }
    @Override public Set<CommandBinding> commandBindings() { return Set.of(CREATE_BINDING); }
    @Override public Set<ReferenceBinding> referenceBindings() { return Set.of(ORGANIZATION_BINDING); }
    public EntityDefinitionCatalog.Publication publication() { return publication; }

    private static EntityDefinition.Field presentation(String key, FieldSchema query) {
        String label = LABELS.get(key);
        if (label == null) throw new IllegalStateException("Missing Finding field label");
        UiKind kind = switch (query.type()) {
            case TEXT, UUID -> UiKind.TEXT;
            case DATETIME -> UiKind.DATE_TIME;
            case ENUM -> UiKind.ENUM;
            case NUMBER -> UiKind.NUMBER;
            case BOOLEAN -> UiKind.BOOLEAN;
            default -> throw new IllegalStateException("Unsupported Finding presentation type");
        };
        if (key.equals("description") || key.equals("interimMeasure")) kind = UiKind.MULTILINE_TEXT;
        if (key.equals("organizationId")) kind = UiKind.REFERENCE;
        if (key.equals("state")) kind = UiKind.STATUS;
        Map<String, String> labels = new LinkedHashMap<>();
        query.enumValues().forEach(value -> labels.put(value, VALUE_LABELS.getOrDefault(value, value)));
        return new EntityDefinition.Field(label, kind, History.CURRENT,
                key.equals("organizationId") ? "report-organization" : null, labels, input(key));
    }
    private static Edit input(String key) {
        if (!ACCEPTED_INPUTS.contains(key)) return null;
        boolean required = !List.of("sourceEventId", "riskId", "interimMeasure", "relatedFindingId").contains(key);
        int max = switch (key) { case "description" -> 20000; case "location" -> 200; case "interimMeasure" -> 2000; default -> 0; };
        List<String> values = switch (key) {
            case "grade" -> List.of("UNKNOWN");
            case "sourceCategory" -> List.of("FIELD_OBSERVATION", "INSPECTION", "PERSON_REPORT");
            case "urgency" -> List.of("NORMAL", "URGENT");
            default -> List.of();
        };
        return new Edit(required, max, key.equals("description") ? LengthUnit.UTF16_CODE_UNITS : LengthUnit.UNICODE_CODE_POINTS, values);
    }
    private static final Map<String, String> LABELS = Map.ofEntries(
            Map.entry("id", "标识"), Map.entry("code", "隐患编号"), Map.entry("organizationId", "上报组织"),
            Map.entry("organizationName", "上报组织名称"), Map.entry("description", "隐患描述"), Map.entry("discoveredAt", "发现时间"),
            Map.entry("sourceCategory", "发现来源"), Map.entry("urgency", "紧急程度"), Map.entry("location", "所在位置 / 设备"),
            Map.entry("interimMeasure", "临时措施"), Map.entry("backfill", "补报"), Map.entry("sourceEventId", "来源事件"),
            Map.entry("riskId", "关联风险"), Map.entry("relatedFindingId", "关联隐患"), Map.entry("grade", "隐患等级"),
            Map.entry("state", "状态"), Map.entry("roundNo", "整改轮次"), Map.entry("reviewCycle", "复核轮次"),
            Map.entry("reviewProtocol", "复核协议"), Map.entry("version", "版本"), Map.entry("createdAt", "上报日期"), Map.entry("updatedAt", "更新时间"));
    private static final Map<String, String> VALUE_LABELS = Map.ofEntries(
            Map.entry("UNKNOWN", "尚未判定"), Map.entry("GENERAL", "一般隐患"), Map.entry("MAJOR", "重大隐患"),
            Map.entry("FIELD_OBSERVATION", "现场发现"), Map.entry("INSPECTION", "排查发现"), Map.entry("PERSON_REPORT", "人员上报"),
            Map.entry("NORMAL", "一般"), Map.entry("URGENT", "需立即处置"), Map.entry("DISCOVERED", "已发现"),
            Map.entry("PERSON_CONFIRM_PENDING", "待责任人确认"), Map.entry("SAFETY_CONFIRM_PENDING", "待安全确认"),
            Map.entry("REPORTER_SUPPLEMENT_PENDING", "待上报人补充"), Map.entry("SAFETY_DISPATCH_PENDING", "待安全派单"),
            Map.entry("RECTIFYING", "整改中"), Map.entry("ACCEPTANCE_PENDING", "待验收"), Map.entry("NOT_ESTABLISHED", "不成立"),
            Map.entry("CLOSED", "已关闭"), Map.entry("LEGACY", "原复核协议"), Map.entry("CYCLE_V1", "分轮复核协议"));

    private static EntitySchema querySchema() {
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
                        "REPORTER_SUPPLEMENT_PENDING", "SAFETY_DISPATCH_PENDING", "RECTIFYING", "ACCEPTANCE_PENDING", "NOT_ESTABLISHED", "CLOSED")));
        fields.put("roundNo", field(FieldType.NUMBER, false, true, 0,
                List.of(EQ, GT, GTE, LT, LTE, BETWEEN), List.of()));
        fields.put("reviewCycle", field(FieldType.NUMBER, false, false, 0, List.of(), List.of()));
        fields.put("reviewProtocol", field(FieldType.ENUM, false, false, 0, List.of(), List.of("LEGACY", "CYCLE_V1")));
        fields.put("version", field(FieldType.NUMBER, false, true, 0,
                List.of(EQ, GT, GTE, LT, LTE, BETWEEN), List.of()));
        fields.put("createdAt", field(FieldType.DATETIME, false, true, 0,
                List.of(EQ, GT, GTE, LT, LTE, BETWEEN), List.of()));
        fields.put("updatedAt", field(FieldType.DATETIME, false, true, 0,
                List.of(EQ, GT, GTE, LT, LTE, BETWEEN), List.of()));
        return new EntitySchema("finding", FindingEntitySchema.SCHEMA_VERSION, "id", FindingEntitySchema.DEFAULT_FIELDS,
                Collections.unmodifiableMap(fields));
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
