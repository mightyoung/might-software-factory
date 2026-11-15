-- D0 V19-V25 direct runtime privilege reconciliation. Operator-applied, never Flyway.
-- psql -X -v ON_ERROR_STOP=1 -v runtime_role=... -v target_database=... \
--   -v d0_ack=d0-runtime-direct-grants-v1 -f d0-runtime-grants.sql
-- Run only against the approved isolated/new D0 database, after V25 and framework provisioning.
-- Existing V2-V18 permissions are preserved. No shared-role/PUBLIC/default ACL is changed.
\set ON_ERROR_STOP on
begin;
select set_config('d0.runtime_role', :'runtime_role', true),
       set_config('d0.target_database', :'target_database', true),
       set_config('d0.ack', :'d0_ack', true);

do $$
declare runtime record; role_name text := current_setting('d0.runtime_role');
begin
    if current_setting('d0.ack') <> 'd0-runtime-direct-grants-v1'
       or current_database() <> current_setting('d0.target_database')
       or (current_database() <> 'might_software_factory_dev_20261115'
           and current_database() !~ '^msf_d0_[a-z0-9_]+_test$') then
        raise exception 'D0 target acknowledgement mismatch';
    end if;
    select * into strict runtime from pg_roles where rolname=role_name;
    if runtime.rolsuper or runtime.rolbypassrls or runtime.rolcreaterole or runtime.rolcreatedb
       or runtime.rolreplication or not runtime.rolcanlogin or role_name=session_user then
        raise exception 'D0 requires a separate non-elevated login runtime role';
    end if;
    if exists(select 1 from pg_auth_members where member=runtime.oid) then
        raise exception 'D0 runtime role memberships require separate review';
    end if;
    if exists(select 1 from pg_class c join pg_namespace n on n.oid=c.relnamespace
              where n.nspname='public' and c.relowner=runtime.oid)
       or exists(select 1 from pg_proc p join pg_namespace n on n.oid=p.pronamespace
              where n.nspname='public' and p.proowner=runtime.oid) then
        raise exception 'D0 runtime must not own application relations or routines';
    end if;
end $$;

