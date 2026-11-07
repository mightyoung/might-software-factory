-- G014 risk draft SOURCE CANDIDATE. Not applied or accepted against PostgreSQL.
-- Six tables, thirteen deferred FKs and three deferred closure triggers.
-- No grants, roles, business/identity fixture seeds, extension, SECURITY DEFINER or V21 replacement.
-- Only the three fixed capability definitions below are seeded; no role assignment.
-- Runtime must be a separate non-owner/non-BYPASSRLS role. All guard triggers must be enabled.
-- Canonical advisory keys complement the sealed graph's ordered physical row locks and authority checks.

create table public.risk_scenario (
    tenant_id uuid not null references public.tenant(id) on delete restrict,
    id uuid not null,
    organization_id uuid not null,
    code varchar(64) collate "C" not null,
    version bigint not null check (version between 1 and 9007199254740991),
    description text collate "C" not null check (public.g014_text_valid(description,20000,false)),
    triggering_conditions text collate "C" not null check (public.g014_text_valid(triggering_conditions,20000,false)),
    exposure_description text collate "C" not null check (public.g014_text_valid(exposure_description,20000,false)),
    consequence text collate "C" not null check (public.g014_text_valid(consequence,20000,false)),
    accident_code_ref varchar(64) collate "C",
    accident_version varchar(64) collate "C",
    created_by_actor_id uuid not null references public.principal(id) on delete restrict,
    created_by_member_id uuid not null,
    created_at timestamptz(6) not null check (public.g014_instant_valid(created_at)),
    updated_by_actor_id uuid not null references public.principal(id) on delete restrict,
    updated_by_member_id uuid not null,
    updated_at timestamptz(6) not null check (public.g014_instant_valid(updated_at)),
    last_command_id uuid not null,
    primary key (tenant_id,id),
    unique (tenant_id,code),
    foreign key (tenant_id,organization_id) references public.org_unit(tenant_id,id) on delete restrict,
    foreign key (tenant_id,created_by_member_id) references public.tenant_member(tenant_id,id) on delete restrict,
    foreign key (tenant_id,updated_by_member_id) references public.tenant_member(tenant_id,id) on delete restrict,
    check (code ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$'),
    check ((accident_code_ref is null) = (accident_version is null)),
    check (accident_code_ref is null or public.g014_text_valid(accident_code_ref,64,true)),
    check (accident_version is null or public.g014_text_valid(accident_version,64,true)),
    check (updated_at >= created_at)
);

create table public.risk_scenario_revision (
    tenant_id uuid not null references public.tenant(id) on delete restrict,
    entity_id uuid not null,
    organization_id uuid not null,
    code varchar(64) collate "C" not null,
    version bigint not null check (version between 1 and 9007199254740991),
    description text collate "C" not null check (public.g014_text_valid(description,20000,false)),
    triggering_conditions text collate "C" not null check (public.g014_text_valid(triggering_conditions,20000,false)),
    exposure_description text collate "C" not null check (public.g014_text_valid(exposure_description,20000,false)),
    consequence text collate "C" not null check (public.g014_text_valid(consequence,20000,false)),
    accident_code_ref varchar(64) collate "C",
    accident_version varchar(64) collate "C",
    created_by_actor_id uuid not null references public.principal(id) on delete restrict,
    created_by_member_id uuid not null,
    created_at timestamptz(6) not null check (public.g014_instant_valid(created_at)),
    updated_by_actor_id uuid not null references public.principal(id) on delete restrict,
    updated_by_member_id uuid not null,
    updated_at timestamptz(6) not null check (public.g014_instant_valid(updated_at)),
    command_id uuid not null,
    actor_id uuid not null references public.principal(id) on delete restrict,
    member_id uuid not null,
    occurred_at timestamptz(6) not null check (public.g014_instant_valid(occurred_at)),
    change_kind varchar(8) collate "C" not null check (change_kind in ('CREATE','UPDATE')),
    foreign key (tenant_id,entity_id) references public.risk_scenario(tenant_id,id) on delete restrict,
    foreign key (tenant_id,member_id) references public.tenant_member(tenant_id,id) on delete restrict,
    primary key (tenant_id,entity_id,version),
    unique (tenant_id,command_id),
    foreign key (tenant_id,organization_id) references public.org_unit(tenant_id,id) on delete restrict,
    foreign key (tenant_id,created_by_member_id) references public.tenant_member(tenant_id,id) on delete restrict,
    foreign key (tenant_id,updated_by_member_id) references public.tenant_member(tenant_id,id) on delete restrict,
    check (code ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$'),
    check ((accident_code_ref is null) = (accident_version is null)),
    check (accident_code_ref is null or public.g014_text_valid(accident_code_ref,64,true)),
    check (accident_version is null or public.g014_text_valid(accident_version,64,true)),
    check (updated_at >= created_at)
);

create table public.risk_hazard (
    tenant_id uuid not null references public.tenant(id) on delete restrict,
    id uuid not null,
    scenario_id uuid not null,
    hazard_id uuid not null,
    hazard_version_at_link bigint not null check (hazard_version_at_link between 1 and 9007199254740991),
    version bigint not null check (version in (1,2)),
    linked_at timestamptz(6) not null check (public.g014_instant_valid(linked_at)),
    ended_at timestamptz(6) check (ended_at is null or public.g014_instant_valid(ended_at)),
    created_by_actor_id uuid not null references public.principal(id) on delete restrict,
    created_by_member_id uuid not null,
    last_scenario_version bigint not null check (last_scenario_version between 1 and 9007199254740991),
    last_command_id uuid not null,
    last_actor_id uuid not null references public.principal(id) on delete restrict,
    last_member_id uuid not null,
    last_occurred_at timestamptz(6) not null check (public.g014_instant_valid(last_occurred_at)),
    primary key (tenant_id,id),
    foreign key (tenant_id,scenario_id) references public.risk_scenario(tenant_id,id) on delete restrict,
    foreign key (tenant_id,hazard_id) references public.hazard_source(tenant_id,id) on delete restrict,
    foreign key (tenant_id,created_by_member_id) references public.tenant_member(tenant_id,id) on delete restrict,
    foreign key (tenant_id,last_member_id) references public.tenant_member(tenant_id,id) on delete restrict,
    check ((version=1 and ended_at is null) or (version=2 and ended_at>linked_at)),
    check (last_occurred_at=coalesce(ended_at,linked_at))
);
create unique index g014r_active_hazard_unique on public.risk_hazard(tenant_id,scenario_id,hazard_id) where ended_at is null;
create index g014r_scenario_links on public.risk_hazard(tenant_id,scenario_id,id);

create table public.risk_hazard_revision (
    tenant_id uuid not null references public.tenant(id) on delete restrict,
    entity_id uuid not null,
    scenario_id uuid not null,
    hazard_id uuid not null,
    hazard_version_at_link bigint not null check (hazard_version_at_link between 1 and 9007199254740991),
    version bigint not null check (version in (1,2)),
    linked_at timestamptz(6) not null check (public.g014_instant_valid(linked_at)),
    ended_at timestamptz(6) check (ended_at is null or public.g014_instant_valid(ended_at)),
    created_by_actor_id uuid not null references public.principal(id) on delete restrict,
    created_by_member_id uuid not null,
    scenario_version bigint not null check (scenario_version between 1 and 9007199254740991),
    command_id uuid not null,
    actor_id uuid not null references public.principal(id) on delete restrict,
    member_id uuid not null,
    occurred_at timestamptz(6) not null check (public.g014_instant_valid(occurred_at)),
    primary key (tenant_id,entity_id,version),
    foreign key (tenant_id,entity_id) references public.risk_hazard(tenant_id,id) on delete restrict,
    foreign key (tenant_id,scenario_id) references public.risk_scenario(tenant_id,id) on delete restrict,
    foreign key (tenant_id,hazard_id) references public.hazard_source(tenant_id,id) on delete restrict,
    foreign key (tenant_id,created_by_member_id) references public.tenant_member(tenant_id,id) on delete restrict,
    foreign key (tenant_id,member_id) references public.tenant_member(tenant_id,id) on delete restrict,
    check ((version=1 and ended_at is null) or (version=2 and ended_at>linked_at)),
    check (occurred_at=coalesce(ended_at,linked_at))
);

create table public.risk_scenario_revision_hazard (
    tenant_id uuid not null references public.tenant(id) on delete restrict,
    scenario_id uuid not null,
    scenario_version bigint not null check (scenario_version between 1 and 9007199254740991),
    hazard_id uuid not null,
    link_id uuid not null,
    link_version bigint not null check (link_version=1),
    hazard_version_at_link bigint not null check (hazard_version_at_link between 1 and 9007199254740991),
    hazard_version_at_scenario_revision bigint not null check (hazard_version_at_scenario_revision between 1 and 9007199254740991),
    snapshot_code varchar(64) collate "C" not null check (snapshot_code ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$'),
    full_description text collate "C" not null check (public.g014_text_valid(full_description,20000,false)),
    description_preview text collate "C" not null,
    description_truncated boolean not null,
    existence varchar(24) collate "C" not null check (existence in ('UNKNOWN','PRESENT','NOT_PRESENT')),
    command_id uuid not null,
    actor_id uuid not null references public.principal(id) on delete restrict,
    member_id uuid not null,
    occurred_at timestamptz(6) not null check (public.g014_instant_valid(occurred_at)),
    primary key (tenant_id,scenario_id,scenario_version,hazard_id),
    unique (tenant_id,scenario_id,scenario_version,link_id),
    foreign key (tenant_id,member_id) references public.tenant_member(tenant_id,id) on delete restrict,
    check (description_preview=left(full_description,200)),
    check (description_truncated=(char_length(full_description)>200))
);
create table public.risk_scenario_receipt (
    tenant_id uuid not null references public.tenant(id) on delete restrict,
    command_id uuid not null,
    actor_id uuid not null references public.principal(id) on delete restrict,
    account_id uuid not null references public.account(id) on delete restrict,
    member_id uuid not null,
    action_code varchar(128) collate "C" not null check (action_code in ('risk_scenario.create','risk_scenario.update')),
    idempotency_key uuid not null,
    fingerprint_version integer not null check (fingerprint_version=1),
    payload_sha256 char(64) collate "C" not null check (payload_sha256 ~ '^[0-9a-f]{64}$'),
    result_scenario_id uuid not null,
    result_version bigint not null check (result_version between 1 and 9007199254740991),
    result_code varchar(64) collate "C" not null,
    occurred_at timestamptz(6) not null check (public.g014_instant_valid(occurred_at)),
    audit_event_id uuid not null,
    trace_id uuid not null,
    primary key (tenant_id,command_id),
    unique (tenant_id,actor_id,action_code,idempotency_key),
    foreign key (tenant_id,member_id) references public.tenant_member(tenant_id,id) on delete restrict,
    foreign key (tenant_id,audit_event_id) references public.audit_event(tenant_id,id) on delete restrict
);
create index g014r_scenario_history on public.risk_scenario_revision(tenant_id,entity_id,occurred_at,command_id);

alter table public.risk_scenario add constraint g014r_scenario_last_command_fk foreign key (tenant_id,last_command_id) references public.risk_scenario_receipt(tenant_id,command_id) on delete restrict deferrable initially deferred;
alter table public.risk_scenario add constraint g014r_scenario_current_revision_fk foreign key (tenant_id,id,version) references public.risk_scenario_revision(tenant_id,entity_id,version) on delete restrict deferrable initially deferred;
alter table public.risk_scenario_revision add constraint g014r_revision_receipt_fk foreign key (tenant_id,command_id) references public.risk_scenario_receipt(tenant_id,command_id) on delete restrict deferrable initially deferred;
alter table public.risk_scenario_receipt add constraint g014r_receipt_result_fk foreign key (tenant_id,result_scenario_id,result_version) references public.risk_scenario_revision(tenant_id,entity_id,version) on delete restrict deferrable initially deferred;
alter table public.risk_hazard add constraint g014r_link_last_command_fk foreign key (tenant_id,last_command_id) references public.risk_scenario_receipt(tenant_id,command_id) on delete restrict deferrable initially deferred;
alter table public.risk_hazard add constraint g014r_link_current_revision_fk foreign key (tenant_id,id,version) references public.risk_hazard_revision(tenant_id,entity_id,version) on delete restrict deferrable initially deferred;
alter table public.risk_hazard_revision add constraint g014r_link_revision_receipt_fk foreign key (tenant_id,command_id) references public.risk_scenario_receipt(tenant_id,command_id) on delete restrict deferrable initially deferred;
alter table public.risk_hazard_revision add constraint g014r_link_revision_scenario_fk foreign key (tenant_id,scenario_id,scenario_version) references public.risk_scenario_revision(tenant_id,entity_id,version) on delete restrict deferrable initially deferred;
alter table public.risk_hazard add constraint g014r_link_hazard_bind_fk foreign key (tenant_id,hazard_id,hazard_version_at_link) references public.hazard_source_revision(tenant_id,entity_id,version) on delete restrict deferrable initially deferred;
alter table public.risk_scenario_revision_hazard add constraint g014r_snapshot_scenario_fk foreign key (tenant_id,scenario_id,scenario_version) references public.risk_scenario_revision(tenant_id,entity_id,version) on delete restrict deferrable initially deferred;
alter table public.risk_scenario_revision_hazard add constraint g014r_snapshot_link_fk foreign key (tenant_id,link_id,link_version) references public.risk_hazard_revision(tenant_id,entity_id,version) on delete restrict deferrable initially deferred;
alter table public.risk_scenario_revision_hazard add constraint g014r_snapshot_hazard_bind_fk foreign key (tenant_id,hazard_id,hazard_version_at_link) references public.hazard_source_revision(tenant_id,entity_id,version) on delete restrict deferrable initially deferred;
alter table public.risk_scenario_revision_hazard add constraint g014r_snapshot_hazard_observed_fk foreign key (tenant_id,hazard_id,hazard_version_at_scenario_revision) references public.hazard_source_revision(tenant_id,entity_id,version) on delete restrict deferrable initially deferred;
alter table public.risk_scenario enable row level security;
alter table public.risk_scenario force row level security;
create policy g014r_tenant_isolation on public.risk_scenario
    using (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
    with check (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
alter table public.risk_scenario_revision enable row level security;
alter table public.risk_scenario_revision force row level security;
create policy g014r_tenant_isolation on public.risk_scenario_revision
    using (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
    with check (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
alter table public.risk_hazard enable row level security;
alter table public.risk_hazard force row level security;
create policy g014r_tenant_isolation on public.risk_hazard
    using (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
    with check (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
alter table public.risk_hazard_revision enable row level security;
alter table public.risk_hazard_revision force row level security;
create policy g014r_tenant_isolation on public.risk_hazard_revision
    using (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
    with check (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
alter table public.risk_scenario_revision_hazard enable row level security;
alter table public.risk_scenario_revision_hazard force row level security;
create policy g014r_tenant_isolation on public.risk_scenario_revision_hazard
    using (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
    with check (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
alter table public.risk_scenario_receipt enable row level security;
alter table public.risk_scenario_receipt force row level security;
create policy g014r_tenant_isolation on public.risk_scenario_receipt
    using (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
    with check (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
create function public.g014r_require_context(p_tenant uuid) returns void
language plpgsql volatile security invoker set search_path = pg_catalog, public
as $$
declare tenant_bits bigint;
begin
    if p_tenant is null or p_tenant is distinct from nullif(current_setting('app.tenant_id',true),'')::uuid
        or current_setting('transaction_isolation') is distinct from 'read committed'
        or current_setting('transaction_read_only') is distinct from 'off' then
        raise exception 'invalid risk command boundary' using errcode='23514';
    end if;
    tenant_bits := ('x'||substr(encode(sha256(convert_to('msf.authorization.v1/tenant/'||p_tenant::text,'UTF8')),'hex'),1,16))::bit(64)::bigint;
    if not exists(select 1 from pg_locks where locktype='advisory' and pid=pg_backend_pid()
        and database=(select oid from pg_database where datname=current_database())
        and classid=1297303111::oid and objid=1::oid and objsubid=2 and mode='ShareLock' and granted)
        or not exists(select 1 from pg_locks where locktype='advisory' and pid=pg_backend_pid()
        and database=(select oid from pg_database where datname=current_database())
        and classid::bigint=((tenant_bits>>32)&4294967295) and objid::bigint=(tenant_bits&4294967295)
        and objsubid=1 and mode='ShareLock' and granted)
        or exists(select 1 from pg_locks where locktype='advisory' and pid=pg_backend_pid()
        and database=(select oid from pg_database where datname=current_database()) and mode='ExclusiveLock' and granted
        and ((classid=1297303111::oid and objid=1::oid and objsubid=2)
          or (classid::bigint=((tenant_bits>>32)&4294967295) and objid::bigint=(tenant_bits&4294967295) and objsubid=1))) then
        raise exception 'risk command shared security locks are not held' using errcode='23514';
    end if;
    -- Presence was checked first; establish transaction scope without a blocking late acquisition.
    if not pg_try_advisory_xact_lock_shared(1297303111,1) or not pg_try_advisory_xact_lock_shared(tenant_bits) then
        raise exception 'risk command transaction lock scope unavailable' using errcode='23514';
    end if;
end
$$;

create function public.g014r_key_header(p_table text,p_tenant uuid) returns bytea
language plpgsql immutable security invoker set search_path = pg_catalog, public
as $$
begin
    if p_tenant is null or p_table is null or p_table collate "C" not in
        ('risk_scenario','risk_scenario_revision','risk_hazard','risk_hazard_revision','risk_scenario_revision_hazard','risk_scenario_receipt') then
        raise exception 'invalid risk key table' using errcode='23514';
    end if;
    return decode('01','hex')||int4send(4)||int4send(1)
        ||decode('02','hex')||int4send(octet_length(convert_to(p_table,'UTF8')))||convert_to(p_table,'UTF8')
        ||decode('03','hex')||int4send(16)||uuid_send(p_tenant);
end
$$;

create function public.g014r_natural_key_bytes(p_tenant uuid,p_code text) returns bytea
language plpgsql immutable security invoker set search_path = pg_catalog, public
as $$
begin
    if p_code is null or p_code collate "C" !~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$' then
        raise exception 'invalid risk natural key' using errcode='23514';
    end if;
    return public.g014r_key_header('risk_scenario',p_tenant)||decode('05','hex')||int4send(octet_length(convert_to(p_code,'UTF8')))||convert_to(p_code,'UTF8');
end
$$;

create function public.g014r_receipt_key_bytes(p_tenant uuid,p_actor uuid,p_action text,p_idempotency uuid) returns bytea
language plpgsql immutable security invoker set search_path = pg_catalog, public
as $$
begin
    if p_actor is null or p_idempotency is null or p_action is null or p_action collate "C" not in ('risk_scenario.create','risk_scenario.update') then
        raise exception 'invalid risk receipt key' using errcode='23514';
    end if;
    return public.g014r_key_header('risk_scenario_receipt',p_tenant)
        ||decode('06','hex')||int4send(1)||decode('01','hex')
        ||decode('07','hex')||int4send(16)||uuid_send(p_actor)
        ||decode('08','hex')||int4send(octet_length(convert_to(p_action,'UTF8')))||convert_to(p_action,'UTF8')
        ||decode('09','hex')||int4send(16)||uuid_send(p_idempotency);
end
$$;

create function public.g014r_mutation_key_bytes(p_table text,p_tenant uuid,p_entity uuid,p_version bigint,p_command uuid,p_hazard uuid) returns bytea
language plpgsql immutable security invoker set search_path = pg_catalog, public
as $$
declare key_bytes bytea; revision_key boolean;
begin
    key_bytes := public.g014r_key_header(p_table,p_tenant);
    if p_table='risk_scenario_receipt' then
        if p_command is null or num_nonnulls(p_entity,p_version,p_hazard)<>0 then
            raise exception 'invalid risk command key shape' using errcode='23514';
        end if;
        return key_bytes||decode('06','hex')||int4send(1)||decode('04','hex')||decode('0c','hex')||int4send(16)||uuid_send(p_command);
    end if;
    revision_key := p_table in ('risk_scenario_revision','risk_hazard_revision','risk_scenario_revision_hazard');
    if p_entity is null or p_command is not null or (revision_key and (p_version is null or p_version not between 1 and 9007199254740991))
        or (not revision_key and p_version is not null)
        or ((p_table='risk_scenario_revision_hazard') is distinct from (p_hazard is not null)) then
        raise exception 'invalid risk mutation key shape' using errcode='23514';
    end if;
    key_bytes := key_bytes||decode('06','hex')||int4send(1)||case when revision_key then decode('03','hex') else decode('02','hex') end
        ||decode('0a','hex')||int4send(16)||uuid_send(p_entity);
    if revision_key then key_bytes := key_bytes||decode('0b','hex')||int4send(8)||int8send(p_version); end if;
    if p_hazard is not null then key_bytes := key_bytes||decode('0d','hex')||int4send(16)||uuid_send(p_hazard); end if;
    return key_bytes;
end
$$;

create function public.g014r_key_hash(p_bytes bytea) returns integer
language plpgsql immutable security invoker set search_path = pg_catalog, public
as $$
declare h bytea; bits bigint;
begin
    if p_bytes is null then raise exception 'missing risk key' using errcode='23514'; end if;
    h:=sha256(p_bytes); bits:=get_byte(h,0)::bigint*16777216+get_byte(h,1)::bigint*65536+get_byte(h,2)::bigint*256+get_byte(h,3)::bigint;
    if bits>=2147483648 then bits:=bits-4294967296; end if;
    return bits::integer;
end
$$;

create function public.g014r_require_key(p_namespace integer,p_bytes bytea) returns void
language plpgsql volatile security invoker set search_path = pg_catalog, public
as $$
declare key_bits integer;
begin
    if p_namespace is null or p_namespace not in (1297303118,1297303122,1297303123) then
        raise exception 'invalid risk key namespace' using errcode='23514';
    end if;
    key_bits:=public.g014r_key_hash(p_bytes);
    if not exists(select 1 from pg_locks where locktype='advisory' and pid=pg_backend_pid()
        and database=(select oid from pg_database where datname=current_database())
        and classid::bigint=(p_namespace::bigint&4294967295) and objid::bigint=(key_bits::bigint&4294967295)
        and objsubid=2 and mode='ExclusiveLock' and granted) then
        raise exception 'required risk command key is not held' using errcode='23514';
    end if;
    if not pg_try_advisory_xact_lock(p_namespace,key_bits) then
        raise exception 'required risk transaction hold unavailable' using errcode='23514';
    end if;
end
$$;

create function public.g014r_lock_natural_key(p_tenant uuid,p_code text) returns void
language plpgsql volatile security invoker set search_path = pg_catalog, public
as $$
begin
    perform public.g014r_require_context(p_tenant);
    perform pg_advisory_xact_lock(1297303118,public.g014r_key_hash(public.g014r_natural_key_bytes(p_tenant,p_code)));
end
$$;

create function public.g014r_require_natural_key(p_tenant uuid,p_code text) returns void
language plpgsql volatile security invoker set search_path = pg_catalog, public
as $$
begin
    perform public.g014r_require_context(p_tenant);
    perform public.g014r_require_key(1297303118,public.g014r_natural_key_bytes(p_tenant,p_code));
end
$$;

create function public.g014r_lock_receipt_key(p_tenant uuid,p_actor uuid,p_action text,p_idempotency uuid) returns void
language plpgsql volatile security invoker set search_path = pg_catalog, public
as $$
begin
    perform public.g014r_require_context(p_tenant);
    perform pg_advisory_xact_lock(1297303122,public.g014r_key_hash(public.g014r_receipt_key_bytes(p_tenant,p_actor,p_action,p_idempotency)));
end
$$;

create function public.g014r_require_mutation_key(p_table text,p_tenant uuid,p_entity uuid,p_version bigint,p_command uuid,p_hazard uuid) returns void
language plpgsql volatile security invoker set search_path = pg_catalog, public
as $$
begin
    perform public.g014r_require_context(p_tenant);
    perform public.g014r_require_key(1297303123,public.g014r_mutation_key_bytes(p_table,p_tenant,p_entity,p_version,p_command,p_hazard));
end
$$;

create function public.g014r_require_scenario_keys(p_tenant uuid,p_scenario uuid,p_version bigint,p_command uuid) returns void
language plpgsql volatile security invoker set search_path = pg_catalog, public
as $$
begin
    perform public.g014r_require_mutation_key('risk_scenario',p_tenant,p_scenario,null,null,null);
    perform public.g014r_require_mutation_key('risk_scenario_revision',p_tenant,p_scenario,p_version,null,null);
    perform public.g014r_require_mutation_key('risk_scenario_receipt',p_tenant,null,null,p_command,null);
end
$$;

create function public.g014r_mutation_key_plan(p_tenant uuid,p_scenario uuid,p_version bigint,p_command uuid,p_links uuid[],p_link_versions bigint[],p_hazards uuid[]) returns table(family integer,canonical_bytes bytea)
language plpgsql immutable security invoker set search_path = pg_catalog, public
as $$
begin
    if p_links is null or p_link_versions is null or p_hazards is null
        or cardinality(p_links)>200 or cardinality(p_link_versions)<>cardinality(p_links)
        or cardinality(p_hazards) not between 1 and 100
        or exists(select 1 from unnest(p_link_versions) v where v is null or v not in (1,2))
        or exists(select 1 from unnest(p_links) v where v is null)
        or exists(select 1 from unnest(p_hazards) v where v is null)
        or (select count(distinct v) from unnest(p_links) v)<>cardinality(p_links)
        or (select count(distinct v) from unnest(p_hazards) v)<>cardinality(p_hazards) then
        raise exception 'invalid bounded risk key plan' using errcode='23514';
    end if;
    return query
        select 20,public.g014r_mutation_key_bytes('risk_scenario',p_tenant,p_scenario,null,null,null)
        union select 30,public.g014r_mutation_key_bytes('risk_scenario_revision',p_tenant,p_scenario,p_version,null,null)
        union select 40,public.g014r_mutation_key_bytes('risk_scenario_receipt',p_tenant,null,null,p_command,null)
        union select 20,public.g014r_mutation_key_bytes('risk_hazard',p_tenant,x,null,null,null) from unnest(p_links) x
        union select 30,public.g014r_mutation_key_bytes('risk_hazard_revision',p_tenant,x,v,null,null) from unnest(p_links,p_link_versions) x(x,v)
        union select 30,public.g014r_mutation_key_bytes('risk_scenario_revision_hazard',p_tenant,p_scenario,p_version,null,h) from unnest(p_hazards) h;
end
$$;

create function public.g014r_lock_mutation_keys(p_tenant uuid,p_scenario uuid,p_version bigint,p_command uuid,p_links uuid[],p_link_versions bigint[],p_hazards uuid[]) returns void
language plpgsql volatile security invoker set search_path = pg_catalog, public
as $$
declare planned record;
begin
    perform public.g014r_require_context(p_tenant);
    for planned in select * from public.g014r_mutation_key_plan(p_tenant,p_scenario,p_version,p_command,p_links,p_link_versions,p_hazards)
        order by family,canonical_bytes loop
        perform pg_advisory_xact_lock(1297303123,public.g014r_key_hash(planned.canonical_bytes));
    end loop;
end
$$;


create function public.g014r_require_identity(p_tenant uuid,p_actor uuid,p_member uuid) returns void
language plpgsql volatile security invoker set search_path = pg_catalog, public
as $$
begin
    if not exists(select 1 from public.principal p join public.tenant_member m on m.account_id=p.account_id
        where p.id=p_actor and p.kind='HUMAN' and m.tenant_id=p_tenant and m.id=p_member) then
        raise exception 'risk command actor/member structure mismatch' using errcode='23514';
    end if;
end
$$;

create function public.g014r_open_command(p_tenant uuid,p_scenario uuid,p_version bigint,p_command uuid,p_actor uuid,p_member uuid,p_occurred timestamptz) returns public.risk_scenario
language plpgsql volatile security invoker set search_path = pg_catalog, public
as $$
declare s public.risk_scenario;
begin
    perform public.g014r_require_scenario_keys(p_tenant,p_scenario,p_version,p_command);
    select * into s from public.risk_scenario where tenant_id=p_tenant and id=p_scenario;
    if not found or row(s.version,s.last_command_id,s.updated_by_actor_id,s.updated_by_member_id,s.updated_at)
        is distinct from row(p_version,p_command,p_actor,p_member,p_occurred)
        or exists(select 1 from public.risk_scenario_receipt where tenant_id=p_tenant and command_id=p_command) then
        raise exception 'risk current command is absent or already closed' using errcode='23514';
    end if;
    perform public.g014r_require_identity(p_tenant,p_actor,p_member);
    return s;
end
$$;

create function public.g014r_validate_root_revision(p_tenant uuid,p_scenario uuid,p_version bigint,p_command uuid) returns void
language plpgsql volatile security invoker set search_path = pg_catalog, public
as $$
declare s public.risk_scenario; r public.risk_scenario_revision;
begin
    select * into s from public.risk_scenario where tenant_id=p_tenant and id=p_scenario;
    select * into r from public.risk_scenario_revision where tenant_id=p_tenant and entity_id=p_scenario and version=p_version;
    if s.id is null or r.entity_id is null or s.version is distinct from p_version or s.last_command_id is distinct from p_command
        or r.command_id is distinct from p_command or r.actor_id is distinct from s.updated_by_actor_id
        or r.member_id is distinct from s.updated_by_member_id or r.occurred_at is distinct from s.updated_at
        or r.change_kind is distinct from (case when p_version=1 then 'CREATE' else 'UPDATE' end)
        or (to_jsonb(r)-array['entity_id','command_id','actor_id','member_id','occurred_at','change_kind']::text[])
            is distinct from (to_jsonb(s)-array['id','last_command_id']::text[]) then
        raise exception 'risk complete root revision mismatch' using errcode='23514';
    end if;
end
$$;

create function public.g014r_validate_receipt(p_receipt public.risk_scenario_receipt) returns void
language plpgsql volatile security invoker set search_path = pg_catalog, public
as $$
declare s public.risk_scenario; expected_fields jsonb; relationship_changed boolean;
begin
    select * into s from public.risk_scenario where tenant_id=p_receipt.tenant_id and id=p_receipt.result_scenario_id;
    if s.id is null or row(s.version,s.last_command_id,s.code,s.updated_by_actor_id,s.updated_by_member_id,s.updated_at)
        is distinct from row(p_receipt.result_version,p_receipt.command_id,p_receipt.result_code,p_receipt.actor_id,p_receipt.member_id,p_receipt.occurred_at)
        or p_receipt.action_code is distinct from (case when s.version=1 then 'risk_scenario.create' else 'risk_scenario.update' end)
        or not exists(select 1 from public.principal p join public.tenant_member m on m.account_id=p.account_id
            where p.id=p_receipt.actor_id and p.kind='HUMAN' and p.account_id=p_receipt.account_id
            and m.tenant_id=p_receipt.tenant_id and m.id=p_receipt.member_id) then
        raise exception 'risk receipt current result or identity mismatch' using errcode='23514';
    end if;
    relationship_changed := exists(select 1 from public.risk_hazard_revision where tenant_id=s.tenant_id and scenario_id=s.id and command_id=s.last_command_id);
    expected_fields := case when s.version=1 then '["IDENTITY","DESCRIPTION","RELATIONSHIP"]'::jsonb
        when relationship_changed then '["DESCRIPTION","RELATIONSHIP"]'::jsonb else '["DESCRIPTION"]'::jsonb end;
    -- Requires separately reviewed column SELECT on audit_event; an existence FK is not tuple proof.
    if not exists(select 1 from public.audit_event a where a.tenant_id=p_receipt.tenant_id and a.id=p_receipt.audit_event_id
        and a.actor_id=p_receipt.actor_id and a.action=p_receipt.action_code and a.resource_type='risk_scenario'
        and a.resource_id=p_receipt.result_scenario_id and a.result='SUCCESS'
        and a.trace_id=p_receipt.trace_id::text and a.occurred_at=p_receipt.occurred_at
        and jsonb_typeof(a.diff_redacted)='object'
        and jsonb_typeof(a.diff_redacted->'schemaVersion')='number' and a.diff_redacted->>'schemaVersion'='1'
        and jsonb_typeof(a.diff_redacted->'commandId')='string'
        and jsonb_typeof(a.diff_redacted->'changeKind')='string'
        and jsonb_typeof(a.diff_redacted->'changedFieldCategories')='array'
        and a.diff_redacted=jsonb_build_object('schemaVersion',1,'commandId',p_receipt.command_id::text,
            'changeKind',case when s.version=1 then 'CREATE' else 'UPDATE' end,'changedFieldCategories',expected_fields)) then
        raise exception 'risk receipt successful typed audit mismatch' using errcode='23514';
    end if;
end
$$;

create function public.g014r_validate_scenario(p_tenant uuid,p_scenario uuid,p_version bigint,p_command uuid,p_require_receipt boolean) returns void
language plpgsql volatile security invoker set search_path = pg_catalog, public
as $$
declare s public.risk_scenario; c public.risk_scenario_receipt; item record; active_count bigint;
begin
    perform public.g014r_require_scenario_keys(p_tenant,p_scenario,p_version,p_command);
    perform public.g014r_validate_root_revision(p_tenant,p_scenario,p_version,p_command);
    select * into s from public.risk_scenario where tenant_id=p_tenant and id=p_scenario;
    select count(*) into active_count from public.risk_hazard where tenant_id=p_tenant and scenario_id=p_scenario and ended_at is null;
    if active_count not between 1 and 100 then raise exception 'risk complete hazard set outside bounds' using errcode='23514'; end if;
    -- Full bidirectional set comparison, not count equivalence. Both anchors and all frozen label/C fields participate.
    if exists(with expected as (select l.tenant_id,l.scenario_id,s.version as scenario_version,l.hazard_id,l.id as link_id,l.version as link_version,
        l.hazard_version_at_link,h.version as hazard_version_at_scenario_revision,hr.snapshot_code,
        hr.snapshot_description as full_description,left(hr.snapshot_description,200) as description_preview,
        (char_length(hr.snapshot_description)>200) as description_truncated,hr.snapshot_existence_state as existence,
        s.last_command_id as command_id,s.updated_by_actor_id as actor_id,s.updated_by_member_id as member_id,s.updated_at as occurred_at
    from public.risk_hazard l join public.hazard_source h on h.tenant_id=l.tenant_id and h.id=l.hazard_id
    join public.hazard_source_revision hr on hr.tenant_id=h.tenant_id and hr.entity_id=h.id and hr.version=h.version
    where l.tenant_id=p_tenant and l.scenario_id=p_scenario and l.ended_at is null), actual as (
        select tenant_id,scenario_id,scenario_version,hazard_id,link_id,link_version,hazard_version_at_link,hazard_version_at_scenario_revision,snapshot_code,full_description,description_preview,description_truncated,existence,command_id,actor_id,member_id,occurred_at from public.risk_scenario_revision_hazard
        where tenant_id=p_tenant and scenario_id=p_scenario and scenario_version=p_version)
        (select * from expected except select * from actual) union all (select * from actual except select * from expected)) then
        raise exception 'risk complete immutable hazard snapshot mismatch' using errcode='23514';
    end if;
    -- Every changed link has exactly its complete revision; unchanged links cannot acquire spurious revisions.
    if exists(with expected as (select tenant_id,id,version,scenario_id,hazard_id,hazard_version_at_link,linked_at,ended_at,created_by_actor_id,created_by_member_id,last_scenario_version,last_command_id,last_actor_id,last_member_id,last_occurred_at from public.risk_hazard
        where tenant_id=p_tenant and scenario_id=p_scenario and last_command_id=p_command), actual as (
        select tenant_id,entity_id,version,scenario_id,hazard_id,hazard_version_at_link,linked_at,ended_at,created_by_actor_id,created_by_member_id,scenario_version,command_id,actor_id,member_id,occurred_at from public.risk_hazard_revision where tenant_id=p_tenant and command_id=p_command)
        (select * from expected except select * from actual) union all (select * from actual except select * from expected)) then
        raise exception 'risk actual changed link revision set mismatch' using errcode='23514';
    end if;
    for item in select id,version from public.risk_hazard where tenant_id=p_tenant and scenario_id=p_scenario and last_command_id=p_command loop
        perform public.g014r_require_mutation_key('risk_hazard',p_tenant,item.id,null,null,null);
        perform public.g014r_require_mutation_key('risk_hazard_revision',p_tenant,item.id,item.version,null,null);
    end loop;
    for item in select hazard_id from public.risk_scenario_revision_hazard where tenant_id=p_tenant and scenario_id=p_scenario and scenario_version=p_version loop
        perform public.g014r_require_mutation_key('risk_scenario_revision_hazard',p_tenant,p_scenario,p_version,null,item.hazard_id);
    end loop;
    if p_require_receipt then
        select * into c from public.risk_scenario_receipt where tenant_id=p_tenant and command_id=p_command;
        if not found then raise exception 'risk command has no immutable receipt' using errcode='23514'; end if;
        perform public.g014r_validate_receipt(c);
        perform public.g014r_require_key(1297303122,public.g014r_receipt_key_bytes(c.tenant_id,c.actor_id,c.action_code,c.idempotency_key));
    end if;
end
$$;

create function public.g014r_before_scenario() returns trigger
language plpgsql volatile security invoker set search_path = pg_catalog, public
as $$
declare previous_receipt public.risk_scenario_receipt;
begin
    if tg_op not in ('INSERT','UPDATE') then raise exception 'risk roots cannot be deleted' using errcode='23514'; end if;
    perform public.g014r_require_scenario_keys(new.tenant_id,new.id,new.version,new.last_command_id);
    perform public.g014r_require_identity(new.tenant_id,new.updated_by_actor_id,new.updated_by_member_id);
    if exists(select 1 from public.risk_scenario_receipt where tenant_id=new.tenant_id and command_id=new.last_command_id)
        or new.accident_code_ref is not null or new.accident_version is not null then
        raise exception 'risk command is closed or unsupported professional fields supplied' using errcode='23514';
    end if;
    if tg_op='INSERT' then
        perform public.g014r_require_natural_key(new.tenant_id,new.code);
        if new.version<>1 or row(new.created_by_actor_id,new.created_by_member_id,new.created_at)
            is distinct from row(new.updated_by_actor_id,new.updated_by_member_id,new.updated_at)
            or not exists(select 1 from public.org_unit where tenant_id=new.tenant_id and id=new.organization_id and active) then
            raise exception 'invalid initial risk revision' using errcode='23514';
        end if;
    else
        if (to_jsonb(new)-array['description','triggering_conditions','exposure_description','consequence','version','updated_by_actor_id','updated_by_member_id','updated_at','last_command_id']::text[])
            is distinct from (to_jsonb(old)-array['description','triggering_conditions','exposure_description','consequence','version','updated_by_actor_id','updated_by_member_id','updated_at','last_command_id']::text[])
            or old.version>=9007199254740991 or new.version is distinct from old.version+1
            or new.last_command_id is not distinct from old.last_command_id or new.updated_at<old.updated_at then
            raise exception 'invalid risk identity or version transition' using errcode='23514';
        end if;
        -- A previous committed root must already be closed. This also forbids advancing a root twice in one NEW command.
        perform public.g014r_validate_root_revision(old.tenant_id,old.id,old.version,old.last_command_id);
        select * into previous_receipt from public.risk_scenario_receipt where tenant_id=old.tenant_id and command_id=old.last_command_id;
        if not found then raise exception 'previous risk revision is not closed' using errcode='23514'; end if;
        perform public.g014r_validate_receipt(previous_receipt);
    end if;
    return new;
end
$$;

create function public.g014r_before_revision() returns trigger
language plpgsql volatile security invoker set search_path = pg_catalog, public
as $$
declare s public.risk_scenario;
begin
    if tg_op<>'INSERT' then raise exception 'risk revisions are immutable' using errcode='23514'; end if;
    s:=public.g014r_open_command(new.tenant_id,new.entity_id,new.version,new.command_id,new.actor_id,new.member_id,new.occurred_at);
    if (to_jsonb(new)-array['entity_id','command_id','actor_id','member_id','occurred_at','change_kind']::text[])
        is distinct from (to_jsonb(s)-array['id','last_command_id']::text[])
        or new.change_kind is distinct from (case when s.version=1 then 'CREATE' else 'UPDATE' end) then
        raise exception 'risk inserted complete root revision mismatch' using errcode='23514';
    end if;
    return new;
end
$$;

create function public.g014r_before_link() returns trigger
language plpgsql volatile security invoker set search_path = pg_catalog, public
as $$
declare s public.risk_scenario;
begin
    if tg_op not in ('INSERT','UPDATE') then raise exception 'risk links cannot be deleted' using errcode='23514'; end if;
    s:=public.g014r_open_command(new.tenant_id,new.scenario_id,new.last_scenario_version,new.last_command_id,new.last_actor_id,new.last_member_id,new.last_occurred_at);
    perform public.g014r_require_mutation_key('risk_hazard',new.tenant_id,new.id,null,null,null);
    perform public.g014r_require_mutation_key('risk_hazard_revision',new.tenant_id,new.id,new.version,null,null);
    if tg_op='INSERT' then
        if new.version<>1 or new.ended_at is not null or new.linked_at is distinct from s.updated_at
            or row(new.created_by_actor_id,new.created_by_member_id) is distinct from row(s.updated_by_actor_id,s.updated_by_member_id)
            or not exists(select 1 from public.hazard_source h join public.hazard_source_revision r
                on r.tenant_id=h.tenant_id and r.entity_id=h.id and r.version=h.version
                where h.tenant_id=new.tenant_id and h.id=new.hazard_id and h.version=new.hazard_version_at_link) then
            raise exception 'invalid newly linked risk hazard' using errcode='23514';
        end if;
    else
        if (to_jsonb(new)-array['version','ended_at','last_scenario_version','last_command_id','last_actor_id','last_member_id','last_occurred_at']::text[])
            is distinct from (to_jsonb(old)-array['version','ended_at','last_scenario_version','last_command_id','last_actor_id','last_member_id','last_occurred_at']::text[])
            or old.version<>1 or old.ended_at is not null or new.version<>2
            or new.ended_at is distinct from s.updated_at or new.ended_at<=old.linked_at
            or old.last_command_id=new.last_command_id then
            raise exception 'risk links only permit one current END transition' using errcode='23514';
        end if;
    end if;
    return new;
end
$$;

create function public.g014r_before_link_revision() returns trigger
language plpgsql volatile security invoker set search_path = pg_catalog, public
as $$
declare s public.risk_scenario; l public.risk_hazard;
begin
    if tg_op<>'INSERT' then raise exception 'risk link revisions are immutable' using errcode='23514'; end if;
    s:=public.g014r_open_command(new.tenant_id,new.scenario_id,new.scenario_version,new.command_id,new.actor_id,new.member_id,new.occurred_at);
    perform public.g014r_require_mutation_key('risk_hazard',new.tenant_id,new.entity_id,null,null,null);
    perform public.g014r_require_mutation_key('risk_hazard_revision',new.tenant_id,new.entity_id,new.version,null,null);
    select * into l from public.risk_hazard where tenant_id=new.tenant_id and id=new.entity_id;
    if not found or row(l.last_scenario_version,l.last_command_id,l.last_actor_id,l.last_member_id,l.last_occurred_at)
        is distinct from row(new.scenario_version,new.command_id,new.actor_id,new.member_id,new.occurred_at)
        or (to_jsonb(new)-array['entity_id','scenario_version','command_id','actor_id','member_id','occurred_at']::text[])
            is distinct from (to_jsonb(l)-array['id','last_scenario_version','last_command_id','last_actor_id','last_member_id','last_occurred_at']::text[]) then
        raise exception 'risk link revision is not the exact current NEW or END' using errcode='23514';
    end if;
    return new;
end
$$;

create function public.g014r_before_snapshot() returns trigger
language plpgsql volatile security invoker set search_path = pg_catalog, public
as $$
declare s public.risk_scenario;
begin
    if tg_op<>'INSERT' then raise exception 'risk hazard snapshots are immutable' using errcode='23514'; end if;
    s:=public.g014r_open_command(new.tenant_id,new.scenario_id,new.scenario_version,new.command_id,new.actor_id,new.member_id,new.occurred_at);
    perform public.g014r_require_mutation_key('risk_scenario_revision_hazard',new.tenant_id,new.scenario_id,new.scenario_version,null,new.hazard_id);
    perform public.g014r_validate_root_revision(new.tenant_id,new.scenario_id,new.scenario_version,new.command_id);
    if not exists(select 1 from public.risk_hazard l join public.hazard_source h on h.tenant_id=l.tenant_id and h.id=l.hazard_id
        join public.hazard_source_revision hr on hr.tenant_id=h.tenant_id and hr.entity_id=h.id and hr.version=h.version
        where l.tenant_id=new.tenant_id and l.scenario_id=new.scenario_id and l.id=new.link_id and l.ended_at is null
        and row(l.hazard_id,l.version,l.hazard_version_at_link,h.version,hr.snapshot_code,hr.snapshot_description,
            left(hr.snapshot_description,200),(char_length(hr.snapshot_description)>200),hr.snapshot_existence_state)
            is not distinct from row(new.hazard_id,new.link_version,new.hazard_version_at_link,new.hazard_version_at_scenario_revision,
                new.snapshot_code,new.full_description,new.description_preview,new.description_truncated,new.existence)) then
        raise exception 'risk snapshot does not match current link and immutable observed hazard revision' using errcode='23514';
    end if;
    return new;
end
$$;

create function public.g014r_before_receipt() returns trigger
language plpgsql volatile security invoker set search_path = pg_catalog, public
as $$
declare s public.risk_scenario;
begin
    if tg_op<>'INSERT' then raise exception 'risk receipts are immutable' using errcode='23514'; end if;
    s:=public.g014r_open_command(new.tenant_id,new.result_scenario_id,new.result_version,new.command_id,new.actor_id,new.member_id,new.occurred_at);
    perform public.g014r_require_key(1297303122,public.g014r_receipt_key_bytes(new.tenant_id,new.actor_id,new.action_code,new.idempotency_key));
    perform public.g014r_validate_receipt(new);
    perform public.g014r_validate_scenario(new.tenant_id,new.result_scenario_id,new.result_version,new.command_id,false);
    -- Receipt is the last row of this command's six-table graph. Later child INSERT/END fails g014r_open_command.
    return new;
end
$$;

create trigger g014r_before_scenario before insert or update or delete on public.risk_scenario
    for each row execute function public.g014r_before_scenario();

create trigger g014r_before_revision before insert or update or delete on public.risk_scenario_revision
    for each row execute function public.g014r_before_revision();

create trigger g014r_before_link before insert or update or delete on public.risk_hazard
    for each row execute function public.g014r_before_link();

create trigger g014r_before_link_revision before insert or update or delete on public.risk_hazard_revision
    for each row execute function public.g014r_before_link_revision();

create trigger g014r_before_snapshot before insert or update or delete on public.risk_scenario_revision_hazard
    for each row execute function public.g014r_before_snapshot();

create trigger g014r_before_receipt before insert or update or delete on public.risk_scenario_receipt
    for each row execute function public.g014r_before_receipt();

create function public.g014r_scenario_history_complete() returns trigger
language plpgsql volatile security invoker set search_path = pg_catalog, public
as $$
begin
    perform public.g014r_validate_scenario(new.tenant_id,new.id,new.version,new.last_command_id,true);
    return null;
end
$$;

create function public.g014r_link_history_complete() returns trigger
language plpgsql volatile security invoker set search_path = pg_catalog, public
as $$
begin
    perform public.g014r_validate_scenario(new.tenant_id,new.scenario_id,new.last_scenario_version,new.last_command_id,true);
    return null;
end
$$;

create function public.g014r_receipt_complete() returns trigger
language plpgsql volatile security invoker set search_path = pg_catalog, public
as $$
begin
    perform public.g014r_validate_scenario(new.tenant_id,new.result_scenario_id,new.result_version,new.command_id,true);
    return null;
end
$$;

create constraint trigger g014r_scenario_history_complete after insert or update on public.risk_scenario
    deferrable initially deferred for each row execute function public.g014r_scenario_history_complete();

create constraint trigger g014r_link_history_complete after insert or update on public.risk_hazard
    deferrable initially deferred for each row execute function public.g014r_link_history_complete();

create constraint trigger g014r_receipt_complete after insert on public.risk_scenario_receipt
    deferrable initially deferred for each row execute function public.g014r_receipt_complete();

-- Global row-kind registry remains: production_line=10, workshop=20, process_stage=30,
-- equipment=40, work_unit=50, hazard_source=60, line_workshop=70, stage_equipment=80,
-- responsibility_binding=90, risk_scenario=100, risk_hazard=110.
-- Risk commands acquire only the needed 60 -> 100 -> 110 rows, UUID network-byte order within kind.
-- This acquisition helper is invoked by the sealed plan BEFORE t0, never by a BEFORE/closure trigger.

create function public.g014r_lock_row(p_kind integer,p_tenant uuid,p_id uuid) returns bigint
language plpgsql volatile security invoker set search_path = pg_catalog, public
as $$
declare held_version bigint;
begin
    perform public.g014r_require_context(p_tenant);
    if p_kind is null or p_id is null then raise exception 'invalid risk row key' using errcode='23514'; end if;
    case p_kind
        when 60 then select version into held_version from public.hazard_source where tenant_id=p_tenant and id=p_id for update;
        when 100 then select version into held_version from public.risk_scenario where tenant_id=p_tenant and id=p_id for update;
        when 110 then select version into held_version from public.risk_hazard where tenant_id=p_tenant and id=p_id for update;
        else raise exception 'unregistered risk row kind' using errcode='23514';
    end case;
    return held_version;
end
$$;

-- Capability definitions only. UUIDv5(DNS,"might-software-factory/capability/"||code).
-- Existing equivalent codes may retain their established IDs; IDs must never point at another triple.
do $$
declare expected record; actual record;
begin
    for expected in select * from (values
        ('d8d4d55d-d453-5ea9-9ab9-4c7ce1f25777','risk_scenario.read','risk_scenario','read'),
        ('4a8649f7-be73-587a-b7f3-c0fe98a917c4','risk_scenario.create','risk_scenario','create'),
        ('b9c33d83-7aa4-543b-bfd0-ea2c613e11f8','risk_scenario.update','risk_scenario','update')) as catalog(id,code,resource_type,action) loop
        if exists(select 1 from public.permission where id=expected.id::uuid
            and (code is distinct from expected.code or resource_type is distinct from expected.resource_type or action is distinct from expected.action)) then
            raise exception 'risk permission identifier triple mismatch' using errcode='23514';
        end if;
        select resource_type,action into actual from public.permission where code=expected.code;
        if found then
            if actual.resource_type is distinct from expected.resource_type or actual.action is distinct from expected.action then
                raise exception 'existing risk permission triple mismatch' using errcode='23514';
            end if;
        else
            insert into public.permission(id,code,resource_type,action)
                values(expected.id::uuid,expected.code,expected.resource_type,expected.action);
        end if;
    end loop;
end $$;

revoke execute on function public.g014r_require_context(uuid) from public;
revoke execute on function public.g014r_key_header(text,uuid) from public;
revoke execute on function public.g014r_natural_key_bytes(uuid,text) from public;
revoke execute on function public.g014r_receipt_key_bytes(uuid,uuid,text,uuid) from public;
revoke execute on function public.g014r_mutation_key_bytes(text,uuid,uuid,bigint,uuid,uuid) from public;
revoke execute on function public.g014r_key_hash(bytea) from public;
revoke execute on function public.g014r_require_key(integer,bytea) from public;
revoke execute on function public.g014r_lock_natural_key(uuid,text) from public;
revoke execute on function public.g014r_require_natural_key(uuid,text) from public;
revoke execute on function public.g014r_lock_receipt_key(uuid,uuid,text,uuid) from public;
revoke execute on function public.g014r_require_mutation_key(text,uuid,uuid,bigint,uuid,uuid) from public;
revoke execute on function public.g014r_require_scenario_keys(uuid,uuid,bigint,uuid) from public;
revoke execute on function public.g014r_mutation_key_plan(uuid,uuid,bigint,uuid,uuid[],bigint[],uuid[]) from public;
revoke execute on function public.g014r_lock_mutation_keys(uuid,uuid,bigint,uuid,uuid[],bigint[],uuid[]) from public;
revoke execute on function public.g014r_require_identity(uuid,uuid,uuid) from public;
revoke execute on function public.g014r_open_command(uuid,uuid,bigint,uuid,uuid,uuid,timestamptz) from public;
revoke execute on function public.g014r_validate_root_revision(uuid,uuid,bigint,uuid) from public;
revoke execute on function public.g014r_validate_receipt(public.risk_scenario_receipt) from public;
revoke execute on function public.g014r_validate_scenario(uuid,uuid,bigint,uuid,boolean) from public;
revoke execute on function public.g014r_before_scenario() from public;
revoke execute on function public.g014r_before_revision() from public;
revoke execute on function public.g014r_before_link() from public;
revoke execute on function public.g014r_before_link_revision() from public;
revoke execute on function public.g014r_before_snapshot() from public;
revoke execute on function public.g014r_before_receipt() from public;
revoke execute on function public.g014r_scenario_history_complete() from public;
revoke execute on function public.g014r_link_history_complete() from public;
revoke execute on function public.g014r_receipt_complete() from public;
revoke execute on function public.g014r_lock_row(integer,uuid,uuid) from public;
revoke all on table public.risk_scenario,public.risk_scenario_revision,public.risk_hazard,
    public.risk_hazard_revision,public.risk_scenario_revision_hazard,public.risk_scenario_receipt from public;
