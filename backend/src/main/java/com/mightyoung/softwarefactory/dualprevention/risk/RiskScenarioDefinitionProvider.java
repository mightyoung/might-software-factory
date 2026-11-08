package com.mightyoung.softwarefactory.dualprevention.risk;

import com.mightyoung.softwarefactory.applicationregistry.definition.EntityDefinition;
import com.mightyoung.softwarefactory.applicationregistry.definition.EntityDefinition.Use;
import com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.EntitySchema;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import static com.mightyoung.softwarefactory.dualprevention.risk.RiskScenarioContracts.*;

/** Pure risk-specific publication: command inputs and query fields have deliberately separate namespaces.
 * Explicit construction only. The HTTP owner must authorize before projecting, computing contextual ETags or 304.
 * This does not implement the shared catalog whose current invariants require all input fields to be query fields. */
public final class RiskScenarioDefinitionProvider {
    public static final String VERSION = "1";
    public static final List<String> CREATE_INPUTS = List.of("code", "organizationId", "description", "triggeringConditions",
            "exposureDescription", "consequence", "hazardIds");
    private static final EntityDefinition.CommandBinding CREATE = new EntityDefinition.CommandBinding(
            EntityDefinition.Method.POST, "risk_scenario.create", "/api/v1/risk-scenarios");
    private final Map<Use, Publication> publications;

    public RiskScenarioDefinitionProvider() {
        var found = new EnumMap<Use, Publication>(Use.class);
        for (Use use : Use.values()) {
            Definition definition = build(use);
            found.put(use, new Publication(definition, checksum(definition)));
        }
        publications = Collections.unmodifiableMap(found);
    }
    /** No permission decision is performed by this lookup. */
    public Publication publication(Use use) { return publications.get(Objects.requireNonNull(use)); }
    public Definition definition(Use use) { return publication(use).definition(); }

    public enum InputType { TEXT, UUID, UUID_ARRAY }
    public record Input(InputType type, String label, boolean required, int maxLength,
                        Integer minItems, Integer maxItems, boolean uniqueItems, String referenceKey) {
        public Input {
            Objects.requireNonNull(type); Objects.requireNonNull(label);
            if (maxLength < 0 || type == InputType.UUID_ARRAY && (minItems == null || maxItems == null
                    || minItems != 1 || maxItems != MAX_HAZARDS || !uniqueItems || referenceKey == null))
                throw new IllegalArgumentException("Invalid risk command input publication");
        }
    }
    public record Presentation(String label, EntityDefinition.UiKind uiKind, EntityDefinition.History history,
                               Map<String, String> valueLabels) {
        public Presentation {
            Objects.requireNonNull(label); Objects.requireNonNull(uiKind); Objects.requireNonNull(history);
            valueLabels = immutable(valueLabels);
        }
    }
    public record Reference(String entityKey, String method, String path, SelectorUse use,
                            String anchorInput, String ownerPermission, String targetReadPermission) { }
    public record Action(String label, EntityDefinition.Position position, EntityDefinition.CommandBinding binding,
                         boolean expectedVersionRequired, boolean idempotencyKeyRequired, List<String> inputFields) {
        public Action { inputFields = List.copyOf(inputFields); }
    }
    public record Layout(List<String> list, List<String> detail, List<String> create, List<String> query) {
        public Layout { list = List.copyOf(list); detail = List.copyOf(detail); create = List.copyOf(create); query = List.copyOf(query); }
    }
    public record Definition(String appKey, String entityKey, String definitionVersion, String schemaVersion,
                             Use use, String title, EntitySchema querySchema, Map<String, Presentation> fields,
                             Map<String, Input> commandInputs, Layout layout, Map<String, Action> actions,
                             Map<String, Reference> references) {
        public Definition {
            Objects.requireNonNull(use); Objects.requireNonNull(querySchema); Objects.requireNonNull(layout);
            if (!querySchema.equals(RiskScenarioEntitySchema.schema()))
                throw new IllegalArgumentException("Risk definition must use its sole root query schema");
            querySchema = RiskScenarioEntitySchema.schema();
            fields = immutable(fields); commandInputs = immutable(commandInputs); actions = immutable(actions); references = immutable(references);
            if (!ENTITY_KEY.equals(entityKey) || !fields.keySet().equals(querySchema.fields().keySet()))
                throw new IllegalArgumentException("Invalid risk query publication");
            if (querySchema.fields().containsKey("hazardIds") || querySchema.fields().containsKey("hazards"))
                throw new IllegalArgumentException("Relationships cannot become risk query fields");
            if (use == Use.READ && (!commandInputs.isEmpty() || !actions.isEmpty() || !references.isEmpty() || !layout.create().isEmpty()))
                throw new IllegalArgumentException("READ definition cannot publish editing controls");
            if (use == Use.CREATE && (!commandInputs.keySet().equals(new java.util.LinkedHashSet<>(CREATE_INPUTS))
                    || !layout.create().equals(CREATE_INPUTS) || !actions.keySet().equals(java.util.Set.of("create"))
                    || !actions.get("create").inputFields().equals(CREATE_INPUTS)))
                throw new IllegalArgumentException("CREATE definition must publish the entire accepted command");
        }
    }
    public record Publication(Definition definition, String checksum) { }