create temporary table d0_runtime_tables (
    table_name text primary key, migration integer not null,
    select_columns text[] not null, insert_columns text[] not null, update_columns text[] not null
) on commit drop;
insert into d0_runtime_tables values
('account_recovery_contact',19,ARRAY['id','account_id','channel','destination','status','version','verified_at','verified_by_challenge_id']::text[],ARRAY['account_id','channel','destination','id','status','verified_at','verified_by_challenge_id','version']::text[],ARRAY['version']::text[]),
('identity_credential_challenge',19,ARRAY['id','purpose','target_account_id','target_tenant_id','issuer_account_id','issuer_actor_id','issuer_tenant_id','issuer_member_id','issuer_credential_version','issuer_tenant_epoch','issuer_member_epoch','intended_login','display_name','channel','destination','destination_digest','recovery_contact_id','issued_contact_version','issued_credential_version','secret_digest','state','version','created_at','expires_at','consumed_receipt_id']::text[],ARRAY['channel','created_at','destination','destination_digest','display_name','expires_at','id','intended_login','issued_contact_version','issued_credential_version','issuer_account_id','issuer_actor_id','issuer_credential_version','issuer_member_epoch','issuer_member_id','issuer_tenant_epoch','issuer_tenant_id','purpose','recovery_contact_id','secret_digest','state','target_account_id','target_tenant_id','version']::text[],ARRAY['consumed_receipt_id','state','version']::text[]),
('identity_lifecycle_command_receipt',19,ARRAY['id','operation','owner_kind','owner_id','command_key','challenge_id','fingerprint_key_id','fingerprint','result_account_id','result_actor_id','result_member_id','result_contact_id','result_challenge_id','result_version','result_expires_at','result_delivery_state','completed_at']::text[],ARRAY['challenge_id','command_key','completed_at','fingerprint','fingerprint_key_id','id','operation','owner_id','owner_kind','result_account_id','result_actor_id','result_challenge_id','result_contact_id','result_delivery_state','result_expires_at','result_member_id','result_version']::text[],ARRAY[]::text[]),
('identity_capability_delivery_attempt',19,ARRAY['id','challenge_id','provider_generation','template_version','state','version','origin_instance_id','created_at','delivery_deadline_at','occurred_at']::text[],ARRAY['challenge_id','created_at','delivery_deadline_at','id','occurred_at','origin_instance_id','provider_generation','state','template_version','version']::text[],ARRAY[]::text[]),
('identity_lifecycle_event',19,ARRAY['id','challenge_id','operation','result','reason','initiator_kind','tenant_id','subject_account_id','subject_actor_id','initiator_account_id','initiator_actor_id','initiator_member_id','selector_digest','trace_id','policy_version','occurred_at']::text[],ARRAY['challenge_id','id','initiator_account_id','initiator_actor_id','initiator_kind','initiator_member_id','occurred_at','operation','policy_version','reason','result','subject_account_id','subject_actor_id','tenant_id','trace_id']::text[],ARRAY[]::text[]),
('identity_lifecycle_rate_bucket',19,ARRAY['selector_digest','window_start','attempts']::text[],ARRAY['attempts','selector_digest','window_start']::text[],ARRAY['attempts']::text[]),
('session_authority_activation',20,ARRAY['singleton','state','version','activated_at','activation_event_id','activation_evidence_sha256']::text[],ARRAY[]::text[],ARRAY[]::text[]),
('account_session_authority',20,ARRAY['primary_id','account_id','binding_kind','session_key_id','session_digest','bound_credential_version','authenticated_at','absolute_expires_at','state','version','created_at','created_event_id','revoked_at','revocation_event_id']::text[],ARRAY['absolute_expires_at','account_id','authenticated_at','binding_kind','bound_credential_version','created_at','created_event_id','primary_id','revocation_event_id','revoked_at','session_digest','session_key_id','state','version']::text[],ARRAY['revocation_event_id','revoked_at','state','version']::text[]),
('sensitive_reauth_proof',20,ARRAY['id','account_id','actor_id','source_primary_id','issued_credential_version','action','target_primary_id','expected_authority_version','expected_contact_id','expected_contact_version','destination_key_id','destination_digest','expected_pending_change_id','expected_pending_change_version','body_key_id','body_digest','secret_digest','state','version','created_at','expires_at','issuance_receipt_id','session_consume_receipt_id','contact_consume_receipt_id']::text[],ARRAY[]::text[],ARRAY['session_consume_receipt_id','state','version']::text[]),
('sensitive_reauth_issuance_receipt',20,ARRAY['id','account_id','actor_id','source_primary_id','command_key','fingerprint_key_id','fingerprint','proof_id','expires_at','event_id','completed_at']::text[],ARRAY[]::text[],ARRAY[]::text[]),
('session_authority_command_receipt',20,ARRAY['id','operation','account_id','actor_id','source_primary_id','target_primary_id','command_key','expected_authority_version','fingerprint_key_id','fingerprint','proof_id','result_authority_version','event_id','completed_at']::text[],ARRAY['account_id','actor_id','command_key','completed_at','event_id','expected_authority_version','fingerprint','fingerprint_key_id','id','operation','proof_id','result_authority_version','source_primary_id','target_primary_id']::text[],ARRAY[]::text[]),
('recovery_contact_change',20,ARRAY['id','account_id','actor_id','source_primary_id','issued_credential_version','proof_id','expected_contact_id','expected_contact_version','previous_pending_id','previous_pending_version','channel','destination','destination_key_id','destination_digest','secret_digest','state','version','created_at','expires_at','consumed_receipt_id']::text[],ARRAY[]::text[],ARRAY[]::text[]),
('recovery_contact_command_receipt',20,ARRAY['id','operation','account_id','actor_id','source_primary_id','command_key','challenge_id','fingerprint_key_id','fingerprint','proof_id','result_contact_id','result_challenge_version','event_id','completed_at']::text[],ARRAY[]::text[],ARRAY[]::text[]),
('recovery_contact_delivery_attempt',20,ARRAY['id','challenge_id','provider_generation','template_version','state','version','origin_instance_id','created_at','delivery_deadline_at','occurred_at']::text[],ARRAY[]::text[],ARRAY[]::text[]),
('identity_sensitive_event',20,ARRAY['id','operation','result','reason','provenance','subject_account_id','actor_id','source_primary_id','target_primary_id','proof_id','challenge_id','trace_id','policy_version','occurred_at']::text[],ARRAY['actor_id','challenge_id','id','occurred_at','operation','policy_version','proof_id','provenance','reason','result','source_primary_id','subject_account_id','target_primary_id','trace_id']::text[],ARRAY[]::text[]),
('workshop',21,ARRAY['tenant_id','id','organization_id','version','interval_mutex_revision','created_at','updated_at','created_by','updated_by','last_command_id','code','name','boundary_description','lifecycle']::text[],ARRAY['boundary_description','code','created_at','created_by','id','last_command_id','lifecycle','name','organization_id','tenant_id','updated_at','updated_by','version']::text[],ARRAY['boundary_description','interval_mutex_revision','last_command_id','lifecycle','name','updated_at','updated_by','version']::text[]),
('production_line',21,ARRAY['tenant_id','id','organization_id','version','interval_mutex_revision','created_at','updated_at','created_by','updated_by','last_command_id','code','name','workshop_id','description','lifecycle']::text[],ARRAY['code','created_at','created_by','description','id','last_command_id','lifecycle','name','organization_id','tenant_id','updated_at','updated_by','version','workshop_id']::text[],ARRAY['description','interval_mutex_revision','last_command_id','lifecycle','name','updated_at','updated_by','version']::text[]),
('process_stage',21,ARRAY['tenant_id','id','organization_id','version','interval_mutex_revision','created_at','updated_at','created_by','updated_by','last_command_id','code','name','line_id','ordinal','description','lifecycle']::text[],ARRAY['code','created_at','created_by','description','id','last_command_id','lifecycle','line_id','name','ordinal','organization_id','tenant_id','updated_at','updated_by','version']::text[],ARRAY['description','interval_mutex_revision','last_command_id','lifecycle','name','ordinal','updated_at','updated_by','version']::text[]),
('equipment',21,ARRAY['tenant_id','id','organization_id','version','interval_mutex_revision','created_at','updated_at','created_by','updated_by','last_command_id','code','name','type_description','location_description','operational_state','lifecycle']::text[],ARRAY['code','created_at','created_by','id','last_command_id','lifecycle','location_description','name','operational_state','organization_id','tenant_id','type_description','updated_at','updated_by','version']::text[],ARRAY['interval_mutex_revision','last_command_id','lifecycle','location_description','name','operational_state','type_description','updated_at','updated_by','version']::text[]),
('work_unit',21,ARRAY['tenant_id','id','organization_id','version','interval_mutex_revision','created_at','updated_at','created_by','updated_by','last_command_id','code','name','kind','workshop_id','line_id','stage_id','equipment_id','boundary_description','lifecycle']::text[],ARRAY['boundary_description','code','created_at','created_by','equipment_id','id','kind','last_command_id','lifecycle','line_id','name','organization_id','stage_id','tenant_id','updated_at','updated_by','version','workshop_id']::text[],ARRAY['boundary_description','interval_mutex_revision','last_command_id','lifecycle','name','updated_at','updated_by','version']::text[]),
('hazard_source',21,ARRAY['tenant_id','id','organization_id','version','interval_mutex_revision','created_at','updated_at','created_by','updated_by','last_command_id','code','work_unit_id','description','identified_at','existence_state','factor_code_ref','factor_version']::text[],ARRAY['code','created_at','created_by','description','existence_state','factor_code_ref','factor_version','id','identified_at','last_command_id','organization_id','tenant_id','updated_at','updated_by','version','work_unit_id']::text[],ARRAY['description','existence_state','identified_at','last_command_id','updated_at','updated_by','version']::text[]),
('line_workshop',21,ARRAY['tenant_id','id','organization_id','version','created_at','updated_at','created_by','updated_by','last_command_id','line_id','line_version_at_bind','workshop_id','workshop_version_at_bind','valid_from','valid_to','end_observed_at']::text[],ARRAY['created_at','created_by','end_observed_at','id','last_command_id','line_id','organization_id','tenant_id','updated_at','updated_by','valid_from','valid_to','version','workshop_id']::text[],ARRAY['end_observed_at','last_command_id','updated_at','updated_by','valid_to','version']::text[]),
('stage_equipment',21,ARRAY['tenant_id','id','organization_id','version','created_at','updated_at','created_by','updated_by','last_command_id','stage_id','stage_version_at_bind','equipment_id','equipment_version_at_bind','valid_from','valid_to','end_observed_at']::text[],ARRAY['created_at','created_by','end_observed_at','equipment_id','id','last_command_id','organization_id','stage_id','tenant_id','updated_at','updated_by','valid_from','valid_to','version']::text[],ARRAY['end_observed_at','last_command_id','updated_at','updated_by','valid_to','version']::text[]),
('responsibility_binding',21,ARRAY['tenant_id','id','organization_id','version','created_at','updated_at','created_by','updated_by','last_command_id','work_unit_id','org_id','position_id','duty','valid_from','valid_to','end_observed_at','org_code_snapshot','org_name_snapshot','position_code_snapshot','position_name_snapshot','directory_observed_at']::text[],ARRAY['created_at','created_by','directory_observed_at','duty','end_observed_at','id','last_command_id','org_code_snapshot','org_id','org_name_snapshot','organization_id','position_code_snapshot','position_id','position_name_snapshot','tenant_id','updated_at','updated_by','valid_from','valid_to','version','work_unit_id']::text[],ARRAY['end_observed_at','last_command_id','updated_at','updated_by','valid_to','version']::text[]),
('workshop_revision',21,ARRAY['tenant_id','entity_id','version','command_id','change_kind','occurred_at','actor_id','snapshot_organization_id','snapshot_created_at','snapshot_updated_at','snapshot_created_by','snapshot_updated_by','snapshot_code','snapshot_name','snapshot_boundary_description','snapshot_lifecycle']::text[],ARRAY['actor_id','change_kind','command_id','entity_id','occurred_at','snapshot_boundary_description','snapshot_code','snapshot_created_at','snapshot_created_by','snapshot_lifecycle','snapshot_name','snapshot_organization_id','snapshot_updated_at','snapshot_updated_by','tenant_id','version']::text[],ARRAY[]::text[]),
('production_line_revision',21,ARRAY['tenant_id','entity_id','version','command_id','change_kind','occurred_at','actor_id','snapshot_organization_id','snapshot_created_at','snapshot_updated_at','snapshot_created_by','snapshot_updated_by','snapshot_code','snapshot_name','snapshot_workshop_id','snapshot_description','snapshot_lifecycle']::text[],ARRAY['actor_id','change_kind','command_id','entity_id','occurred_at','snapshot_code','snapshot_created_at','snapshot_created_by','snapshot_description','snapshot_lifecycle','snapshot_name','snapshot_organization_id','snapshot_updated_at','snapshot_updated_by','snapshot_workshop_id','tenant_id','version']::text[],ARRAY[]::text[]),
('process_stage_revision',21,ARRAY['tenant_id','entity_id','version','command_id','change_kind','occurred_at','actor_id','snapshot_organization_id','snapshot_created_at','snapshot_updated_at','snapshot_created_by','snapshot_updated_by','snapshot_code','snapshot_name','snapshot_line_id','snapshot_ordinal','snapshot_description','snapshot_lifecycle']::text[],ARRAY['actor_id','change_kind','command_id','entity_id','occurred_at','snapshot_code','snapshot_created_at','snapshot_created_by','snapshot_description','snapshot_lifecycle','snapshot_line_id','snapshot_name','snapshot_ordinal','snapshot_organization_id','snapshot_updated_at','snapshot_updated_by','tenant_id','version']::text[],ARRAY[]::text[]),
('equipment_revision',21,ARRAY['tenant_id','entity_id','version','command_id','change_kind','occurred_at','actor_id','snapshot_organization_id','snapshot_created_at','snapshot_updated_at','snapshot_created_by','snapshot_updated_by','snapshot_code','snapshot_name','snapshot_type_description','snapshot_location_description','snapshot_operational_state','snapshot_lifecycle']::text[],ARRAY['actor_id','change_kind','command_id','entity_id','occurred_at','snapshot_code','snapshot_created_at','snapshot_created_by','snapshot_lifecycle','snapshot_location_description','snapshot_name','snapshot_operational_state','snapshot_organization_id','snapshot_type_description','snapshot_updated_at','snapshot_updated_by','tenant_id','version']::text[],ARRAY[]::text[]),
('work_unit_revision',21,ARRAY['tenant_id','entity_id','version','command_id','change_kind','occurred_at','actor_id','snapshot_organization_id','snapshot_created_at','snapshot_updated_at','snapshot_created_by','snapshot_updated_by','snapshot_code','snapshot_name','snapshot_kind','snapshot_workshop_id','snapshot_line_id','snapshot_stage_id','snapshot_equipment_id','snapshot_boundary_description','snapshot_lifecycle']::text[],ARRAY['actor_id','change_kind','command_id','entity_id','occurred_at','snapshot_boundary_description','snapshot_code','snapshot_created_at','snapshot_created_by','snapshot_equipment_id','snapshot_kind','snapshot_lifecycle','snapshot_line_id','snapshot_name','snapshot_organization_id','snapshot_stage_id','snapshot_updated_at','snapshot_updated_by','snapshot_workshop_id','tenant_id','version']::text[],ARRAY[]::text[]),
('hazard_source_revision',21,ARRAY['tenant_id','entity_id','version','command_id','change_kind','occurred_at','actor_id','snapshot_organization_id','snapshot_created_at','snapshot_updated_at','snapshot_created_by','snapshot_updated_by','snapshot_code','snapshot_work_unit_id','snapshot_description','snapshot_identified_at','snapshot_existence_state','snapshot_factor_code_ref','snapshot_factor_version']::text[],ARRAY['actor_id','change_kind','command_id','entity_id','occurred_at','snapshot_code','snapshot_created_at','snapshot_created_by','snapshot_description','snapshot_existence_state','snapshot_factor_code_ref','snapshot_factor_version','snapshot_identified_at','snapshot_organization_id','snapshot_updated_at','snapshot_updated_by','snapshot_work_unit_id','tenant_id','version']::text[],ARRAY[]::text[]),
('line_workshop_revision',21,ARRAY['tenant_id','entity_id','version','command_id','change_kind','occurred_at','actor_id','snapshot_organization_id','snapshot_created_at','snapshot_updated_at','snapshot_created_by','snapshot_updated_by','snapshot_line_id','snapshot_line_version_at_bind','snapshot_workshop_id','snapshot_workshop_version_at_bind','snapshot_valid_from','snapshot_valid_to','snapshot_end_observed_at']::text[],ARRAY['actor_id','change_kind','command_id','entity_id','occurred_at','snapshot_created_at','snapshot_created_by','snapshot_end_observed_at','snapshot_line_id','snapshot_line_version_at_bind','snapshot_organization_id','snapshot_updated_at','snapshot_updated_by','snapshot_valid_from','snapshot_valid_to','snapshot_workshop_id','snapshot_workshop_version_at_bind','tenant_id','version']::text[],ARRAY[]::text[]),
('stage_equipment_revision',21,ARRAY['tenant_id','entity_id','version','command_id','change_kind','occurred_at','actor_id','snapshot_organization_id','snapshot_created_at','snapshot_updated_at','snapshot_created_by','snapshot_updated_by','snapshot_stage_id','snapshot_stage_version_at_bind','snapshot_equipment_id','snapshot_equipment_version_at_bind','snapshot_valid_from','snapshot_valid_to','snapshot_end_observed_at']::text[],ARRAY['actor_id','change_kind','command_id','entity_id','occurred_at','snapshot_created_at','snapshot_created_by','snapshot_end_observed_at','snapshot_equipment_id','snapshot_equipment_version_at_bind','snapshot_organization_id','snapshot_stage_id','snapshot_stage_version_at_bind','snapshot_updated_at','snapshot_updated_by','snapshot_valid_from','snapshot_valid_to','tenant_id','version']::text[],ARRAY[]::text[]),
('responsibility_binding_revision',21,ARRAY['tenant_id','entity_id','version','command_id','change_kind','occurred_at','actor_id','snapshot_organization_id','snapshot_created_at','snapshot_updated_at','snapshot_created_by','snapshot_updated_by','snapshot_work_unit_id','snapshot_org_id','snapshot_position_id','snapshot_duty','snapshot_valid_from','snapshot_valid_to','snapshot_end_observed_at','snapshot_org_code_snapshot','snapshot_org_name_snapshot','snapshot_position_code_snapshot','snapshot_position_name_snapshot','snapshot_directory_observed_at']::text[],ARRAY['actor_id','change_kind','command_id','entity_id','occurred_at','snapshot_created_at','snapshot_created_by','snapshot_directory_observed_at','snapshot_duty','snapshot_end_observed_at','snapshot_org_code_snapshot','snapshot_org_id','snapshot_org_name_snapshot','snapshot_organization_id','snapshot_position_code_snapshot','snapshot_position_id','snapshot_position_name_snapshot','snapshot_updated_at','snapshot_updated_by','snapshot_valid_from','snapshot_valid_to','snapshot_work_unit_id','tenant_id','version']::text[],ARRAY[]::text[]),
('asset_foundation_receipt',21,ARRAY['tenant_id','command_id','actor_id','account_id','member_id','action_code','idempotency_key','fingerprint_version','payload_sha256','result_resource_type','result_version','result_occurred_at','audit_event_id','created_at','result_workshop_id','result_production_line_id','result_process_stage_id','result_equipment_id','result_work_unit_id','result_hazard_source_id','result_line_workshop_id','result_stage_equipment_id','result_responsibility_binding_id','change_kind','trace_id']::text[],ARRAY['account_id','action_code','actor_id','audit_event_id','change_kind','command_id','created_at','fingerprint_version','idempotency_key','member_id','payload_sha256','result_equipment_id','result_hazard_source_id','result_line_workshop_id','result_occurred_at','result_process_stage_id','result_production_line_id','result_resource_type','result_responsibility_binding_id','result_stage_equipment_id','result_version','result_work_unit_id','result_workshop_id','tenant_id','trace_id']::text[],ARRAY[]::text[]),
('risk_scenario',22,ARRAY['tenant_id','id','organization_id','code','version','description','triggering_conditions','exposure_description','consequence','accident_code_ref','accident_version','created_by_actor_id','created_by_member_id','created_at','updated_by_actor_id','updated_by_member_id','updated_at','last_command_id']::text[],ARRAY['accident_code_ref','accident_version','code','consequence','created_at','created_by_actor_id','created_by_member_id','description','exposure_description','id','last_command_id','organization_id','tenant_id','triggering_conditions','updated_at','updated_by_actor_id','updated_by_member_id','version']::text[],ARRAY['consequence','description','exposure_description','last_command_id','triggering_conditions','updated_at','updated_by_actor_id','updated_by_member_id','version']::text[]),
('risk_scenario_revision',22,ARRAY['tenant_id','entity_id','organization_id','code','version','description','triggering_conditions','exposure_description','consequence','accident_code_ref','accident_version','created_by_actor_id','created_by_member_id','created_at','updated_by_actor_id','updated_by_member_id','updated_at','command_id','actor_id','member_id','occurred_at','change_kind']::text[],ARRAY['accident_code_ref','accident_version','actor_id','change_kind','code','command_id','consequence','created_at','created_by_actor_id','created_by_member_id','description','entity_id','exposure_description','member_id','occurred_at','organization_id','tenant_id','triggering_conditions','updated_at','updated_by_actor_id','updated_by_member_id','version']::text[],ARRAY[]::text[]),
('risk_hazard',22,ARRAY['tenant_id','id','scenario_id','hazard_id','hazard_version_at_link','version','linked_at','ended_at','created_by_actor_id','created_by_member_id','last_scenario_version','last_command_id','last_actor_id','last_member_id','last_occurred_at']::text[],ARRAY['created_by_actor_id','created_by_member_id','ended_at','hazard_id','hazard_version_at_link','id','last_actor_id','last_command_id','last_member_id','last_occurred_at','last_scenario_version','linked_at','scenario_id','tenant_id','version']::text[],ARRAY['ended_at','last_actor_id','last_command_id','last_member_id','last_occurred_at','last_scenario_version','version']::text[]),
('risk_hazard_revision',22,ARRAY['tenant_id','entity_id','scenario_id','hazard_id','hazard_version_at_link','version','linked_at','ended_at','created_by_actor_id','created_by_member_id','scenario_version','command_id','actor_id','member_id','occurred_at']::text[],ARRAY['actor_id','command_id','created_by_actor_id','created_by_member_id','ended_at','entity_id','hazard_id','hazard_version_at_link','linked_at','member_id','occurred_at','scenario_id','scenario_version','tenant_id','version']::text[],ARRAY[]::text[]),
('risk_scenario_revision_hazard',22,ARRAY['tenant_id','scenario_id','scenario_version','hazard_id','link_id','link_version','hazard_version_at_link','hazard_version_at_scenario_revision','snapshot_code','full_description','description_preview','description_truncated','existence','command_id','actor_id','member_id','occurred_at']::text[],ARRAY['actor_id','command_id','description_preview','description_truncated','existence','full_description','hazard_id','hazard_version_at_link','hazard_version_at_scenario_revision','link_id','link_version','member_id','occurred_at','scenario_id','scenario_version','snapshot_code','tenant_id']::text[],ARRAY[]::text[]),
('risk_scenario_receipt',22,ARRAY['tenant_id','command_id','actor_id','account_id','member_id','action_code','idempotency_key','fingerprint_version','payload_sha256','result_scenario_id','result_version','result_code','occurred_at','audit_event_id','trace_id']::text[],ARRAY['account_id','action_code','actor_id','audit_event_id','command_id','fingerprint_version','idempotency_key','member_id','occurred_at','payload_sha256','result_code','result_scenario_id','result_version','tenant_id','trace_id']::text[],ARRAY[]::text[]),
('risk_change_cause',24,ARRAY['tenant_id','source_command_id','consumer_version','recorded_at','recorded_by_service_actor_id']::text[],ARRAY['consumer_version','recorded_at','recorded_by_service_actor_id','source_command_id','tenant_id']::text[],ARRAY[]::text[]),
('permission_change_event',25,ARRAY['tenant_id','id','role_id','actor_member_id','added_permission_codes','removed_permission_codes','trace_id','occurred_at']::text[],ARRAY['actor_member_id','added_permission_codes','id','occurred_at','removed_permission_codes','role_id','tenant_id','trace_id']::text[],ARRAY[]::text[]);

