package com.mightyoung.softwarefactory.dualprevention.asset;

import com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static com.mightyoung.softwarefactory.dualprevention.asset.AssetFoundationEntitySchemas.*;
import static org.junit.jupiter.api.Assertions.*;

class AssetFoundationEntitySchemasTest {
    @Test void sixExactOrderedFieldsAreSelectableSortableAndPublishedOnce() {
        List<String> expected=List.of(
                "id version code name organizationId boundaryDescription createdAt updatedAt lifecycle",
                "id version code name organizationId workshopId description createdAt updatedAt lifecycle",
                "id version code name organizationId lineId ordinal description createdAt updatedAt lifecycle",
                "id version code name organizationId typeDescription operationalState locationDescription createdAt updatedAt lifecycle",
                "id version code name organizationId kind boundaryDescription createdAt updatedAt lifecycle workshopId lineId stageId equipmentId",
                "id version code organizationId workUnitId description identifiedAt existenceState createdAt updatedAt factorCodeRef factorVersion");
        assertEquals(6,schemas().size());
        for(var root:Root.values()) {
            var schema=schema(root);var keys=List.of(expected.get(root.ordinal()).split(" "));
            assertSame(schema,schemas().get(root.ordinal()));assertEquals(root.key(),schema.key());assertEquals("1",schema.schemaVersion());
            assertEquals("id",schema.idField());assertEquals(keys,schema.defaultFields());assertEquals(keys,List.copyOf(schema.fields().keySet()));
            schema.fields().values().forEach(field->{assertTrue(field.selectable());assertTrue(field.sortable());assertFalse(field.operators().contains(FilterOperator.IN_SUBTREE));});
            assertEquals(root.key()+".read",root.readAction().permissionCode());assertEquals(root.key()+".create",root.createAction().permissionCode());
        }
    }
    @Test void nestedMapsListsEnumsAndOperatorsAreImmutable() {
        var schema=schema(Root.WORKSHOP);var field=schema.fields().get("lifecycle");
        assertThrows(UnsupportedOperationException.class,()->schemas().clear());
        assertThrows(UnsupportedOperationException.class,()->schema.fields().clear());
        assertThrows(UnsupportedOperationException.class,()->schema.defaultFields().clear());
        assertThrows(UnsupportedOperationException.class,()->field.operators().clear());
        assertThrows(UnsupportedOperationException.class,()->field.enumValues().clear());
    }
    @Test void typedPolicyPreservesDecimalNumbersNullableReferencesAndLongHazardText() {
        assertEquals(FieldType.NUMBER,schema(Root.PROCESS_STAGE).fields().get("ordinal").type());
        assertEquals(FieldType.NUMBER,schema(Root.WORKSHOP).fields().get("version").type());
        assertEquals(20000,schema(Root.HAZARD_SOURCE).fields().get("description").maxLength());
        assertFalse(schema(Root.HAZARD_SOURCE).fields().get("description").nullable());
        assertTrue(schema(Root.WORK_UNIT).fields().get("workshopId").nullable());
        assertFalse(schema(Root.PRODUCTION_LINE).fields().get("workshopId").nullable());
        for(String key:List.of("factorCodeRef","factorVersion")) {
            var factor=schema(Root.HAZARD_SOURCE).fields().get(key);
            assertTrue(factor.nullable());assertEquals(FieldType.TEXT,factor.type());assertEquals(64,factor.maxLength());
        }
        for(var schema:schemas())for(var field:schema.fields().values()) {
            assertEquals(field.nullable(),field.operators().contains(FilterOperator.IS_NULL));
            assertEquals(field.nullable(),field.operators().contains(FilterOperator.IS_NOT_NULL));
            if(field.type()==FieldType.TEXT) assertEquals(Set.of(FilterOperator.EQ,FilterOperator.CONTAINS,FilterOperator.IS_EMPTY,FilterOperator.IS_NOT_EMPTY),difference(field.operators()));
        }
    }
    private static Set<FilterOperator> difference(Set<FilterOperator> operators) {
        var result=EnumSet.copyOf(operators);result.remove(FilterOperator.IS_NULL);result.remove(FilterOperator.IS_NOT_NULL);return result;
    }
}
