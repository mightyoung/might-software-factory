package com.mightyoung.softwarefactory.dualprevention.asset;

import com.mightyoung.softwarefactory.applicationregistry.definition.EntityDefinition;
import com.mightyoung.softwarefactory.applicationregistry.definition.EntityDefinitionCatalog;
import com.mightyoung.softwarefactory.applicationregistry.definition.EntityDefinitionProvider;
import com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.FieldSchema;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import static com.mightyoung.softwarefactory.applicationregistry.definition.EntityDefinition.*;
import static com.mightyoung.softwarefactory.dualprevention.asset.AssetFoundationEntitySchemas.Root;

/** Asset owner publishes immutable definitions beside the fixed command and selector handlers. */
@Component
public final class AssetFoundationDefinitionProvider implements EntityDefinitionProvider {
    public static final String VERSION = "1";
    private final List<EntityDefinition> definitions;
    private final Map<Root, EntityDefinitionCatalog.Publication> publications;

    public AssetFoundationDefinitionProvider() {
        definitions = java.util.Arrays.stream(Root.values()).map(this::definition).toList();
        var catalog = new EntityDefinitionCatalog(List.of(this));
        var found = new EnumMap<Root, EntityDefinitionCatalog.Publication>(Root.class);
        for (Root root : Root.values()) found.put(root, catalog.require(new EntityDefinitionCatalog.Key("safety", root.key(), VERSION)));
        publications = Collections.unmodifiableMap(found);
    }
    @Override public List<EntityDefinition> definitions() { return definitions; }
    @Override public Set<CommandBinding> commandBindings() {
        return java.util.Arrays.stream(Root.values()).map(AssetFoundationDefinitionProvider::command).collect(Collectors.toUnmodifiableSet());
    }
    @Override public Set<ReferenceBinding> referenceBindings() {
        return java.util.Arrays.stream(Root.values()).flatMap(root -> references(root).values().stream())
                .map(Reference::binding).collect(Collectors.toUnmodifiableSet());
    }
    public EntityDefinitionCatalog.Publication publication(Root root) { return publications.get(root); }

    private EntityDefinition definition(Root root) {
        var schema = AssetFoundationEntitySchemas.schema(root);
        Map<String, Reference> references = references(root);
        Map<String, EntityDefinition.Field> fields = new LinkedHashMap<>();
        schema.fields().forEach((key, query) -> fields.put(key, presentation(root, key, query)));
        var inputs = inputs(root);
        var action = new Action("新建", Position.CREATE, command(root), false, true, inputs);
        var primary = root == Root.HAZARD_SOURCE ? List.of("code", "description", "existenceState") : List.of("code", "name", "lifecycle");
        return new EntityDefinition("safety", root.key(), VERSION, title(root), schema, fields,
                new Layout(primary, schema.defaultFields(), inputs, List.of("code", root == Root.HAZARD_SOURCE ? "existenceState" : "lifecycle")),
                Map.of(root.createAction().permissionCode(), action), references);
    }