-- Ordinary V21/V22 invoker helpers only. Trigger bodies are intentionally excluded.
create temporary table d0_runtime_functions (signature text primary key) on commit drop;
insert into d0_runtime_functions values
('public.g014_instant_valid(timestamptz)'),
('public.g014_text_valid(text,integer,boolean)'),
('public.g014_lock_natural_key(text,uuid,text,uuid)'),
('public.g014_fence_parent(integer,uuid,uuid,boolean)'),
('public.g014_natural_key_bytes(text,uuid,text,uuid)'),
('public.g014_receipt_key_bytes(uuid,uuid,text,uuid)'),
('public.g014_mutation_key_plan(text,uuid,uuid,bigint,uuid)'),
('public.g014_require_natural_key(text,uuid,text,uuid)'),
('public.g014_lock_receipt_key(uuid,uuid,text,uuid)'),
('public.g014_require_receipt_key(uuid,uuid,text,uuid)'),
('public.g014_lock_mutation_keys(text,uuid,uuid,bigint,uuid)'),
('public.g014_require_mutation_keys(text,uuid,uuid,bigint,uuid)'),
('public.g014r_require_context(uuid)'),
('public.g014r_key_header(text,uuid)'),
('public.g014r_natural_key_bytes(uuid,text)'),
('public.g014r_receipt_key_bytes(uuid,uuid,text,uuid)'),
('public.g014r_mutation_key_bytes(text,uuid,uuid,bigint,uuid,uuid)'),
('public.g014r_key_hash(bytea)'),
('public.g014r_require_key(integer,bytea)'),
('public.g014r_lock_natural_key(uuid,text)'),
('public.g014r_require_natural_key(uuid,text)'),
('public.g014r_lock_receipt_key(uuid,uuid,text,uuid)'),
('public.g014r_require_mutation_key(text,uuid,uuid,bigint,uuid,uuid)'),
('public.g014r_require_scenario_keys(uuid,uuid,bigint,uuid)'),
('public.g014r_mutation_key_plan(uuid,uuid,bigint,uuid,uuid[],bigint[],uuid[])'),
('public.g014r_lock_mutation_keys(uuid,uuid,bigint,uuid,uuid[],bigint[],uuid[])'),
('public.g014r_require_identity(uuid,uuid,uuid)'),
('public.g014r_open_command(uuid,uuid,bigint,uuid,uuid,uuid,timestamptz)'),
('public.g014r_validate_root_revision(uuid,uuid,bigint,uuid)'),
('public.g014r_validate_receipt(public.risk_scenario_receipt)'),
('public.g014r_validate_scenario(uuid,uuid,bigint,uuid,boolean)'),
('public.g014r_lock_row(integer,uuid,uuid)');

