package com.mightyoung.softwarefactory.dualprevention.asset;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.jooq.Field;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static com.mightyoung.softwarefactory.dualprevention.asset.AssetFoundationHistoryContracts.*;
import static org.assertj.core.api.Assertions.*;

class AssetFoundationHistoryTest {
    private final JsonMapper json=JsonMapper.builder().build();
    private final AssetFoundationHistoryParser parser=new AssetFoundationHistoryParser(json);
    @Test void historyPageHasIndependentLimitsAndCanonicalCommandCursor(){
        assertThat(parser.parse(json.readTree("{}")).limit()).isEqualTo(20);
        var request=parser.parse(json.readTree("{\"limit\":50,\"after\":{\"occurredAt\":\"2026-11-04T00:00:00.123456Z\",\"id\":\"80000000-0000-0000-0000-000000000001\"}}"));
        assertThat(request.limit()).isEqualTo(50);assertThat(request.after().occurredAt()).isEqualTo(Instant.parse("2026-11-04T00:00:00.123456Z"));
        for(String body:new String[]{"{\"limit\":51}","{\"limit\":20.0}","{\"after\":{\"occurredAt\":\"2026-11-04T00:00:00.1234567Z\",\"id\":\"80000000-0000-0000-0000-000000000001\"}}"})
            assertThatThrownBy(()->parser.parse(json.readTree(body))).isInstanceOf(AssetFoundationFailure.class);
    }
    @Test void exactAtBindVersionsJoinImmutableRootRevisionsAndEndedRowsRemainVisible(){
        String line=AssetFoundationHistoryRepository.sql(Kind.LINE_WORKSHOP,true),stage=AssetFoundationHistoryRepository.sql(Kind.STAGE_EQUIPMENT,true);
        assertThat(line).contains("public.production_line_revision","public.workshop_revision","p.version=r.snapshot_line_version_at_bind","c.version=r.snapshot_workshop_version_at_bind");
        assertThat(stage).contains("public.process_stage_revision","public.equipment_revision","p.version=r.snapshot_stage_version_at_bind","c.version=r.snapshot_equipment_version_at_bind");
        for(Kind kind:Kind.values()) {
            String sql=AssetFoundationHistoryRepository.sql(kind,true);
            assertThat(sql).contains("r.tenant_id=?","snapshot_organization_id=any(cast(? as uuid[]))","r.command_id>?","order by r.occurred_at asc,r.command_id asc limit ?");
            assertThat(sql).doesNotContain("lifecycle=","snapshot_valid_to>","clock_timestamp","current_timestamp");
        }
    }
    @Test void endpointSnapshotsUseRecordedLabelsAndFinalPageOmitsCursor(){
        Field<?>[] fields={DSL.field("id",UUID.class),DSL.field("version",Long.class),DSL.field("command_id",UUID.class),DSL.field("change_kind",String.class),DSL.field("occurred_at",OffsetDateTime.class),
                DSL.field("valid_from",OffsetDateTime.class),DSL.field("valid_to",OffsetDateTime.class),DSL.field("end_observed_at",OffsetDateTime.class),DSL.field("parent_id",UUID.class),DSL.field("counterpart_id",UUID.class),
                DSL.field("parent_version",Long.class),DSL.field("counterpart_version",Long.class),DSL.field("parent_code",String.class),DSL.field("parent_name",String.class),DSL.field("counterpart_code",String.class),DSL.field("counterpart_name",String.class)};
        var row=DSL.using(SQLDialect.POSTGRES).newRecord(fields);
        for(Field<?> field:fields)set(row,field,field.getType()==UUID.class?UUID.randomUUID():field.getType()==Long.class?1L:field.getType()==OffsetDateTime.class?OffsetDateTime.parse("2026-11-04T00:00:00Z"):field.getName().endsWith("name")?"recorded-name":field.getName().equals("change_kind")?"END":"recorded-code");
        var wire=AssetFoundationHistoryRepository.wire(Kind.LINE_WORKSHOP,row);
        assertThat((Endpoint)wire.get("workshopAtBind")).extracting(Endpoint::name).isEqualTo("recorded-name");
        assertThat(wire).doesNotContainKeys("actorId","tenantId","payloadSha256");
        assertThat(json.writeValueAsString(new Page(java.util.List.of(wire),null))).doesNotContain("nextAfter");
    }
    @SuppressWarnings({"unchecked","rawtypes"}) private static void set(org.jooq.Record row,Field field,Object value){row.set(field,value);}
}
