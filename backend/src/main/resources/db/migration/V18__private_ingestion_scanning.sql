-- Source-only expand migration. Applying it and its exact grants/resources needs separate authority.
-- Technical ingestion never publishes READY metadata, evidence items, links, or workflow commands.
drop trigger finding_upload_intent_immutable on public.finding_evidence_upload_intent;
alter table public.finding_evidence_upload_intent
    drop constraint finding_evidence_upload_intent_state_check,
    alter column state type varchar(40),
    add column technical_version bigint not null default 0 check (technical_version >= 0),
    add column lease_epoch bigint not null default 0 check (lease_epoch >= 0),
    add column attempt_no integer not null default 0 check (attempt_no between 0 and 3),
    add column current_execution_id uuid,
    add column attempt_id uuid,
    add column reserved_scan_attempt_id uuid,
    add column lease_token uuid,
    add column lease_until timestamptz,
    add column heartbeat_at timestamptz,
    add column next_attempt_at timestamptz,
    add column quarantine_allocation_id uuid,
    add column immutable_allocation_id uuid,
    add column scan_proof_id uuid,
    add column content_receipt_id uuid,
    add column scanner_generation_sha256 varchar(64) check (scanner_generation_sha256 ~ '^[0-9a-f]{64}$'),
    add column last_error_code varchar(64) check (last_error_code in ('STREAM_INCOMPLETE','MEDIA_REJECTED',
        'SCAN_DETECTED','SCAN_LIMIT','SCANNER_UNAVAILABLE','SCAN_TIMEOUT','STORAGE_UNAVAILABLE','LEASE_LOST',
        'CONTEXT_CHANGED','CANCELLED','EXPIRED','ATTEMPTS_EXHAUSTED','QUARANTINE_NOT_REUSABLE')),
    add column cancel_requested boolean not null default false,
    add column technical_updated_at timestamptz not null default current_timestamp,
    add constraint finding_upload_ingestion_state check (state in ('INTENT_CREATED','RECEIVING','QUARANTINED',
        'SCANNING','VERIFIED_PENDING_FINALIZATION','RETRY_WAIT','FAILED','REJECTED','CANCELLED','EXPIRED','FINALIZED')),
    add constraint finding_upload_ingestion_lease_shape check (
        (state in ('RECEIVING','SCANNING') and attempt_id is not null and lease_epoch >= 1
            and lease_token is not null and heartbeat_at is not null and lease_until is not null
            and heartbeat_at < lease_until and lease_until <= heartbeat_at + interval '120 seconds')
        or (state not in ('RECEIVING','SCANNING') and lease_token is null and heartbeat_at is null and lease_until is null)),
    add constraint finding_upload_ingestion_owner_key unique (tenant_id,id,owner_member_id);

