package com.mightyoung.softwarefactory.dualprevention;

import com.mightyoung.softwarefactory.applicationregistry.definition.EntityDefinition;
import com.mightyoung.softwarefactory.applicationregistry.definition.EntityDefinitionCatalog;
import com.mightyoung.softwarefactory.applicationregistry.definition.EntityDefinitionProvider;
import com.mightyoung.softwarefactory.dualprevention.validation.CodePointSize;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import static org.assertj.core.api.Assertions.assertThat;

class FindingSchemaV5CompatibilityTest {
    @Test
    void retainsTheExactPublishedV5SerializedSchema() throws Exception {
        var mapper = JsonMapper.builder().build();
        try (var fixture = Objects.requireNonNull(getClass().getResourceAsStream("/contracts/finding-schema-v5.json"))) {
            var expected = mapper.readTree(new String(fixture.readAllBytes(), StandardCharsets.UTF_8));
            var actual = mapper.readTree(mapper.writeValueAsString(new FindingEntitySchema().schema()));
            assertThat(actual).isEqualTo(expected);
        }
    }

    @Test
    void retainsAllElevenActualCommandInputsWithoutInventingDesktopAssociationSelectors() throws Exception {
        var inputFields = Arrays.stream(FindingWorkflowApiModels.CreateRequest.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName).toList();
        assertThat(inputFields).containsExactly("organizationId", "description", "discoveredAt", "sourceEventId",
                "riskId", "grade", "sourceCategory", "urgency", "location", "interimMeasure", "relatedFindingId");
        var query = new FindingEntitySchema().schema();
        Map<String, EntityDefinition.Field> fields = new LinkedHashMap<>();
        for (var entry : query.fields().entrySet()) {
            EntityDefinition.Edit edit = null;
            if (inputFields.contains(entry.getKey())) {
                var actualInput = FindingWorkflowApiModels.CreateRequest.class.getDeclaredField(entry.getKey());
                var codePoints = actualInput.getAnnotation(CodePointSize.class);
                var utf16 = actualInput.getAnnotation(Size.class);
                var pattern = actualInput.getAnnotation(Pattern.class);
                boolean required = actualInput.isAnnotationPresent(NotNull.class) || actualInput.isAnnotationPresent(NotBlank.class);
                var allowedValues = entry.getValue().enumValues().stream()
                        .filter(value -> pattern == null || value.matches(pattern.regexp())).sorted().toList();
                edit = new EntityDefinition.Edit(required, codePoints != null ? codePoints.max() : utf16 != null ? utf16.max() : 0,
                        codePoints != null ? EntityDefinition.LengthUnit.UNICODE_CODE_POINTS : EntityDefinition.LengthUnit.UTF16_CODE_UNITS,
                        allowedValues);
            }
            // Test-only publication proves representability, not production labels or reference providers.
            fields.put(entry.getKey(), new EntityDefinition.Field(entry.getKey(), EntityDefinition.UiKind.TEXT,
                    EntityDefinition.History.CURRENT, null, Map.of(), edit));
        }
        var desktopControls = List.of("organizationId", "sourceCategory", "discoveredAt", "location",
                "description", "urgency", "interimMeasure");
        var binding = new EntityDefinition.CommandBinding(EntityDefinition.Method.POST, "finding.create", "/api/v1/findings");
        var definition = new EntityDefinition("safety", "finding", "1", "Test fixture", query, fields,
                new EntityDefinition.Layout(query.defaultFields(), List.copyOf(query.fields().keySet()), desktopControls, List.of("code")),
                Map.of("finding.create", new EntityDefinition.Action("Create", EntityDefinition.Position.CREATE, binding, false, true, inputFields)), Map.of());
        var owner = new EntityDefinitionProvider() {
            public List<EntityDefinition> definitions() { return List.of(definition); }
            public Set<EntityDefinition.CommandBinding> commandBindings() { return Set.of(binding); }
            public Set<EntityDefinition.ReferenceBinding> referenceBindings() { return Set.of(); }
        };
        var registered = new EntityDefinitionCatalog(List.of(owner))
                .require(new EntityDefinitionCatalog.Key("safety", "finding", "1")).definition();
        assertThat(registered.actions().get("finding.create").inputFields()).containsExactlyElementsOf(inputFields);
        assertThat(registered.layout().create()).containsExactlyElementsOf(desktopControls)
                .doesNotContain("sourceEventId", "riskId", "relatedFindingId", "grade");
        for (var association : List.of("sourceEventId", "riskId", "relatedFindingId")) {
            assertThat(registered.fields().get(association).edit()).isNotNull();
            assertThat(registered.fields().get(association).edit().required()).isFalse();
            assertThat(registered.fields().get(association).referenceKey()).isNull();
        }
        assertThat(registered.references()).isEmpty();
        assertThat(registered.fields().get("grade").edit().allowedValues()).containsExactly("UNKNOWN");
        assertThat(registered.fields().get("description").edit().maxLength()).isEqualTo(20000);
        assertThat(registered.fields().get("description").edit().lengthUnit()).isEqualTo(EntityDefinition.LengthUnit.UTF16_CODE_UNITS);
        assertThat(registered.fields().get("location").edit().maxLength()).isEqualTo(200);
        assertThat(registered.fields().get("location").edit().lengthUnit()).isEqualTo(EntityDefinition.LengthUnit.UNICODE_CODE_POINTS);
    }
}
