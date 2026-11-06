package com.mightyoung.softwarefactory.dualprevention.asset;

import com.mightyoung.softwarefactory.applicationregistry.definition.EntityDefinition;
import com.mightyoung.softwarefactory.applicationregistry.definition.EntityDefinitionCatalog;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.assertj.core.api.Assertions.*;
import com.mightyoung.softwarefactory.dualprevention.asset.AssetFoundationEntitySchemas.Root;

class AssetFoundationDefinitionProviderTest {
    private final AssetFoundationDefinitionProvider provider = new AssetFoundationDefinitionProvider();
    @Test void registersSixRealCommandsAndThirteenExactCreateSelectors() {
        assertThat(provider.definitions()).hasSize(6);
        assertThat(provider.commandBindings()).hasSize(6);
        assertThat(provider.referenceBindings()).hasSize(13);
        assertThatCode(() -> new EntityDefinitionCatalog(List.of(provider))).doesNotThrowAnyException();
        assertThat(provider.commandBindings()).allSatisfy(binding -> {
            assertThat(binding.method()).isEqualTo(EntityDefinition.Method.POST);
            assertThat(binding.permission()).doesNotStartWith("finding.");
        });
    }
    @ParameterizedTest @EnumSource(Root.class)
    void publicationReusesSharedSchemaSemanticsAndProjectsIndependentReadAndCreate(Root root) {
        var publication = provider.publication(root);
        assertThat(publication.definition().querySchema()).isEqualTo(AssetFoundationEntitySchemas.schema(root));
        var read = AssetFoundationDefinitionService.project(publication, EntityDefinition.Use.READ, root);
        assertThat(read.actions()).isEmpty(); assertThat(read.references()).isEmpty();
        assertThat(read.layout().create()).isEmpty();
        assertThat(read.fields().values()).allSatisfy(field -> {
            assertThat(field.presentation().edit()).isNull(); assertThat(field.presentation().referenceKey()).isNull();
        });
        var create = AssetFoundationDefinitionService.project(publication, EntityDefinition.Use.CREATE, root);
        assertThat(create.actions()).containsOnlyKeys("create");
        assertThat(create.actions().get("create").binding().permission()).isEqualTo(root.createAction().permissionCode());
        assertThat(create.actions().get("create").inputFields()).isEqualTo(AssetFoundationDefinitionProvider.inputs(root));
        assertThat(create.fields().keySet()).containsExactlyInAnyOrderElementsOf(create.actions().get("create").inputFields());
        assertThat(create.layout().list()).isEmpty(); assertThat(create.layout().query()).isEmpty();
        assertThatThrownBy(() -> create.actions().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> publication.definition().querySchema().fields().clear()).isInstanceOf(UnsupportedOperationException.class);
    }
    @Test void workUnitPublishesFixedSourcesWithoutInventingProfessionalOrConditionalControls() {
        var create = AssetFoundationDefinitionService.project(provider.publication(Root.WORK_UNIT), EntityDefinition.Use.CREATE, Root.WORK_UNIT);
        assertThat(create.references().keySet()).isEqualTo(Set.of("create-organization", "source-workshop", "source-line", "source-stage", "source-equipment"));
        for (String key : List.of("workshopId", "lineId", "stageId", "equipmentId")) assertThat(create.fields().get(key).presentation().edit().required()).isFalse();
        var hazard = AssetFoundationDefinitionService.project(provider.publication(Root.HAZARD_SOURCE), EntityDefinition.Use.CREATE, Root.HAZARD_SOURCE);
        assertThat(hazard.fields()).doesNotContainKeys("factorCodeRef", "factorVersion", "riskGrade");
        assertThat(hazard.fields().get("description").presentation().edit().maxLength()).isEqualTo(20_000);
    }
}
