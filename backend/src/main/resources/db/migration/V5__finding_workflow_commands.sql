-- P2 finding workflow records. V2 remains immutable; this migration adds dependent history.
-- The application runtime role receives no grants here. Flyway uses the separate migration identity.

insert into public.permission (id, code, resource_type, action)
values
    (gen_random_uuid(), 'finding.create', 'finding', 'create'),
    (gen_random_uuid(), 'finding.confirm.person', 'finding', 'confirm.person'),
    (gen_random_uuid(), 'finding.confirm.safety', 'finding', 'confirm.safety'),
    (gen_random_uuid(), 'finding.dispatch', 'finding', 'dispatch'),
    (gen_random_uuid(), 'finding.remediate', 'finding', 'remediate'),
    (gen_random_uuid(), 'finding.verify', 'finding', 'verify')
on conflict (code) do nothing;

create table public.workflow_binding (
    tenant_id uuid not null references public.tenant(id) on delete restrict,
    business_type varchar(64) not null,
    business_id uuid not null,
    business_revision integer not null check (business_revision >= 1),
    submission_no integer not null check (submission_no >= 1),
    definition_id varchar(255) not null,
    process_instance_id varchar(64) not null,
    created_at timestamptz not null default current_timestamp,
    constraint workflow_binding_pkey primary key (tenant_id, business_type, business_id, submission_no),
    constraint workflow_binding_instance_key unique (tenant_id, process_instance_id)
);

create table public.finding_command_receipt (
    tenant_id uuid not null references public.tenant(id) on delete restrict,
    command_id uuid not null,
    actor_id uuid not null,
    action varchar(128) not null,
    request_hash char(64) not null check (request_hash ~ '^[0-9a-f]{64}$'),
    result_finding_id uuid,
    result_state varchar(40),
    result_round_no integer,
    result_version bigint,
    created_at timestamptz not null default current_timestamp,
    completed_at timestamptz,
    constraint finding_command_receipt_pkey primary key (tenant_id, command_id),
    constraint finding_command_receipt_result_shape check (
        (result_finding_id is null and result_state is null and result_round_no is null
            and result_version is null and completed_at is null)
        or (result_finding_id is not null and result_state is not null
            and result_round_no is not null and result_round_no >= 1
            and result_version is not null and result_version >= 1 and completed_at is not null)
    )
);

create table public.finding_transition (
    tenant_id uuid not null,
    id uuid not null,
    finding_id uuid not null,
    from_state varchar(40),
    to_state varchar(40) not null,
    command_id uuid not null,
    actor_id uuid not null,
    occurred_at timestamptz not null,
    reason text,
    from_version bigint not null check (from_version >= 0),
    to_version bigint not null check (to_version > from_version),
    constraint finding_transition_pkey primary key (tenant_id, id),
    constraint finding_transition_command_key unique (tenant_id, command_id),
    constraint finding_transition_finding_fkey foreign key (tenant_id, finding_id)
        references public.finding(tenant_id, id) on delete restrict
);

create table public.finding_confirmation (
    tenant_id uuid not null,
    finding_id uuid not null,
    stage varchar(16) not null check (stage in ('PERSON', 'SAFETY')),
    actor_member_id uuid not null,
    decision varchar(16) not null check (decision = 'APPROVE'),
    reason text,
    decided_at timestamptz not null,
    expected_version bigint not null check (expected_version >= 1),
    command_id uuid not null,
    constraint finding_confirmation_pkey primary key (tenant_id, finding_id, stage),
    constraint finding_confirmation_command_key unique (tenant_id, command_id),
    constraint finding_confirmation_finding_fkey foreign key (tenant_id, finding_id)
        references public.finding(tenant_id, id) on delete restrict,
    constraint finding_confirmation_member_fkey foreign key (tenant_id, actor_member_id)
        references public.tenant_member(tenant_id, id) on delete restrict
);

create table public.remediation_round (
    tenant_id uuid not null,
    finding_id uuid not null,
    round_no integer not null check (round_no >= 1),
    state varchar(16) not null check (state in ('OPEN', 'FAILED', 'CLOSED')),
    opened_at timestamptz not null,
    opened_by_command_id uuid not null,
    closed_at timestamptz,
    closed_by_command_id uuid,
    constraint remediation_round_pkey primary key (tenant_id, finding_id, round_no),
    constraint remediation_round_finding_fkey foreign key (tenant_id, finding_id)
        references public.finding(tenant_id, id) on delete restrict,
    constraint remediation_round_close_shape check (
        (state = 'OPEN' and closed_at is null and closed_by_command_id is null)
        or (state in ('FAILED', 'CLOSED') and closed_at is not null and closed_by_command_id is not null)
    )
);

