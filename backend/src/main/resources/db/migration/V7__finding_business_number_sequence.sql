-- Finding business numbers are allocated per tenant and Asia/Shanghai submission date.
-- Runtime grants are managed separately; this migration does not grant application DML.
-- The application command needs SELECT, INSERT, and UPDATE on this table; do not run the grant here.

create table public.finding_number_sequence (
    tenant_id uuid not null references public.tenant(id) on delete restrict,
    business_day date not null,
    last_sequence bigint not null check (last_sequence >= 1),
    constraint finding_number_sequence_pkey primary key (tenant_id, business_day)
);

alter table public.finding_number_sequence enable row level security;
alter table public.finding_number_sequence force row level security;

create policy finding_number_sequence_tenant_isolation
    on public.finding_number_sequence
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    with check (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
