-- Retried scans may leave untransferred immutable/receipt allocations. Retain their charges until
-- the existing exact recovery/disposal protocol confirms absence. Transferred allocations remain
-- permanently terminal under the unchanged predecessor guard and cannot be recovered or disposed.
create or replace function public.check_file_ingestion_event() returns trigger language plpgsql as $$
declare a public.file_ingestion_allocation%rowtype;
        previous public.file_ingestion_allocation_event%rowtype;
        baseline public.file_ingestion_allocation_event%rowtype;
        intent public.finding_evidence_upload_intent%rowtype;
        execution public.file_ingestion_execution%rowtype;
        previous_charge bigint;
        quiescent boolean;
begin
    -- Server-owned provenance prevents an older committed event authorizing a later same-state CAS.
    new.append_transaction_id=pg_current_xact_id();
    select * into strict a from public.file_ingestion_allocation where tenant_id=new.tenant_id and id=new.allocation_id;
    select * into strict intent from public.finding_evidence_upload_intent where tenant_id=new.tenant_id and id=a.intent_id for update;
    if row(new.intent_id,new.file_id,new.attempt_id,new.allocation_kind,new.reservation_lease_epoch,
           new.observed_root_generation_sha256,new.observed_server_object_name,new.observed_storage_version)
       is distinct from row(a.intent_id,a.file_id,a.attempt_id,a.kind,a.reservation_lease_epoch,
           a.root_generation_sha256,a.server_object_name,a.storage_version)
       or new.technical_version<>intent.technical_version or new.retained_bytes>a.reserved_bytes
       or new.observed_size_bytes>a.reserved_bytes then
        raise exception 'File ingestion allocation projection is invalid' using errcode='23514';
    end if;
    select * into previous from public.file_ingestion_allocation_event where tenant_id=new.tenant_id and allocation_id=new.allocation_id order by event_no desc limit 1;
    if found then
        if new.event_no<>previous.event_no+1 or new.predecessor_event_id is distinct from previous.id
           or previous.event_kind in ('DISPOSAL_CONFIRMED','TRANSFER_FINALIZED') then
            raise exception 'File ingestion event predecessor is invalid' using errcode='23514';
        end if;
        previous_charge=previous.retained_bytes;
    else
        if new.event_no<>1 or new.predecessor_event_id is not null or new.event_kind not in ('RETAIN','RECOVERY_OBSERVED') then
            raise exception 'File ingestion first event is invalid' using errcode='23514';
        end if;
        previous_charge=a.reserved_bytes;
    end if;
    select * into baseline from public.file_ingestion_allocation_event where tenant_id=new.tenant_id and allocation_id=new.allocation_id
        and event_kind='RETAIN' and observation_kind in ('COMPLETE_QUARANTINE','COMPLETE_IMMUTABLE','RECEIPT') order by event_no limit 1;
    if new.baseline_event_id is distinct from baseline.id or new.baseline_observation_sha256 is distinct from baseline.observation_sha256 then
        raise exception 'File ingestion original complete baseline reference is immutable' using errcode='23514';
    end if;
    if new.event_kind in ('RETAIN','RECOVERY_OBSERVED','DISPOSAL_CLAIMED','TRANSFER_FINALIZED') and new.retained_bytes<>previous_charge then
        raise exception 'File ingestion charges must be conserved' using errcode='23514';
    end if;
    if new.event_kind='RETAIN' then
        if new.recovery_id is not null or new.expected_current_technical_version is not null or new.expected_intent_lease_epoch is not null or new.target_event_no is not null or new.target_event_sha256 is not null or new.execution_id is not null or new.descriptor_sha256 is not null or new.recovery_reason is not null or new.retention_policy_sha256 is not null or new.recovery_presence is not null or new.process_state is not null or new.writer_quiescence_sha256 is not null or new.writer_quiescence_observed_at is not null or new.stop_write_fence_sha256 is not null or new.kernel_challenge is not null or new.kernel_physical_resource_realm_id is not null or new.kernel_slot_id is not null or new.kernel_profile_sha256 is not null or new.kernel_boot_id is not null or new.kernel_supervisor_pid is not null or new.kernel_supervisor_start_ticks is not null or new.kernel_child_pid is not null or new.kernel_child_start_ticks is not null or new.kernel_child_absent_reason is not null or new.kernel_pid_namespace is not null or new.kernel_mount_namespace is not null or new.kernel_net_namespace is not null or new.kernel_ipc_namespace is not null or new.kernel_uts_namespace is not null or new.kernel_cgroup_name is not null or new.kernel_cgroup_mount_id is not null or new.kernel_cgroup_inode is not null or new.kernel_pids_policy_sha256 is not null or new.kernel_pids_current is not null or new.kernel_populated is not null or new.kernel_pids_controller_continuous is not null or new.kernel_no_migration_or_future_entry is not null or new.kernel_slot_lock_exclusive is not null or new.kernel_slot_lock_identity_sha256 is not null or new.kernel_permanent_stop is not null or new.kernel_stop_sha256 is not null or new.kernel_child_observed is not null or new.kernel_image_verified is not null or new.kernel_wait_reaped is not null or new.kernel_input_drained is not null or new.kernel_stdout_drained is not null or new.kernel_stderr_drained is not null or new.kernel_interrupted_output_discarded is not null or new.kernel_process_state is not null or new.kernel_control_sha256 is not null or new.kernel_report_sha256 is not null or new.kernel_observed_at is not null
           or new.object_absent or new.disposal_token is not null
           or new.disposal_epoch is not null or new.disposal_claim_technical_version is not null or new.finalized_file_id is not null
           or new.eligibility_policy_sha256 is not null or new.writer_quiescent or new.kernel_facts_present
           or (baseline.id is not null and new.observation_sha256<>baseline.observation_sha256)
           or exists(select 1 from public.file_ingestion_allocation_event where tenant_id=new.tenant_id and allocation_id=new.allocation_id and event_kind='DISPOSAL_CLAIMED') then
            raise exception 'File ingestion retain cannot replace a baseline' using errcode='23514';
        end if;
        if (new.observation_kind='COMPLETE_QUARANTINE' and a.kind<>'QUARANTINE')
           or (new.observation_kind='COMPLETE_IMMUTABLE' and a.kind<>'IMMUTABLE')
           or (new.observation_kind='RECEIPT' and a.kind<>'RECEIPT')
           or (new.observation_kind='SCRATCH' and a.kind<>'SCANNER_SCRATCH') then
            raise exception 'File ingestion observation kind is invalid' using errcode='23514';
        end if;
        if new.observation_kind in ('COMPLETE_QUARANTINE','COMPLETE_IMMUTABLE','RECEIPT') and
           (intent.state not in ('RECEIVING','SCANNING') or intent.attempt_id<>a.attempt_id or intent.lease_until<=statement_timestamp() or intent.cancel_requested) then
            raise exception 'File ingestion execution fence is stale' using errcode='23514';
        end if;
        return new;
    end if;
    if new.event_kind not in ('RESIZE_CONFIRMED','RECOVERY_OBSERVED','DISPOSAL_CLAIMED','DISPOSAL_CONFIRMED') then
        raise exception 'File ingestion publication or reduction is not enabled' using errcode='23514';
    end if;
    if new.recovery_id is null or new.expected_current_technical_version is null or new.expected_intent_lease_epoch is null
       or new.target_event_no is null or (new.target_event_no=0) is distinct from (new.target_event_sha256 is null)
       or new.recovery_reason is null or new.retention_policy_sha256 is null or new.recovery_presence is null or new.process_state is null
       or (new.execution_id is null) is distinct from (new.descriptor_sha256 is null)
       or new.writer_quiescent is distinct from (new.writer_quiescence_sha256 is not null)
       or new.writer_quiescent is distinct from (new.writer_quiescence_observed_at is not null)
       or new.writer_quiescence_observed_at>new.observed_at or new.observed_sha256 is not null or new.observed_media_type is not null
       or new.child_reaped or new.child_reap_evidence_sha256 is not null or new.finalized_file_id is not null
       or new.observation_kind is distinct from (case when a.kind='SCANNER_SCRATCH' then 'SCRATCH' else 'UNTRUSTED_PARTIAL' end)
       or new.object_absent is distinct from (new.recovery_presence='ABSENT')
       or new.absence_durable_at is distinct from (case when new.recovery_presence='ABSENT' then new.observed_at else null end) then
        raise exception 'File ingestion recovery projection is invalid' using errcode='23514';
    end if;
    if (new.recovery_presence in ('ABSENT','UNKNOWN') and
          (new.observed_size_bytes is not null or new.observed_file_key is not null or new.observed_file_store_identity is not null
            or new.observed_unix_device is not null or new.observed_unix_inode is not null or new.observed_link_count is not null or new.durability_completed_at is not null))
       or (new.recovery_presence not in ('ABSENT','UNKNOWN') and
          (new.observed_size_bytes is null or new.observed_file_key is null or new.observed_file_store_identity is null
            or new.observed_unix_device is null or new.observed_unix_inode is null or new.observed_link_count is distinct from 1 or new.durability_completed_at is null))
       or (new.recovery_presence='PRESENT_SCRATCH' and a.kind<>'SCANNER_SCRATCH')
       or (new.recovery_presence='PRESENT_COMPLETE_UNCHANGED' and baseline.id is null)
       or (baseline.id is not null and new.recovery_presence not in ('ABSENT','UNKNOWN') and
          (new.recovery_presence<>'PRESENT_COMPLETE_UNCHANGED' or row(new.observed_size_bytes,new.observed_file_store_identity,new.observed_file_key,
            new.observed_unix_device,new.observed_unix_inode,new.observed_link_count,new.durability_completed_at)
           is distinct from row(baseline.observed_size_bytes,baseline.observed_file_store_identity,baseline.observed_file_key,
            baseline.observed_unix_device,baseline.observed_unix_inode,baseline.observed_link_count,baseline.durability_completed_at))) then
        raise exception 'File ingestion recovery cannot invent content or replace original complete identity' using errcode='23514';
    end if;
    if new.execution_id is not null then
        select * into strict execution from public.file_ingestion_execution where tenant_id=new.tenant_id and execution_id=new.execution_id;
        if execution.descriptor_sha256<>new.descriptor_sha256 or execution.intent_id<>a.intent_id or execution.file_id<>a.file_id
           or (a.kind='QUARANTINE' and execution.quarantine_allocation_id<>a.id)
           or (a.kind<>'QUARANTINE' and execution.scan_attempt_id<>a.attempt_id)
           or (a.kind='SCANNER_SCRATCH' and execution.scratch_allocation_id<>a.id) then
            raise exception 'File ingestion recovery execution binding is invalid' using errcode='23514';
        end if;
    end if;
    if a.kind='SCANNER_SCRATCH' and new.execution_id is null and (new.process_state<>'NONE_STARTED' or exists(
        select 1 from public.file_ingestion_execution where tenant_id=a.tenant_id and intent_id=a.intent_id
            and scan_attempt_id=a.attempt_id and scratch_allocation_id=a.id)) then
        raise exception 'File ingestion never-admitted scratch already has an execution' using errcode='23514';
    end if;
    if new.kernel_facts_present then
        if new.execution_id is null or row(new.kernel_physical_resource_realm_id,new.kernel_profile_sha256,new.kernel_slot_id)
            is distinct from row(execution.physical_resource_realm_id,execution.profile_sha256,execution.slot_id)
           or new.kernel_process_state is distinct from new.process_state or new.kernel_observed_at>new.observed_at
           or (new.kernel_child_pid is null) is distinct from (new.kernel_child_start_ticks is null)
           or (new.kernel_child_pid is null) is distinct from (new.kernel_child_absent_reason is not null)
           or new.kernel_permanent_stop is distinct from (new.kernel_stop_sha256 is not null) then
            raise exception 'File ingestion kernel binding is invalid' using errcode='23514';
        end if;
        if new.kernel_challenge is null or new.kernel_physical_resource_realm_id is null or new.kernel_slot_id is null or new.kernel_profile_sha256 is null or new.kernel_boot_id is null or new.kernel_supervisor_pid is null or new.kernel_supervisor_start_ticks is null or new.kernel_cgroup_name is null or new.kernel_cgroup_mount_id is null or new.kernel_cgroup_inode is null or new.kernel_pids_policy_sha256 is null or new.kernel_pids_current is null or new.kernel_populated is null or new.kernel_pids_controller_continuous is null or new.kernel_no_migration_or_future_entry is null or new.kernel_slot_lock_exclusive is null or new.kernel_slot_lock_identity_sha256 is null or new.kernel_permanent_stop is null or new.kernel_child_observed is null or new.kernel_image_verified is null or new.kernel_wait_reaped is null or new.kernel_input_drained is null or new.kernel_stdout_drained is null or new.kernel_stderr_drained is null or new.kernel_interrupted_output_discarded is null or new.kernel_process_state is null or new.kernel_control_sha256 is null or new.kernel_report_sha256 is null or new.kernel_observed_at is null then
            raise exception 'File ingestion kernel projection is incomplete' using errcode='23514';
        end if;
        if new.process_state='REAPED' and (not new.kernel_child_observed or new.kernel_child_pid is null or not new.kernel_wait_reaped
             or not new.kernel_input_drained or not new.kernel_stdout_drained or not new.kernel_stderr_drained
             or new.kernel_interrupted_output_discarded or new.kernel_pids_current<>0 or new.kernel_populated)
           or new.process_state='KERNEL_ABSENT' and (not new.kernel_permanent_stop or not new.kernel_slot_lock_exclusive
             or not new.kernel_pids_controller_continuous or not new.kernel_no_migration_or_future_entry
             or new.kernel_pids_current<>0 or new.kernel_populated or not new.kernel_interrupted_output_discarded
             or new.kernel_wait_reaped or new.kernel_input_drained or new.kernel_stdout_drained or new.kernel_stderr_drained or new.kernel_image_verified)
           or new.process_state='NONE_STARTED' and (new.kernel_child_observed or new.kernel_child_pid is not null or new.kernel_wait_reaped or new.kernel_image_verified) then
            raise exception 'File ingestion process quiescence proof is invalid' using errcode='23514';
        end if;
    else
        if new.process_state not in ('NONE_STARTED','UNKNOWN') or new.kernel_challenge is not null or new.kernel_physical_resource_realm_id is not null or new.kernel_slot_id is not null or new.kernel_profile_sha256 is not null or new.kernel_boot_id is not null or new.kernel_supervisor_pid is not null or new.kernel_supervisor_start_ticks is not null or new.kernel_child_pid is not null or new.kernel_child_start_ticks is not null or new.kernel_child_absent_reason is not null or new.kernel_pid_namespace is not null or new.kernel_mount_namespace is not null or new.kernel_net_namespace is not null or new.kernel_ipc_namespace is not null or new.kernel_uts_namespace is not null or new.kernel_cgroup_name is not null or new.kernel_cgroup_mount_id is not null or new.kernel_cgroup_inode is not null or new.kernel_pids_policy_sha256 is not null or new.kernel_pids_current is not null or new.kernel_populated is not null or new.kernel_pids_controller_continuous is not null or new.kernel_no_migration_or_future_entry is not null or new.kernel_slot_lock_exclusive is not null or new.kernel_slot_lock_identity_sha256 is not null or new.kernel_permanent_stop is not null or new.kernel_stop_sha256 is not null or new.kernel_child_observed is not null or new.kernel_image_verified is not null or new.kernel_wait_reaped is not null or new.kernel_input_drained is not null or new.kernel_stdout_drained is not null or new.kernel_stderr_drained is not null or new.kernel_interrupted_output_discarded is not null or new.kernel_process_state is not null or new.kernel_control_sha256 is not null or new.kernel_report_sha256 is not null or new.kernel_observed_at is not null then
            raise exception 'File ingestion absent kernel projection is invalid' using errcode='23514';
        end if;
    end if;
    quiescent=new.stop_write_fence_sha256 is not null and
        case when a.kind='SCANNER_SCRATCH' then
            (new.kernel_facts_present and new.kernel_permanent_stop and new.kernel_slot_lock_exclusive and
             (new.process_state in ('REAPED','KERNEL_ABSENT') or (new.process_state='NONE_STARTED' and new.kernel_pids_current=0 and not new.kernel_populated)))
            or (new.execution_id is null and new.process_state='NONE_STARTED' and new.writer_quiescent and not exists(
                select 1 from public.file_ingestion_execution where tenant_id=a.tenant_id and intent_id=a.intent_id
                  and scan_attempt_id=a.attempt_id and scratch_allocation_id=a.id))
        else new.writer_quiescent end;
    if new.event_kind in ('RESIZE_CONFIRMED','RECOVERY_OBSERVED','DISPOSAL_CONFIRMED') then
        if new.expected_current_technical_version<>intent.technical_version or new.expected_intent_lease_epoch<>intent.lease_epoch
           or new.target_event_no<>new.event_no-1 or new.target_event_sha256 is distinct from previous.observation_sha256 then
            raise exception 'File ingestion recovery checkpoint is stale' using errcode='23514';
        end if;
    end if;
    if not (
          (new.recovery_reason='TERMINAL' and intent.state in ('FAILED','REJECTED','CANCELLED','EXPIRED','FINALIZED'))
       or (new.recovery_reason='SUPERSEDED' and a.id is distinct from intent.quarantine_allocation_id
            and a.id is distinct from intent.immutable_allocation_id and a.attempt_id is distinct from intent.attempt_id
            and a.attempt_id is distinct from intent.reserved_scan_attempt_id)
       or (new.recovery_reason='EXPIRED_WORK' and intent.state='RETRY_WAIT' and intent.lease_token is null
            and intent.lease_epoch>(case when new.execution_id is null then a.reservation_lease_epoch else execution.claim_epoch end))) then
        raise exception 'File ingestion allocation is not recoverable or disposable' using errcode='23514';
    end if;
    if new.event_kind='RESIZE_CONFIRMED' then
        if baseline.id is null or a.kind='SCANNER_SCRATCH' or new.recovery_presence<>'PRESENT_COMPLETE_UNCHANGED'
           or not new.writer_quiescent or new.stop_write_fence_sha256 is null or new.retained_bytes<=0
           or new.retained_bytes is distinct from baseline.observed_size_bytes or new.retained_bytes>previous_charge
           or new.disposal_token is not null or new.disposal_epoch is not null or new.disposal_claim_technical_version is not null
           or new.eligibility_policy_sha256 is not null or exists(select 1 from public.file_ingestion_allocation_event
                where tenant_id=new.tenant_id and allocation_id=new.allocation_id and event_kind='DISPOSAL_CLAIMED') then
            raise exception 'File ingestion reduction lacks actual allocation writer quiescence' using errcode='23514';
        end if;
    elsif new.event_kind='RECOVERY_OBSERVED' then
        if new.disposal_token is not null or new.disposal_epoch is not null or new.disposal_claim_technical_version is not null
           or new.eligibility_policy_sha256 is not null or exists(select 1 from public.file_ingestion_allocation_event
               where tenant_id=new.tenant_id and allocation_id=new.allocation_id and event_kind='DISPOSAL_CLAIMED') then
            raise exception 'File ingestion recovery cannot erase tombstone' using errcode='23514';
        end if;
    elsif new.event_kind='DISPOSAL_CLAIMED' then
        if not quiescent or new.recovery_presence='UNKNOWN' or previous.id is null or new.disposal_token is null or new.disposal_epoch is null
           or new.disposal_claim_technical_version is distinct from new.technical_version or new.eligibility_policy_sha256 is null
           or new.canonical_observation is distinct from previous.canonical_observation
           or (previous.event_kind='DISPOSAL_CLAIMED' and (new.disposal_epoch<>previous.disposal_epoch+1 or new.disposal_token=previous.disposal_token))
           or (previous.event_kind<>'DISPOSAL_CLAIMED' and (previous.event_kind not in ('RECOVERY_OBSERVED','RESIZE_CONFIRMED') or new.disposal_epoch<>1)) then
            raise exception 'File ingestion disposal claim is invalid' using errcode='23514';
        end if;
    else
        if previous.event_kind is distinct from 'DISPOSAL_CLAIMED' or new.disposal_token is distinct from previous.disposal_token
           or new.disposal_epoch is distinct from previous.disposal_epoch or new.disposal_claim_technical_version is distinct from previous.disposal_claim_technical_version
           or new.eligibility_policy_sha256 is distinct from previous.eligibility_policy_sha256
           or new.execution_id is distinct from previous.execution_id or new.descriptor_sha256 is distinct from previous.descriptor_sha256
           or new.stop_write_fence_sha256 is distinct from previous.stop_write_fence_sha256
           or new.recovery_presence<>'ABSENT' or not quiescent or new.retained_bytes<>0 then
            raise exception 'File ingestion disposal is unconfirmed' using errcode='23514';
        end if;
    end if;
    return new;
