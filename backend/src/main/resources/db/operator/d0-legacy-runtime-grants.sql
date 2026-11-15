-- D0 V2-V18.3 + pinned Flowable32 / Spring Session2 runtime privilege reconciliation.
-- Operator-applied, never Flyway. Apply this file BEFORE d0-runtime-grants.sql (V19+ delta).
-- psql -X -v ON_ERROR_STOP=1 -v runtime_role=... -v target_database=... \
--   -v d0_ack=d0-runtime-direct-grants-v1 -f d0-legacy-runtime-grants.sql
-- The database must already have V26 and the selected framework tables. No role/PUBLIC/default ACL is changed.
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
       or current_database() !~ '^msf_d0_[a-z0-9_]+_test$' then
        raise exception 'D0 legacy runtime target acknowledgement mismatch';
    end if;
    select * into strict runtime from pg_roles where rolname=role_name;
    if runtime.rolsuper or runtime.rolbypassrls or runtime.rolcreaterole or runtime.rolcreatedb
       or runtime.rolreplication or not runtime.rolcanlogin or role_name=session_user then
        raise exception 'D0 requires a separate non-elevated login runtime role';
    end if;
    if exists(select 1 from pg_auth_members where member=runtime.oid) then
        raise exception 'D0 runtime role memberships require separate review';
    end if;
    if exists(select 1 from pg_class c join pg_namespace n on n.oid=c.relnamespace where n.nspname='public' and c.relowner=runtime.oid)
       or exists(select 1 from pg_proc p join pg_namespace n on n.oid=p.pronamespace where n.nspname='public' and p.proowner=runtime.oid)
       or exists(select 1 from pg_namespace where nspname='public' and nspowner=runtime.oid)
       or exists(select 1 from pg_database where datname=current_database() and datdba=runtime.oid) then
        raise exception 'D0 runtime must not own database/schema/application objects';
    end if;
    if has_database_privilege(role_name,current_database(),'CREATE,TEMP') or has_schema_privilege(role_name,'public','CREATE') then
        raise exception 'Remove effective runtime CREATE/TEMP rights separately, including inherited PUBLIC rights';
    end if;
    if not exists(select 1 from public.flyway_schema_history_app where version='26' and success) then
        raise exception 'Apply reviewed application migrations through V26 first';
    end if;
end $$;

create temporary table d0_legacy_runtime (
    table_name text primary key, readable boolean not null,
    insertable boolean not null, update_columns text[] not null,
    deletable boolean not null default false, framework boolean not null default false
) on commit drop;

-- Identity is read-only except the three columns used by self-service password change.
insert into d0_legacy_runtime(table_name,readable,insertable,update_columns)
select name,true,false,case name when 'account' then ARRAY['credential_version']
    when 'credential' then ARRAY['secret_hash','changed_at'] else ARRAY[]::text[] end
from unnest(ARRAY['account','principal','credential','tenant','tenant_application','tenant_member','org_unit',
    'member_org','position','member_position','user_group','group_member','permission','role','scope_rule',
    'role_permission','role_grant','scope_org']) name;

-- Mutable aggregates and durable command/provenance projections: only their concrete update columns.
insert into d0_legacy_runtime(table_name,readable,insertable,update_columns) values
('finding',true,true,ARRAY['grade','state','round_no','version','updated_at','updated_by_member_id','review_cycle','review_protocol']),
('finding_number_sequence',true,true,ARRAY['last_sequence']),
('finding_command_receipt',true,true,ARRAY['result_finding_id','result_state','result_round_no','result_version','result_review_cycle','completed_at']),
('remediation_round',true,true,ARRAY['state','closed_at','closed_by_command_id']),
('remediation_notice',true,true,ARRAY['state']),
('evidence_set',true,true,ARRAY['state','verified_at','evidence_revision','submission_sealed_at']),
('remediation_submission',true,true,ARRAY['evidence_snapshots_sealed_at']),
('finding_evidence_upload_intent',true,true,ARRAY['state','technical_version','lease_epoch','attempt_no','attempt_id','reserved_scan_attempt_id',
    'lease_token','lease_until','heartbeat_at','next_attempt_at','quarantine_allocation_id','immutable_allocation_id','scan_proof_id','content_receipt_id',
    'scanner_generation_sha256','last_error_code','cancel_requested','current_execution_id','technical_updated_at']);

-- Append-only records: no UPDATE/DELETE. SELECT is required for authorization, replay and retained evidence.
insert into d0_legacy_runtime(table_name,readable,insertable,update_columns)
select name,true,true,ARRAY[]::text[] from unnest(ARRAY[
    'workflow_binding','finding_transition','finding_confirmation','evidence_item','verification','audit_event',
    'file_object','file_link','remediation_submission_evidence','finding_evidence_set_initialization_receipt',
    'finding_review_cycle','finding_review_decision','finding_report_supplement','finding_command_participant',
    'file_ingestion_allocation','file_ingestion_execution','file_ingestion_allocation_event','file_scanner_generation',
    'file_ingestion_scan_proof','file_ingestion_content_receipt','file_ingestion_publication']) name;

