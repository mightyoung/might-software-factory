-- CI-only prerequisite fixture in a fresh disposable database. Full V35 -> V36 upgrade is verified separately.
-- Existing names intentionally fail so this cannot initialize over a deployed application database.
create table public.tenant(id uuid primary key);
create table public.tenant_member(tenant_id uuid not null references public.tenant(id), id uuid not null, primary key(tenant_id,id));
create table public.permission(id uuid primary key,code text unique,resource_type text,action text,application_key text,label text,sort_order integer);
insert into public.tenant values ('11111111-1111-4111-8111-111111111111');
insert into public.tenant_member values ('11111111-1111-4111-8111-111111111111','22222222-2222-4222-8222-222222222222');
create role msf_dev_runtime_20261107 login password 'ci_configuration_probe_only' nosuperuser nobypassrls;

create table public.org_unit(tenant_id uuid not null references public.tenant(id), id uuid not null,
    name text not null, active boolean not null default true, primary key(tenant_id,id));
insert into public.org_unit(tenant_id,id,name) values
    ('11111111-1111-4111-8111-111111111111','33333333-3333-4333-8333-333333333333','Production'),
    ('11111111-1111-4111-8111-111111111111','44444444-4444-4444-8444-444444444444','Warehouse');
grant select on public.org_unit, public.permission to msf_dev_runtime_20261107;