do $$
declare spec record; privilege text; columns_sql text; routine record;
        role_name text := current_setting('d0.runtime_role'); rel regclass;
begin
    -- Validate the entire manifest before changing any ACL.
    for spec in select * from d0_runtime_tables loop
        rel := to_regclass('public.'||quote_ident(spec.table_name));
        if rel is null then raise exception 'Missing D0 relation %',spec.table_name; end if;
        if exists(select 1 from unnest(spec.select_columns||spec.insert_columns||spec.update_columns) name
                  where not exists(select 1 from pg_attribute a where a.attrelid=rel
                    and a.attname=name and a.attnum>0 and not a.attisdropped)) then
            raise exception 'D0 column manifest mismatch for %',spec.table_name;
        end if;
    end loop;
    for spec in select * from d0_runtime_functions loop
        select p.* into strict routine from pg_proc p where p.oid=to_regprocedure(spec.signature);
        if routine.prosecdef or routine.prorettype='pg_catalog.trigger'::regtype then
            raise exception 'D0 helper must be an ordinary invoker function: %',spec.signature;
        end if;
    end loop;
    for spec in select * from d0_runtime_tables loop
        rel := to_regclass('public.'||quote_ident(spec.table_name));
        -- Remove stale table grants and independently remove stale column grants.
        execute format('revoke select,insert,update,delete,truncate,references,trigger,maintain on table %s from %I',rel,role_name);
        select string_agg(quote_ident(attname),',') into columns_sql from pg_attribute
            where attrelid=rel and attnum>0 and not attisdropped;
        foreach privilege in array ARRAY['select','insert','update','references'] loop
            execute format('revoke %s(%s) on table %s from %I',privilege,columns_sql,rel,role_name);
        end loop;
        foreach privilege in array ARRAY['select','insert','update'] loop
            select string_agg(quote_ident(name),',') into columns_sql
              from unnest(case privilege when 'select' then spec.select_columns
                          when 'insert' then spec.insert_columns else spec.update_columns end) name;
            if columns_sql is not null then
                execute format('grant %s(%s) on table %s to %I',privilege,columns_sql,rel,role_name);
            end if;
        end loop;
    end loop;
    for spec in select * from d0_runtime_functions loop
        execute format('grant execute on function %s to %I',to_regprocedure(spec.signature),role_name);
    end loop;