-- FOR SHARE requires one UPDATE column. V6 rejects changes to tenant_id for both metadata tables.
-- Verify the immutable identity trigger is enabled before exposing that locking-only column.
do $$
declare spec record;
begin
    for spec in select * from (values
        ('file_object','file_object_submission_version_immutable','reject_file_object_submission_version_rewrite'),
        ('file_link','file_link_identity_immutable','reject_file_link_rebinding')
    ) guards(table_name,trigger_name,function_name) loop
        if not exists(select 1 from pg_trigger t join pg_proc p on p.oid=t.tgfoid
            join pg_namespace n on n.oid=p.pronamespace
            where t.tgrelid=to_regclass('public.'||quote_ident(spec.table_name))
              and t.tgname=spec.trigger_name and not t.tgisinternal and t.tgenabled in ('O','A')
              and n.nspname='public' and p.proname=spec.function_name) then
            raise exception 'D0 metadata locking requires enabled immutable identity guard on %',spec.table_name;
        end if;
    end loop;
end $$;
update d0_legacy_runtime set update_columns=ARRAY['tenant_id']
 where table_name in ('file_object','file_link');

insert into d0_legacy_runtime(table_name,readable,insertable,update_columns) values
('finding_stage_assignment',true,false,ARRAY[]::text[]),
('security_event',false,true,ARRAY[]::text[]),
('authentication_event',false,true,ARRAY[]::text[]);

-- The selected Flowable8 schema is common20 + engine7 + history5; no IDM/event-registry tables.
insert into d0_legacy_runtime(table_name,readable,insertable,update_columns,deletable,framework)
select name,true,true,ARRAY[]::text[],true,true from unnest(ARRAY[
    'act_ge_property','act_ge_bytearray','act_ru_entitylink','act_hi_entitylink','act_ru_identitylink','act_hi_identitylink',
    'act_ru_job','act_ru_timer_job','act_ru_suspended_job','act_ru_deadletter_job','act_ru_history_job','act_ru_external_job',
    'flw_ru_batch','flw_ru_batch_part','act_ru_task','act_hi_taskinst','act_hi_tsk_log','act_ru_variable','act_hi_varinst','act_ru_event_subscr',
    'act_hi_procinst','act_hi_actinst','act_hi_detail','act_hi_comment','act_hi_attachment',
    'act_re_deployment','act_re_model','act_ru_execution','act_re_procdef','act_evt_log','act_procdef_info','act_ru_actinst',
    'spring_session','spring_session_attributes','event_publication']) name;

do $$
declare spec record; rel regclass; columns_sql text; privilege text; col record; expected boolean;
        role_name text := current_setting('d0.runtime_role');
begin
    for spec in select * from d0_legacy_runtime loop
        rel := to_regclass('public.'||quote_ident(spec.table_name));
        if rel is null then raise exception 'Missing D0 prerequisite table: %',spec.table_name; end if;
        if exists(select 1 from unnest(spec.update_columns) as u(name) where not exists(
            select 1 from pg_attribute where attrelid=rel and attname=u.name and attnum>0 and not attisdropped)) then
            raise exception 'D0 update column manifest differs for %',spec.table_name;
        end if;
    end loop;
    execute format('grant connect on database %I to %I',current_database(),role_name);
    execute format('grant usage on schema public to %I',role_name);
    for spec in select * from d0_legacy_runtime loop
        rel := to_regclass('public.'||quote_ident(spec.table_name));
        execute format('revoke select,insert,update,delete,truncate,references,trigger,maintain on table %s from %I',rel,role_name);
        select string_agg(quote_ident(attname),',') into columns_sql from pg_attribute where attrelid=rel and attnum>0 and not attisdropped;
        foreach privilege in array ARRAY['select','insert','update','references'] loop
            execute format('revoke %s(%s) on table %s from %I',privilege,columns_sql,rel,role_name);
        end loop;
        if spec.readable then execute format('grant select on table %s to %I',rel,role_name); end if;
        if spec.insertable then execute format('grant insert on table %s to %I',rel,role_name); end if;
        if spec.framework then
            execute format('grant update on table %s to %I',rel,role_name);
        elsif cardinality(spec.update_columns)>0 then
            select string_agg(quote_ident(u.name),',') into columns_sql from unnest(spec.update_columns) as u(name);
            execute format('grant update(%s) on table %s to %I',columns_sql,rel,role_name);
        end if;
        if spec.deletable then execute format('grant delete on table %s to %I',rel,role_name); end if;
        -- Effective checks also catch unexpected PUBLIC or inherited grants. Failure rolls back the reconciliation.
        foreach privilege in array ARRAY['SELECT','INSERT','UPDATE','DELETE','TRUNCATE','REFERENCES','TRIGGER','MAINTAIN'] loop
            expected := case privilege when 'SELECT' then spec.readable when 'INSERT' then spec.insertable
                when 'UPDATE' then spec.framework when 'DELETE' then spec.deletable else false end;
            if has_table_privilege(role_name,rel,privilege) is distinct from expected then
                raise exception 'Unexpected effective D0 table privilege % on %',privilege,spec.table_name;
            end if;
        end loop;
        for col in select attname from pg_attribute where attrelid=rel and attnum>0 and not attisdropped loop
            foreach privilege in array ARRAY['SELECT','INSERT','UPDATE','REFERENCES'] loop
                expected := case privilege when 'SELECT' then spec.readable when 'INSERT' then spec.insertable
                    when 'UPDATE' then spec.framework or col.attname=any(spec.update_columns) else false end;
                if has_column_privilege(role_name,rel,col.attname,privilege) is distinct from expected then
                    raise exception 'Unexpected effective D0 column privilege % on %.%',privilege,spec.table_name,col.attname;
                end if;
            end loop;
        end loop;
    end loop;
end $$;
commit;
