-- Owned foundation prerequisites only, not proof of Asset/hazard producer acceptance.
-- No risk_scenario/risk receipt/risk audit/publication rows are seeded here.
DO $fixture$
DECLARE
 t uuid := '@tenant@'; a uuid := '@actor@'; acct uuid := '@account@'; m uuid := '@member@'; o uuid := '@organization@';
 w uuid := '@work@'; h uuid := '@hazard@';
 wc uuid := '@workCommand@'; hc uuid := '@hazardCommand@'; tr uuid := '@trace@';
 wa uuid := '@workAudit@'; ha uuid := '@hazardAudit@';
 wi uuid := '@workIdempotency@'; hi uuid := '@hazardIdempotency@';
 at_time timestamptz := '@occurredAt@'; tenant_bits bigint;
 wcode text := 'WU-'||w::text; hcode text := 'HZ-'||h::text;
BEGIN
 PERFORM pg_catalog.set_config('app.tenant_id',t::text,true);
 PERFORM pg_catalog.pg_advisory_xact_lock_shared(1297303111,1);
 tenant_bits := ('x'||substr(encode(sha256(convert_to('msf.authorization.v1/tenant/'||t::text,'UTF8')),'hex'),1,16))::bit(64)::bigint;
 PERFORM pg_catalog.pg_advisory_xact_lock_shared(tenant_bits);
 PERFORM public.g014_lock_natural_key('work_unit',t,wcode,null);
 PERFORM public.g014_lock_receipt_key(t,a,'work_unit.create',wi);
 PERFORM public.g014_lock_mutation_keys('work_unit',t,w,1,wc);
 PERFORM public.g014_lock_natural_key('hazard_source',t,hcode,null);
 PERFORM public.g014_lock_receipt_key(t,a,'hazard_source.create',hi);
 PERFORM public.g014_lock_mutation_keys('hazard_source',t,h,1,hc);
 INSERT INTO public.work_unit(tenant_id,id,organization_id,version,created_at,updated_at,created_by,updated_by,last_command_id,code,name,kind,lifecycle)
 VALUES(t,w,o,1,at_time,at_time,a,a,wc,wcode,'CI standalone unit','STANDALONE','ACTIVE');
 INSERT INTO public.work_unit_revision(tenant_id,entity_id,version,command_id,change_kind,occurred_at,actor_id,snapshot_organization_id,snapshot_created_at,snapshot_updated_at,snapshot_created_by,snapshot_updated_by,snapshot_code,snapshot_name,snapshot_kind,snapshot_workshop_id,snapshot_line_id,snapshot_stage_id,snapshot_equipment_id,snapshot_boundary_description,snapshot_lifecycle)
 SELECT tenant_id,id,version,last_command_id,'CREATE',updated_at,updated_by,organization_id,created_at,updated_at,created_by,updated_by,code,name,kind,workshop_id,line_id,stage_id,equipment_id,boundary_description,lifecycle FROM public.work_unit WHERE tenant_id=t AND id=w;
 INSERT INTO public.audit_event(tenant_id,id,actor_id,action,resource_type,resource_id,result,diff_redacted,trace_id,policy_version,occurred_at)
 VALUES(t,wa,a,'work_unit.create','work_unit',w,'SUCCESS',jsonb_build_object('schemaVersion',1,'commandId',wc::text,'changeKind','CREATE','changedFieldCategories',jsonb_build_array('IDENTITY','DESCRIPTION')),tr::text,'asset-foundation-v1',at_time);
 INSERT INTO public.asset_foundation_receipt(tenant_id,command_id,actor_id,account_id,member_id,action_code,idempotency_key,fingerprint_version,payload_sha256,result_resource_type,result_version,result_occurred_at,audit_event_id,created_at,result_work_unit_id,change_kind,trace_id)
 VALUES(t,wc,a,acct,m,'work_unit.create',wi,1,repeat('0',64),'work_unit',1,at_time,wa,at_time,w,'CREATE',tr);
 INSERT INTO public.hazard_source(tenant_id,id,organization_id,version,created_at,updated_at,created_by,updated_by,last_command_id,code,work_unit_id,description,identified_at,existence_state)
 VALUES(t,h,o,1,at_time,at_time,a,a,hc,hcode,w,'CI source hazard',at_time,'PRESENT');
 INSERT INTO public.hazard_source_revision(tenant_id,entity_id,version,command_id,change_kind,occurred_at,actor_id,snapshot_organization_id,snapshot_created_at,snapshot_updated_at,snapshot_created_by,snapshot_updated_by,snapshot_code,snapshot_work_unit_id,snapshot_description,snapshot_identified_at,snapshot_existence_state,snapshot_factor_code_ref,snapshot_factor_version)
 SELECT tenant_id,id,version,last_command_id,'CREATE',updated_at,updated_by,organization_id,created_at,updated_at,created_by,updated_by,code,work_unit_id,description,identified_at,existence_state,factor_code_ref,factor_version FROM public.hazard_source WHERE tenant_id=t AND id=h;
 INSERT INTO public.audit_event(tenant_id,id,actor_id,action,resource_type,resource_id,result,diff_redacted,trace_id,policy_version,occurred_at)
 VALUES(t,ha,a,'hazard_source.create','hazard_source',h,'SUCCESS',jsonb_build_object('schemaVersion',1,'commandId',hc::text,'changeKind','CREATE','changedFieldCategories',jsonb_build_array('IDENTITY','DESCRIPTION')),tr::text,'asset-foundation-v1',at_time);
 INSERT INTO public.asset_foundation_receipt(tenant_id,command_id,actor_id,account_id,member_id,action_code,idempotency_key,fingerprint_version,payload_sha256,result_resource_type,result_version,result_occurred_at,audit_event_id,created_at,result_hazard_source_id,change_kind,trace_id)
 VALUES(t,hc,a,acct,m,'hazard_source.create',hi,1,repeat('0',64),'hazard_source',1,at_time,ha,at_time,h,'CREATE',tr);
END
$fixture$;
