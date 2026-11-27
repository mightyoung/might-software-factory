-- Minimal lexical equivalents of migration V3 tenant/member keys and V5 audit key.
-- No production bootstrap, privileges, password or data are reused.
create table public.tenant(id uuid primary key);
create table public.tenant_member(
    tenant_id uuid not null references public.tenant(id), id uuid not null, primary key(tenant_id,id)
);
create table public.audit_event(
    tenant_id uuid not null references public.tenant(id), id uuid not null,
    actor_id uuid not null, primary key(tenant_id,id)
);
alter table public.audit_event enable row level security;
alter table public.audit_event force row level security;
create policy fixture_audit_scope on public.audit_event
    using (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
    with check (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
create role msf_ci_studio_reader login noinherit nosuperuser nobypassrls password 'synthetic_studio_ci_runtime_only';
create role msf_ci_studio_writer login noinherit nosuperuser nobypassrls password 'synthetic_studio_ci_writer_only';