end $$;

-- Existing security tables: only exact additional columns needed by implemented invitation commands.
grant insert(id,login_key,status,credential_version) on public.account to :"runtime_role";
grant insert(id,kind,account_id,status) on public.principal to :"runtime_role";
grant insert(account_id,kind,secret_hash,changed_at) on public.credential to :"runtime_role";
grant insert(tenant_id,id,account_id,status,auth_epoch,display_name) on public.tenant_member to :"runtime_role";
-- Existing-member invitation checks use FOR UPDATE; PostgreSQL requires one writable column.
grant update(auth_epoch) on public.tenant_member to :"runtime_role";
-- Permission replacement explicitly deletes rows then inserts the chosen scope/permission tuple.
grant insert(tenant_id,role_id,permission_id,scope_rule_id),delete on public.role_permission to :"runtime_role";
-- V23 invoker parent checks use FOR KEY SHARE, which requires one UPDATE column per parent.
-- These are writable metadata columns, not immutable no-op columns. Semantic identity stays read-only.
grant update(label) on public.permission to :"runtime_role";
grant update(version_no) on public.scope_rule to :"runtime_role";

-- Fail the transaction if PUBLIC or an unreviewed grant still widens any managed table.
do $$
declare spec record; col record; privilege text; expected boolean; actual boolean;
        role_name text := current_setting('d0.runtime_role'); rel regclass;