end $$;

create or replace function public.guard_finding_ingestion_finalized() returns trigger language plpgsql as $$
declare p public.file_ingestion_publication%rowtype;
begin
    select * into strict p from public.file_ingestion_publication where tenant_id=old.tenant_id and intent_id=old.id;
    if old.state='FINALIZED' then
        if new.technical_version<>old.technical_version+1 or new.technical_updated_at<old.technical_updated_at
           or (to_jsonb(new)-array['technical_version','technical_updated_at']) is distinct from
              (to_jsonb(old)-array['technical_version','technical_updated_at'])
           or not exists(select 1 from public.file_ingestion_allocation_event e
               join public.file_ingestion_allocation a on a.tenant_id=e.tenant_id and a.id=e.allocation_id
               where e.tenant_id=old.tenant_id and e.intent_id=old.id and e.technical_version=old.technical_version
                 and e.append_transaction_id=pg_current_xact_id()
                 and e.event_kind in ('RESIZE_CONFIRMED','RECOVERY_OBSERVED','DISPOSAL_CLAIMED','DISPOSAL_CONFIRMED')) then
            raise exception 'Finalized upload residual recovery checkpoint is invalid' using errcode='23514';
        end if;
        return new;
    end if;
    if old.state<>'VERIFIED_PENDING_FINALIZATION' or old.cancel_requested or new.cancel_requested
       or old.expires_at<=statement_timestamp() or new.technical_version<>old.technical_version+1
       or new.technical_updated_at<old.technical_updated_at or p.technical_version<>old.technical_version
       or p.append_transaction_id<>pg_current_xact_id()
       or (to_jsonb(new)-array['state','technical_version','technical_updated_at'])
          is distinct from (to_jsonb(old)-array['state','technical_version','technical_updated_at']) then
        raise exception 'Scanned evidence finalization is invalid' using errcode='23514';
    end if;
    return new;
end $$;
