package com.mightyoung.softwarefactory.dualprevention.risk;

import com.mightyoung.softwarefactory.applicationregistry.definition.EntityDefinition.Use;
import com.mightyoung.softwarefactory.applicationregistry.definition.EntityDefinitionProvider;
import com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.EntitySchema;
import com.mightyoung.softwarefactory.authorization.RegisteredEntityAction;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;
import static com.mightyoung.softwarefactory.dualprevention.risk.RiskScenarioContracts.*;
import static org.junit.jupiter.api.Assertions.*;

class RiskScenarioDefinitionProviderTest {
    private final RiskScenarioDefinitionProvider provider = new RiskScenarioDefinitionProvider();

    @Test void mandatoryHazardArrayLivesOnlyInCommandInputsNotQueryNamespace() {
        var create = provider.definition(Use.CREATE);
        assertEquals(RiskScenarioEntitySchema.schema(), create.querySchema());
        assertFalse(create.querySchema().fields().containsKey("hazardIds")); assertFalse(create.fields().containsKey("hazardIds"));
        assertEquals(Set.copyOf(RiskScenarioDefinitionProvider.CREATE_INPUTS), create.commandInputs().keySet());
        assertEquals(RiskScenarioDefinitionProvider.CREATE_INPUTS, create.actions().get("create").inputFields());
        var hazards = create.commandInputs().get("hazardIds");
        assertEquals(RiskScenarioDefinitionProvider.InputType.UUID_ARRAY, hazards.type());
        assertTrue(hazards.required()); assertTrue(hazards.uniqueItems()); assertEquals(1, hazards.minItems()); assertEquals(100, hazards.maxItems());
        assertEquals("eligible-hazards", hazards.referenceKey());
        assertEquals(RiskScenarioDefinitionProvider.CREATE_INPUTS, create.layout().create());
    }
    @Test void fixedCreateBindingAndReferenceRequireIndependentOwnerAndHazardReadActions() {
        var create = provider.definition(Use.CREATE); var action = create.actions().get("create");
        assertEquals(RegisteredEntityAction.RISK_SCENARIO_CREATE.permissionCode(), action.binding().permission());
        assertEquals("/api/v1/risk-scenarios", action.binding().path());
        assertTrue(action.idempotencyKeyRequired()); assertFalse(action.expectedVersionRequired());
        var reference = create.references().get("eligible-hazards");
        assertEquals("/api/v1/risk-scenarios/eligible-hazards", reference.path());
        assertEquals(SelectorUse.CREATE, reference.use()); assertEquals("organizationId", reference.anchorInput());
        assertEquals("risk_scenario.create", reference.ownerPermission()); assertEquals("hazard_source.read", reference.targetReadPermission());
        assertFalse(create.layout().query().contains("hazardIds"));
    }
    @Test void readPublicationContainsNoCreateActionsInputsReferencesOrProfessionalDefaults() {
        var read = provider.definition(Use.READ);
        assertTrue(read.commandInputs().isEmpty()); assertTrue(read.actions().isEmpty()); assertTrue(read.references().isEmpty());
        assertTrue(read.layout().create().isEmpty());
        assertEquals(Map.of("UNASSESSED", "待评估"), read.fields().get("assessmentStatus").valueLabels());
        var wire = JsonMapper.builder().build().readTree(encodeResponse(read, ResponseBudget.METADATA));
        assertFalse(wire.get("fields").get("description").has("edit"));
        assertFalse(wire.get("querySchema").get("fields").has("hazards"));
        assertFalse(wire.get("querySchema").get("fields").has("grade"));
    }
    @Test void publicationsAndNestedMapsAreImmutableAndMutableInputSchemaIsCanonicalized() {
        var read = provider.definition(Use.READ);
        assertThrows(UnsupportedOperationException.class, () -> read.fields().clear());
        assertThrows(UnsupportedOperationException.class, () -> read.fields().get("assessmentStatus").valueLabels().clear());
        assertThrows(UnsupportedOperationException.class, () -> provider.definition(Use.CREATE).commandInputs().clear());
        assertThrows(UnsupportedOperationException.class, () -> provider.definition(Use.CREATE).actions().get("create").inputFields().clear());
        var mutableFields = new LinkedHashMap<>(read.querySchema().fields());
        var mutableSchema = new EntitySchema("risk_scenario", "1", "id", read.querySchema().defaultFields(), mutableFields);
        var copy = new RiskScenarioDefinitionProvider.Definition(read.appKey(), read.entityKey(), read.definitionVersion(), read.schemaVersion(),
                read.use(), read.title(), mutableSchema, read.fields(), read.commandInputs(), read.layout(), read.actions(), read.references());
        mutableFields.clear();
        assertSame(RiskScenarioEntitySchema.schema(), copy.querySchema()); assertEquals(11, copy.querySchema().fields().size());
    }
    @Test void checksumIsRepeatableButPublicationDoesNotClaimCatalogOrPermissionWiring() {
        var another = new RiskScenarioDefinitionProvider();
        assertEquals(provider.publication(Use.READ).checksum(), another.publication(Use.READ).checksum());
        assertEquals(provider.publication(Use.CREATE).checksum(), another.publication(Use.CREATE).checksum());
        assertNotEquals(provider.publication(Use.READ).checksum(), provider.publication(Use.CREATE).checksum());
        assertTrue(provider.publication(Use.READ).checksum().matches("[0-9a-f]{64}"));
        assertFalse(RiskScenarioDefinitionProvider.class.isAnnotationPresent(Component.class));
        assertFalse(EntityDefinitionProvider.class.isAssignableFrom(RiskScenarioDefinitionProvider.class));
        assertTrue(encodeResponse(provider.definition(Use.CREATE), ResponseBudget.METADATA).length <= 256 * 1024);
    }
}