    private static EntityDefinition.Field presentation(Root root, String key, FieldSchema query) {
        UiKind kind = switch (query.type()) {
            case UUID, TEXT -> UiKind.TEXT;
            case NUMBER -> UiKind.NUMBER;
            case DATETIME -> UiKind.DATE_TIME;
            case ENUM -> UiKind.ENUM;
            case BOOLEAN -> UiKind.BOOLEAN;
            default -> throw new IllegalArgumentException("Unsupported asset presentation type");
        };
        String reference = referenceKey(root, key);
        if (reference != null) kind = UiKind.REFERENCE;
        else if (key.equals("description") || key.equals("boundaryDescription") || key.equals("locationDescription")) kind = UiKind.MULTILINE_TEXT;
        else if (key.equals("lifecycle")) kind = UiKind.STATUS;
        Map<String, String> values = new LinkedHashMap<>();
        query.enumValues().stream().sorted().forEach(value -> values.put(value, VALUE_LABELS.getOrDefault(value, value)));
        Edit edit = null;
        if (inputs(root).contains(key)) {
            boolean required = !List.of("boundaryDescription", "locationDescription").contains(key)
                    && !(key.equals("description") && root != Root.HAZARD_SOURCE)
                    && !(root == Root.WORK_UNIT && List.of("workshopId", "lineId", "stageId", "equipmentId").contains(key));
            edit = new Edit(required, query.maxLength(), LengthUnit.UNICODE_CODE_POINTS, query.enumValues().stream().sorted().toList());
        }
        return new EntityDefinition.Field(LABELS.get(key), kind, History.CURRENT, reference, values, edit);
    }
    static List<String> inputs(Root root) {
        return switch (root) {
            case WORKSHOP -> List.of("code", "name", "organizationId", "boundaryDescription");
            case PRODUCTION_LINE -> List.of("code", "name", "organizationId", "workshopId", "description");
            case PROCESS_STAGE -> List.of("code", "name", "organizationId", "lineId", "ordinal", "description");
            case EQUIPMENT -> List.of("code", "name", "organizationId", "typeDescription", "locationDescription", "operationalState");
            case WORK_UNIT -> List.of("code", "name", "organizationId", "kind", "boundaryDescription", "workshopId", "lineId", "stageId", "equipmentId");
            case HAZARD_SOURCE -> List.of("code", "organizationId", "workUnitId", "description", "identifiedAt", "existenceState");
        };
    }
    private static CommandBinding command(Root root) {
        String path = switch (root) {
            case WORKSHOP -> "/api/v1/assets/workshops";
            case PRODUCTION_LINE -> "/api/v1/assets/production-lines";
            case PROCESS_STAGE -> "/api/v1/assets/process-stages";
            case EQUIPMENT -> "/api/v1/assets/equipment";
            case WORK_UNIT -> "/api/v1/assets/work-units";
            case HAZARD_SOURCE -> "/api/v1/hazard-sources";
        };
        return new CommandBinding(Method.POST, root.createAction().permissionCode(), path);
    }
    private static Map<String, Reference> references(Root root) {
        Map<String, Reference> result = new LinkedHashMap<>();
        result.put("create-organization", reference(root, "create-organization", "organization"));
        switch (root) {
            case PRODUCTION_LINE -> result.put("parent-workshop", reference(root, "parent-workshop", "workshop"));
            case PROCESS_STAGE -> result.put("parent-line", reference(root, "parent-line", "production_line"));
            case HAZARD_SOURCE -> result.put("work-unit", reference(root, "work-unit", "work_unit"));
            case WORK_UNIT -> {
                result.put("source-workshop", reference(root, "source-workshop", "workshop"));
                result.put("source-line", reference(root, "source-line", "production_line"));
                result.put("source-stage", reference(root, "source-stage", "process_stage"));
                result.put("source-equipment", reference(root, "source-equipment", "equipment"));
            }
            default -> { }
        }
        return Collections.unmodifiableMap(result);
    }
    private static Reference reference(Root root, String key, String entity) {
        return new Reference(new ReferenceBinding(key, Method.POST, root.createAction().permissionCode(),
                "/api/v1/entities/" + root.key() + "/references/" + key + "/query"), entity, History.CURRENT);
    }
    private static String referenceKey(Root root, String field) {
        if (field.equals("organizationId")) return "create-organization";
        return switch (root) {
            case PRODUCTION_LINE -> field.equals("workshopId") ? "parent-workshop" : null;
            case PROCESS_STAGE -> field.equals("lineId") ? "parent-line" : null;
            case HAZARD_SOURCE -> field.equals("workUnitId") ? "work-unit" : null;
            case WORK_UNIT -> switch (field) {
                case "workshopId" -> "source-workshop"; case "lineId" -> "source-line";
                case "stageId" -> "source-stage"; case "equipmentId" -> "source-equipment"; default -> null;
            };
            default -> null;
        };
    }
    private static String title(Root root) {
        return switch (root) { case WORKSHOP -> "车间"; case PRODUCTION_LINE -> "生产线"; case PROCESS_STAGE -> "工序";
            case EQUIPMENT -> "设备设施"; case WORK_UNIT -> "作业单元"; case HAZARD_SOURCE -> "危险源"; };
    }
    private static final Map<String, String> LABELS = Map.ofEntries(
            Map.entry("id", "标识"), Map.entry("version", "版本"), Map.entry("code", "编号"), Map.entry("name", "名称"),
            Map.entry("organizationId", "归属组织"), Map.entry("boundaryDescription", "边界说明"), Map.entry("description", "描述"),
            Map.entry("createdAt", "创建时间"), Map.entry("updatedAt", "更新时间"), Map.entry("lifecycle", "状态"),
            Map.entry("workshopId", "车间"), Map.entry("lineId", "生产线"), Map.entry("stageId", "工序"),
            Map.entry("equipmentId", "设备设施"), Map.entry("ordinal", "顺序"), Map.entry("typeDescription", "类型说明"),
            Map.entry("operationalState", "运行状态"), Map.entry("locationDescription", "位置说明"), Map.entry("kind", "类型"),
            Map.entry("workUnitId", "作业单元"), Map.entry("identifiedAt", "辨识时间"), Map.entry("existenceState", "存在情况"),
            Map.entry("factorCodeRef", "因素代码"), Map.entry("factorVersion", "因素版本"));
    private static final Map<String, String> VALUE_LABELS = Map.ofEntries(Map.entry("ACTIVE", "启用"), Map.entry("ARCHIVED", "已归档"),
            Map.entry("UNKNOWN", "尚未确认"), Map.entry("IN_SERVICE", "在用"), Map.entry("OUT_OF_SERVICE", "停用"),
            Map.entry("PRESENT", "存在"), Map.entry("NOT_PRESENT", "不存在"), Map.entry("STANDALONE", "独立"),
            Map.entry("WORKSHOP", "车间"), Map.entry("LINE", "生产线"), Map.entry("STAGE", "工序"), Map.entry("EQUIPMENT", "设备设施"));
}
