package com.mightyoung.softwarefactory.dualprevention;

import com.mightyoung.softwarefactory.applicationregistry.definition.EntityDefinition;
import com.mightyoung.softwarefactory.dualprevention.validation.CodePointSize;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import static org.assertj.core.api.Assertions.*;

class FindingDefinitionProviderTest {
    private final FindingDefinitionProvider provider = new FindingDefinitionProvider();

    @Test void publicationIsConsumedByTheLegacyQueryProjectionWithoutChangingV5() {
        var publication = provider.publication();
        assertThat(new FindingEntitySchema(provider).schema()).isSameAs(publication.definition().querySchema());
        assertThat(new FindingEntitySchema().schema()).isEqualTo(publication.definition().querySchema());
        assertThat(publication.definition().querySchema().fields()).hasSize(22);
        assertThat(publication.definition().querySchema().defaultFields()).hasSize(18);
        assertThat(publication.definition().querySchema().schemaVersion()).isEqualTo("5");
    }

    @Test void registeredCommandMatchesTheActualHandlerAndEveryAcceptedInputConstraint() throws Exception {
        var definition = provider.publication().definition();
        var action = definition.actions().get("finding.create");
        var inputs = Arrays.stream(FindingWorkflowApiModels.CreateRequest.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName).toList();
        assertThat(action.inputFields()).containsExactlyElementsOf(inputs);
        var handler = FindingWorkflowController.class.getMethod("create", String.class, FindingWorkflowApiModels.CreateRequest.class);
        assertThat(handler.getAnnotation(PostMapping.class).value()).containsExactly(action.binding().path());
        assertThat(handler.getParameters()[0].getAnnotation(RequestHeader.class).value()).isEqualTo("Idempotency-Key");
        assertThat(action.binding().method()).isEqualTo(EntityDefinition.Method.POST);
        assertThat(action.idempotencyKeyRequired()).isTrue();
        assertThat(action.expectedVersionRequired()).isFalse();
        for (String name : inputs) {
            var input = FindingWorkflowApiModels.CreateRequest.class.getDeclaredField(name);
            var hints = definition.fields().get(name).edit();
            assertThat(hints).isNotNull();
            assertThat(hints.required()).isEqualTo(input.isAnnotationPresent(NotNull.class) || input.isAnnotationPresent(NotBlank.class));
            if (input.isAnnotationPresent(Size.class)) {
                assertThat(hints.maxLength()).isEqualTo(input.getAnnotation(Size.class).max());
                assertThat(hints.lengthUnit()).isEqualTo(EntityDefinition.LengthUnit.UTF16_CODE_UNITS);
            }
            if (input.isAnnotationPresent(CodePointSize.class)) {
                assertThat(hints.maxLength()).isEqualTo(input.getAnnotation(CodePointSize.class).max());
                assertThat(hints.lengthUnit()).isEqualTo(EntityDefinition.LengthUnit.UNICODE_CODE_POINTS);
            }
            if (input.isAnnotationPresent(Pattern.class) && !hints.allowedValues().isEmpty()) {
                for (String value : hints.allowedValues()) assertThat(value).matches(input.getAnnotation(Pattern.class).regexp());
            }
        }
        assertThat(definition.layout().create()).containsExactly("organizationId", "description", "discoveredAt",
                "sourceCategory", "urgency", "location", "interimMeasure");
        assertThat(definition.layout().create()).doesNotContain("sourceEventId", "riskId", "relatedFindingId", "grade");
        for (String optional : List.of("sourceEventId", "riskId", "relatedFindingId"))
            assertThat(definition.fields().get(optional).referenceKey()).isNull();
    }

    @Test void readProjectionPublishesNoCreateActionEditConstraintOrReferenceRoute() {
        var read = FindingDefinitionService.project(provider.publication(), EntityDefinition.Use.READ);
        assertThat(read.fields()).hasSize(22);
        assertThat(read.actions()).isEmpty(); assertThat(read.references()).isEmpty();
        assertThat(read.layout().create()).isEmpty();
        read.fields().forEach((key, value) -> {
            assertThat(value.presentation().edit()).isNull();
            assertThat(value.presentation().referenceKey()).isNull();
            assertThat(value.query()).isEqualTo(provider.publication().definition().querySchema().fields().get(key));
        });
    }

    @Test void createProjectionIsTheExactAcceptedTransportWithNoLedgerOrReadQueryCapabilities() {
        var create = FindingDefinitionService.project(provider.publication(), EntityDefinition.Use.CREATE);
        assertThat(create.fields().keySet()).containsExactlyInAnyOrderElementsOf(FindingDefinitionProvider.ACCEPTED_INPUTS);
        assertThat(create.fields()).doesNotContainKeys("id", "code", "state", "version", "roundNo", "backfill", "createdAt");
        assertThat(create.layout().list()).isEmpty(); assertThat(create.layout().detail()).isEmpty();
        assertThat(create.layout().query()).isEmpty();
        create.fields().values().forEach(field -> {
            assertThat(field.query().operators()).isEmpty();
            assertThat(field.query().sortable()).isFalse(); assertThat(field.query().selectable()).isFalse();
        });
        assertThat(create.fields().get("grade").query().enumValues()).containsExactly("UNKNOWN");
        assertThat(create.fields().get("grade").presentation().valueLabels()).containsOnlyKeys("UNKNOWN");
        assertThat(create.fields().get("location").query().nullable()).isFalse();
        assertThat(create.actions()).containsOnlyKeys("finding.create");
        assertThat(create.references()).containsOnlyKeys("report-organization");
    }
}
