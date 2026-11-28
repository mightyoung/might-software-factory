create table public.ordinary_ledger_record (
    tenant_id uuid not null,
    configuration_key varchar(64) not null,
    id uuid not null,
    definition_version bigint not null,
    organization_id uuid not null,
    values_json jsonb not null check (jsonb_typeof(values_json)='object'),
    version bigint not null check (version>0),
    state varchar(16) not null default 'DRAFT' check (state='DRAFT'),
    deleted boolean not null default false,
    created_by_member_id uuid not null,
    updated_by_member_id uuid not null,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    primary key (tenant_id,configuration_key,id),
    foreign key (tenant_id,configuration_key,definition_version) references public.ledger_configuration_snapshot(tenant_id,configuration_key,version),
    foreign key (tenant_id,organization_id) references public.org_unit(tenant_id,id),
    foreign key (tenant_id,created_by_member_id) references public.tenant_member(tenant_id,id),
    foreign key (tenant_id,updated_by_member_id) references public.tenant_member(tenant_id,id)
);
create index ordinary_ledger_record_page on public.ordinary_ledger_record(tenant_id,configuration_key,created_at,id) where not deleted;
create table public.ordinary_ledger_command (
    tenant_id uuid not null,
    configuration_key varchar(64) not null,
    request_id uuid not null,
    member_id uuid not null,
    operation varchar(64) not null,
    record_id uuid not null,
    request_payload jsonb not null,
    response_payload jsonb not null,
    created_at timestamptz not null default now(),
    primary key (tenant_id,configuration_key,request_id),
    foreign key (tenant_id,configuration_key,record_id) references public.ordinary_ledger_record(tenant_id,configuration_key,id),
    foreign key (tenant_id,member_id) references public.tenant_member(tenant_id,id)
);
create trigger ordinary_ledger_command_append_only before update or delete on public.ordinary_ledger_command
    for each row execute function public.reject_ledger_configuration_history_mutation();
do $$
declare relation text;
begin
    foreach relation in array array['ordinary_ledger_record','ordinary_ledger_command'] loop
        execute format('alter table public.%I enable row level security',relation);
        execute format('alter table public.%I force row level security',relation);
        execute format('create policy %I on public.%I using (tenant_id=nullif(current_setting(''app.tenant_id'',true),'''')::uuid) with check (tenant_id=nullif(current_setting(''app.tenant_id'',true),'''')::uuid)',relation||'_tenant_isolation',relation);
    end loop;
end $$;
create function public.register_ledger_record_permissions(ledger_key text) returns void
language plpgsql security definer set search_path=pg_catalog,public as $$
declare action_name text;
begin
    if not exists(select 1 from public.ledger_configuration where tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid and configuration_key=ledger_key and published_version is not null) then
        raise exception 'published ledger unavailable' using errcode='42501';
    end if;
    foreach action_name in array array['read','create','update','delete'] loop
        insert into public.permission(id,code,resource_type,action,application_key,label,sort_order)
        values(gen_random_uuid(),'ledger.record.'||ledger_key||'.'||action_name,'ledger_record',action_name,'platform',
            ledger_key||' · '||action_name,350)
        on conflict(code) do nothing;
    end loop;
end $$;
revoke all on function public.register_ledger_record_permissions(text) from public;
-- The migration owner temporarily reads existing published heads for catalog backfill.
alter table public.ledger_configuration no force row level security;
-- Existing published definitions receive their ungranted action catalog during upgrade.
insert into public.permission(id,code,resource_type,action,application_key,label,sort_order)
select gen_random_uuid(),'ledger.record.'||c.configuration_key||'.'||a.action_name,'ledger_record',a.action_name,'platform',
       c.configuration_key||' · '||a.action_name,350
from (select distinct configuration_key from public.ledger_configuration where published_version is not null) c
cross join (values('read'),('create'),('update'),('delete')) a(action_name)
on conflict(code) do nothing;

alter table public.ledger_configuration force row level security;
