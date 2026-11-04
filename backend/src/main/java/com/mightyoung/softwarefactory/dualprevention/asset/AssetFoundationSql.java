package com.mightyoung.softwarefactory.dualprevention.asset;

import com.mightyoung.softwarefactory.support.TransactionSqlSealGate.SqlTemplate;
import java.util.List;

/** Finite source-owned SQL. No identifier or predicate comes from a request. */
enum AssetFoundationSql {
    SET_TENANT("""
        select set_config('app.tenant_id', ?, true)
        """, false, null),
    RECEIPT_LOCK("""
        select public.g014_lock_receipt_key(cast(? as uuid),cast(? as uuid),?,cast(? as uuid))
        """, false, null),
    MUTATION_LOCK("""
        select public.g014_lock_mutation_keys(?,cast(? as uuid),cast(? as uuid),cast(? as bigint),cast(? as uuid))
        """, false, null),
    RECEIPT_FIND("""
        with authorized as (select cast(? as uuid[]) as organizations)
        select command_id,account_id,member_id,payload_sha256,result_resource_type,result_version,result_occurred_at,
        coalesce(result_workshop_id,result_production_line_id,result_process_stage_id,result_equipment_id,result_work_unit_id,result_hazard_source_id,result_line_workshop_id,result_stage_equipment_id,result_responsibility_binding_id) as result_id
        from public.asset_foundation_receipt r cross join authorized
        where r.tenant_id=? and r.actor_id=? and r.action_code=? and r.idempotency_key=?
          and ((r.result_workshop_id is not null and exists (select 1 from public.workshop target where target.tenant_id=r.tenant_id and target.id=r.result_workshop_id and target.organization_id=any(authorized.organizations)))
            or (r.result_production_line_id is not null and exists (select 1 from public.production_line target where target.tenant_id=r.tenant_id and target.id=r.result_production_line_id and target.organization_id=any(authorized.organizations)))
            or (r.result_process_stage_id is not null and exists (select 1 from public.process_stage target where target.tenant_id=r.tenant_id and target.id=r.result_process_stage_id and target.organization_id=any(authorized.organizations)))
            or (r.result_equipment_id is not null and exists (select 1 from public.equipment target where target.tenant_id=r.tenant_id and target.id=r.result_equipment_id and target.organization_id=any(authorized.organizations)))
            or (r.result_work_unit_id is not null and exists (select 1 from public.work_unit target where target.tenant_id=r.tenant_id and target.id=r.result_work_unit_id and target.organization_id=any(authorized.organizations)))
            or (r.result_hazard_source_id is not null and exists (select 1 from public.hazard_source target where target.tenant_id=r.tenant_id and target.id=r.result_hazard_source_id and target.organization_id=any(authorized.organizations)))
            or (r.result_line_workshop_id is not null and exists (select 1 from public.line_workshop target where target.tenant_id=r.tenant_id and target.id=r.result_line_workshop_id and target.organization_id=any(authorized.organizations)))
            or (r.result_stage_equipment_id is not null and exists (select 1 from public.stage_equipment target where target.tenant_id=r.tenant_id and target.id=r.result_stage_equipment_id and target.organization_id=any(authorized.organizations)))
            or (r.result_responsibility_binding_id is not null and exists (select 1 from public.responsibility_binding target where target.tenant_id=r.tenant_id and target.id=r.result_responsibility_binding_id and target.organization_id=any(authorized.organizations))))
        """, false, null),
    WORKSHOP_NATURAL("""
        select public.g014_lock_natural_key('workshop', cast(? as uuid), ?, null)
        """, false, null),
    WORKSHOP_LOCK("""
        select tenant_id,id,organization_id,version,created_at,updated_at,created_by,updated_by,last_command_id,code,name,boundary_description,lifecycle from public.workshop where tenant_id=? and id=? for update
        """, false, null),
    WORKSHOP_CURRENT("""
        select tenant_id,id,organization_id,version,created_at,updated_at,created_by,updated_by,last_command_id,code,name,boundary_description,lifecycle from public.workshop where tenant_id=? and id=? and organization_id=any(cast(? as uuid[]))
        """, true, null),
    WORKSHOP_INSERT("""
        insert into public.workshop (tenant_id,id,organization_id,version,created_at,updated_at,created_by,updated_by,last_command_id,code,name,boundary_description,lifecycle) values (?,?,?,?,?,?,?,?,?,?,?,?,?)
        """, false, "workshop.insert"),
    WORKSHOP_UPDATE("""
        update public.workshop set name=?,boundary_description=?,lifecycle=?,version=?,updated_at=?,updated_by=?,last_command_id=? where tenant_id=? and id=? and version=?
        """, false, "workshop.update"),
    WORKSHOP_REVISION("""
        insert into public.workshop_revision (tenant_id,entity_id,version,command_id,change_kind,occurred_at,actor_id,snapshot_organization_id,snapshot_created_at,snapshot_updated_at,snapshot_created_by,snapshot_updated_by,snapshot_code,snapshot_name,snapshot_boundary_description,snapshot_lifecycle) select tenant_id,id,version,last_command_id,?,updated_at,updated_by,organization_id,created_at,updated_at,created_by,updated_by,code,name,boundary_description,lifecycle from public.workshop where tenant_id=? and id=? and version=? and last_command_id=?
        """, false, "workshop.revision"),
    WORKSHOP_RECEIPT("""
        insert into public.asset_foundation_receipt (tenant_id,command_id,actor_id,account_id,member_id,action_code,idempotency_key,fingerprint_version,payload_sha256,result_resource_type,result_version,result_occurred_at,audit_event_id,created_at,result_workshop_id,change_kind,trace_id) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
        """, false, "workshop.receipt"),
    WORK_UNIT_NATURAL("""
        select public.g014_lock_natural_key('work_unit', cast(? as uuid), ?, null)
        """, false, null),
    WORK_UNIT_LOCK("""
        select tenant_id,id,organization_id,version,created_at,updated_at,created_by,updated_by,last_command_id,code,name,kind,workshop_id,line_id,stage_id,equipment_id,boundary_description,lifecycle from public.work_unit where tenant_id=? and id=? for update
        """, false, null),
    WORK_UNIT_CURRENT("""
        select tenant_id,id,organization_id,version,created_at,updated_at,created_by,updated_by,last_command_id,code,name,kind,workshop_id,line_id,stage_id,equipment_id,boundary_description,lifecycle from public.work_unit where tenant_id=? and id=? and organization_id=any(cast(? as uuid[]))
        """, true, null),
    WORK_UNIT_INSERT("""
        insert into public.work_unit (tenant_id,id,organization_id,version,created_at,updated_at,created_by,updated_by,last_command_id,code,name,kind,workshop_id,line_id,stage_id,equipment_id,boundary_description,lifecycle) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
        """, false, "work_unit.insert"),
    WORK_UNIT_UPDATE("""
        update public.work_unit set name=?,boundary_description=?,lifecycle=?,version=?,updated_at=?,updated_by=?,last_command_id=? where tenant_id=? and id=? and version=?
        """, false, "work_unit.update"),
    WORK_UNIT_REVISION("""
        insert into public.work_unit_revision (tenant_id,entity_id,version,command_id,change_kind,occurred_at,actor_id,snapshot_organization_id,snapshot_created_at,snapshot_updated_at,snapshot_created_by,snapshot_updated_by,snapshot_code,snapshot_name,snapshot_kind,snapshot_workshop_id,snapshot_line_id,snapshot_stage_id,snapshot_equipment_id,snapshot_boundary_description,snapshot_lifecycle) select tenant_id,id,version,last_command_id,?,updated_at,updated_by,organization_id,created_at,updated_at,created_by,updated_by,code,name,kind,workshop_id,line_id,stage_id,equipment_id,boundary_description,lifecycle from public.work_unit where tenant_id=? and id=? and version=? and last_command_id=?
        """, false, "work_unit.revision"),
    WORK_UNIT_RECEIPT("""
        insert into public.asset_foundation_receipt (tenant_id,command_id,actor_id,account_id,member_id,action_code,idempotency_key,fingerprint_version,payload_sha256,result_resource_type,result_version,result_occurred_at,audit_event_id,created_at,result_work_unit_id,change_kind,trace_id) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
        """, false, "work_unit.receipt"),
    HAZARD_SOURCE_NATURAL("""
        select public.g014_lock_natural_key('hazard_source', cast(? as uuid), ?, null)
        """, false, null),
    HAZARD_SOURCE_LOCK("""
        select tenant_id,id,organization_id,version,created_at,updated_at,created_by,updated_by,last_command_id,code,work_unit_id,description,identified_at,existence_state,factor_code_ref,factor_version from public.hazard_source where tenant_id=? and id=? for update
        """, false, null),
    HAZARD_SOURCE_CURRENT("""
        select tenant_id,id,organization_id,version,created_at,updated_at,created_by,updated_by,last_command_id,code,work_unit_id,description,identified_at,existence_state,factor_code_ref,factor_version from public.hazard_source where tenant_id=? and id=? and organization_id=any(cast(? as uuid[]))
        """, true, null),
    HAZARD_SOURCE_INSERT("""
        insert into public.hazard_source (tenant_id,id,organization_id,version,created_at,updated_at,created_by,updated_by,last_command_id,code,work_unit_id,description,identified_at,existence_state,factor_code_ref,factor_version) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
        """, false, "hazard_source.insert"),
    HAZARD_SOURCE_UPDATE("""
        update public.hazard_source set description=?,identified_at=?,existence_state=?,version=?,updated_at=?,updated_by=?,last_command_id=? where tenant_id=? and id=? and version=?
        """, false, "hazard_source.update"),
    HAZARD_SOURCE_REVISION("""
        insert into public.hazard_source_revision (tenant_id,entity_id,version,command_id,change_kind,occurred_at,actor_id,snapshot_organization_id,snapshot_created_at,snapshot_updated_at,snapshot_created_by,snapshot_updated_by,snapshot_code,snapshot_work_unit_id,snapshot_description,snapshot_identified_at,snapshot_existence_state,snapshot_factor_code_ref,snapshot_factor_version) select tenant_id,id,version,last_command_id,?,updated_at,updated_by,organization_id,created_at,updated_at,created_by,updated_by,code,work_unit_id,description,identified_at,existence_state,factor_code_ref,factor_version from public.hazard_source where tenant_id=? and id=? and version=? and last_command_id=?
        """, false, "hazard_source.revision"),
    HAZARD_SOURCE_RECEIPT("""
        insert into public.asset_foundation_receipt (tenant_id,command_id,actor_id,account_id,member_id,action_code,idempotency_key,fingerprint_version,payload_sha256,result_resource_type,result_version,result_occurred_at,audit_event_id,created_at,result_hazard_source_id,change_kind,trace_id) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
        """, false, "hazard_source.receipt"),
    PRODUCTION_LINE_LOCK("""
        select tenant_id,id,organization_id,version,created_at,updated_at,created_by,updated_by,last_command_id,code,name,workshop_id,description,lifecycle from public.production_line where tenant_id=? and id=? for update
        """, false, null),
    PRODUCTION_LINE_CURRENT("""
        select tenant_id,id,organization_id,version,created_at,updated_at,created_by,updated_by,last_command_id,code,name,workshop_id,description,lifecycle from public.production_line where tenant_id=? and id=? and organization_id=any(cast(? as uuid[]))
        """, true, null),
    PROCESS_STAGE_LOCK("""
        select tenant_id,id,organization_id,version,created_at,updated_at,created_by,updated_by,last_command_id,code,name,line_id,ordinal,description,lifecycle from public.process_stage where tenant_id=? and id=? for update
        """, false, null),
    PROCESS_STAGE_CURRENT("""
        select tenant_id,id,organization_id,version,created_at,updated_at,created_by,updated_by,last_command_id,code,name,line_id,ordinal,description,lifecycle from public.process_stage where tenant_id=? and id=? and organization_id=any(cast(? as uuid[]))
        """, true, null),
    EQUIPMENT_LOCK("""
        select tenant_id,id,organization_id,version,created_at,updated_at,created_by,updated_by,last_command_id,code,name,type_description,location_description,operational_state,lifecycle from public.equipment where tenant_id=? and id=? for update
        """, false, null),
    EQUIPMENT_CURRENT("""
        select tenant_id,id,organization_id,version,created_at,updated_at,created_by,updated_by,last_command_id,code,name,type_description,location_description,operational_state,lifecycle from public.equipment where tenant_id=? and id=? and organization_id=any(cast(? as uuid[]))
        """, true, null),
    PRODUCTION_LINE_NATURAL("""
        select public.g014_lock_natural_key('production_line', cast(? as uuid), ?, null)
        """, false, null),
    PRODUCTION_LINE_INSERT("""
        insert into public.production_line (tenant_id,id,organization_id,version,created_at,updated_at,created_by,updated_by,last_command_id,code,name,workshop_id,description,lifecycle) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
        """, false, "production_line.insert"),
    PRODUCTION_LINE_UPDATE("""
        update public.production_line set name=?,description=?,lifecycle=?,version=?,updated_at=?,updated_by=?,last_command_id=? where tenant_id=? and id=? and version=?
        """, false, "production_line.update"),
    PRODUCTION_LINE_REVISION("""
        insert into public.production_line_revision (tenant_id,entity_id,version,command_id,change_kind,occurred_at,actor_id,snapshot_organization_id,snapshot_created_at,snapshot_updated_at,snapshot_created_by,snapshot_updated_by,snapshot_code,snapshot_name,snapshot_workshop_id,snapshot_description,snapshot_lifecycle) select tenant_id,id,version,last_command_id,?,updated_at,updated_by,organization_id,created_at,updated_at,created_by,updated_by,code,name,workshop_id,description,lifecycle from public.production_line where tenant_id=? and id=? and version=? and last_command_id=?
        """, false, "production_line.revision"),
    PRODUCTION_LINE_RECEIPT("""
        insert into public.asset_foundation_receipt (tenant_id,command_id,actor_id,account_id,member_id,action_code,idempotency_key,fingerprint_version,payload_sha256,result_resource_type,result_version,result_occurred_at,audit_event_id,created_at,result_production_line_id,change_kind,trace_id) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
        """, false, "production_line.receipt"),
    PROCESS_STAGE_NATURAL("""
        select public.g014_lock_natural_key('process_stage', cast(? as uuid), ?, cast(? as uuid))
        """, false, null),
    PROCESS_STAGE_INSERT("""
        insert into public.process_stage (tenant_id,id,organization_id,version,created_at,updated_at,created_by,updated_by,last_command_id,code,name,line_id,ordinal,description,lifecycle) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
        """, false, "process_stage.insert"),
    PROCESS_STAGE_UPDATE("""
        update public.process_stage set name=?,ordinal=?,description=?,lifecycle=?,version=?,updated_at=?,updated_by=?,last_command_id=? where tenant_id=? and id=? and version=?
        """, false, "process_stage.update"),
    PROCESS_STAGE_REVISION("""
        insert into public.process_stage_revision (tenant_id,entity_id,version,command_id,change_kind,occurred_at,actor_id,snapshot_organization_id,snapshot_created_at,snapshot_updated_at,snapshot_created_by,snapshot_updated_by,snapshot_code,snapshot_name,snapshot_line_id,snapshot_ordinal,snapshot_description,snapshot_lifecycle) select tenant_id,id,version,last_command_id,?,updated_at,updated_by,organization_id,created_at,updated_at,created_by,updated_by,code,name,line_id,ordinal,description,lifecycle from public.process_stage where tenant_id=? and id=? and version=? and last_command_id=?
        """, false, "process_stage.revision"),
    PROCESS_STAGE_RECEIPT("""
        insert into public.asset_foundation_receipt (tenant_id,command_id,actor_id,account_id,member_id,action_code,idempotency_key,fingerprint_version,payload_sha256,result_resource_type,result_version,result_occurred_at,audit_event_id,created_at,result_process_stage_id,change_kind,trace_id) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
        """, false, "process_stage.receipt"),
    EQUIPMENT_NATURAL("""
        select public.g014_lock_natural_key('equipment', cast(? as uuid), ?, null)
        """, false, null),
    EQUIPMENT_INSERT("""
        insert into public.equipment (tenant_id,id,organization_id,version,created_at,updated_at,created_by,updated_by,last_command_id,code,name,type_description,location_description,operational_state,lifecycle) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
        """, false, "equipment.insert"),
    EQUIPMENT_UPDATE("""
        update public.equipment set name=?,type_description=?,location_description=?,operational_state=?,lifecycle=?,version=?,updated_at=?,updated_by=?,last_command_id=? where tenant_id=? and id=? and version=?
        """, false, "equipment.update"),
    EQUIPMENT_REVISION("""
        insert into public.equipment_revision (tenant_id,entity_id,version,command_id,change_kind,occurred_at,actor_id,snapshot_organization_id,snapshot_created_at,snapshot_updated_at,snapshot_created_by,snapshot_updated_by,snapshot_code,snapshot_name,snapshot_type_description,snapshot_location_description,snapshot_operational_state,snapshot_lifecycle) select tenant_id,id,version,last_command_id,?,updated_at,updated_by,organization_id,created_at,updated_at,created_by,updated_by,code,name,type_description,location_description,operational_state,lifecycle from public.equipment where tenant_id=? and id=? and version=? and last_command_id=?
        """, false, "equipment.revision"),
    EQUIPMENT_RECEIPT("""
        insert into public.asset_foundation_receipt (tenant_id,command_id,actor_id,account_id,member_id,action_code,idempotency_key,fingerprint_version,payload_sha256,result_resource_type,result_version,result_occurred_at,audit_event_id,created_at,result_equipment_id,change_kind,trace_id) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
        """, false, "equipment.receipt"),
    LINE_WORKSHOP_PEEK("""
        select tenant_id,id,organization_id,version,created_at,updated_at,created_by,updated_by,last_command_id,line_id,line_version_at_bind,workshop_id,workshop_version_at_bind,valid_from,valid_to,end_observed_at from public.line_workshop where tenant_id=? and id=?
        """, false, null),
    LINE_WORKSHOP_LOCK("""
        select tenant_id,id,organization_id,version,created_at,updated_at,created_by,updated_by,last_command_id,line_id,line_version_at_bind,workshop_id,workshop_version_at_bind,valid_from,valid_to,end_observed_at from public.line_workshop where tenant_id=? and id=? for update
        """, false, null),
    LINE_WORKSHOP_CURRENT("""
        select tenant_id,id,organization_id,version,created_at,updated_at,created_by,updated_by,last_command_id,line_id,line_version_at_bind,workshop_id,workshop_version_at_bind,valid_from,valid_to,end_observed_at from public.line_workshop where tenant_id=? and id=? and organization_id=any(cast(? as uuid[]))
        """, true, null),
    LINE_WORKSHOP_INSERT("""
        insert into public.line_workshop (tenant_id,id,organization_id,version,created_at,updated_at,created_by,updated_by,last_command_id,line_id,workshop_id,valid_from,valid_to,end_observed_at) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
        """, false, "line_workshop.insert"),
    LINE_WORKSHOP_END("""
        update public.line_workshop set valid_to=?,end_observed_at=?,version=?,updated_at=?,updated_by=?,last_command_id=? where tenant_id=? and id=? and version=?
        """, false, "line_workshop.end"),
    LINE_WORKSHOP_REVISION("""
        insert into public.line_workshop_revision (tenant_id,entity_id,version,command_id,change_kind,occurred_at,actor_id,snapshot_organization_id,snapshot_created_at,snapshot_updated_at,snapshot_created_by,snapshot_updated_by,snapshot_line_id,snapshot_line_version_at_bind,snapshot_workshop_id,snapshot_workshop_version_at_bind,snapshot_valid_from,snapshot_valid_to,snapshot_end_observed_at) select tenant_id,id,version,last_command_id,?,updated_at,updated_by,organization_id,created_at,updated_at,created_by,updated_by,line_id,line_version_at_bind,workshop_id,workshop_version_at_bind,valid_from,valid_to,end_observed_at from public.line_workshop where tenant_id=? and id=? and version=? and last_command_id=?
        """, false, "line_workshop.revision"),
    LINE_WORKSHOP_RECEIPT("""
        insert into public.asset_foundation_receipt (tenant_id,command_id,actor_id,account_id,member_id,action_code,idempotency_key,fingerprint_version,payload_sha256,result_resource_type,result_version,result_occurred_at,audit_event_id,created_at,result_line_workshop_id,change_kind,trace_id) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
        """, false, "line_workshop.receipt"),
    STAGE_EQUIPMENT_PEEK("""
        select tenant_id,id,organization_id,version,created_at,updated_at,created_by,updated_by,last_command_id,stage_id,stage_version_at_bind,equipment_id,equipment_version_at_bind,valid_from,valid_to,end_observed_at from public.stage_equipment where tenant_id=? and id=?
        """, false, null),
    STAGE_EQUIPMENT_LOCK("""
        select tenant_id,id,organization_id,version,created_at,updated_at,created_by,updated_by,last_command_id,stage_id,stage_version_at_bind,equipment_id,equipment_version_at_bind,valid_from,valid_to,end_observed_at from public.stage_equipment where tenant_id=? and id=? for update
        """, false, null),
    STAGE_EQUIPMENT_CURRENT("""
        select tenant_id,id,organization_id,version,created_at,updated_at,created_by,updated_by,last_command_id,stage_id,stage_version_at_bind,equipment_id,equipment_version_at_bind,valid_from,valid_to,end_observed_at from public.stage_equipment where tenant_id=? and id=? and organization_id=any(cast(? as uuid[]))
        """, true, null),
    STAGE_EQUIPMENT_INSERT("""
        insert into public.stage_equipment (tenant_id,id,organization_id,version,created_at,updated_at,created_by,updated_by,last_command_id,stage_id,equipment_id,valid_from,valid_to,end_observed_at) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
        """, false, "stage_equipment.insert"),
    STAGE_EQUIPMENT_END("""
        update public.stage_equipment set valid_to=?,end_observed_at=?,version=?,updated_at=?,updated_by=?,last_command_id=? where tenant_id=? and id=? and version=?
        """, false, "stage_equipment.end"),
    STAGE_EQUIPMENT_REVISION("""
        insert into public.stage_equipment_revision (tenant_id,entity_id,version,command_id,change_kind,occurred_at,actor_id,snapshot_organization_id,snapshot_created_at,snapshot_updated_at,snapshot_created_by,snapshot_updated_by,snapshot_stage_id,snapshot_stage_version_at_bind,snapshot_equipment_id,snapshot_equipment_version_at_bind,snapshot_valid_from,snapshot_valid_to,snapshot_end_observed_at) select tenant_id,id,version,last_command_id,?,updated_at,updated_by,organization_id,created_at,updated_at,created_by,updated_by,stage_id,stage_version_at_bind,equipment_id,equipment_version_at_bind,valid_from,valid_to,end_observed_at from public.stage_equipment where tenant_id=? and id=? and version=? and last_command_id=?
        """, false, "stage_equipment.revision"),
    STAGE_EQUIPMENT_RECEIPT("""
        insert into public.asset_foundation_receipt (tenant_id,command_id,actor_id,account_id,member_id,action_code,idempotency_key,fingerprint_version,payload_sha256,result_resource_type,result_version,result_occurred_at,audit_event_id,created_at,result_stage_equipment_id,change_kind,trace_id) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
        """, false, "stage_equipment.receipt"),
    RESPONSIBILITY_BINDING_PEEK("""
        select tenant_id,id,organization_id,version,created_at,updated_at,created_by,updated_by,last_command_id,work_unit_id,org_id,position_id,duty,valid_from,valid_to,end_observed_at,org_code_snapshot,org_name_snapshot,position_code_snapshot,position_name_snapshot,directory_observed_at from public.responsibility_binding where tenant_id=? and id=?
        """, false, null),
    RESPONSIBILITY_BINDING_LOCK("""
        select tenant_id,id,organization_id,version,created_at,updated_at,created_by,updated_by,last_command_id,work_unit_id,org_id,position_id,duty,valid_from,valid_to,end_observed_at,org_code_snapshot,org_name_snapshot,position_code_snapshot,position_name_snapshot,directory_observed_at from public.responsibility_binding where tenant_id=? and id=? for update
        """, false, null),
    RESPONSIBILITY_BINDING_CURRENT("""
        select tenant_id,id,organization_id,version,created_at,updated_at,created_by,updated_by,last_command_id,work_unit_id,org_id,position_id,duty,valid_from,valid_to,end_observed_at,org_code_snapshot,org_name_snapshot,position_code_snapshot,position_name_snapshot,directory_observed_at from public.responsibility_binding where tenant_id=? and id=? and organization_id=any(cast(? as uuid[]))
        """, true, null),
    RESPONSIBILITY_BINDING_INSERT("""
        insert into public.responsibility_binding (tenant_id,id,organization_id,version,created_at,updated_at,created_by,updated_by,last_command_id,work_unit_id,org_id,position_id,duty,valid_from,valid_to,end_observed_at,org_code_snapshot,org_name_snapshot,position_code_snapshot,position_name_snapshot,directory_observed_at) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
        """, false, "responsibility_binding.insert"),
    RESPONSIBILITY_BINDING_END("""
        update public.responsibility_binding set valid_to=?,end_observed_at=?,version=?,updated_at=?,updated_by=?,last_command_id=? where tenant_id=? and id=? and version=?
        """, false, "responsibility_binding.end"),
    RESPONSIBILITY_BINDING_REVISION("""
        insert into public.responsibility_binding_revision (tenant_id,entity_id,version,command_id,change_kind,occurred_at,actor_id,snapshot_organization_id,snapshot_created_at,snapshot_updated_at,snapshot_created_by,snapshot_updated_by,snapshot_work_unit_id,snapshot_org_id,snapshot_position_id,snapshot_duty,snapshot_valid_from,snapshot_valid_to,snapshot_end_observed_at,snapshot_org_code_snapshot,snapshot_org_name_snapshot,snapshot_position_code_snapshot,snapshot_position_name_snapshot,snapshot_directory_observed_at) select tenant_id,id,version,last_command_id,?,updated_at,updated_by,organization_id,created_at,updated_at,created_by,updated_by,work_unit_id,org_id,position_id,duty,valid_from,valid_to,end_observed_at,org_code_snapshot,org_name_snapshot,position_code_snapshot,position_name_snapshot,directory_observed_at from public.responsibility_binding where tenant_id=? and id=? and version=? and last_command_id=?
        """, false, "responsibility_binding.revision"),
    RESPONSIBILITY_BINDING_RECEIPT("""
        insert into public.asset_foundation_receipt (tenant_id,command_id,actor_id,account_id,member_id,action_code,idempotency_key,fingerprint_version,payload_sha256,result_resource_type,result_version,result_occurred_at,audit_event_id,created_at,result_responsibility_binding_id,change_kind,trace_id) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
        """, false, "responsibility_binding.receipt"),
    WORKSHOP_ARCHIVE_CLEAR("""
        with cutoff as (select cast(? as uuid) as tenant_id,cast(? as uuid) as entity_id,cast(? as timestamptz) as observed_at)
        select not (exists (select 1 from public.production_line dependent where dependent.tenant_id=cutoff.tenant_id and dependent.workshop_id=cutoff.entity_id and lifecycle='ACTIVE')
            or exists (select 1 from public.line_workshop dependent where dependent.tenant_id=cutoff.tenant_id and dependent.workshop_id=cutoff.entity_id and (valid_to is null or valid_to>cutoff.observed_at))
            or exists (select 1 from public.work_unit dependent where dependent.tenant_id=cutoff.tenant_id and dependent.workshop_id=cutoff.entity_id and lifecycle='ACTIVE')) as archive_clear from cutoff
        """, true, null),
    PRODUCTION_LINE_ARCHIVE_CLEAR("""
        with cutoff as (select cast(? as uuid) as tenant_id,cast(? as uuid) as entity_id,cast(? as timestamptz) as observed_at)
        select not (exists (select 1 from public.process_stage dependent where dependent.tenant_id=cutoff.tenant_id and dependent.line_id=cutoff.entity_id and lifecycle='ACTIVE')
            or exists (select 1 from public.line_workshop dependent where dependent.tenant_id=cutoff.tenant_id and dependent.line_id=cutoff.entity_id and (valid_to is null or valid_to>cutoff.observed_at))
            or exists (select 1 from public.work_unit dependent where dependent.tenant_id=cutoff.tenant_id and dependent.line_id=cutoff.entity_id and lifecycle='ACTIVE')) as archive_clear from cutoff
        """, true, null),
    PROCESS_STAGE_ARCHIVE_CLEAR("""
        with cutoff as (select cast(? as uuid) as tenant_id,cast(? as uuid) as entity_id,cast(? as timestamptz) as observed_at)
        select not (exists (select 1 from public.stage_equipment dependent where dependent.tenant_id=cutoff.tenant_id and dependent.stage_id=cutoff.entity_id and (valid_to is null or valid_to>cutoff.observed_at))
            or exists (select 1 from public.work_unit dependent where dependent.tenant_id=cutoff.tenant_id and dependent.stage_id=cutoff.entity_id and lifecycle='ACTIVE')) as archive_clear from cutoff
        """, true, null),
    EQUIPMENT_ARCHIVE_CLEAR("""
        with cutoff as (select cast(? as uuid) as tenant_id,cast(? as uuid) as entity_id,cast(? as timestamptz) as observed_at)
        select not (exists (select 1 from public.stage_equipment dependent where dependent.tenant_id=cutoff.tenant_id and dependent.equipment_id=cutoff.entity_id and (valid_to is null or valid_to>cutoff.observed_at))
            or exists (select 1 from public.work_unit dependent where dependent.tenant_id=cutoff.tenant_id and dependent.equipment_id=cutoff.entity_id and lifecycle='ACTIVE')) as archive_clear from cutoff
        """, true, null),
    WORK_UNIT_ARCHIVE_CLEAR("""
        with cutoff as (select cast(? as uuid) as tenant_id,cast(? as uuid) as entity_id,cast(? as timestamptz) as observed_at)
        select not (exists (select 1 from public.hazard_source dependent where dependent.tenant_id=cutoff.tenant_id and dependent.work_unit_id=cutoff.entity_id)
            or exists (select 1 from public.responsibility_binding dependent where dependent.tenant_id=cutoff.tenant_id and dependent.work_unit_id=cutoff.entity_id and (valid_to is null or valid_to>cutoff.observed_at))) as archive_clear from cutoff
        """, true, null),
    PARENT_FENCE("""
        select public.g014_fence_parent(?,cast(? as uuid),cast(? as uuid),?)
        """, false, null),
    FLUSH_CONSTRAINTS("""
        set constraints g014_workshop_last_command_fk,
            g014_workshop_revision_receipt_fk,
            g014_receipt_workshop_result_fk,
            g014_workshop_history_complete,
            g014_production_line_last_command_fk,
            g014_production_line_revision_receipt_fk,
            g014_receipt_production_line_result_fk,
            g014_production_line_history_complete,
            g014_process_stage_last_command_fk,
            g014_process_stage_revision_receipt_fk,
            g014_receipt_process_stage_result_fk,
            g014_process_stage_history_complete,
            g014_equipment_last_command_fk,
            g014_equipment_revision_receipt_fk,
            g014_receipt_equipment_result_fk,
            g014_equipment_history_complete,
            g014_work_unit_last_command_fk,
            g014_work_unit_revision_receipt_fk,
            g014_receipt_work_unit_result_fk,
            g014_work_unit_history_complete,
            g014_hazard_source_last_command_fk,
            g014_hazard_source_revision_receipt_fk,
            g014_receipt_hazard_source_result_fk,
            g014_hazard_source_history_complete,
            g014_line_workshop_last_command_fk,
            g014_line_workshop_revision_receipt_fk,
            g014_receipt_line_workshop_result_fk,
            g014_line_workshop_history_complete,
            g014_stage_equipment_last_command_fk,
            g014_stage_equipment_revision_receipt_fk,
            g014_receipt_stage_equipment_result_fk,
            g014_stage_equipment_history_complete,
            g014_responsibility_binding_last_command_fk,
            g014_responsibility_binding_revision_receipt_fk,
            g014_receipt_responsibility_binding_result_fk,
            g014_responsibility_binding_history_complete,
            g014_line_workshop_line_label_revision_fk,
            g014_line_workshop_workshop_label_revision_fk,
            g014_stage_equipment_stage_label_revision_fk,
            g014_stage_equipment_equipment_label_revision_fk immediate
        """, false, null);
    final String sql;
    final boolean finalRead;
    final String effect;
    AssetFoundationSql(String sql,boolean finalRead,String effect) { this.sql=name().equals("FLUSH_CONSTRAINTS")?SqlTemplate.deferredConstraintsImmediate().sql():sql;this.finalRead=finalRead;this.effect=effect; }
    SqlTemplate template() { if(this==FLUSH_CONSTRAINTS)return SqlTemplate.deferredConstraintsImmediate(); return new SqlTemplate(sql,(int)sql.chars().filter(c->c=='?').count(),finalRead,effect); }
    static List<SqlTemplate> templates() { return java.util.Arrays.stream(values()).map(AssetFoundationSql::template).toList(); }
}
