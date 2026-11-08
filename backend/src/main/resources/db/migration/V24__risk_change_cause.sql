-- G014 durable risk-change cause: source candidate, not applied or activated.
-- Runtime roles and exact privileges require separate reviewed provisioning.
-- Historical sources are immutable revision/receipt references, never current-root validation.

create table public.risk_change_cause (
    tenant_id uuid not null,
    source_command_id uuid not null,
    consumer_version smallint not null check (consumer_version = 1),
    recorded_at timestamptz(6) not null check (public.g014_instant_valid(recorded_at)),
    recorded_by_service_actor_id uuid not null,
    constraint risk_change_cause_pkey primary key (tenant_id, source_command_id),
    constraint risk_change_cause_revision_fkey foreign key (tenant_id, source_command_id)
        references public.risk_scenario_revision(tenant_id, command_id) on delete restrict,
    constraint risk_change_cause_receipt_fkey foreign key (tenant_id, source_command_id)
        references public.risk_scenario_receipt(tenant_id, command_id) on delete restrict,
    constraint risk_change_cause_service_actor_fkey foreign key (recorded_by_service_actor_id)
        references public.principal(id) on delete restrict
);

alter table public.risk_change_cause enable row level security;
alter table public.risk_change_cause force row level security;
create policy g014r_cause_tenant_isolation on public.risk_change_cause
    using (tenant_id = nullif(pg_catalog.current_setting('app.tenant_id', true), '')::pg_catalog.uuid)
    with check (tenant_id = nullif(pg_catalog.current_setting('app.tenant_id', true), '')::pg_catalog.uuid);

-- Source eligibility is checked in the current guarded SERVICE transaction.
-- A later SERVICE deactivation must not rewrite or invalidate historical attribution.
create function public.g014r_reject_cause_mutation() returns trigger
language plpgsql as $$
begin
    raise exception using errcode = '55000', message = 'Risk change causes are append-only';
end;
$$;

create trigger g014r_cause_no_rewrite
    before update or delete on public.risk_change_cause
    for each row execute function public.g014r_reject_cause_mutation();
create trigger g014r_cause_no_truncate
    before truncate on public.risk_change_cause
    for each statement execute function public.g014r_reject_cause_mutation();
