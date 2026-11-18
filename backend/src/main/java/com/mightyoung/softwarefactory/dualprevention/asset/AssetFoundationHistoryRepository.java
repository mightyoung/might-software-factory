package com.mightyoung.softwarefactory.dualprevention.asset;

import com.mightyoung.softwarefactory.authorization.EntityActionAuthorization.ActionScope;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.SQLDialect;
import org.jooq.conf.Settings;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DSL;
import static com.mightyoung.softwarefactory.dualprevention.asset.AssetFoundationHistoryContracts.*;

/** Immutable relationship revisions and their exact recorded label versions, on the read owner lease. */
public final class AssetFoundationHistoryRepository {
    private final AssetFoundationRepository owner;
    public AssetFoundationHistoryRepository(AssetFoundationRepository owner){this.owner=Objects.requireNonNull(owner);}
    Page page(Kind kind,UUID parent,Request request,ActionScope scope) {
        owner.require(scope);
        if(scope.action()!=kind.read)throw new AssetFoundationFailure(503);
        // Detail ownership authorizes the actual retained parent; its ACTIVE state is not required for history.
        switch(kind){case LINE_WORKSHOP->owner.productionLine(scope,parent);case STAGE_EQUIPMENT->owner.processStage(scope,parent);case RESPONSIBILITY_BINDING->owner.workUnit(scope,parent);}
        var arguments=new ArrayList<Object>(List.of(scope.context().tenantId(),parent,AssetFoundationRepository.organizations(scope)));
        if(request.after()!=null){arguments.add(request.after().occurredAt().atOffset(ZoneOffset.UTC));arguments.add(request.after().occurredAt().atOffset(ZoneOffset.UTC));arguments.add(request.after().id());}
        arguments.add(request.limit()+1);
        try {
            var rows=DSL.using(owner.connection(),SQLDialect.POSTGRES,new Settings().withExecuteLogging(false).withFetchWarnings(false).withBindOffsetDateTimeType(true))
                    .resultQuery(sql(kind,request.after()!=null),arguments.toArray()).coerce(fields(kind)).fetch();
            boolean more=rows.size()>request.limit();
            var selected=rows.subList(0,Math.min(rows.size(),request.limit()));
            var items=selected.stream().map(row->wire(kind,row)).toList();
            Cursor next=more?new Cursor(selected.getLast().get("occurred_at",OffsetDateTime.class).toInstant(),selected.getLast().get("command_id",UUID.class)):null;
            owner.require(scope);return new Page(items,next);
        }catch(DataAccessException failure){throw new AssetFoundationFailure(503,failure);}
    }
    static String sql(Kind kind,boolean after) {
        String common="select r.entity_id as id,r.version,r.command_id,r.change_kind,r.occurred_at,r.snapshot_valid_from as valid_from,r.snapshot_valid_to as valid_to,r.snapshot_end_observed_at as end_observed_at,";
        String select=switch(kind){
            case LINE_WORKSHOP->"r.snapshot_line_id as parent_id,r.snapshot_workshop_id as counterpart_id,r.snapshot_line_version_at_bind as parent_version,r.snapshot_workshop_version_at_bind as counterpart_version,p.snapshot_code as parent_code,p.snapshot_name as parent_name,c.snapshot_code as counterpart_code,c.snapshot_name as counterpart_name from public.line_workshop_revision r join public.production_line_revision p on p.tenant_id=r.tenant_id and p.entity_id=r.snapshot_line_id and p.version=r.snapshot_line_version_at_bind join public.workshop_revision c on c.tenant_id=r.tenant_id and c.entity_id=r.snapshot_workshop_id and c.version=r.snapshot_workshop_version_at_bind";
            case STAGE_EQUIPMENT->"r.snapshot_stage_id as parent_id,r.snapshot_equipment_id as counterpart_id,r.snapshot_stage_version_at_bind as parent_version,r.snapshot_equipment_version_at_bind as counterpart_version,p.snapshot_code as parent_code,p.snapshot_name as parent_name,c.snapshot_code as counterpart_code,c.snapshot_name as counterpart_name from public.stage_equipment_revision r join public.process_stage_revision p on p.tenant_id=r.tenant_id and p.entity_id=r.snapshot_stage_id and p.version=r.snapshot_stage_version_at_bind join public.equipment_revision c on c.tenant_id=r.tenant_id and c.entity_id=r.snapshot_equipment_id and c.version=r.snapshot_equipment_version_at_bind";
            case RESPONSIBILITY_BINDING->"r.snapshot_work_unit_id as parent_id,r.snapshot_org_id as counterpart_id,r.snapshot_position_id as position_id,r.snapshot_duty as duty,r.snapshot_org_code_snapshot as org_code,r.snapshot_org_name_snapshot as org_name,r.snapshot_position_code_snapshot as position_code,r.snapshot_position_name_snapshot as position_name,r.snapshot_directory_observed_at as directory_observed_at from public.responsibility_binding_revision r";
        };
        String parent=switch(kind){case LINE_WORKSHOP->"snapshot_line_id";case STAGE_EQUIPMENT->"snapshot_stage_id";case RESPONSIBILITY_BINDING->"snapshot_work_unit_id";};
        return common+select+" where r.tenant_id=? and r."+parent+"=? and r.snapshot_organization_id=any(cast(? as uuid[]))"
                +(after?" and (r.occurred_at>? or (r.occurred_at=? and r.command_id>?))":"")
                +" order by r.occurred_at asc,r.command_id asc limit ?";
    }
    private static Field<?>[] fields(Kind kind) {
        var fields=new ArrayList<Field<?>>();
        for(String name:List.of("id","version","command_id","change_kind","occurred_at","valid_from","valid_to","end_observed_at","parent_id","counterpart_id"))fields.add(field(name));
        var extra=kind==Kind.RESPONSIBILITY_BINDING?List.of("position_id","duty","org_code","org_name","position_code","position_name","directory_observed_at"):
                List.of("parent_version","counterpart_version","parent_code","parent_name","counterpart_code","counterpart_name");
        extra.forEach(name->fields.add(field(name)));return fields.toArray(Field<?>[]::new);
    }
    private static Field<?> field(String name){
        Class<?> type=name.equals("version")||name.endsWith("_version")?Long.class:name.endsWith("_at")||name.equals("valid_from")||name.equals("valid_to")?OffsetDateTime.class:
                name.equals("id")||name.endsWith("_id")?UUID.class:String.class;
        return DSL.field(name,type);
    }
    static Map<String,Object> wire(Kind kind,Record row) {
        var values=new LinkedHashMap<String,Object>();
        values.put("commandId",row.get("command_id",UUID.class));values.put("id",row.get("id",UUID.class));values.put("resourceType",kind.key);
        values.put("version",row.get("version",Long.class));values.put("changeKind",row.get("change_kind",String.class));values.put("occurredAt",time(row,"occurred_at"));
        values.put("validFrom",time(row,"valid_from"));values.put("validTo",time(row,"valid_to"));values.put("endObservedAt",time(row,"end_observed_at"));
        if(kind==Kind.RESPONSIBILITY_BINDING){
            values.put("workUnitId",row.get("parent_id",UUID.class));values.put("orgId",row.get("counterpart_id",UUID.class));values.put("positionId",row.get("position_id",UUID.class));
            values.put("duty",row.get("duty",String.class));values.put("orgCodeSnapshot",row.get("org_code",String.class));values.put("orgNameSnapshot",row.get("org_name",String.class));
            values.put("positionCodeSnapshot",row.get("position_code",String.class));values.put("positionNameSnapshot",row.get("position_name",String.class));values.put("directoryObservedAt",time(row,"directory_observed_at"));
        }else {
            boolean line=kind==Kind.LINE_WORKSHOP;
            values.put(line?"lineId":"stageId",row.get("parent_id",UUID.class));values.put(line?"workshopId":"equipmentId",row.get("counterpart_id",UUID.class));
            values.put(line?"lineAtBind":"stageAtBind",endpoint(row,"parent"));values.put(line?"workshopAtBind":"equipmentAtBind",endpoint(row,"counterpart"));
        }
        return Collections.unmodifiableMap(values);
    }
    private static Endpoint endpoint(Record row,String prefix){return new Endpoint(row.get(prefix+"_id",UUID.class),row.get(prefix+"_version",Long.class),row.get(prefix+"_code",String.class),row.get(prefix+"_name",String.class));}
    private static java.time.Instant time(Record row,String field){var value=row.get(field,OffsetDateTime.class);return value==null?null:value.toInstant();}
}