create table public.remediation_notice (
    tenant_id uuid not null,
    id uuid not null,
    finding_id uuid not null,
    revision integer not null check (revision >= 1),
    round_no integer not null check (round_no >= 1),
    assignee_id uuid not null,
    due_at timestamptz not null,
    original_due_at timestamptz not null,
    requirements text not null check (length(btrim(requirements)) > 0),
    state varchar(24) not null check (state in ('ISSUED', 'NEEDS_REWORK', 'VERIFIED')),
    issued_at timestamptz not null,
    command_id uuid not null,
    constraint remediation_notice_pkey primary key (tenant_id, id),
    constraint remediation_notice_round_key unique (tenant_id, finding_id, round_no),
    constraint remediation_notice_round_fkey foreign key (tenant_id, finding_id, round_no)
        references public.remediation_round(tenant_id, finding_id, round_no) on delete restrict,
    constraint remediation_notice_assignee_fkey foreign key (tenant_id, assignee_id)
        references public.tenant_member(tenant_id, id) on delete restrict
);

create table public.evidence_set (
    tenant_id uuid not null references public.tenant(id) on delete restrict,
    id uuid not null,
    owner_member_id uuid not null,
    state varchar(24) not null check (state in ('UPLOADING', 'QUARANTINED', 'READY', 'FAILED')),
    created_at timestamptz not null,
    verified_at timestamptz,
    constraint evidence_set_pkey primary key (tenant_id, id),
    constraint evidence_set_owner_fkey foreign key (tenant_id, owner_member_id)
        references public.tenant_member(tenant_id, id) on delete restrict,
    constraint evidence_set_ready_shape check (state <> 'READY' or verified_at is not null)
);

create table public.evidence_item (
    tenant_id uuid not null,
    id uuid not null,
    evidence_set_id uuid not null,
    source_kind varchar(16) not null check (source_kind in ('FILE', 'URL')),
    file_id uuid,
    source_url text,
    sha256 char(64),
    state varchar(24) not null check (state in ('QUARANTINED', 'READY', 'FAILED')),
    captured_at timestamptz,
    verification_level varchar(24) not null check (verification_level in ('UNVERIFIED', 'METADATA', 'CONTENT')),
    constraint evidence_item_pkey primary key (tenant_id, id),
    constraint evidence_item_set_fkey foreign key (tenant_id, evidence_set_id)
        references public.evidence_set(tenant_id, id) on delete restrict,
    constraint evidence_item_source_shape check (
        (source_kind = 'FILE' and file_id is not null and source_url is null)
        or (source_kind = 'URL' and file_id is null and source_url is not null)
    ),
    constraint evidence_item_hash_shape check (sha256 is null or sha256 ~ '^[0-9a-f]{64}$')
);

create index evidence_item_ready_lookup_idx
    on public.evidence_item (tenant_id, evidence_set_id, state);

create table public.remediation_submission (
    tenant_id uuid not null,
    id uuid not null,
    finding_id uuid not null,
    notice_id uuid not null,
    round_no integer not null check (round_no >= 1),
    submitted_by uuid not null,
    submitted_at timestamptz not null,
    evidence_set_id uuid not null,
    statement text not null check (length(btrim(statement)) > 0),
    expected_version bigint not null check (expected_version >= 1),
    command_id uuid not null,
    constraint remediation_submission_pkey primary key (tenant_id, id),
    constraint remediation_submission_round_key unique (tenant_id, finding_id, round_no),
    constraint remediation_submission_command_key unique (tenant_id, command_id),
    constraint remediation_submission_round_fkey foreign key (tenant_id, finding_id, round_no)
        references public.remediation_round(tenant_id, finding_id, round_no) on delete restrict,
    constraint remediation_submission_notice_fkey foreign key (tenant_id, notice_id)
        references public.remediation_notice(tenant_id, id) on delete restrict,
    constraint remediation_submission_actor_fkey foreign key (tenant_id, submitted_by)
        references public.tenant_member(tenant_id, id) on delete restrict,
    constraint remediation_submission_evidence_fkey foreign key (tenant_id, evidence_set_id)
        references public.evidence_set(tenant_id, id) on delete restrict
);

create table public.verification (
    tenant_id uuid not null,
    id uuid not null,
    finding_id uuid not null,
    submission_id uuid not null,
    round_no integer not null check (round_no >= 1),
    reviewer_id uuid not null,
    decision varchar(8) not null check (decision in ('PASS', 'FAIL')),
    reason text,
    decided_at timestamptz not null,
    command_id uuid not null,
    constraint verification_pkey primary key (tenant_id, id),
    constraint verification_submission_key unique (tenant_id, submission_id),
    constraint verification_command_key unique (tenant_id, command_id),
    constraint verification_round_fkey foreign key (tenant_id, finding_id, round_no)
        references public.remediation_round(tenant_id, finding_id, round_no) on delete restrict,
    constraint verification_submission_fkey foreign key (tenant_id, submission_id)
        references public.remediation_submission(tenant_id, id) on delete restrict,
    constraint verification_reviewer_fkey foreign key (tenant_id, reviewer_id)
        references public.tenant_member(tenant_id, id) on delete restrict,
    constraint verification_failure_reason check (decision <> 'FAIL' or length(btrim(reason)) > 0)
);