begin
    for spec in select * from d0_runtime_tables loop
        rel := to_regclass('public.'||quote_ident(spec.table_name));
        foreach privilege in array ARRAY['SELECT','INSERT','UPDATE','DELETE','TRUNCATE','REFERENCES','TRIGGER','MAINTAIN'] loop
            if has_table_privilege(role_name,rel,privilege) then
                raise exception 'Unexpected effective table privilege % on %',privilege,spec.table_name;
            end if;
        end loop;
        for col in select attname,attnum from pg_attribute where attrelid=rel and attnum>0 and not attisdropped loop
            foreach privilege in array ARRAY['SELECT','INSERT','UPDATE','REFERENCES'] loop
                expected := case privilege when 'SELECT' then col.attname=any(spec.select_columns)
                    when 'INSERT' then col.attname=any(spec.insert_columns)
                    when 'UPDATE' then col.attname=any(spec.update_columns) else false end;
                actual := has_column_privilege(role_name,rel,col.attnum,privilege);
                if actual is distinct from expected then
                    raise exception 'D0 effective column privilege mismatch %.% %',spec.table_name,col.attname,privilege;
                end if;
            end loop;
        end loop;
    end loop;
    -- The V23 parent-lock delta must not accidentally permit semantic or whole-table updates.
    for spec in select * from (values
        ('permission','label'),('scope_rule','version_no')
    ) parents(table_name,lock_column) loop
        rel := to_regclass('public.'||quote_ident(spec.table_name));
        if has_table_privilege(role_name,rel,'UPDATE') then
            raise exception 'D0 parent locking forbids table UPDATE on %',spec.table_name;
        end if;
        for col in select attname,attnum from pg_attribute where attrelid=rel and attnum>0 and not attisdropped loop
            actual := has_column_privilege(role_name,rel,col.attnum,'UPDATE');
            if actual is distinct from (col.attname=spec.lock_column) then
                raise exception 'D0 parent locking UPDATE mismatch %.%',spec.table_name,col.attname;
            end if;
        end loop;
    end loop;
end $$;

select count(*) as reconciled_tables from d0_runtime_tables;
select count(*) as executable_helpers from d0_runtime_functions;
commit;
