-- Logical v1 finding projection for the first read-only business slice.
-- Cross-module references remain identifiers until their owning modules publish stable table contracts.
create table public.finding (
    tenant_id uuid not null,
    id uuid not null,
    organization_id uuid not null,
    code varchar(64) not null,
    description text not null,
    discovered_at timestamptz not null,
    source_event_id uuid,
    risk_id uuid,
    grade varchar(16) not null,
    state varchar(40) not null,
    round_no integer not null,
    version bigint not null,
    created_at timestamptz not null default current_timestamp,
    updated_at timestamptz not null default current_timestamp,
    created_by_member_id uuid not null,
    updated_by_member_id uuid not null,
    constraint finding_pkey primary key (tenant_id, id),
    constraint finding_tenant_code_key unique (tenant_id, code),
    constraint finding_round_no_positive check (round_no >= 1),
    constraint finding_version_positive check (version >= 1),
    constraint finding_grade_valid check (grade in ('UNKNOWN', 'GENERAL', 'MAJOR')),
    constraint finding_state_valid check (state in (
        'DISCOVERED',
        'PERSON_CONFIRM_PENDING',
        'SAFETY_CONFIRM_PENDING',
        'SAFETY_DISPATCH_PENDING',
        'RECTIFYING',
        'ACCEPTANCE_PENDING',
        'CLOSED'
    ))
);

create index finding_tenant_org_discovered_idx
    on public.finding (tenant_id, organization_id, discovered_at desc, id);

create index finding_tenant_grade_state_updated_idx
    on public.finding (tenant_id, grade, state, updated_at desc, id);

alter table public.finding enable row level security;
alter table public.finding force row level security;

create policy finding_tenant_isolation on public.finding
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    with check (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

-- Do not grant to the application role in migration code. Permission changes are managed separately.
