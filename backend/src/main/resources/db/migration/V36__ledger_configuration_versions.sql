-- Versioned ordinary-ledger configuration. Runtime role privileges are applied separately.
insert into public.permission(id,code,resource_type,action,application_key,label,sort_order) values
    (gen_random_uuid(),'ledger.configuration.read','ledger','configuration.read','platform','查看台账配置',320),
    (gen_random_uuid(),'ledger.configuration.manage','ledger','configuration.manage','platform','维护台账配置草稿',330),
    (gen_random_uuid(),'ledger.configuration.publish','ledger','configuration.publish','platform','发布台账配置',340);

create table public.ledger_configuration (
    tenant_id uuid not null references public.tenant(id),
    configuration_key varchar(64) not null check (configuration_key ~ '^[a-z][a-z0-9_]{0,63}$'),
    latest_version bigint not null default 0 check (latest_version >= 0),
    published_version bigint,
    primary key (tenant_id,configuration_key),
    check (published_version is null or published_version between 1 and latest_version)
);
create table public.ledger_configuration_snapshot (
    tenant_id uuid not null,
    configuration_key varchar(64) not null,
    version bigint not null check (version > 0),
    source_version bigint,
    request_id uuid not null,
    request_payload jsonb not null,
    definition jsonb not null check (jsonb_typeof(definition)='object'),
    actor_member_id uuid not null,
    saved_at timestamptz not null default now(),
    primary key (tenant_id,configuration_key,version),
    unique (tenant_id,configuration_key,request_id),
    foreign key (tenant_id,configuration_key) references public.ledger_configuration(tenant_id,configuration_key),
    foreign key (tenant_id,actor_member_id) references public.tenant_member(tenant_id,id),
    foreign key (tenant_id,configuration_key,source_version) references public.ledger_configuration_snapshot(tenant_id,configuration_key,version),
    check (source_version is null or source_version < version)
);
alter table public.ledger_configuration add constraint ledger_configuration_published_snapshot
    foreign key (tenant_id,configuration_key,published_version)
    references public.ledger_configuration_snapshot(tenant_id,configuration_key,version);
create table public.ledger_configuration_publication (
    tenant_id uuid not null,
    configuration_key varchar(64) not null,
    id uuid not null,
    version bigint not null,
    actor_member_id uuid not null,
    published_at timestamptz not null default now(),
    primary key (tenant_id,id),
    foreign key (tenant_id,configuration_key,version) references public.ledger_configuration_snapshot(tenant_id,configuration_key,version),
    foreign key (tenant_id,actor_member_id) references public.tenant_member(tenant_id,id)
);
create function public.reject_ledger_configuration_history_mutation() returns trigger language plpgsql as $$
begin raise exception 'ledger configuration history is append-only' using errcode='23514'; end $$;
create trigger ledger_configuration_snapshot_append_only before update or delete on public.ledger_configuration_snapshot
    for each row execute function public.reject_ledger_configuration_history_mutation();
create trigger ledger_configuration_publication_append_only before update or delete on public.ledger_configuration_publication
    for each row execute function public.reject_ledger_configuration_history_mutation();
do $$
declare relation text;
begin
    foreach relation in array array['ledger_configuration','ledger_configuration_snapshot','ledger_configuration_publication'] loop
        execute format('alter table public.%I enable row level security',relation);
        execute format('alter table public.%I force row level security',relation);
        execute format('create policy %I on public.%I using (tenant_id=nullif(current_setting(''app.tenant_id'',true),'''')::uuid) with check (tenant_id=nullif(current_setting(''app.tenant_id'',true),'''')::uuid)',relation||'_tenant_isolation',relation);
    end loop;
end $$;
