package com.mightyoung.softwarefactory.dualprevention.risk;

import com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.FieldType;
import com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.FilterOperator;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RiskScenarioEntitySchemaTest {
    @Test void rootOnlyPublicationNeverAdvertisesRelationshipOrProfessionalFields() {
        var schema = RiskScenarioEntitySchema.schema();
        assertEquals("risk_scenario", schema.key()); assertEquals("1", schema.schemaVersion());
        assertEquals("id", schema.idField()); assertEquals(List.of("id"), schema.defaultFields());
        assertEquals(Set.of("id", "organizationId", "code", "version", "description", "triggeringConditions",
                "exposureDescription", "consequence", "createdAt", "updatedAt", "assessmentStatus"), schema.fields().keySet());
        for (String forbidden : List.of("hazardIds", "hazards", "hazardCount", "existence", "hazardVersionAtLink", "score", "grade", "currentAssessment"))
            assertFalse(schema.fields().containsKey(forbidden));
        assertEquals(Set.of("UNASSESSED"), schema.fields().get("assessmentStatus").enumValues());
    }
    @Test void fieldPoliciesPreserveDecimalComparisonsAndFiveExplicitSortKeys() {
        var fields = RiskScenarioEntitySchema.schema().fields();
        assertEquals(FieldType.NUMBER, fields.get("version").type());
        assertEquals(Set.of(FilterOperator.EQ, FilterOperator.GT, FilterOperator.GTE, FilterOperator.LT,
                FilterOperator.LTE, FilterOperator.BETWEEN), fields.get("version").operators());
        assertEquals(Set.of("code", "version", "createdAt", "updatedAt", "organizationId"), fields.entrySet().stream()
                .filter(entry -> entry.getValue().sortable()).map(java.util.Map.Entry::getKey).collect(java.util.stream.Collectors.toSet()));
        assertFalse(fields.get("id").sortable());
        assertTrue(fields.values().stream().allMatch(field -> field.selectable() && !field.nullable()));
        for (String key : List.of("description", "triggeringConditions", "exposureDescription", "consequence"))
            assertEquals(20_000, fields.get(key).maxLength());
        assertEquals(64, fields.get("code").maxLength());
    }
    @Test void publicationIsDeeplyImmutableAndSameCanonicalInstance() {
        var schema = RiskScenarioEntitySchema.schema();
        assertSame(schema, RiskScenarioEntitySchema.schema());
        assertThrows(UnsupportedOperationException.class, () -> schema.fields().clear());
        assertThrows(UnsupportedOperationException.class, () -> schema.defaultFields().clear());
        assertThrows(UnsupportedOperationException.class, () -> schema.fields().get("version").operators().clear());
        assertThrows(UnsupportedOperationException.class, () -> schema.fields().get("assessmentStatus").enumValues().clear());
    }
}
