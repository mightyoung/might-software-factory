-- Forward-only publication of authenticated scanned evidence. V18 remains unchanged.
create table public.file_ingestion_publication (
    tenant_id uuid not null,
    intent_id uuid not null,
    file_id uuid not null,
    file_link_id uuid not null,
    evidence_item_id uuid not null,
    finding_id uuid not null,
    round_no integer not null check (round_no>=1),
    evidence_set_id uuid not null,
    owner_member_id uuid not null,
    owner_actor_id uuid not null references public.principal(id),
    publication_actor_id uuid not null references public.principal(id),
    technical_version bigint not null check (technical_version>=0),
    finding_version bigint not null check (finding_version>=0),
    evidence_revision bigint not null check (evidence_revision>=0),
    scan_proof_id uuid not null,
    content_receipt_id uuid not null,
    immutable_allocation_id uuid not null,
    receipt_allocation_id uuid not null,
    append_transaction_id xid8 not null default pg_current_xact_id(),
    published_at timestamptz not null default statement_timestamp(),
    primary key(tenant_id,intent_id),
    unique(tenant_id,file_id), unique(tenant_id,file_link_id), unique(tenant_id,evidence_item_id),
    foreign key(tenant_id,intent_id,owner_member_id) references public.finding_evidence_upload_intent(tenant_id,id,owner_member_id),
    foreign key(tenant_id,scan_proof_id) references public.file_ingestion_scan_proof(tenant_id,id),
    foreign key(tenant_id,content_receipt_id) references public.file_ingestion_content_receipt(tenant_id,id),
    foreign key(tenant_id,immutable_allocation_id) references public.file_ingestion_allocation(tenant_id,id),
    foreign key(tenant_id,receipt_allocation_id) references public.file_ingestion_allocation(tenant_id,id),
    check(immutable_allocation_id<>receipt_allocation_id)
);
alter table public.file_ingestion_publication enable row level security;
alter table public.file_ingestion_publication force row level security;
create policy file_ingestion_publication_tenant_isolation on public.file_ingestion_publication
    using(tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
    with check(tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
create trigger file_ingestion_publication_immutable before update or delete on public.file_ingestion_publication
    for each row execute function public.reject_file_ingestion_mutation();

create function public.guard_file_ingestion_publication() returns trigger language plpgsql as $$
declare i public.finding_evidence_upload_intent%rowtype;
        r public.file_ingestion_content_receipt%rowtype;
        s public.evidence_set%rowtype;
        f public.finding%rowtype;
begin
    new.append_transaction_id=pg_current_xact_id();new.published_at=statement_timestamp();
    select * into strict f from public.finding where tenant_id=new.tenant_id and id=new.finding_id for update;
    select * into strict s from public.evidence_set where tenant_id=new.tenant_id and id=new.evidence_set_id for update;
    select * into strict i from public.finding_evidence_upload_intent where tenant_id=new.tenant_id and id=new.intent_id for update;
    select * into strict r from public.file_ingestion_content_receipt where tenant_id=i.tenant_id and id=i.content_receipt_id;
    if i.state<>'VERIFIED_PENDING_FINALIZATION' or i.cancel_requested or i.expires_at<=statement_timestamp()
       or s.submission_sealed_at is not null or s.evidence_revision<>i.expected_evidence_revision
       or f.version<>i.expected_finding_version or f.state<>'RECTIFYING'
       or row(new.finding_id,new.round_no,new.evidence_set_id,new.owner_member_id,new.owner_actor_id,new.technical_version,
              new.finding_version,new.evidence_revision,new.scan_proof_id,new.content_receipt_id,new.immutable_allocation_id,new.file_id)
          is distinct from row(i.finding_id,i.round_no,i.evidence_set_id,i.owner_member_id,i.owner_actor_id,i.technical_version,
              i.expected_finding_version,i.expected_evidence_revision,i.scan_proof_id,i.content_receipt_id,i.immutable_allocation_id,r.file_id)
       or r.expires_at<=statement_timestamp() or r.not_before>statement_timestamp()
       or not exists(select 1 from public.principal where id=new.publication_actor_id and kind='SERVICE' and status='ACTIVE' and account_id is null)
       or not exists(select 1 from public.file_ingestion_allocation a where a.tenant_id=i.tenant_id and a.id=new.receipt_allocation_id
           and a.intent_id=i.id and a.file_id=r.file_id and a.owner_member_id=i.owner_member_id and a.attempt_id=r.scan_attempt_id and a.kind='RECEIPT') then
        raise exception 'Scanned evidence publication context is stale' using errcode='23514';
    end if;
    return new;
end $$;
create trigger check_file_ingestion_publication before insert on public.file_ingestion_publication
    for each row execute function public.guard_file_ingestion_publication();

-- Preserve all V18 recovery checks; only finalized residual quarantine/scratch may now be disposed.
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
    if (intent.state='FINALIZED' and a.kind not in ('QUARANTINE','SCANNER_SCRATCH')) or not (
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
-- The original guard continues to protect every non-publication event.
drop trigger check_file_ingestion_event on public.file_ingestion_allocation_event;
create trigger check_file_ingestion_event before insert on public.file_ingestion_allocation_event
    for each row when(new.event_kind<>'TRANSFER_FINALIZED') execute function public.check_file_ingestion_event();
create function public.guard_file_ingestion_transfer() returns trigger language plpgsql as $$
declare a public.file_ingestion_allocation%rowtype;
        b public.file_ingestion_allocation_event%rowtype;
        e public.file_ingestion_allocation_event%rowtype;
        p public.file_ingestion_publication%rowtype;
        allowed text[]=array['id','event_no','predecessor_event_id','event_kind','append_transaction_id','observed_at',
            'baseline_event_id','baseline_observation_sha256','finalized_file_id','technical_version'];
begin
    new.append_transaction_id=pg_current_xact_id();
    select * into strict a from public.file_ingestion_allocation where tenant_id=new.tenant_id and id=new.allocation_id;
    select * into strict p from public.file_ingestion_publication where tenant_id=a.tenant_id and intent_id=a.intent_id;
    select * into strict e from public.file_ingestion_allocation_event where tenant_id=a.tenant_id and allocation_id=a.id order by event_no desc limit 1;
    select * into strict b from public.file_ingestion_allocation_event where tenant_id=a.tenant_id and allocation_id=a.id
        and event_kind='RETAIN' and observation_kind in ('COMPLETE_IMMUTABLE','RECEIPT') order by event_no limit 1;
    if p.append_transaction_id<>pg_current_xact_id() or a.kind not in ('IMMUTABLE','RECEIPT')
       or a.id<>(case when a.kind='IMMUTABLE' then p.immutable_allocation_id else p.receipt_allocation_id end)
       or e.event_kind<>'RETAIN' or new.event_no<>e.event_no+1 or new.predecessor_event_id is distinct from e.id
       or new.retained_bytes<>e.retained_bytes or new.finalized_file_id is distinct from p.file_id
       or new.technical_version<>p.technical_version or new.baseline_event_id is distinct from b.id
       or new.baseline_observation_sha256 is distinct from b.observation_sha256
       or (to_jsonb(new)-allowed) is distinct from (to_jsonb(b)-allowed) then
        raise exception 'Scanned evidence physical transfer is invalid' using errcode='23514';
    end if;
    return new;
end $$;
create trigger check_file_ingestion_transfer before insert on public.file_ingestion_allocation_event
    for each row when(new.event_kind='TRANSFER_FINALIZED') execute function public.guard_file_ingestion_transfer();

-- Insert/delete and all other state transitions retain the original V18 guard.
drop trigger check_finding_ingestion_transition on public.finding_evidence_upload_intent;
create trigger check_finding_ingestion_declaration before insert or delete on public.finding_evidence_upload_intent
    for each row execute function public.check_finding_ingestion_transition();
create trigger check_finding_ingestion_transition before update on public.finding_evidence_upload_intent
    for each row when(new.state<>'FINALIZED') execute function public.check_finding_ingestion_transition();
create function public.guard_finding_ingestion_finalized() returns trigger language plpgsql as $$
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
                 and e.append_transaction_id=pg_current_xact_id() and a.kind in ('QUARANTINE','SCANNER_SCRATCH')
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
create trigger check_finding_ingestion_finalized before update on public.finding_evidence_upload_intent
    for each row when(new.state='FINALIZED') execute function public.guard_finding_ingestion_finalized();

create function public.require_complete_ingestion_publication() returns trigger language plpgsql as $$
declare i public.finding_evidence_upload_intent%rowtype;
        r public.file_ingestion_content_receipt%rowtype;
begin
    select * into strict i from public.finding_evidence_upload_intent where tenant_id=new.tenant_id and id=new.intent_id;
    select * into strict r from public.file_ingestion_content_receipt where tenant_id=new.tenant_id and id=new.content_receipt_id;
    if i.state<>'FINALIZED' or i.technical_version<>new.technical_version+1
       or not exists(select 1 from public.file_object f join public.file_link l on l.tenant_id=f.tenant_id and l.file_id=f.id
           join public.evidence_item e on e.tenant_id=f.tenant_id and e.id=l.resource_id
           join public.evidence_set s on s.tenant_id=e.tenant_id and s.id=e.evidence_set_id
           where f.tenant_id=new.tenant_id and f.id=new.file_id and f.owner_member_id=new.owner_member_id and f.owner_actor_id=new.owner_actor_id
             and f.state='READY' and f.checksum=r.sha256 and f.storage_version=r.storage_version and f.size_bytes=r.size_bytes
             and f.mime=r.detected_media_type and f.verified_at=r.issued_at
             and l.id=new.file_link_id and l.resource_type='FINDING_EVIDENCE_ITEM' and l.purpose='REMEDIATION_EVIDENCE'
             and l.revoked_at is null and l.linked_by_actor_id=new.publication_actor_id
             and e.id=new.evidence_item_id and e.file_id=f.id and btrim(e.sha256)=r.sha256
             and e.state='READY' and e.source_kind='FILE' and e.verification_level='SCANNED_CONTENT_V2'
             and s.id=new.evidence_set_id and s.finding_id=new.finding_id and s.round_no=new.round_no
             and s.owner_member_id=new.owner_member_id and s.state='READY' and s.submission_sealed_at is null
             and s.evidence_revision=new.evidence_revision+1)
       or (select count(*) from public.file_ingestion_allocation_event e where e.tenant_id=new.tenant_id and e.intent_id=new.intent_id
             and e.event_kind='TRANSFER_FINALIZED' and e.allocation_id in(new.immutable_allocation_id,new.receipt_allocation_id)
             and e.finalized_file_id=new.file_id and e.technical_version=new.technical_version
             and e.append_transaction_id=new.append_transaction_id)<>2 then
        raise exception 'Scanned evidence publication is incomplete' using errcode='23514';
    end if;
    return null;
end $$;
create constraint trigger file_ingestion_publication_complete after insert on public.file_ingestion_publication
    deferrable initially deferred for each row execute function public.require_complete_ingestion_publication();

alter table public.evidence_item drop constraint evidence_item_verification_level_check;
alter table public.evidence_item add constraint evidence_item_verification_level_check
    check(verification_level in ('UNVERIFIED','METADATA','CONTENT','SCANNED_CONTENT_V2'));
alter table public.remediation_submission_evidence drop constraint remediation_submission_evidence_verification_level_check;
alter table public.remediation_submission_evidence add constraint remediation_submission_evidence_verification_level_check
    check(verification_level in ('CONTENT','SCANNED_CONTENT_V2'));

create function public.guard_evidence_set_pending_ingestion_seal() returns trigger language plpgsql as $$
begin
    if old.submission_sealed_at is null and new.submission_sealed_at is not null and exists(
        select 1 from public.finding_evidence_upload_intent i where i.tenant_id=old.tenant_id and i.evidence_set_id=old.id
          and i.state in ('INTENT_CREATED','RECEIVING','QUARANTINED','SCANNING','VERIFIED_PENDING_FINALIZATION','RETRY_WAIT')) then
        raise exception 'Evidence set still has unpublished uploads' using errcode='23514';
    end if;
    return new;
end $$;
create trigger evidence_set_pending_ingestion_seal before update on public.evidence_set
    for each row execute function public.guard_evidence_set_pending_ingestion_seal();

create index finding_transition_version_history_idx on public.finding_transition(tenant_id,finding_id,to_version desc,id desc);

create or replace function public.validate_submission_evidence_snapshot() returns trigger
language plpgsql
as $$
declare
    evidence_row record;
begin
    select ei.file_id, btrim(ei.sha256) as evidence_sha256, ei.source_kind, ei.state as evidence_state,
           ei.verification_level, es.finding_id, es.round_no, es.owner_member_id, es.state as set_state,
           fo.owner_member_id as file_owner_member_id, fo.owner_actor_id as file_owner_actor_id,
           fo.checksum as file_checksum, fo.storage_version as file_storage_version,
           fo.state as file_state, fo.verified_at as file_verified_at, fl.revoked_at
      into evidence_row
      from public.evidence_item ei
      join public.evidence_set es
        on es.tenant_id = ei.tenant_id and es.id = ei.evidence_set_id
      join public.file_object fo
        on fo.tenant_id = ei.tenant_id and fo.id = ei.file_id
      join public.file_link fl
        on fl.tenant_id = NEW.tenant_id and fl.id = NEW.file_link_id
       and fl.file_id = NEW.file_id and fl.resource_type = NEW.resource_type
       and fl.resource_id = NEW.resource_id and fl.purpose = NEW.purpose
     where ei.tenant_id = NEW.tenant_id
       and ei.id = NEW.evidence_item_id
       and ei.evidence_set_id = NEW.evidence_set_id
       and es.finding_id = NEW.finding_id
       and es.round_no = NEW.round_no
       and es.owner_member_id = NEW.submitted_by;

    if not found then
        raise exception 'submission evidence snapshot source rows were not found';
    end if;
    if evidence_row.source_kind <> 'FILE'
       or evidence_row.evidence_state <> 'READY'
       or evidence_row.verification_level not in ('CONTENT','SCANNED_CONTENT_V2')
       or evidence_row.verification_level is distinct from NEW.verification_level
       or evidence_row.set_state <> 'READY'
       or evidence_row.file_state <> 'READY'
       or evidence_row.file_verified_at is null
       or evidence_row.file_id is distinct from NEW.file_id
       or evidence_row.evidence_sha256 is distinct from NEW.sha256
       or evidence_row.file_checksum is distinct from NEW.sha256
       or evidence_row.file_storage_version is distinct from NEW.storage_version
       or evidence_row.file_owner_member_id is distinct from NEW.owner_member_id
       or evidence_row.file_owner_actor_id is distinct from NEW.owner_actor_id
       or NEW.owner_member_id is distinct from NEW.submitted_by
       or NEW.resource_id is distinct from NEW.evidence_item_id
       or evidence_row.revoked_at is not null then
        raise exception 'submission evidence snapshot does not match verified source rows';
    end if;
    if NEW.verification_level='SCANNED_CONTENT_V2' and not exists(
        select 1 from public.file_ingestion_publication p
        join public.file_ingestion_content_receipt r on r.tenant_id=p.tenant_id and r.id=p.content_receipt_id
        where p.tenant_id=NEW.tenant_id and p.evidence_item_id=NEW.evidence_item_id and p.file_link_id=NEW.file_link_id
          and p.file_id=NEW.file_id and p.content_receipt_id=NEW.verification_receipt_id
          and p.evidence_set_id=NEW.evidence_set_id and p.finding_id=NEW.finding_id and p.round_no=NEW.round_no
          and r.sha256=NEW.sha256 and r.storage_version=NEW.storage_version and r.issued_at=NEW.verified_at) then
        raise exception 'Scanned snapshot lacks its exact publication provenance' using errcode='23514';
    end if;
    return NEW;
end;
$$;

