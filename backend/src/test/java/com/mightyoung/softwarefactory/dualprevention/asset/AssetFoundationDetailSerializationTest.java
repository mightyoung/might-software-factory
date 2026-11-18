package com.mightyoung.softwarefactory.dualprevention.asset;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;
import static com.mightyoung.softwarefactory.dualprevention.asset.AssetContracts.*;

class AssetFoundationDetailSerializationTest {
    private final ObjectMapper json=new ObjectMapper();
    private final UUID id=UUID.randomUUID();
    private final Instant time=Instant.parse("2026-11-04T00:00:00Z");

    @Test void commandResultPreservesExactMicrosecondsWithoutRoundingForTheReplayShape() {
        Instant cut=Instant.parse("2026-11-04T00:00:00.123456Z");
        assertSame(cut,AssetContracts.stageInstant(cut));
        var result=new CommandResult(id,"workshop",id,1,cut);
        assertSame(cut,result.occurredAt());
        assertEquals("2026-11-04T00:00:00.123456Z",json.valueToTree(result).get("occurredAt").asText());
        assertEquals(result,new CommandResult(id,"workshop",id,1,Instant.parse("2026-11-04T00:00:00.123456Z")));
    }
    @Test void standaloneOmitsForeignSourceKeysButRetainsRequiredNullableDescriptionAndTimes() {
        var value=json.valueToTree(new WorkUnit(id,id,1,"WU","Standalone",WorkUnitKind.STANDALONE,
                null,null,null,null,null,Lifecycle.ACTIVE,time,time));
        for(String field:new String[]{"workshopId","lineId","stageId","equipmentId"})assertFalse(value.has(field));
        assertTrue(value.has("boundaryDescription"));assertTrue(value.get("boundaryDescription").isNull());
        assertTrue(value.hasNonNull("createdAt"));assertTrue(value.hasNonNull("updatedAt"));
    }
    @Test void workshopUnitExposesOnlyItsConcreteSource() {
        var value=json.valueToTree(new WorkUnit(id,id,1,"WU","Workshop",WorkUnitKind.WORKSHOP,
                id,null,null,null,null,Lifecycle.ACTIVE,time,time));
        assertEquals(id.toString(),value.get("workshopId").asText());
        assertFalse(value.has("lineId"));assertFalse(value.has("stageId"));assertFalse(value.has("equipmentId"));
    }
}