    private static Definition build(Use use) {
        var schema = RiskScenarioEntitySchema.schema();
        Map<String, Presentation> fields = new LinkedHashMap<>();
        schema.fields().forEach((key, field) -> {
            EntityDefinition.UiKind kind = switch (field.type()) {
                case UUID, TEXT -> EntityDefinition.UiKind.TEXT;
                case NUMBER -> EntityDefinition.UiKind.NUMBER;
                case DATETIME -> EntityDefinition.UiKind.DATE_TIME;
                case ENUM -> EntityDefinition.UiKind.STATUS;
                default -> throw new IllegalArgumentException("Unregistered risk presentation");
            };
            if (NARRATIVE_FIELDS.contains(key)) kind = EntityDefinition.UiKind.MULTILINE_TEXT;
            fields.put(key, new Presentation(LABELS.get(key), kind, EntityDefinition.History.CURRENT,
                    key.equals("assessmentStatus") ? Map.of("UNASSESSED", "待评估") : Map.of()));
        });
        if (use == Use.READ) return new Definition("safety", ENTITY_KEY, VERSION, SCHEMA_VERSION, use, "风险场景草稿", schema, fields,
                Map.of(), new Layout(List.of("code", "description", "assessmentStatus"), RiskScenarioEntitySchema.FIELD_KEYS,
                        List.of(), List.of("code", "organizationId", "assessmentStatus")), Map.of(), Map.of());
        Map<String, Input> inputs = new LinkedHashMap<>();
        for (String key : CREATE_INPUTS) {
            InputType type = key.equals("hazardIds") ? InputType.UUID_ARRAY : key.equals("organizationId") ? InputType.UUID : InputType.TEXT;
            inputs.put(key, new Input(type, key.equals("hazardIds") ? "关联危险源" : LABELS.get(key), true,
                    key.equals("code") ? 64 : NARRATIVE_FIELDS.contains(key) ? MAX_NARRATIVE_CODE_POINTS : 0,
                    type == InputType.UUID_ARRAY ? 1 : null, type == InputType.UUID_ARRAY ? MAX_HAZARDS : null,
                    type == InputType.UUID_ARRAY, type == InputType.UUID_ARRAY ? "eligible-hazards" : null));
        }
        var reference = new Reference("hazard_source", "POST", "/api/v1/risk-scenarios/eligible-hazards", SelectorUse.CREATE,
                "organizationId", "risk_scenario.create", "hazard_source.read");
        return new Definition("safety", ENTITY_KEY, VERSION, SCHEMA_VERSION, use, "风险场景草稿", schema, fields, inputs,
                new Layout(List.of(), List.of(), CREATE_INPUTS, List.of()),
                Map.of("create", new Action("新建", EntityDefinition.Position.CREATE, CREATE, false, true, CREATE_INPUTS)),
                Map.of("eligible-hazards", reference));
    }
    private static String checksum(Definition definition) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(encodeResponse(definition, ResponseBudget.METADATA))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static <K, V> Map<K, V> immutable(Map<K, V> source) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }
    private static final java.util.Set<String> NARRATIVE_FIELDS = java.util.Set.of(
            "description", "triggeringConditions", "exposureDescription", "consequence");
    private static final Map<String, String> LABELS = Map.ofEntries(Map.entry("id", "标识"), Map.entry("organizationId", "归属组织"),
            Map.entry("code", "编号"), Map.entry("version", "版本"), Map.entry("description", "风险描述"),
            Map.entry("triggeringConditions", "触发条件"), Map.entry("exposureDescription", "暴露说明"), Map.entry("consequence", "后果"),
            Map.entry("createdAt", "创建时间"), Map.entry("updatedAt", "更新时间"), Map.entry("assessmentStatus", "评估状态"));
}