create table public.finding_stage_assignment (
    tenant_id uuid not null,
    organization_id uuid not null,
    stage varchar(24) not null check (stage in (
        'PERSON_CONFIRM', 'SAFETY_CONFIRM', 'SAFETY_DISPATCH', 'SAFETY_VERIFY'
    )),
    assignee_member_id uuid not null,
    valid_from timestamptz not null,
    valid_to timestamptz,
    constraint finding_stage_assignment_pkey primary key
        (tenant_id, organization_id, stage, valid_from),
    constraint finding_stage_assignment_period check (valid_to is null or valid_from < valid_to),
    constraint finding_stage_assignment_org_fkey foreign key (tenant_id, organization_id)
        references public.org_unit(tenant_id, id) on delete restrict,
    constraint finding_stage_assignment_member_fkey foreign key (tenant_id, assignee_member_id)
        references public.tenant_member(tenant_id, id) on delete restrict
);

create table public.audit_event (
    tenant_id uuid not null references public.tenant(id) on delete restrict,
    id uuid not null,
    actor_id uuid not null,
    delegator_id uuid,
    action varchar(128) not null,
    resource_type varchar(64) not null,
    resource_id uuid,
    result varchar(16) not null check (result in ('SUCCESS', 'DENIED', 'FAILED')),
    reason text,
    diff_redacted jsonb,
    trace_id varchar(128),
    policy_version varchar(64) not null,
    occurred_at timestamptz not null,
    constraint audit_event_pkey primary key (tenant_id, id)
);

create index audit_event_resource_history_idx
    on public.audit_event (tenant_id, resource_type, resource_id, occurred_at desc);

create function public.reject_audit_event_mutation() returns trigger
language plpgsql
as $$
begin
    raise exception 'audit_event is append-only';
end;
$$;

create trigger audit_event_append_only
    before update or delete on public.audit_event
    for each row execute function public.reject_audit_event_mutation();

-- Spring Modulith's JDBC event publication registry is the shared persistent outbox.
create table public.event_publication (
    id uuid not null,
    listener_id text not null,
    event_type text not null,
    serialized_event text not null,
    publication_date timestamptz not null,
    completion_date timestamptz,
    status text,
    completion_attempts int,
    last_resubmission_date timestamptz,
    constraint event_publication_pkey primary key (id)
);

create index event_publication_serialized_event_hash_idx
    on public.event_publication using hash (serialized_event);
create index event_publication_by_completion_date_idx
    on public.event_publication (completion_date);

create index finding_stage_assignment_active_idx
    on public.finding_stage_assignment (tenant_id, organization_id, stage, valid_from, valid_to);

create index finding_transition_history_idx
    on public.finding_transition (tenant_id, finding_id, occurred_at, id);

create index remediation_notice_due_idx
    on public.remediation_notice (tenant_id, assignee_id, state, due_at, finding_id);

-- Every tenant-owned workflow/evidence table uses the same transaction-local RLS context.
alter table public.workflow_binding enable row level security;
alter table public.workflow_binding force row level security;
create policy workflow_binding_tenant_isolation on public.workflow_binding
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    with check (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

alter table public.finding_command_receipt enable row level security;
alter table public.finding_command_receipt force row level security;
create policy finding_command_receipt_tenant_isolation on public.finding_command_receipt
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    with check (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

alter table public.finding_transition enable row level security;
alter table public.finding_transition force row level security;
create policy finding_transition_tenant_isolation on public.finding_transition
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    with check (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

alter table public.finding_confirmation enable row level security;
alter table public.finding_confirmation force row level security;
create policy finding_confirmation_tenant_isolation on public.finding_confirmation
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    with check (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

alter table public.remediation_round enable row level security;
alter table public.remediation_round force row level security;
create policy remediation_round_tenant_isolation on public.remediation_round
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    with check (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

alter table public.remediation_notice enable row level security;
alter table public.remediation_notice force row level security;
create policy remediation_notice_tenant_isolation on public.remediation_notice
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    with check (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

alter table public.evidence_set enable row level security;
alter table public.evidence_set force row level security;
create policy evidence_set_tenant_isolation on public.evidence_set
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    with check (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

alter table public.evidence_item enable row level security;
alter table public.evidence_item force row level security;
create policy evidence_item_tenant_isolation on public.evidence_item
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    with check (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

alter table public.remediation_submission enable row level security;
alter table public.remediation_submission force row level security;
create policy remediation_submission_tenant_isolation on public.remediation_submission
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    with check (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

alter table public.verification enable row level security;
alter table public.verification force row level security;
create policy verification_tenant_isolation on public.verification
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    with check (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

alter table public.finding_stage_assignment enable row level security;
alter table public.finding_stage_assignment force row level security;
create policy finding_stage_assignment_tenant_isolation on public.finding_stage_assignment
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    with check (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

alter table public.audit_event enable row level security;
alter table public.audit_event force row level security;
create policy audit_event_tenant_isolation on public.audit_event
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    with check (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
