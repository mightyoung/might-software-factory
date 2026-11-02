-- V9 and V10 are reserved for historical adoption/review candidates; never reuse them.
-- Missing rows deny application access. Provision entitlements through a controlled writer.
-- This migration grants no runtime privileges and enables no application by default.
create table public.tenant_application (
    tenant_id uuid not null references public.tenant(id) on delete restrict,
    application_key varchar(64) not null check (application_key ~ '^[a-z][a-z0-9._-]{0,63}$'),
    enabled boolean not null default false,
    version bigint not null default 1 check (version >= 1),
    primary key (tenant_id, application_key)
);
alter table public.tenant_application enable row level security;
alter table public.tenant_application force row level security;
create policy tenant_application_isolation on public.tenant_application
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    with check (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