create table public.file_ingestion_allocation (
    tenant_id uuid not null references public.tenant(id) on delete restrict,
    id uuid not null,
    intent_id uuid not null,
    file_id uuid not null,
    attempt_id uuid not null,
    owner_member_id uuid not null,
    kind varchar(24) not null check (kind in ('QUARANTINE','SCANNER_SCRATCH','IMMUTABLE','RECEIPT')),
    root_generation_sha256 varchar(64) not null check (root_generation_sha256 ~ '^[0-9a-f]{64}$'),
    server_object_name varchar(128) not null check (server_object_name ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$'),
    storage_version varchar(128) not null check (storage_version ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$'),
    reserved_bytes bigint not null check (reserved_bytes > 0),
    reservation_lease_epoch bigint not null check (reservation_lease_epoch >= 1),
    created_at timestamptz not null,
    primary key (tenant_id,id),
    unique (tenant_id,root_generation_sha256,server_object_name),
    unique (tenant_id,intent_id,attempt_id,kind),
    unique (tenant_id,id,intent_id,file_id,attempt_id,kind),
    unique (tenant_id,intent_id,id),
    foreign key (tenant_id,intent_id,owner_member_id)
        references public.finding_evidence_upload_intent(tenant_id,id,owner_member_id) on delete restrict
);

create table public.file_ingestion_execution (
    tenant_id uuid not null,
    execution_id uuid not null,
    intent_id uuid not null,
    file_id uuid not null,
    source_attempt_id uuid not null,
    scan_attempt_id uuid not null check (scan_attempt_id<>source_attempt_id),
    quarantine_allocation_id uuid not null,
    scratch_allocation_id uuid not null check (scratch_allocation_id<>quarantine_allocation_id),
    physical_resource_realm_id uuid not null,
    profile_sha256 varchar(64) not null check (profile_sha256 ~ '^[0-9a-f]{64}$'),
    slot_id varchar(64) not null check (slot_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$'),
    claim_epoch bigint not null check (claim_epoch>=1),
    claim_token uuid not null,
    claim_technical_version bigint not null check (claim_technical_version>=1),
    canonical_descriptor bytea not null check (octet_length(canonical_descriptor) between 1 and 32768),
    descriptor_sha256 varchar(64) not null check (descriptor_sha256 ~ '^[0-9a-f]{64}$' and descriptor_sha256=encode(sha256(canonical_descriptor),'hex')),
    created_at timestamptz not null,
    quarantine_kind varchar(24) not null default 'QUARANTINE' check (quarantine_kind='QUARANTINE'),
    scratch_kind varchar(24) not null default 'SCANNER_SCRATCH' check (scratch_kind='SCANNER_SCRATCH'),
    primary key (tenant_id,execution_id),
    unique (physical_resource_realm_id,execution_id),
    unique (tenant_id,intent_id,scan_attempt_id),
    unique (tenant_id,intent_id,execution_id),
    unique (tenant_id,execution_id,intent_id,file_id,source_attempt_id,scan_attempt_id,quarantine_allocation_id),
    unique (tenant_id,execution_id,descriptor_sha256),
    foreign key (tenant_id,intent_id) references public.finding_evidence_upload_intent(tenant_id,id) on delete restrict,
    foreign key (tenant_id,quarantine_allocation_id,intent_id,file_id,source_attempt_id,quarantine_kind)
        references public.file_ingestion_allocation(tenant_id,id,intent_id,file_id,attempt_id,kind) on delete restrict,
    foreign key (tenant_id,scratch_allocation_id,intent_id,file_id,scan_attempt_id,scratch_kind)
        references public.file_ingestion_allocation(tenant_id,id,intent_id,file_id,attempt_id,kind) on delete restrict
);

create table public.file_ingestion_allocation_event (
    tenant_id uuid not null references public.tenant(id) on delete restrict,
    id uuid not null,
    allocation_id uuid not null,
    event_no bigint not null check (event_no >= 1),
    predecessor_event_id uuid,
    event_kind varchar(24) not null check (event_kind in ('RETAIN','RESIZE_CONFIRMED','DISPOSAL_CLAIMED','DISPOSAL_CONFIRMED','TRANSFER_FINALIZED','RECOVERY_OBSERVED')),
    retained_bytes bigint not null check (retained_bytes >= 0),
    append_transaction_id xid8 not null default pg_current_xact_id(),
    observed_at timestamptz not null,
    canonical_observation bytea not null check (octet_length(canonical_observation) between 1 and 32768),
    observation_sha256 varchar(64) not null check (observation_sha256 ~ '^[0-9a-f]{64}$'
        and observation_sha256 = encode(sha256(canonical_observation),'hex')),
    baseline_event_id uuid,
    baseline_observation_sha256 varchar(64),
    disposal_token uuid,
    disposal_claim_technical_version bigint check (disposal_claim_technical_version >= 0),
    disposal_epoch bigint check (disposal_epoch >= 1),
    finalized_file_id uuid,
    technical_version bigint not null check (technical_version >= 0),
    eligibility_policy_sha256 varchar(64) check (eligibility_policy_sha256 ~ '^[0-9a-f]{64}$'),
    intent_id uuid not null,
    file_id uuid not null,
    attempt_id uuid not null,
    allocation_kind varchar(24) not null check (allocation_kind in ('QUARANTINE','SCANNER_SCRATCH','IMMUTABLE','RECEIPT')),
    reservation_lease_epoch bigint not null check (reservation_lease_epoch >= 1),
    observation_kind varchar(24) not null check (observation_kind in ('COMPLETE_QUARANTINE','COMPLETE_IMMUTABLE','RECEIPT','SCRATCH','UNTRUSTED_PARTIAL')),
    observed_root_generation_sha256 varchar(64) not null check (observed_root_generation_sha256 ~ '^[0-9a-f]{64}$'),
    observed_server_object_name varchar(128) not null check (observed_server_object_name ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$'),
    observed_storage_version varchar(128) not null check (observed_storage_version ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$'),
    observed_sha256 varchar(64) check (observed_sha256 ~ '^[0-9a-f]{64}$'),
    observed_size_bytes bigint check (observed_size_bytes >= 0),
    observed_media_type varchar(255) check (observed_media_type ~ '^[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+$'),
    observed_file_store_identity varchar(255) check (length(observed_file_store_identity)>0 and observed_file_store_identity !~ '[[:cntrl:]]'),
    observed_file_key varchar(255) check (length(observed_file_key)>0 and observed_file_key !~ '[[:cntrl:]]'),
    observed_unix_device numeric(20,0) check (observed_unix_device between 0 and 9223372036854775807),
    observed_unix_inode numeric(20,0) check (observed_unix_inode between 0 and 9223372036854775807),
    observed_link_count integer check (observed_link_count >= 1),
    durability_completed_at timestamptz,
    object_absent boolean not null default false,
    absence_durable_at timestamptz,
    child_reaped boolean not null default false,
    child_reap_evidence_sha256 varchar(64) check (child_reap_evidence_sha256 ~ '^[0-9a-f]{64}$'),
    recovery_id uuid,
    expected_current_technical_version bigint check (expected_current_technical_version>=0),
    expected_intent_lease_epoch bigint check (expected_intent_lease_epoch>=0),
    target_event_no bigint check (target_event_no>=0),
    target_event_sha256 varchar(64) check (target_event_sha256 ~ '^[0-9a-f]{64}$'),
    execution_id uuid,
    descriptor_sha256 varchar(64) check (descriptor_sha256 ~ '^[0-9a-f]{64}$'),
    recovery_reason varchar(24) check (recovery_reason in ('TERMINAL','SUPERSEDED','EXPIRED_WORK')),
    retention_policy_sha256 varchar(64) check (retention_policy_sha256 ~ '^[0-9a-f]{64}$'),
    recovery_presence varchar(32) check (recovery_presence in ('ABSENT','PRESENT_UNTRUSTED_PARTIAL','PRESENT_SCRATCH','PRESENT_COMPLETE_UNCHANGED','UNKNOWN')),
    process_state varchar(16) check (process_state in ('NONE_STARTED','REAPED','KERNEL_ABSENT','UNKNOWN')),
    writer_quiescent boolean not null default false,
    writer_quiescence_sha256 varchar(64) check (writer_quiescence_sha256 ~ '^[0-9a-f]{64}$'),
    writer_quiescence_observed_at timestamptz,
    stop_write_fence_sha256 varchar(64) check (stop_write_fence_sha256 ~ '^[0-9a-f]{64}$'),
    kernel_facts_present boolean not null default false,
    kernel_challenge varchar(64) check (kernel_challenge ~ '^[0-9a-f]{64}$'),
    kernel_physical_resource_realm_id uuid,
    kernel_slot_id varchar(64) check (kernel_slot_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$'),
    kernel_profile_sha256 varchar(64) check (kernel_profile_sha256 ~ '^[0-9a-f]{64}$'),
    kernel_boot_id uuid,
    kernel_supervisor_pid bigint check (kernel_supervisor_pid >0),
    kernel_supervisor_start_ticks bigint check (kernel_supervisor_start_ticks >0),
    kernel_child_pid bigint check (kernel_child_pid >0),
    kernel_child_start_ticks bigint check (kernel_child_start_ticks >0),
    kernel_child_absent_reason varchar(64) check (kernel_child_absent_reason ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$'),
    kernel_pid_namespace bigint check (kernel_pid_namespace >0),
    kernel_mount_namespace bigint check (kernel_mount_namespace >0),
    kernel_net_namespace bigint check (kernel_net_namespace >0),
    kernel_ipc_namespace bigint check (kernel_ipc_namespace >0),
    kernel_uts_namespace bigint check (kernel_uts_namespace >0),
    kernel_cgroup_name uuid,
    kernel_cgroup_mount_id bigint check (kernel_cgroup_mount_id >0),
    kernel_cgroup_inode bigint check (kernel_cgroup_inode >0),
    kernel_pids_policy_sha256 varchar(64) check (kernel_pids_policy_sha256 ~ '^[0-9a-f]{64}$'),
    kernel_pids_current bigint check (kernel_pids_current >=0),
    kernel_populated boolean,
    kernel_pids_controller_continuous boolean,
    kernel_no_migration_or_future_entry boolean,
    kernel_slot_lock_exclusive boolean,
    kernel_slot_lock_identity_sha256 varchar(64) check (kernel_slot_lock_identity_sha256 ~ '^[0-9a-f]{64}$'),
    kernel_permanent_stop boolean,
    kernel_stop_sha256 varchar(64) check (kernel_stop_sha256 ~ '^[0-9a-f]{64}$'),
    kernel_child_observed boolean,
    kernel_image_verified boolean,
    kernel_wait_reaped boolean,
    kernel_input_drained boolean,
    kernel_stdout_drained boolean,
    kernel_stderr_drained boolean,
    kernel_interrupted_output_discarded boolean,
    kernel_process_state varchar(16) check (kernel_process_state in ('NONE_STARTED','REAPED','KERNEL_ABSENT','UNKNOWN')),
    kernel_control_sha256 varchar(64) check (kernel_control_sha256 ~ '^[0-9a-f]{64}$'),
    kernel_report_sha256 varchar(64) check (kernel_report_sha256 ~ '^[0-9a-f]{64}$'),
    kernel_observed_at timestamptz,
    primary key (tenant_id,id),
    unique (tenant_id,allocation_id,event_no),
    unique (tenant_id,allocation_id,id),
    unique (tenant_id,allocation_id,id,observation_sha256),
    unique (tenant_id,allocation_id,predecessor_event_id),
    foreign key (tenant_id,allocation_id) references public.file_ingestion_allocation(tenant_id,id) on delete restrict,
    foreign key (tenant_id,allocation_id,predecessor_event_id)
        references public.file_ingestion_allocation_event(tenant_id,allocation_id,id) on delete restrict,
    foreign key (tenant_id,allocation_id,baseline_event_id,baseline_observation_sha256)
        references public.file_ingestion_allocation_event(tenant_id,allocation_id,id,observation_sha256) on delete restrict,
    check ((event_no=1 and predecessor_event_id is null and event_kind in ('RETAIN','RECOVERY_OBSERVED')) or (event_no>1 and predecessor_event_id is not null)),
    check ((baseline_event_id is null)=(baseline_observation_sha256 is null)),
    check ((object_absent and absence_durable_at is not null) or (not object_absent and absence_durable_at is null)),
    check ((child_reaped and child_reap_evidence_sha256 is not null) or (not child_reaped and child_reap_evidence_sha256 is null)),
    check ((observation_kind in ('SCRATCH','UNTRUSTED_PARTIAL')) or
        (observed_sha256 is not null and observed_size_bytes between 1 and 67108864 and observed_media_type is not null
            and observed_file_store_identity is not null and observed_file_key is not null and observed_unix_device is not null
            and observed_unix_inode is not null and observed_link_count=1 and durability_completed_at is not null))
);

create table public.file_scanner_generation (
    tenant_id uuid not null references public.tenant(id) on delete restrict,
    id uuid not null,
    generation_sha256 varchar(64) not null check (generation_sha256 ~ '^[0-9a-f]{64}$'),
    manifest_sha256 varchar(64) not null check (manifest_sha256 ~ '^[0-9a-f]{64}$'),
    canonical_manifest bytea not null check (octet_length(canonical_manifest) between 1 and 32768),
    validated_at timestamptz not null,
    valid_until timestamptz not null check (valid_until > validated_at),
    primary key (tenant_id,id),
    unique (tenant_id,generation_sha256),
    unique (tenant_id,id,generation_sha256),
    check (manifest_sha256=encode(sha256(canonical_manifest),'hex'))
);

create table public.file_ingestion_scan_proof (
    tenant_id uuid not null references public.tenant(id) on delete restrict,
    id uuid not null,
    intent_id uuid not null,
    file_id uuid not null,
    source_attempt_id uuid not null,
    scan_attempt_id uuid not null check (scan_attempt_id <> source_attempt_id),
    quarantine_allocation_id uuid not null,
    quarantine_observation_event_id uuid not null,
    execution_id uuid not null,
    descriptor_sha256 varchar(64) not null check (descriptor_sha256 ~ '^[0-9a-f]{64}$'),
    kernel_result_sha256 varchar(64) not null check (kernel_result_sha256 ~ '^[0-9a-f]{64}$'),
    process_state varchar(16) not null check (process_state='REAPED'),
    generation_id uuid not null,
    allocation_kind varchar(24) not null default 'QUARANTINE' check (allocation_kind='QUARANTINE'),
    generation_sha256 varchar(64) not null check (generation_sha256 ~ '^[0-9a-f]{64}$'),
    quarantine_observation_sha256 varchar(64) not null check (quarantine_observation_sha256 ~ '^[0-9a-f]{64}$'),
    sha256 varchar(64) not null check (sha256 ~ '^[0-9a-f]{64}$'),
    canonical_payload_sha256 varchar(64) not null check (canonical_payload_sha256 ~ '^[0-9a-f]{64}$'),
    size_bytes bigint not null check (size_bytes between 1 and 67108864),
    detected_media_type varchar(255) not null check (detected_media_type ~ '^[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+$'),
    storage_version varchar(128) not null check (storage_version ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$'),
    protocol varchar(24) not null check (protocol='SCAN_PROOF_V1'),
    canonical_payload bytea not null check (octet_length(canonical_payload) between 1 and 32768),
    signature bytea not null check (octet_length(signature)=32),
    key_id varchar(64) not null check (key_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$'),
    started_at timestamptz not null,
    completed_at timestamptz not null check (completed_at >= started_at),
    issued_at timestamptz not null check (issued_at >= completed_at),
    not_before timestamptz not null check (not_before <= issued_at),
    expires_at timestamptz not null check (expires_at > issued_at),
    primary key (tenant_id,id),
    unique (tenant_id,intent_id,id),
    unique (tenant_id,id,intent_id,file_id,scan_attempt_id),
    unique (tenant_id,id,intent_id,file_id,scan_attempt_id,canonical_payload_sha256),
    foreign key (tenant_id,quarantine_allocation_id,intent_id,file_id,source_attempt_id,allocation_kind)
        references public.file_ingestion_allocation(tenant_id,id,intent_id,file_id,attempt_id,kind) on delete restrict,
    foreign key (tenant_id,quarantine_allocation_id,quarantine_observation_event_id,quarantine_observation_sha256)
        references public.file_ingestion_allocation_event(tenant_id,allocation_id,id,observation_sha256) on delete restrict,
    foreign key (tenant_id,generation_id,generation_sha256)
        references public.file_scanner_generation(tenant_id,id,generation_sha256) on delete restrict,
    foreign key (tenant_id,execution_id,intent_id,file_id,source_attempt_id,scan_attempt_id,quarantine_allocation_id)
        references public.file_ingestion_execution(tenant_id,execution_id,intent_id,file_id,source_attempt_id,scan_attempt_id,quarantine_allocation_id) on delete restrict,
    foreign key (tenant_id,execution_id,descriptor_sha256)
        references public.file_ingestion_execution(tenant_id,execution_id,descriptor_sha256) on delete restrict,
    check (canonical_payload_sha256=encode(sha256(canonical_payload),'hex'))
);

create table public.file_ingestion_content_receipt (
    tenant_id uuid not null references public.tenant(id) on delete restrict,
    id uuid not null,
    intent_id uuid not null,
    file_id uuid not null,
    source_attempt_id uuid not null,
    scan_attempt_id uuid not null check (scan_attempt_id <> source_attempt_id),
    immutable_allocation_id uuid not null,
    immutable_observation_event_id uuid not null,
    scan_proof_id uuid not null,
    allocation_kind varchar(24) not null default 'IMMUTABLE' check (allocation_kind='IMMUTABLE'),
    scan_proof_payload_sha256 varchar(64) not null check (scan_proof_payload_sha256 ~ '^[0-9a-f]{64}$'),
    immutable_observation_sha256 varchar(64) not null check (immutable_observation_sha256 ~ '^[0-9a-f]{64}$'),
    canonical_payload_sha256 varchar(64) not null check (canonical_payload_sha256 ~ '^[0-9a-f]{64}$'),
    sha256 varchar(64) not null check (sha256 ~ '^[0-9a-f]{64}$'),
    size_bytes bigint not null check (size_bytes between 1 and 67108864),
    detected_media_type varchar(255) not null check (detected_media_type ~ '^[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+$'),
    storage_version varchar(128) not null check (storage_version ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$'),
    protocol varchar(32) not null check (protocol='SCANNED_CONTENT_V2'),
    canonical_payload bytea not null check (octet_length(canonical_payload) between 1 and 32768),
    signature bytea not null check (octet_length(signature)=32),
    key_id varchar(64) not null check (key_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$'),
    issued_at timestamptz not null,
    not_before timestamptz not null check (not_before <= issued_at),
    expires_at timestamptz not null check (expires_at > issued_at),
    primary key (tenant_id,id),
    unique (tenant_id,intent_id,id),
    unique (tenant_id,intent_id,file_id,scan_attempt_id),
    foreign key (tenant_id,scan_proof_id,intent_id,file_id,scan_attempt_id,scan_proof_payload_sha256)
        references public.file_ingestion_scan_proof(tenant_id,id,intent_id,file_id,scan_attempt_id,canonical_payload_sha256) on delete restrict,
    foreign key (tenant_id,immutable_allocation_id,intent_id,file_id,scan_attempt_id,allocation_kind)
        references public.file_ingestion_allocation(tenant_id,id,intent_id,file_id,attempt_id,kind) on delete restrict,
    foreign key (tenant_id,immutable_allocation_id,immutable_observation_event_id,immutable_observation_sha256)
        references public.file_ingestion_allocation_event(tenant_id,allocation_id,id,observation_sha256) on delete restrict,
    check (canonical_payload_sha256=encode(sha256(canonical_payload),'hex'))
);

alter table public.finding_evidence_upload_intent
    add foreign key (tenant_id,id,quarantine_allocation_id) references public.file_ingestion_allocation(tenant_id,intent_id,id) on delete restrict,
    add foreign key (tenant_id,id,immutable_allocation_id) references public.file_ingestion_allocation(tenant_id,intent_id,id) on delete restrict,
    add foreign key (tenant_id,id,scan_proof_id) references public.file_ingestion_scan_proof(tenant_id,intent_id,id) on delete restrict,
    add foreign key (tenant_id,id,content_receipt_id) references public.file_ingestion_content_receipt(tenant_id,intent_id,id) on delete restrict;

alter table public.finding_evidence_upload_intent add foreign key (tenant_id,id,current_execution_id)
    references public.file_ingestion_execution(tenant_id,intent_id,execution_id) on delete restrict;
alter table public.file_ingestion_allocation_event add foreign key (tenant_id,execution_id,descriptor_sha256)
    references public.file_ingestion_execution(tenant_id,execution_id,descriptor_sha256) on delete restrict;

-- All cross-table checks are normal invoker checks under the caller's tenant RLS.
create function public.reject_file_ingestion_mutation() returns trigger language plpgsql as $$
begin raise exception 'File ingestion history is append-only' using errcode='23514'; end $$;

-- Allocation INSERT itself serializes with an empty initial terminal UPDATE, not merely its FK/event.
-- The ordered caller holds intent before files; this normal-invoker lock uses that same parent row.
create function public.check_file_ingestion_allocation_parent() returns trigger language plpgsql as $$
declare parent public.finding_evidence_upload_intent%rowtype;
begin
    select * into strict parent from public.finding_evidence_upload_intent
        where tenant_id=new.tenant_id and id=new.intent_id for update;
    if parent.state in ('FAILED','REJECTED','CANCELLED','EXPIRED','FINALIZED') then
        raise exception 'Terminal upload intent cannot accept a new allocation' using errcode='23514';
    end if;
    -- INTENT_CREATED and RETRY_WAIT reserve before their legal CAS; no premature lease is required.
    return new;
end $$;
create trigger check_file_ingestion_allocation_parent before insert on public.file_ingestion_allocation
    for each row execute function public.check_file_ingestion_allocation_parent();

create function public.check_file_ingestion_event() returns trigger language plpgsql as $$
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
       or new.observation_kind is distinct from case when a.kind='SCANNER_SCRATCH' then 'SCRATCH' else 'UNTRUSTED_PARTIAL' end
       or new.object_absent is distinct from (new.recovery_presence='ABSENT')
       or new.absence_durable_at is distinct from case when new.recovery_presence='ABSENT' then new.observed_at else null end then
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
    if intent.state='FINALIZED' or not (
          (new.recovery_reason='TERMINAL' and intent.state in ('FAILED','REJECTED','CANCELLED','EXPIRED'))
       or (new.recovery_reason='SUPERSEDED' and a.id is distinct from intent.quarantine_allocation_id
            and a.id is distinct from intent.immutable_allocation_id and a.attempt_id is distinct from intent.attempt_id
            and a.attempt_id is distinct from intent.reserved_scan_attempt_id)
       or (new.recovery_reason='EXPIRED_WORK' and intent.state='RETRY_WAIT' and intent.lease_token is null
            and intent.lease_epoch>case when new.execution_id is null then a.reservation_lease_epoch else execution.claim_epoch end)) then
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
create trigger check_file_ingestion_event before insert on public.file_ingestion_allocation_event
    for each row execute function public.check_file_ingestion_event();

create function public.check_file_ingestion_execution() returns trigger language plpgsql as $$
declare intent public.finding_evidence_upload_intent%rowtype;
begin
    select * into strict intent from public.finding_evidence_upload_intent where tenant_id=new.tenant_id and id=new.intent_id for update;
    if intent.state not in ('QUARANTINED','RETRY_WAIT') or new.claim_technical_version<>intent.technical_version+1
       or new.claim_epoch<>intent.lease_epoch+1 or intent.cancel_requested or intent.expires_at<=statement_timestamp()
       or intent.attempt_no>=3 or (intent.state='RETRY_WAIT' and (intent.next_attempt_at is null or intent.next_attempt_at>statement_timestamp()))
       or new.quarantine_allocation_id is distinct from intent.quarantine_allocation_id
       or new.created_at>statement_timestamp()
       or exists(select 1 from public.file_ingestion_allocation_event where tenant_id=new.tenant_id
            and allocation_id in (new.quarantine_allocation_id,new.scratch_allocation_id)
            and event_kind in ('DISPOSAL_CLAIMED','DISPOSAL_CONFIRMED')) then
        raise exception 'File ingestion prospective execution is invalid' using errcode='23514';
    end if;
    return new;
end $$;
create trigger check_file_ingestion_execution before insert on public.file_ingestion_execution
    for each row execute function public.check_file_ingestion_execution();
create function public.check_file_ingestion_committed_execution() returns trigger language plpgsql as $$
declare intent public.finding_evidence_upload_intent%rowtype;
begin
    select * into strict intent from public.finding_evidence_upload_intent where tenant_id=new.tenant_id and id=new.intent_id for update;
    if intent.state<>'SCANNING' or intent.current_execution_id is distinct from new.execution_id
       or intent.attempt_id is distinct from new.scan_attempt_id or intent.reserved_scan_attempt_id is distinct from new.scan_attempt_id
       or intent.quarantine_allocation_id is distinct from new.quarantine_allocation_id or intent.lease_epoch<>new.claim_epoch
       or intent.lease_token is distinct from new.claim_token or intent.technical_version<new.claim_technical_version then
        raise exception 'File ingestion execution lacks its atomic committed claim' using errcode='23514';
    end if;
    return null;
end $$;
create constraint trigger check_file_ingestion_committed_execution after insert on public.file_ingestion_execution
    deferrable initially deferred for each row execute function public.check_file_ingestion_committed_execution();

create function public.check_file_ingestion_scan_proof() returns trigger language plpgsql as $$
declare baseline public.file_ingestion_allocation_event%rowtype;
        intent public.finding_evidence_upload_intent%rowtype;
        generation public.file_scanner_generation%rowtype;
begin
    select * into strict baseline from public.file_ingestion_allocation_event where tenant_id=new.tenant_id
        and allocation_id=new.quarantine_allocation_id and id=new.quarantine_observation_event_id;
    select * into strict intent from public.finding_evidence_upload_intent where tenant_id=new.tenant_id and id=new.intent_id for update;
    select * into strict generation from public.file_scanner_generation where tenant_id=new.tenant_id and id=new.generation_id;
    if baseline.observation_kind<>'COMPLETE_QUARANTINE' or baseline.event_kind<>'RETAIN'
       or row(new.intent_id,new.file_id,new.source_attempt_id,new.sha256,new.size_bytes,new.detected_media_type,new.storage_version)
          is distinct from row(baseline.intent_id,baseline.file_id,baseline.attempt_id,baseline.observed_sha256,
              baseline.observed_size_bytes,baseline.observed_media_type,baseline.observed_storage_version)
       or intent.current_execution_id is distinct from new.execution_id
       or intent.state<>'SCANNING' or intent.attempt_id<>new.scan_attempt_id
       or intent.reserved_scan_attempt_id<>new.scan_attempt_id or intent.quarantine_allocation_id<>new.quarantine_allocation_id
       or intent.cancel_requested or intent.lease_until<=statement_timestamp()
       or generation.generation_sha256<>new.generation_sha256 or generation.valid_until<=statement_timestamp()
       or new.started_at<generation.validated_at or new.expires_at>generation.valid_until
       or exists(select 1 from public.file_ingestion_allocation_event where tenant_id=new.tenant_id
          and allocation_id=new.quarantine_allocation_id and event_kind in ('DISPOSAL_CLAIMED','DISPOSAL_CONFIRMED','TRANSFER_FINALIZED'))
       or (select count(*) from public.file_ingestion_allocation where tenant_id=new.tenant_id and intent_id=new.intent_id
           and file_id=new.file_id and attempt_id=new.scan_attempt_id and kind in ('SCANNER_SCRATCH','IMMUTABLE','RECEIPT'))<>3 then
        raise exception 'File ingestion scan provenance is invalid' using errcode='23514';
    end if;
    return new;
end $$;
create trigger check_file_ingestion_scan_proof before insert on public.file_ingestion_scan_proof
    for each row execute function public.check_file_ingestion_scan_proof();

create function public.check_file_ingestion_content_receipt() returns trigger language plpgsql as $$
declare baseline public.file_ingestion_allocation_event%rowtype;
        proof public.file_ingestion_scan_proof%rowtype;
        intent public.finding_evidence_upload_intent%rowtype;
begin
    select * into strict baseline from public.file_ingestion_allocation_event where tenant_id=new.tenant_id
        and allocation_id=new.immutable_allocation_id and id=new.immutable_observation_event_id;
    select * into strict proof from public.file_ingestion_scan_proof where tenant_id=new.tenant_id and id=new.scan_proof_id;
    select * into strict intent from public.finding_evidence_upload_intent where tenant_id=new.tenant_id and id=new.intent_id for update;
    if baseline.observation_kind<>'COMPLETE_IMMUTABLE' or baseline.event_kind<>'RETAIN'
       or row(new.intent_id,new.file_id,new.scan_attempt_id,new.sha256,new.size_bytes,new.detected_media_type,new.storage_version)
          is distinct from row(baseline.intent_id,baseline.file_id,baseline.attempt_id,baseline.observed_sha256,
              baseline.observed_size_bytes,baseline.observed_media_type,baseline.observed_storage_version)
       or row(new.intent_id,new.file_id,new.source_attempt_id,new.scan_attempt_id,new.sha256,new.size_bytes,new.detected_media_type)
          is distinct from row(proof.intent_id,proof.file_id,proof.source_attempt_id,proof.scan_attempt_id,proof.sha256,
              proof.size_bytes,proof.detected_media_type)
       or new.scan_proof_payload_sha256<>proof.canonical_payload_sha256
       or intent.state<>'SCANNING' or intent.attempt_id<>new.scan_attempt_id or intent.cancel_requested
       or intent.lease_until<=statement_timestamp() or new.issued_at<proof.issued_at or new.expires_at>proof.expires_at
       or exists(select 1 from public.file_ingestion_allocation_event where tenant_id=new.tenant_id
          and allocation_id=new.immutable_allocation_id and event_kind in ('DISPOSAL_CLAIMED','DISPOSAL_CONFIRMED','TRANSFER_FINALIZED')) then
        raise exception 'File ingestion receipt provenance is invalid' using errcode='23514';
    end if;
    return new;
end $$;
create trigger check_file_ingestion_content_receipt before insert on public.file_ingestion_content_receipt
    for each row execute function public.check_file_ingestion_content_receipt();

create function public.check_finding_ingestion_transition() returns trigger language plpgsql as $$
declare q public.file_ingestion_allocation%rowtype;
        baseline public.file_ingestion_allocation_event%rowtype;
        proof public.file_ingestion_scan_proof%rowtype;
        receipt public.file_ingestion_content_receipt%rowtype;
        allowed text[];
        unused_bundle boolean;
        execution public.file_ingestion_execution%rowtype;
begin
    if tg_op='DELETE' then
        raise exception 'Upload intent declarations cannot be deleted' using errcode='23514';
    end if;
    if tg_op='INSERT' then
        if new.state<>'INTENT_CREATED' or new.technical_version<>0 or new.lease_epoch<>0 or new.attempt_no<>0
           or new.current_execution_id is not null or new.attempt_id is not null or new.reserved_scan_attempt_id is not null or new.cancel_requested
           or new.lease_token is not null or new.lease_until is not null or new.heartbeat_at is not null
           or new.next_attempt_at is not null or new.quarantine_allocation_id is not null
           or new.immutable_allocation_id is not null or new.scan_proof_id is not null
           or new.content_receipt_id is not null or new.scanner_generation_sha256 is not null
           or new.last_error_code is not null or new.technical_updated_at<>new.created_at then
            raise exception 'New upload intent cannot seed ingestion effects' using errcode='23514';
        end if;
        return new;
    end if;
    if row(new.tenant_id,new.id,new.owner_account_id,new.owner_actor_id,new.owner_member_id,new.operation,
           new.idempotency_key,new.fingerprint_version,new.payload_sha256,new.finding_id,new.round_no,
           new.notice_id,new.evidence_set_id,new.expected_finding_version,new.expected_evidence_revision,
           new.filename,new.declared_media_type,new.declared_size_bytes,new.producer_configuration_version,
           new.upload_policy_version,new.created_at,new.expires_at)
       is distinct from row(old.tenant_id,old.id,old.owner_account_id,old.owner_actor_id,old.owner_member_id,old.operation,
           old.idempotency_key,old.fingerprint_version,old.payload_sha256,old.finding_id,old.round_no,
           old.notice_id,old.evidence_set_id,old.expected_finding_version,old.expected_evidence_revision,
           old.filename,old.declared_media_type,old.declared_size_bytes,old.producer_configuration_version,
           old.upload_policy_version,old.created_at,old.expires_at)
       or new.technical_version<>old.technical_version+1 or new.technical_updated_at<old.technical_updated_at
       or (old.cancel_requested and not new.cancel_requested) or new.state='FINALIZED' then
        raise exception 'Upload intent immutable declaration or version is invalid' using errcode='23514';
    end if;
    if new.state=old.state and not old.cancel_requested and new.cancel_requested then
        allowed=array['technical_version','technical_updated_at','cancel_requested'];
    elsif new.state=old.state and old.state in ('RECEIVING','SCANNING') then
        if old.cancel_requested or old.lease_until<=statement_timestamp() or new.heartbeat_at<old.heartbeat_at
           or new.lease_until<old.lease_until then
            raise exception 'Upload ingestion heartbeat is stale' using errcode='23514';
        end if;
        allowed=array['technical_version','technical_updated_at','heartbeat_at','lease_until'];
    elsif new.state=old.state and old.state in ('FAILED','REJECTED','CANCELLED','EXPIRED') then
        -- A disposal callback may advance the technical version only with an exact same-transaction event.
        if not exists(select 1 from public.file_ingestion_allocation_event where tenant_id=old.tenant_id
            and intent_id=old.id and technical_version=old.technical_version
            and append_transaction_id=pg_current_xact_id()
            and event_kind in ('RESIZE_CONFIRMED','RECOVERY_OBSERVED','DISPOSAL_CLAIMED','DISPOSAL_CONFIRMED')) then
            raise exception 'Upload terminal checkpoint lacks its allocation event' using errcode='23514';
        end if;
        allowed=array['technical_version','technical_updated_at'];
    elsif new.state=old.state and old.state='RETRY_WAIT' then
        -- Preserve every phase/attempt/epoch/budget/lease/reference field; only the current recovery checkpoint advances.
        -- Exact tenant/intent row is already held before files events by the guarded caller and invoker triggers.
        if not exists(
            select 1 from public.file_ingestion_allocation_event e
            join public.file_ingestion_allocation a on a.tenant_id=e.tenant_id and a.id=e.allocation_id
            left join public.file_ingestion_execution x on x.tenant_id=e.tenant_id and x.execution_id=e.execution_id
            where e.tenant_id=old.tenant_id and e.intent_id=old.id and a.intent_id=old.id
              and e.append_transaction_id=pg_current_xact_id() and e.technical_version=old.technical_version
              and e.event_kind in ('RECOVERY_OBSERVED','DISPOSAL_CLAIMED','DISPOSAL_CONFIRMED')
              and row(e.intent_id,e.file_id,e.attempt_id,e.allocation_kind,e.reservation_lease_epoch)
                  is not distinct from row(a.intent_id,a.file_id,a.attempt_id,a.kind,a.reservation_lease_epoch)
              and (e.execution_id is null or (x.descriptor_sha256=e.descriptor_sha256 and x.intent_id=old.id
                    and x.file_id=a.file_id and (a.kind='QUARANTINE' and x.quarantine_allocation_id=a.id
                        or a.kind<>'QUARANTINE' and x.scan_attempt_id=a.attempt_id)
                    and (a.kind<>'SCANNER_SCRATCH' or x.scratch_allocation_id=a.id)))
              and (e.event_kind='DISPOSAL_CLAIMED' or
                    e.expected_current_technical_version=old.technical_version and e.expected_intent_lease_epoch=old.lease_epoch)
              and ((e.recovery_reason='SUPERSEDED' and a.id is distinct from old.quarantine_allocation_id
                    and a.id is distinct from old.immutable_allocation_id and a.attempt_id is distinct from old.attempt_id
                    and a.attempt_id is distinct from old.reserved_scan_attempt_id)
                   or (e.recovery_reason='EXPIRED_WORK' and old.lease_token is null
                    and old.lease_epoch>case when e.execution_id is null then a.reservation_lease_epoch else x.claim_epoch end))
        ) then
            raise exception 'Upload retry recovery checkpoint lacks its current eligible allocation event' using errcode='23514';
        end if;
        allowed=array['technical_version','technical_updated_at'];
    elsif old.state='INTENT_CREATED' and new.state in ('CANCELLED','EXPIRED') then
        -- The UPDATE owns the exact parent lock before this fresh RC allocation-absence check.
        -- Allocation-first makes this fail; terminal-first makes the waiting allocation INSERT fail.
        if old.attempt_no<>0 or new.attempt_no<>0 or old.lease_epoch<>0 or new.lease_epoch<>0
           or old.last_error_code is not null
           or old.attempt_id is not null or new.attempt_id is not null
           or old.reserved_scan_attempt_id is not null or new.reserved_scan_attempt_id is not null
           or old.lease_token is not null or new.lease_token is not null
           or old.lease_until is not null or new.lease_until is not null
           or old.heartbeat_at is not null or new.heartbeat_at is not null
           or old.next_attempt_at is not null or new.next_attempt_at is not null
           or old.quarantine_allocation_id is not null or new.quarantine_allocation_id is not null
           or old.immutable_allocation_id is not null or new.immutable_allocation_id is not null
           or old.scan_proof_id is not null or new.scan_proof_id is not null
           or old.content_receipt_id is not null or new.content_receipt_id is not null
           or old.scanner_generation_sha256 is not null or new.scanner_generation_sha256 is not null
           or old.current_execution_id is not null or new.current_execution_id is not null
           or exists(select 1 from public.file_ingestion_allocation where tenant_id=old.tenant_id and intent_id=old.id) then
            raise exception 'Initial terminal transition requires an empty unallocated intent' using errcode='23514';
        end if;
        if (new.state='CANCELLED' and (not new.cancel_requested or new.last_error_code is distinct from 'CANCELLED'))
           or (new.state='EXPIRED' and (old.cancel_requested or new.cancel_requested or old.expires_at>statement_timestamp()
                or new.last_error_code is distinct from 'EXPIRED')) then
            raise exception 'Initial terminal transition lacks its current cancellation or expiry predicate' using errcode='23514';
        end if;
        allowed=array['state','technical_version','technical_updated_at','cancel_requested','last_error_code'];
    elsif old.state in ('INTENT_CREATED','RETRY_WAIT') and new.state='RECEIVING' then
        if old.cancel_requested or old.attempt_no>=3 or old.expires_at<=statement_timestamp()
           or (old.state='RETRY_WAIT' and (old.next_attempt_at is null or old.next_attempt_at>statement_timestamp()))
           or new.attempt_id is null or new.reserved_scan_attempt_id is null
           or new.attempt_id=new.reserved_scan_attempt_id or new.attempt_id is not distinct from old.attempt_id
           or new.reserved_scan_attempt_id is not distinct from old.reserved_scan_attempt_id
           or new.lease_epoch<>old.lease_epoch+1 or new.attempt_no<>old.attempt_no
           or new.lease_token is null or new.lease_token is not distinct from old.lease_token
           or new.lease_until<=statement_timestamp() or new.quarantine_allocation_id is null
           or new.immutable_allocation_id is not null or new.scan_proof_id is not null
           or new.content_receipt_id is not null or new.scanner_generation_sha256 is not null or new.current_execution_id is not null then
            raise exception 'Upload receive claim fence is invalid' using errcode='23514';
        end if;
        if old.quarantine_allocation_id is not null and exists(select 1 from public.file_ingestion_allocation_event
            where tenant_id=old.tenant_id and allocation_id=old.quarantine_allocation_id
              and event_kind='RETAIN' and observation_kind='COMPLETE_QUARANTINE')
            and not (old.state='RETRY_WAIT' and old.last_error_code is not distinct from 'QUARANTINE_NOT_REUSABLE') then
            raise exception 'A complete quarantine cannot be overwritten by another receive' using errcode='23514';
        end if;
        select * into strict q from public.file_ingestion_allocation where tenant_id=new.tenant_id and id=new.quarantine_allocation_id;
        if q.intent_id<>new.id or q.owner_member_id<>new.owner_member_id or q.kind<>'QUARANTINE'
           or q.attempt_id<>new.attempt_id or q.reservation_lease_epoch<>new.lease_epoch
           or q.reserved_bytes<new.declared_size_bytes
           or (select count(*) from public.file_ingestion_allocation where tenant_id=new.tenant_id and intent_id=new.id
               and file_id=q.file_id and owner_member_id=new.owner_member_id and attempt_id=new.reserved_scan_attempt_id
               and reservation_lease_epoch=new.lease_epoch and kind in ('SCANNER_SCRATCH','IMMUTABLE','RECEIPT'))<>3 then
            raise exception 'Upload receive reservation bundle is invalid' using errcode='23514';
        end if;
        allowed=array['state','technical_version','technical_updated_at','attempt_id','reserved_scan_attempt_id','lease_epoch',
            'lease_token','lease_until','heartbeat_at','next_attempt_at','last_error_code','quarantine_allocation_id',
            'immutable_allocation_id','scan_proof_id','content_receipt_id','scanner_generation_sha256','current_execution_id'];
    elsif old.state in ('QUARANTINED','RETRY_WAIT') and new.state='SCANNING' then
        if old.cancel_requested or old.attempt_no>=3 or old.expires_at<=statement_timestamp()
           or (old.state='RETRY_WAIT' and (old.next_attempt_at is null or old.next_attempt_at>statement_timestamp()))
           or new.lease_epoch<>old.lease_epoch+1 or new.attempt_no<>old.attempt_no+1
           or new.lease_token is null or new.lease_token is not distinct from old.lease_token
           or new.lease_until<=statement_timestamp() or new.attempt_id is null
           or new.attempt_id is distinct from new.reserved_scan_attempt_id
           or exists(select 1 from public.file_ingestion_allocation_event where tenant_id=new.tenant_id
               and intent_id=new.id and attempt_id=new.attempt_id and allocation_kind='SCANNER_SCRATCH'
               and event_kind in ('DISPOSAL_CLAIMED','DISPOSAL_CONFIRMED')) then
            raise exception 'Upload scan claim fence is invalid' using errcode='23514';
        end if;
        select * into strict q from public.file_ingestion_allocation where tenant_id=old.tenant_id and id=old.quarantine_allocation_id;
        select * into strict baseline from public.file_ingestion_allocation_event where tenant_id=old.tenant_id
            and allocation_id=q.id and event_kind='RETAIN' and observation_kind='COMPLETE_QUARANTINE' order by event_no limit 1;
        if baseline.observation_kind<>'COMPLETE_QUARANTINE' or baseline.observed_size_bytes<>new.declared_size_bytes
           or baseline.observed_media_type<>new.declared_media_type
           or exists(select 1 from public.file_ingestion_allocation_event where tenant_id=old.tenant_id and allocation_id=q.id
               and event_kind in ('DISPOSAL_CLAIMED','DISPOSAL_CONFIRMED','TRANSFER_FINALIZED')) then
            raise exception 'Upload scan source is unavailable' using errcode='23514';
        end if;
        unused_bundle=old.attempt_id=q.attempt_id and old.reserved_scan_attempt_id is distinct from q.attempt_id;
        if unused_bundle then
            if new.attempt_id is distinct from old.reserved_scan_attempt_id
               or (select count(*) from public.file_ingestion_allocation where tenant_id=new.tenant_id and intent_id=new.id
                   and file_id=q.file_id and owner_member_id=new.owner_member_id and attempt_id=new.attempt_id
                   and reservation_lease_epoch=q.reservation_lease_epoch and kind in ('SCANNER_SCRATCH','IMMUTABLE','RECEIPT'))<>3 then
                raise exception 'Upload unused scan reservation is invalid' using errcode='23514';
            end if;
        else
            if old.state<>'RETRY_WAIT' or new.attempt_id is not distinct from old.attempt_id
               or new.attempt_id is not distinct from old.reserved_scan_attempt_id
               or (select count(*) from public.file_ingestion_allocation where tenant_id=new.tenant_id and intent_id=new.id
                   and file_id=q.file_id and owner_member_id=new.owner_member_id and attempt_id=new.attempt_id
                   and reservation_lease_epoch=new.lease_epoch and kind in ('SCANNER_SCRATCH','IMMUTABLE','RECEIPT'))<>3 then
                raise exception 'Upload retry requires fresh scan reservations' using errcode='23514';
            end if;
        end if;
        select * into strict execution from public.file_ingestion_execution where tenant_id=new.tenant_id and execution_id=new.current_execution_id;
        if row(execution.intent_id,execution.file_id,execution.source_attempt_id,execution.scan_attempt_id,execution.quarantine_allocation_id,
               execution.claim_epoch,execution.claim_token,execution.claim_technical_version)
           is distinct from row(new.id,q.file_id,q.attempt_id,new.attempt_id,q.id,new.lease_epoch,new.lease_token,new.technical_version) then
            raise exception 'Upload scan claim requires its exact immutable execution descriptor' using errcode='23514';
        end if;
        allowed=array['state','technical_version','technical_updated_at','attempt_id','reserved_scan_attempt_id','lease_epoch',
            'lease_token','lease_until','heartbeat_at','next_attempt_at','last_error_code','current_execution_id'];
    elsif old.state='RECEIVING' and new.state='QUARANTINED' then
        if old.cancel_requested or old.lease_until<=statement_timestamp() then
            raise exception 'Upload receive checkpoint is stale' using errcode='23514';
        end if;
        select * into strict baseline from public.file_ingestion_allocation_event where tenant_id=old.tenant_id
            and allocation_id=old.quarantine_allocation_id and event_kind='RETAIN' and observation_kind='COMPLETE_QUARANTINE' order by event_no limit 1;
        if baseline.observation_kind<>'COMPLETE_QUARANTINE' or baseline.attempt_id<>old.attempt_id
           or baseline.observed_size_bytes<>old.declared_size_bytes or baseline.observed_media_type<>old.declared_media_type then
            raise exception 'Upload receive checkpoint lacks its original complete baseline' using errcode='23514';
        end if;
        allowed=array['state','technical_version','technical_updated_at','lease_token','lease_until','heartbeat_at','next_attempt_at','last_error_code'];
    elsif old.state='SCANNING' and new.state='VERIFIED_PENDING_FINALIZATION' then
        if old.cancel_requested or old.lease_until<=statement_timestamp() then
            raise exception 'Upload scan checkpoint is stale' using errcode='23514';
        end if;
        select * into strict proof from public.file_ingestion_scan_proof where tenant_id=old.tenant_id and id=new.scan_proof_id;
        select * into strict receipt from public.file_ingestion_content_receipt where tenant_id=old.tenant_id and id=new.content_receipt_id;
        if proof.execution_id is distinct from old.current_execution_id or proof.intent_id<>new.id or proof.scan_attempt_id<>old.attempt_id or proof.quarantine_allocation_id<>old.quarantine_allocation_id
           or receipt.scan_proof_id<>proof.id or receipt.scan_attempt_id<>old.attempt_id
           or receipt.immutable_allocation_id is distinct from new.immutable_allocation_id
           or proof.generation_sha256 is distinct from new.scanner_generation_sha256
           or proof.expires_at<=statement_timestamp() or receipt.expires_at<=statement_timestamp() then
            raise exception 'Upload verified checkpoint provenance is invalid' using errcode='23514';
        end if;
        allowed=array['state','technical_version','technical_updated_at','lease_token','lease_until','heartbeat_at','next_attempt_at',
            'last_error_code','immutable_allocation_id','scan_proof_id','content_receipt_id','scanner_generation_sha256'];
    elsif old.state in ('RECEIVING','SCANNING') and new.state='RETRY_WAIT' and old.lease_until<=statement_timestamp() then
        if new.lease_epoch<>old.lease_epoch+1 or new.last_error_code is distinct from 'LEASE_LOST' then
            raise exception 'Upload recovery must permanently fence the expired execution' using errcode='23514';
        end if;
        allowed=array['state','technical_version','technical_updated_at','lease_epoch','lease_token','lease_until','heartbeat_at','next_attempt_at','last_error_code'];
    elsif old.state in ('RECEIVING','SCANNING') and new.state in ('RETRY_WAIT','FAILED','REJECTED','CANCELLED','EXPIRED') then
        if old.lease_until<=statement_timestamp() or (new.state='REJECTED' and old.state<>'SCANNING') then
            raise exception 'Upload executor terminal checkpoint is stale' using errcode='23514';
        end if;
        allowed=array['state','technical_version','technical_updated_at','lease_token','lease_until','heartbeat_at','next_attempt_at','last_error_code'];
    elsif old.state in ('QUARANTINED','VERIFIED_PENDING_FINALIZATION','RETRY_WAIT') and new.state in ('CANCELLED','EXPIRED') then
        if (new.state='CANCELLED' and not new.cancel_requested)
           or (new.state='EXPIRED' and old.expires_at>statement_timestamp()) then
            raise exception 'Upload terminal predicate is not current' using errcode='23514';
        end if;
        allowed=array['state','technical_version','technical_updated_at','cancel_requested','next_attempt_at','last_error_code'];
    elsif old.state='RETRY_WAIT' and new.state='FAILED' then
        if old.attempt_no<>3 or old.next_attempt_at is null or old.next_attempt_at>statement_timestamp()
           or new.last_error_code is distinct from 'ATTEMPTS_EXHAUSTED' then
            raise exception 'Upload terminal observation is not due' using errcode='23514';
        end if;
        allowed=array['state','technical_version','technical_updated_at','next_attempt_at','last_error_code'];
    elsif old.state='VERIFIED_PENDING_FINALIZATION' and new.state='RETRY_WAIT' then
        if old.cancel_requested or new.last_error_code is null then
            raise exception 'Upload re-verification predicate is invalid' using errcode='23514';
        end if;
        allowed=array['state','technical_version','technical_updated_at','next_attempt_at','last_error_code'];
    else
        raise exception 'Upload ingestion transition is not allowed' using errcode='23514';
    end if;
    if new.state in ('FAILED','REJECTED','RETRY_WAIT') and new.last_error_code is null then
        raise exception 'Upload error state requires a safe reason' using errcode='23514';
    end if;
    if (to_jsonb(new)-allowed) is distinct from (to_jsonb(old)-allowed)
       or (new.state='CANCELLED' and (not new.cancel_requested or new.last_error_code is distinct from 'CANCELLED'))
       or (new.state='EXPIRED' and (new.expires_at>statement_timestamp() or new.last_error_code is distinct from 'EXPIRED'))
       or (new.state in ('QUARANTINED','VERIFIED_PENDING_FINALIZATION') and
           (new.lease_token is not null or new.lease_until is not null or new.heartbeat_at is not null)) then
        raise exception 'Upload ingestion transition changed unrelated facts' using errcode='23514';
    end if;
    return new;
end $$;
create trigger check_finding_ingestion_transition before insert or update or delete on public.finding_evidence_upload_intent
    for each row execute function public.check_finding_ingestion_transition();

create trigger file_ingestion_allocation_immutable before update or delete on public.file_ingestion_allocation
    for each row execute function public.reject_file_ingestion_mutation();
alter table public.file_ingestion_allocation enable row level security;
alter table public.file_ingestion_allocation force row level security;
create policy file_ingestion_allocation_tenant_isolation on public.file_ingestion_allocation
    using (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
    with check (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);

create trigger file_ingestion_allocation_event_immutable before update or delete on public.file_ingestion_allocation_event
    for each row execute function public.reject_file_ingestion_mutation();
alter table public.file_ingestion_allocation_event enable row level security;
alter table public.file_ingestion_allocation_event force row level security;
create policy file_ingestion_allocation_event_tenant_isolation on public.file_ingestion_allocation_event
    using (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
    with check (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);

create trigger file_scanner_generation_immutable before update or delete on public.file_scanner_generation
    for each row execute function public.reject_file_ingestion_mutation();
alter table public.file_scanner_generation enable row level security;
alter table public.file_scanner_generation force row level security;
create policy file_scanner_generation_tenant_isolation on public.file_scanner_generation
    using (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
    with check (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);

create trigger file_ingestion_scan_proof_immutable before update or delete on public.file_ingestion_scan_proof
    for each row execute function public.reject_file_ingestion_mutation();
alter table public.file_ingestion_scan_proof enable row level security;
alter table public.file_ingestion_scan_proof force row level security;
create policy file_ingestion_scan_proof_tenant_isolation on public.file_ingestion_scan_proof
    using (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
    with check (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);

create trigger file_ingestion_content_receipt_immutable before update or delete on public.file_ingestion_content_receipt
    for each row execute function public.reject_file_ingestion_mutation();
alter table public.file_ingestion_content_receipt enable row level security;
alter table public.file_ingestion_content_receipt force row level security;
create policy file_ingestion_content_receipt_tenant_isolation on public.file_ingestion_content_receipt
    using (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
    with check (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);

create index finding_upload_ingestion_due_idx on public.finding_evidence_upload_intent(tenant_id,state,next_attempt_at,lease_until,id);
create index finding_upload_ingestion_owner_idx on public.finding_evidence_upload_intent(tenant_id,owner_member_id,state,expires_at,id);
create index finding_upload_ingestion_quarantine_idx on public.finding_evidence_upload_intent(tenant_id,id,quarantine_allocation_id);
create index finding_upload_ingestion_immutable_idx on public.finding_evidence_upload_intent(tenant_id,id,immutable_allocation_id);
create index finding_upload_ingestion_proof_idx on public.finding_evidence_upload_intent(tenant_id,id,scan_proof_id);
create index finding_upload_ingestion_receipt_idx on public.finding_evidence_upload_intent(tenant_id,id,content_receipt_id);
create index file_ingestion_allocation_owner_idx on public.file_ingestion_allocation(tenant_id,owner_member_id,id);
create index file_ingestion_allocation_attempt_idx on public.file_ingestion_allocation(tenant_id,intent_id,attempt_id,id);
create index file_ingestion_allocation_event_latest_idx on public.file_ingestion_allocation_event(tenant_id,allocation_id,event_no desc)
    include (event_kind,retained_bytes,finalized_file_id);
create index file_ingestion_allocation_event_baseline_idx on public.file_ingestion_allocation_event(tenant_id,allocation_id,baseline_event_id,baseline_observation_sha256);
create index file_ingestion_scan_proof_generation_idx on public.file_ingestion_scan_proof(tenant_id,generation_id,generation_sha256);
create index file_ingestion_scan_proof_quarantine_idx on public.file_ingestion_scan_proof(tenant_id,quarantine_allocation_id,intent_id,file_id,source_attempt_id,allocation_kind);
create index file_ingestion_scan_proof_baseline_idx on public.file_ingestion_scan_proof(tenant_id,quarantine_allocation_id,quarantine_observation_event_id,quarantine_observation_sha256);
create index file_ingestion_content_receipt_proof_idx on public.file_ingestion_content_receipt(tenant_id,scan_proof_id,intent_id,file_id,scan_attempt_id,scan_proof_payload_sha256);
create index file_ingestion_content_receipt_immutable_idx on public.file_ingestion_content_receipt(tenant_id,immutable_allocation_id,intent_id,file_id,scan_attempt_id,allocation_kind);
create index file_ingestion_content_receipt_baseline_idx on public.file_ingestion_content_receipt(tenant_id,immutable_allocation_id,immutable_observation_event_id,immutable_observation_sha256);

create trigger file_ingestion_execution_immutable before update or delete on public.file_ingestion_execution
    for each row execute function public.reject_file_ingestion_mutation();
alter table public.file_ingestion_execution enable row level security;
alter table public.file_ingestion_execution force row level security;
create policy file_ingestion_execution_tenant_isolation on public.file_ingestion_execution
    using (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
    with check (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
create index file_ingestion_execution_quarantine_idx on public.file_ingestion_execution(tenant_id,quarantine_allocation_id);
create index file_ingestion_execution_scratch_idx on public.file_ingestion_execution(tenant_id,scratch_allocation_id);
create index file_ingestion_event_execution_idx on public.file_ingestion_allocation_event(tenant_id,execution_id);
create index finding_upload_current_execution_idx on public.finding_evidence_upload_intent(tenant_id,current_execution_id);
