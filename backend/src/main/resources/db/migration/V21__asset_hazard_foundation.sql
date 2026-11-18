-- G014 SOURCE CANDIDATE: no PostgreSQL execution/acceptance is claimed by this file.
-- Requires V18/V19/V20 source integration. Exactly 19 tables and 40 deferred constraints.
-- Accepted BIND-label addendum: four DB-derived business-version anchors, four deferred FKs.
-- Runtime is a separate non-owner, non-BYPASSRLS role; privilege proposal is separate.
-- Every helper is SECURITY INVOKER. No extensions, role assignment or runtime DELETE.
-- SQL invariants complement, but do not mint, current identity/authorization/final-seal proof.
-- All stages (including these deferred checks) finish before the final authorization cut.

create function public.g014_instant_valid(value timestamptz) returns boolean
language sql immutable strict parallel safe security invoker
as $$ select isfinite(value) and value >= timestamptz '0001-01-01 00:00:00+00'
    and value < timestamptz '10000-01-01 00:00:00+00' $$;

-- PostgreSQL counts Unicode code points; accepted text is stored without normalization.
-- PostgreSQL text cannot contain NUL. Names reject C0/C1 and outer Unicode White_Space.
create function public.g014_text_valid(value text, maximum integer, is_name boolean) returns boolean
language sql immutable strict parallel safe security invoker
as $$ select char_length(value) between 1 and maximum
    and case when is_name then
        value !~ ('[' || chr(1) || '-' || chr(31) || chr(127) || '-' || chr(159) || ']')
        and value = btrim(value, U&'\0009\000A\000B\000C\000D\0020\0085\00A0\1680\2000\2001\2002\2003\2004\2005\2006\2007\2008\2009\200A\2028\2029\202F\205F\3000')
    else value !~ ('[' || chr(1) || '-' || chr(8) || chr(11) || chr(12) || chr(14) || '-' || chr(31) || ']') end $$;

-- MSFN = 0x4D53464E. A collision only serializes unrelated keys; UNIQUE is authoritative.
-- Encoding v1: field tag byte || signed int4 network-order byte length || field bytes.
-- Tags: 01=version (int4 1), 02=table UTF8, 03=tenant uuid_send, 04=line uuid_send
-- (process_stage ONLY), 05=code UTF8. Family/full tuple bytes order, NEVER digest order.
-- The service acquires before row/FK locks; INSERT requires the exact preheld key without waiting.
create function public.g014_natural_key_bytes(table_name text, tenant_id uuid, code text, line_id uuid default null)
returns bytea language plpgsql immutable security invoker set search_path = pg_catalog, public
as $$
declare tuple_bytes bytea;
begin
    if table_name is null or table_name collate "C" not in ('workshop','production_line','process_stage','equipment','work_unit','hazard_source')
        or tenant_id is null
        or code is null or code collate "C" !~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$'
        or (table_name='process_stage') is distinct from (line_id is not null) then
        raise exception 'invalid natural key' using errcode='23514';
    end if;
    tuple_bytes := decode('01','hex') || int4send(4) || int4send(1)
        || decode('02','hex') || int4send(octet_length(convert_to(table_name,'UTF8'))) || convert_to(table_name,'UTF8')
        || decode('03','hex') || int4send(16) || uuid_send(tenant_id);
    if table_name='process_stage' then
        tuple_bytes := tuple_bytes || decode('04','hex') || int4send(16) || uuid_send(line_id);
    end if;
    tuple_bytes := tuple_bytes || decode('05','hex') || int4send(octet_length(convert_to(code,'UTF8'))) || convert_to(code,'UTF8');
    return tuple_bytes;
end $$;

create function public.g014_receipt_key_bytes(tenant_id uuid, actor_id uuid, action_code text, idempotency_key uuid)
returns bytea language plpgsql immutable security invoker set search_path = pg_catalog, public
as $$
begin
    if tenant_id is null or actor_id is null or idempotency_key is null or action_code is null
        or action_code collate "C" not in ('workshop.create','workshop.update','workshop.archive','production_line.create','production_line.update','production_line.archive','process_stage.create','process_stage.update','process_stage.archive','equipment.create','equipment.update','equipment.archive','work_unit.create','work_unit.update','work_unit.archive','hazard_source.create','hazard_source.update','line_workshop.bind','line_workshop.end','stage_equipment.bind','stage_equipment.end','responsibility_binding.bind','responsibility_binding.end') then
        raise exception 'invalid receipt key' using errcode='23514';
    end if;
    return decode('01','hex') || int4send(4) || int4send(1)
        || decode('02','hex') || int4send(octet_length(convert_to('asset_foundation_receipt','UTF8'))) || convert_to('asset_foundation_receipt','UTF8')
        || decode('03','hex') || int4send(16) || uuid_send(tenant_id)
        || decode('06','hex') || int4send(1) || decode('01','hex')
        || decode('07','hex') || int4send(16) || uuid_send(actor_id)
        || decode('08','hex') || int4send(octet_length(convert_to(action_code,'UTF8'))) || convert_to(action_code,'UTF8')
        || decode('09','hex') || int4send(16) || uuid_send(idempotency_key);
end $$;

-- Exactly one mutation per command. These are the complete business/revision/command keys.
create function public.g014_mutation_key_plan(resource_type text, tenant_id uuid, entity_id uuid, new_version bigint, command_id uuid)
returns table(family integer, canonical_bytes bytea)
language plpgsql immutable security invoker set search_path = pg_catalog, public
as $$
declare row_header bytea; revision_header bytea; command_header bytea; revision_table text;
begin
    if resource_type is null or resource_type collate "C" not in ('workshop','production_line','process_stage','equipment','work_unit','hazard_source','line_workshop','stage_equipment','responsibility_binding')
        or tenant_id is null or entity_id is null or command_id is null or new_version is null
        or new_version not between 1 and 9007199254740991 then
        raise exception 'invalid mutation key plan' using errcode='23514';
    end if;
    -- This finite suffix is framed data, NEVER an executable relation name.
    revision_table := resource_type || '_revision';
    row_header := decode('01','hex') || int4send(4) || int4send(1)
        || decode('02','hex') || int4send(octet_length(convert_to(resource_type,'UTF8'))) || convert_to(resource_type,'UTF8')
        || decode('03','hex') || int4send(16) || uuid_send(tenant_id);
    revision_header := decode('01','hex') || int4send(4) || int4send(1)
        || decode('02','hex') || int4send(octet_length(convert_to(revision_table,'UTF8'))) || convert_to(revision_table,'UTF8')
        || decode('03','hex') || int4send(16) || uuid_send(tenant_id);
    command_header := decode('01','hex') || int4send(4) || int4send(1)
        || decode('02','hex') || int4send(octet_length(convert_to('asset_foundation_receipt','UTF8'))) || convert_to('asset_foundation_receipt','UTF8')
        || decode('03','hex') || int4send(16) || uuid_send(tenant_id);
    return query
        select 20,row_header || decode('06','hex') || int4send(1) || decode('02','hex')
            || decode('0a','hex') || int4send(16) || uuid_send(entity_id)
        union all
        select 30,revision_header || decode('06','hex') || int4send(1) || decode('03','hex')
            || decode('0a','hex') || int4send(16) || uuid_send(entity_id)
            || decode('0b','hex') || int4send(8) || int8send(new_version)
        union all
        select 40,command_header || decode('06','hex') || int4send(1) || decode('04','hex')
            || decode('0c','hex') || int4send(16) || uuid_send(command_id);
end $$;

-- MSFN=1297303118, MSFR=1297303122, MSFS=1297303123 (pair-key namespaces).
-- Hash collisions serialize; exact UNIQUE/FK constraints and current final authority remain mandatory.
create function public.g014_lock_natural_key(table_name text, tenant_id uuid, code text, line_id uuid default null)
returns void language plpgsql volatile security invoker set search_path = pg_catalog, public
as $$
declare tuple_bytes bytea; digest_bytes bytea; key_bits bigint;
begin
    if tenant_id is null or tenant_id is distinct from nullif(current_setting('app.tenant_id',true),'')::uuid
        or current_setting('transaction_isolation') is distinct from 'read committed'
        or current_setting('transaction_read_only') is distinct from 'off' then
        raise exception 'invalid command boundary' using errcode='23514';
    end if;
    tuple_bytes := public.g014_natural_key_bytes(table_name,tenant_id,code,line_id);
    digest_bytes := sha256(tuple_bytes);
    key_bits := get_byte(digest_bytes,0)::bigint*16777216 + get_byte(digest_bytes,1)::bigint*65536
        + get_byte(digest_bytes,2)::bigint*256 + get_byte(digest_bytes,3)::bigint;
    if key_bits >= 2147483648 then key_bits := key_bits - 4294967296; end if;
    perform pg_advisory_xact_lock(1297303118, key_bits::integer);
end $$;

create function public.g014_require_natural_key(table_name text, tenant_id uuid, code text, line_id uuid default null)
returns void language plpgsql volatile security invoker set search_path = pg_catalog, public
as $$
declare tuple_bytes bytea; digest_bytes bytea; key_bits bigint;
begin
    if tenant_id is null or tenant_id is distinct from nullif(current_setting('app.tenant_id',true),'')::uuid
        or current_setting('transaction_isolation') is distinct from 'read committed'
        or current_setting('transaction_read_only') is distinct from 'off' then
        raise exception 'invalid command boundary' using errcode='23514';
    end if;
    tuple_bytes := public.g014_natural_key_bytes(table_name,tenant_id,code,line_id);
    digest_bytes := sha256(tuple_bytes);
    key_bits := get_byte(digest_bytes,0)::bigint*16777216 + get_byte(digest_bytes,1)::bigint*65536
        + get_byte(digest_bytes,2)::bigint*256 + get_byte(digest_bytes,3)::bigint;
    if key_bits >= 2147483648 then key_bits := key_bits - 4294967296; end if;
    -- Presence MUST precede try: an absent free key is not acquired after t0.
    if not exists (select 1 from pg_locks where locktype='advisory' and pid=pg_backend_pid()
        and database=(select oid from pg_database where datname=current_database())
        and classid::bigint=(1297303118::bigint & 4294967295)
        and objid::bigint=(key_bits & 4294967295) and objsubid=2 and mode='ExclusiveLock' and granted) then
        raise exception 'required command key is not held' using errcode='23514';
    end if;
    -- pg_locks cannot distinguish session scope; add an independent transaction hold without waiting.
    if not pg_try_advisory_xact_lock(1297303118, key_bits::integer) then
        raise exception 'required command transaction hold unavailable' using errcode='23514';
    end if;
end $$;

create function public.g014_lock_receipt_key(tenant_id uuid, actor_id uuid, action_code text, idempotency_key uuid)
returns void language plpgsql volatile security invoker set search_path = pg_catalog, public
as $$
declare tuple_bytes bytea; digest_bytes bytea; key_bits bigint;
begin
    if tenant_id is null or tenant_id is distinct from nullif(current_setting('app.tenant_id',true),'')::uuid
        or current_setting('transaction_isolation') is distinct from 'read committed'
        or current_setting('transaction_read_only') is distinct from 'off' then
        raise exception 'invalid command boundary' using errcode='23514';
    end if;
    tuple_bytes := public.g014_receipt_key_bytes(tenant_id,actor_id,action_code,idempotency_key);
    digest_bytes := sha256(tuple_bytes);
    key_bits := get_byte(digest_bytes,0)::bigint*16777216 + get_byte(digest_bytes,1)::bigint*65536
        + get_byte(digest_bytes,2)::bigint*256 + get_byte(digest_bytes,3)::bigint;
    if key_bits >= 2147483648 then key_bits := key_bits - 4294967296; end if;
    perform pg_advisory_xact_lock(1297303122, key_bits::integer);
end $$;

create function public.g014_require_receipt_key(tenant_id uuid, actor_id uuid, action_code text, idempotency_key uuid)
returns void language plpgsql volatile security invoker set search_path = pg_catalog, public
as $$
declare tuple_bytes bytea; digest_bytes bytea; key_bits bigint;
begin
    if tenant_id is null or tenant_id is distinct from nullif(current_setting('app.tenant_id',true),'')::uuid
        or current_setting('transaction_isolation') is distinct from 'read committed'
        or current_setting('transaction_read_only') is distinct from 'off' then
        raise exception 'invalid command boundary' using errcode='23514';
    end if;
    tuple_bytes := public.g014_receipt_key_bytes(tenant_id,actor_id,action_code,idempotency_key);
    digest_bytes := sha256(tuple_bytes);
    key_bits := get_byte(digest_bytes,0)::bigint*16777216 + get_byte(digest_bytes,1)::bigint*65536
        + get_byte(digest_bytes,2)::bigint*256 + get_byte(digest_bytes,3)::bigint;
    if key_bits >= 2147483648 then key_bits := key_bits - 4294967296; end if;
    -- Presence MUST precede try: an absent free key is not acquired after t0.
    if not exists (select 1 from pg_locks where locktype='advisory' and pid=pg_backend_pid()
        and database=(select oid from pg_database where datname=current_database())
        and classid::bigint=(1297303122::bigint & 4294967295)
        and objid::bigint=(key_bits & 4294967295) and objsubid=2 and mode='ExclusiveLock' and granted) then
        raise exception 'required command key is not held' using errcode='23514';
    end if;
    -- pg_locks cannot distinguish session scope; add an independent transaction hold without waiting.
    if not pg_try_advisory_xact_lock(1297303122, key_bits::integer) then
        raise exception 'required command transaction hold unavailable' using errcode='23514';
    end if;
end $$;

create function public.g014_lock_mutation_keys(resource_type text, tenant_id uuid, entity_id uuid, new_version bigint, command_id uuid)
returns void language plpgsql volatile security invoker set search_path = pg_catalog, public
as $$
declare tuple_bytes bytea; digest_bytes bytea; key_bits bigint; planned record;
begin
    if tenant_id is null or tenant_id is distinct from nullif(current_setting('app.tenant_id',true),'')::uuid
        or current_setting('transaction_isolation') is distinct from 'read committed'
        or current_setting('transaction_read_only') is distinct from 'off' then
        raise exception 'invalid command boundary' using errcode='23514';
    end if;
    for planned in select * from public.g014_mutation_key_plan(resource_type,tenant_id,entity_id,new_version,command_id) order by family,canonical_bytes loop
        tuple_bytes := planned.canonical_bytes;
    digest_bytes := sha256(tuple_bytes);
    key_bits := get_byte(digest_bytes,0)::bigint*16777216 + get_byte(digest_bytes,1)::bigint*65536
        + get_byte(digest_bytes,2)::bigint*256 + get_byte(digest_bytes,3)::bigint;
    if key_bits >= 2147483648 then key_bits := key_bits - 4294967296; end if;
    perform pg_advisory_xact_lock(1297303123, key_bits::integer);
    end loop;
end $$;

create function public.g014_require_mutation_keys(resource_type text, tenant_id uuid, entity_id uuid, new_version bigint, command_id uuid)
returns void language plpgsql volatile security invoker set search_path = pg_catalog, public
as $$
declare tuple_bytes bytea; digest_bytes bytea; key_bits bigint; planned record;
begin
    if tenant_id is null or tenant_id is distinct from nullif(current_setting('app.tenant_id',true),'')::uuid
        or current_setting('transaction_isolation') is distinct from 'read committed'
        or current_setting('transaction_read_only') is distinct from 'off' then
        raise exception 'invalid command boundary' using errcode='23514';
    end if;
    for planned in select * from public.g014_mutation_key_plan(resource_type,tenant_id,entity_id,new_version,command_id) order by family,canonical_bytes loop
        tuple_bytes := planned.canonical_bytes;
    digest_bytes := sha256(tuple_bytes);
    key_bits := get_byte(digest_bytes,0)::bigint*16777216 + get_byte(digest_bytes,1)::bigint*65536
        + get_byte(digest_bytes,2)::bigint*256 + get_byte(digest_bytes,3)::bigint;
    if key_bits >= 2147483648 then key_bits := key_bits - 4294967296; end if;
    -- Presence MUST precede try: an absent free key is not acquired after t0.
    if not exists (select 1 from pg_locks where locktype='advisory' and pid=pg_backend_pid()
        and database=(select oid from pg_database where datname=current_database())
        and classid::bigint=(1297303123::bigint & 4294967295)
        and objid::bigint=(key_bits & 4294967295) and objsubid=2 and mode='ExclusiveLock' and granted) then
        raise exception 'required command key is not held' using errcode='23514';
    end if;
    -- pg_locks cannot distinguish session scope; add an independent transaction hold without waiting.
    if not pg_try_advisory_xact_lock(1297303123, key_bits::integer) then
        raise exception 'required command transaction hold unavailable' using errcode='23514';
    end if;
    end loop;
end $$;

create table public.workshop (
    tenant_id uuid not null,
    id uuid not null,
    organization_id uuid not null,
    version bigint not null,
    interval_mutex_revision bigint default 0 not null,
    created_at timestamptz(6) not null,
    updated_at timestamptz(6) not null,
    created_by uuid not null,
    updated_by uuid not null,
    last_command_id uuid not null,
    code varchar(64) collate "C" not null,
    name varchar(200) collate "C" not null,
    boundary_description text collate "C",
    lifecycle varchar(16) collate "C" not null,
    check (version between 1 and 9007199254740991),
    check (interval_mutex_revision >= 0),
    check (created_at is null or public.g014_instant_valid(created_at)),
    check (updated_at is null or public.g014_instant_valid(updated_at)),
    check (code collate "C" ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$'),
    check (public.g014_text_valid(name,200,true)),
    check (boundary_description is null or public.g014_text_valid(boundary_description,2000,false)),
    check (lifecycle in ('ACTIVE','ARCHIVED')),
    primary key (tenant_id,id),
    constraint workshop_tenant_code_key unique (tenant_id,code),
    check (updated_at >= created_at)
);

create table public.production_line (
    tenant_id uuid not null,
    id uuid not null,
    organization_id uuid not null,
    version bigint not null,
    interval_mutex_revision bigint default 0 not null,
    created_at timestamptz(6) not null,
    updated_at timestamptz(6) not null,
    created_by uuid not null,
    updated_by uuid not null,
    last_command_id uuid not null,
    code varchar(64) collate "C" not null,
    name varchar(200) collate "C" not null,
    workshop_id uuid not null,
    description text collate "C",
    lifecycle varchar(16) collate "C" not null,
    check (version between 1 and 9007199254740991),
    check (interval_mutex_revision >= 0),
    check (created_at is null or public.g014_instant_valid(created_at)),
    check (updated_at is null or public.g014_instant_valid(updated_at)),
    check (code collate "C" ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$'),
    check (public.g014_text_valid(name,200,true)),
    check (description is null or public.g014_text_valid(description,2000,false)),
    check (lifecycle in ('ACTIVE','ARCHIVED')),
    primary key (tenant_id,id),
    constraint production_line_tenant_code_key unique (tenant_id,code),
    check (updated_at >= created_at)
);

create table public.process_stage (
    tenant_id uuid not null,
    id uuid not null,
    organization_id uuid not null,
    version bigint not null,
    interval_mutex_revision bigint default 0 not null,
    created_at timestamptz(6) not null,
    updated_at timestamptz(6) not null,
    created_by uuid not null,
    updated_by uuid not null,
    last_command_id uuid not null,
    code varchar(64) collate "C" not null,
    name varchar(200) collate "C" not null,
    line_id uuid not null,
    ordinal integer not null,
    description text collate "C",
    lifecycle varchar(16) collate "C" not null,
    check (version between 1 and 9007199254740991),
    check (interval_mutex_revision >= 0),
    check (created_at is null or public.g014_instant_valid(created_at)),
    check (updated_at is null or public.g014_instant_valid(updated_at)),
    check (code collate "C" ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$'),
    check (public.g014_text_valid(name,200,true)),
    check (ordinal between 0 and 1000000),
    check (description is null or public.g014_text_valid(description,2000,false)),
    check (lifecycle in ('ACTIVE','ARCHIVED')),
    primary key (tenant_id,id),
    constraint process_stage_tenant_line_code_key unique (tenant_id,line_id,code),
    check (updated_at >= created_at)
);

create table public.equipment (
    tenant_id uuid not null,
    id uuid not null,
    organization_id uuid not null,
    version bigint not null,
    interval_mutex_revision bigint default 0 not null,
    created_at timestamptz(6) not null,
    updated_at timestamptz(6) not null,
    created_by uuid not null,
    updated_by uuid not null,
    last_command_id uuid not null,
    code varchar(64) collate "C" not null,
    name varchar(200) collate "C" not null,
    type_description varchar(200) collate "C" not null,
    location_description text collate "C",
    operational_state varchar(24) collate "C" not null,
    lifecycle varchar(16) collate "C" not null,
    check (version between 1 and 9007199254740991),
    check (interval_mutex_revision >= 0),
    check (created_at is null or public.g014_instant_valid(created_at)),
    check (updated_at is null or public.g014_instant_valid(updated_at)),
    check (code collate "C" ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$'),
    check (public.g014_text_valid(name,200,true)),
    check (public.g014_text_valid(type_description,200,true)),
    check (location_description is null or public.g014_text_valid(location_description,2000,false)),
    check (operational_state in ('UNKNOWN','IN_SERVICE','OUT_OF_SERVICE')),
    check (lifecycle in ('ACTIVE','ARCHIVED')),
    primary key (tenant_id,id),
    constraint equipment_tenant_code_key unique (tenant_id,code),
    check (updated_at >= created_at)
);

create table public.work_unit (
    tenant_id uuid not null,
    id uuid not null,
    organization_id uuid not null,
    version bigint not null,
    interval_mutex_revision bigint default 0 not null,
    created_at timestamptz(6) not null,
    updated_at timestamptz(6) not null,
    created_by uuid not null,
    updated_by uuid not null,
    last_command_id uuid not null,
    code varchar(64) collate "C" not null,
    name varchar(200) collate "C" not null,
    kind varchar(16) collate "C" not null,
    workshop_id uuid,
    line_id uuid,
    stage_id uuid,
    equipment_id uuid,
    boundary_description text collate "C",
    lifecycle varchar(16) collate "C" not null,
    check (version between 1 and 9007199254740991),
    check (interval_mutex_revision >= 0),
    check (created_at is null or public.g014_instant_valid(created_at)),
    check (updated_at is null or public.g014_instant_valid(updated_at)),
    check (code collate "C" ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$'),
    check (public.g014_text_valid(name,200,true)),
    check (boundary_description is null or public.g014_text_valid(boundary_description,2000,false)),
    check (lifecycle in ('ACTIVE','ARCHIVED')),
    primary key (tenant_id,id),
    constraint work_unit_tenant_code_key unique (tenant_id,code),
    check (updated_at >= created_at),
    check ((kind='STANDALONE' and num_nonnulls(workshop_id,line_id,stage_id,equipment_id)=0) or (kind='WORKSHOP' and workshop_id is not null and num_nonnulls(workshop_id,line_id,stage_id,equipment_id)=1) or (kind='LINE' and line_id is not null and num_nonnulls(workshop_id,line_id,stage_id,equipment_id)=1) or (kind='STAGE' and stage_id is not null and num_nonnulls(workshop_id,line_id,stage_id,equipment_id)=1) or (kind='EQUIPMENT' and equipment_id is not null and num_nonnulls(workshop_id,line_id,stage_id,equipment_id)=1))
);

create table public.hazard_source (
    tenant_id uuid not null,
    id uuid not null,
    organization_id uuid not null,
    version bigint not null,
    interval_mutex_revision bigint default 0 not null,
    created_at timestamptz(6) not null,
    updated_at timestamptz(6) not null,
    created_by uuid not null,
    updated_by uuid not null,
    last_command_id uuid not null,
    code varchar(64) collate "C" not null,
    work_unit_id uuid not null,
    description text collate "C" not null,
    identified_at timestamptz(6) not null,
    existence_state varchar(24) collate "C" not null,
    factor_code_ref varchar(64) collate "C",
    factor_version varchar(64) collate "C",
    check (version between 1 and 9007199254740991),
    check (interval_mutex_revision >= 0),
    check (created_at is null or public.g014_instant_valid(created_at)),
    check (updated_at is null or public.g014_instant_valid(updated_at)),
    check (code collate "C" ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$'),
    check (description is null or public.g014_text_valid(description,20000,false)),
    check (identified_at is null or public.g014_instant_valid(identified_at)),
    check (existence_state in ('UNKNOWN','PRESENT','NOT_PRESENT')),
    primary key (tenant_id,id),
    constraint hazard_source_tenant_code_key unique (tenant_id,code),
    check (updated_at >= created_at),
    check (factor_code_ref is null and factor_version is null)
);

create table public.line_workshop (
    tenant_id uuid not null,
    id uuid not null,
    organization_id uuid not null,
    version bigint not null,
    created_at timestamptz(6) not null,
    updated_at timestamptz(6) not null,
    created_by uuid not null,
    updated_by uuid not null,
    last_command_id uuid not null,
    line_id uuid not null,
    line_version_at_bind bigint not null check (line_version_at_bind between 1 and 9007199254740991),
    workshop_id uuid not null,
    workshop_version_at_bind bigint not null check (workshop_version_at_bind between 1 and 9007199254740991),
    valid_from timestamptz(6) not null,
    valid_to timestamptz(6),
    end_observed_at timestamptz(6),
    check (version between 1 and 9007199254740991),
    check (created_at is null or public.g014_instant_valid(created_at)),
    check (updated_at is null or public.g014_instant_valid(updated_at)),
    check (valid_from is null or public.g014_instant_valid(valid_from)),
    check (valid_to is null or public.g014_instant_valid(valid_to)),
    check (end_observed_at is null or public.g014_instant_valid(end_observed_at)),
    primary key (tenant_id,id),
    check (updated_at >= created_at),
    check (valid_to is null or valid_from < valid_to),
    check (end_observed_at is null or (valid_to is not null and valid_to >= end_observed_at))
);

create table public.stage_equipment (
    tenant_id uuid not null,
    id uuid not null,
    organization_id uuid not null,
    version bigint not null,
    created_at timestamptz(6) not null,
    updated_at timestamptz(6) not null,
    created_by uuid not null,
    updated_by uuid not null,
    last_command_id uuid not null,
    stage_id uuid not null,
    stage_version_at_bind bigint not null check (stage_version_at_bind between 1 and 9007199254740991),
    equipment_id uuid not null,
    equipment_version_at_bind bigint not null check (equipment_version_at_bind between 1 and 9007199254740991),
    valid_from timestamptz(6) not null,
    valid_to timestamptz(6),
    end_observed_at timestamptz(6),
    check (version between 1 and 9007199254740991),
    check (created_at is null or public.g014_instant_valid(created_at)),
    check (updated_at is null or public.g014_instant_valid(updated_at)),
    check (valid_from is null or public.g014_instant_valid(valid_from)),
    check (valid_to is null or public.g014_instant_valid(valid_to)),
    check (end_observed_at is null or public.g014_instant_valid(end_observed_at)),
    primary key (tenant_id,id),
    check (updated_at >= created_at),
    check (valid_to is null or valid_from < valid_to),
    check (end_observed_at is null or (valid_to is not null and valid_to >= end_observed_at))
);

create table public.responsibility_binding (
    tenant_id uuid not null,
    id uuid not null,
    organization_id uuid not null,
    version bigint not null,
    created_at timestamptz(6) not null,
    updated_at timestamptz(6) not null,
    created_by uuid not null,
    updated_by uuid not null,
    last_command_id uuid not null,
    work_unit_id uuid not null,
    org_id uuid not null,
    position_id uuid,
    duty varchar(32) collate "C" not null,
    valid_from timestamptz(6) not null,
    valid_to timestamptz(6),
    end_observed_at timestamptz(6),
    org_code_snapshot varchar(64) collate "C" not null,
    org_name_snapshot varchar(200) collate "C" not null,
    position_code_snapshot varchar(64) collate "C",
    position_name_snapshot varchar(200) collate "C",
    directory_observed_at timestamptz(6) not null,
    check (version between 1 and 9007199254740991),
    check (created_at is null or public.g014_instant_valid(created_at)),
    check (updated_at is null or public.g014_instant_valid(updated_at)),
    check (duty in ('OPERATIONS','MAINTENANCE','SAFETY_COORDINATION')),
    check (valid_from is null or public.g014_instant_valid(valid_from)),
    check (valid_to is null or public.g014_instant_valid(valid_to)),
    check (end_observed_at is null or public.g014_instant_valid(end_observed_at)),
    check (directory_observed_at is null or public.g014_instant_valid(directory_observed_at)),
    primary key (tenant_id,id),
    check (updated_at >= created_at),
    check (valid_to is null or valid_from < valid_to),
    check (end_observed_at is null or (valid_to is not null and valid_to >= end_observed_at)),
    check ((position_id is null and position_code_snapshot is null and position_name_snapshot is null) or (position_id is not null and position_code_snapshot is not null and position_name_snapshot is not null))
);

create table public.workshop_revision (
    tenant_id uuid not null,
    entity_id uuid not null,
    version bigint not null,
    command_id uuid not null,
    change_kind varchar(16) collate "C" not null,
    occurred_at timestamptz(6) not null,
    actor_id uuid not null,
    snapshot_organization_id uuid not null,
    snapshot_created_at timestamptz(6) not null,
    snapshot_updated_at timestamptz(6) not null,
    snapshot_created_by uuid not null,
    snapshot_updated_by uuid not null,
    snapshot_code varchar(64) collate "C" not null,
    snapshot_name varchar(200) collate "C" not null,
    snapshot_boundary_description text collate "C",
    snapshot_lifecycle varchar(16) collate "C" not null,
    check (version between 1 and 9007199254740991),
    check (occurred_at is null or public.g014_instant_valid(occurred_at)),
    check (snapshot_created_at is null or public.g014_instant_valid(snapshot_created_at)),
    check (snapshot_updated_at is null or public.g014_instant_valid(snapshot_updated_at)),
    check (snapshot_code collate "C" ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$'),
    check (public.g014_text_valid(snapshot_name,200,true)),
    check (snapshot_boundary_description is null or public.g014_text_valid(snapshot_boundary_description,2000,false)),
    check (snapshot_lifecycle in ('ACTIVE','ARCHIVED')),
    primary key (tenant_id,entity_id,version),
    unique (tenant_id,command_id),
    check (change_kind in ('CREATE','UPDATE','ARCHIVE')),
    check ((version=1) = (change_kind in ('CREATE','BIND')))
);

create table public.production_line_revision (
    tenant_id uuid not null,
    entity_id uuid not null,
    version bigint not null,
    command_id uuid not null,
    change_kind varchar(16) collate "C" not null,
    occurred_at timestamptz(6) not null,
    actor_id uuid not null,
    snapshot_organization_id uuid not null,
    snapshot_created_at timestamptz(6) not null,
    snapshot_updated_at timestamptz(6) not null,
    snapshot_created_by uuid not null,
    snapshot_updated_by uuid not null,
    snapshot_code varchar(64) collate "C" not null,
    snapshot_name varchar(200) collate "C" not null,
    snapshot_workshop_id uuid not null,
    snapshot_description text collate "C",
    snapshot_lifecycle varchar(16) collate "C" not null,
    check (version between 1 and 9007199254740991),
    check (occurred_at is null or public.g014_instant_valid(occurred_at)),
    check (snapshot_created_at is null or public.g014_instant_valid(snapshot_created_at)),
    check (snapshot_updated_at is null or public.g014_instant_valid(snapshot_updated_at)),
    check (snapshot_code collate "C" ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$'),
    check (public.g014_text_valid(snapshot_name,200,true)),
    check (snapshot_description is null or public.g014_text_valid(snapshot_description,2000,false)),
    check (snapshot_lifecycle in ('ACTIVE','ARCHIVED')),
    primary key (tenant_id,entity_id,version),
    unique (tenant_id,command_id),
    check (change_kind in ('CREATE','UPDATE','ARCHIVE')),
    check ((version=1) = (change_kind in ('CREATE','BIND')))
);

create table public.process_stage_revision (
    tenant_id uuid not null,
    entity_id uuid not null,
    version bigint not null,
    command_id uuid not null,
    change_kind varchar(16) collate "C" not null,
    occurred_at timestamptz(6) not null,
    actor_id uuid not null,
    snapshot_organization_id uuid not null,
    snapshot_created_at timestamptz(6) not null,
    snapshot_updated_at timestamptz(6) not null,
    snapshot_created_by uuid not null,
    snapshot_updated_by uuid not null,
    snapshot_code varchar(64) collate "C" not null,
    snapshot_name varchar(200) collate "C" not null,
    snapshot_line_id uuid not null,
    snapshot_ordinal integer not null,
    snapshot_description text collate "C",
    snapshot_lifecycle varchar(16) collate "C" not null,
    check (version between 1 and 9007199254740991),
    check (occurred_at is null or public.g014_instant_valid(occurred_at)),
    check (snapshot_created_at is null or public.g014_instant_valid(snapshot_created_at)),
    check (snapshot_updated_at is null or public.g014_instant_valid(snapshot_updated_at)),
    check (snapshot_code collate "C" ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$'),
    check (public.g014_text_valid(snapshot_name,200,true)),
    check (snapshot_ordinal between 0 and 1000000),
    check (snapshot_description is null or public.g014_text_valid(snapshot_description,2000,false)),
    check (snapshot_lifecycle in ('ACTIVE','ARCHIVED')),
    primary key (tenant_id,entity_id,version),
    unique (tenant_id,command_id),
    check (change_kind in ('CREATE','UPDATE','ARCHIVE')),
    check ((version=1) = (change_kind in ('CREATE','BIND')))
);

create table public.equipment_revision (
    tenant_id uuid not null,
    entity_id uuid not null,
    version bigint not null,
    command_id uuid not null,
    change_kind varchar(16) collate "C" not null,
    occurred_at timestamptz(6) not null,
    actor_id uuid not null,
    snapshot_organization_id uuid not null,
    snapshot_created_at timestamptz(6) not null,
    snapshot_updated_at timestamptz(6) not null,
    snapshot_created_by uuid not null,
    snapshot_updated_by uuid not null,
    snapshot_code varchar(64) collate "C" not null,
    snapshot_name varchar(200) collate "C" not null,
    snapshot_type_description varchar(200) collate "C" not null,
    snapshot_location_description text collate "C",
    snapshot_operational_state varchar(24) collate "C" not null,
    snapshot_lifecycle varchar(16) collate "C" not null,
    check (version between 1 and 9007199254740991),
    check (occurred_at is null or public.g014_instant_valid(occurred_at)),
    check (snapshot_created_at is null or public.g014_instant_valid(snapshot_created_at)),
    check (snapshot_updated_at is null or public.g014_instant_valid(snapshot_updated_at)),
    check (snapshot_code collate "C" ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$'),
    check (public.g014_text_valid(snapshot_name,200,true)),
    check (public.g014_text_valid(snapshot_type_description,200,true)),
    check (snapshot_location_description is null or public.g014_text_valid(snapshot_location_description,2000,false)),
    check (snapshot_operational_state in ('UNKNOWN','IN_SERVICE','OUT_OF_SERVICE')),
    check (snapshot_lifecycle in ('ACTIVE','ARCHIVED')),
    primary key (tenant_id,entity_id,version),
    unique (tenant_id,command_id),
    check (change_kind in ('CREATE','UPDATE','ARCHIVE')),
    check ((version=1) = (change_kind in ('CREATE','BIND')))
);

create table public.work_unit_revision (
    tenant_id uuid not null,
    entity_id uuid not null,
    version bigint not null,
    command_id uuid not null,
    change_kind varchar(16) collate "C" not null,
    occurred_at timestamptz(6) not null,
    actor_id uuid not null,
    snapshot_organization_id uuid not null,
    snapshot_created_at timestamptz(6) not null,
    snapshot_updated_at timestamptz(6) not null,
    snapshot_created_by uuid not null,
    snapshot_updated_by uuid not null,
    snapshot_code varchar(64) collate "C" not null,
    snapshot_name varchar(200) collate "C" not null,
    snapshot_kind varchar(16) collate "C" not null,
    snapshot_workshop_id uuid,
    snapshot_line_id uuid,
    snapshot_stage_id uuid,
    snapshot_equipment_id uuid,
    snapshot_boundary_description text collate "C",
    snapshot_lifecycle varchar(16) collate "C" not null,
    check (version between 1 and 9007199254740991),
    check (occurred_at is null or public.g014_instant_valid(occurred_at)),
    check (snapshot_created_at is null or public.g014_instant_valid(snapshot_created_at)),
    check (snapshot_updated_at is null or public.g014_instant_valid(snapshot_updated_at)),
    check (snapshot_code collate "C" ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$'),
    check (public.g014_text_valid(snapshot_name,200,true)),
    check (snapshot_boundary_description is null or public.g014_text_valid(snapshot_boundary_description,2000,false)),
    check (snapshot_lifecycle in ('ACTIVE','ARCHIVED')),
    primary key (tenant_id,entity_id,version),
    unique (tenant_id,command_id),
    check (change_kind in ('CREATE','UPDATE','ARCHIVE')),
    check ((version=1) = (change_kind in ('CREATE','BIND'))),
    check ((snapshot_kind='STANDALONE' and num_nonnulls(snapshot_workshop_id,snapshot_line_id,snapshot_stage_id,snapshot_equipment_id)=0) or (snapshot_kind='WORKSHOP' and snapshot_workshop_id is not null and num_nonnulls(snapshot_workshop_id,snapshot_line_id,snapshot_stage_id,snapshot_equipment_id)=1) or (snapshot_kind='LINE' and snapshot_line_id is not null and num_nonnulls(snapshot_workshop_id,snapshot_line_id,snapshot_stage_id,snapshot_equipment_id)=1) or (snapshot_kind='STAGE' and snapshot_stage_id is not null and num_nonnulls(snapshot_workshop_id,snapshot_line_id,snapshot_stage_id,snapshot_equipment_id)=1) or (snapshot_kind='EQUIPMENT' and snapshot_equipment_id is not null and num_nonnulls(snapshot_workshop_id,snapshot_line_id,snapshot_stage_id,snapshot_equipment_id)=1))
);

create table public.hazard_source_revision (
    tenant_id uuid not null,
    entity_id uuid not null,
    version bigint not null,
    command_id uuid not null,
    change_kind varchar(16) collate "C" not null,
    occurred_at timestamptz(6) not null,
    actor_id uuid not null,
    snapshot_organization_id uuid not null,
    snapshot_created_at timestamptz(6) not null,
    snapshot_updated_at timestamptz(6) not null,
    snapshot_created_by uuid not null,
    snapshot_updated_by uuid not null,
    snapshot_code varchar(64) collate "C" not null,
    snapshot_work_unit_id uuid not null,
    snapshot_description text collate "C" not null,
    snapshot_identified_at timestamptz(6) not null,
    snapshot_existence_state varchar(24) collate "C" not null,
    snapshot_factor_code_ref varchar(64) collate "C",
    snapshot_factor_version varchar(64) collate "C",
    check (version between 1 and 9007199254740991),
    check (occurred_at is null or public.g014_instant_valid(occurred_at)),
    check (snapshot_created_at is null or public.g014_instant_valid(snapshot_created_at)),
    check (snapshot_updated_at is null or public.g014_instant_valid(snapshot_updated_at)),
    check (snapshot_code collate "C" ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$'),
    check (snapshot_description is null or public.g014_text_valid(snapshot_description,20000,false)),
    check (snapshot_identified_at is null or public.g014_instant_valid(snapshot_identified_at)),
    check (snapshot_existence_state in ('UNKNOWN','PRESENT','NOT_PRESENT')),
    primary key (tenant_id,entity_id,version),
    unique (tenant_id,command_id),
    check (change_kind in ('CREATE','UPDATE')),
    check ((version=1) = (change_kind in ('CREATE','BIND'))),
    check (snapshot_factor_code_ref is null and snapshot_factor_version is null)
);

create table public.line_workshop_revision (
    tenant_id uuid not null,
    entity_id uuid not null,
    version bigint not null,
    command_id uuid not null,
    change_kind varchar(16) collate "C" not null,
    occurred_at timestamptz(6) not null,
    actor_id uuid not null,
    snapshot_organization_id uuid not null,
    snapshot_created_at timestamptz(6) not null,
    snapshot_updated_at timestamptz(6) not null,
    snapshot_created_by uuid not null,
    snapshot_updated_by uuid not null,
    snapshot_line_id uuid not null,
    snapshot_line_version_at_bind bigint not null check (snapshot_line_version_at_bind between 1 and 9007199254740991),
    snapshot_workshop_id uuid not null,
    snapshot_workshop_version_at_bind bigint not null check (snapshot_workshop_version_at_bind between 1 and 9007199254740991),
    snapshot_valid_from timestamptz(6) not null,
    snapshot_valid_to timestamptz(6),
    snapshot_end_observed_at timestamptz(6),
    check (version between 1 and 9007199254740991),
    check (occurred_at is null or public.g014_instant_valid(occurred_at)),
    check (snapshot_created_at is null or public.g014_instant_valid(snapshot_created_at)),
    check (snapshot_updated_at is null or public.g014_instant_valid(snapshot_updated_at)),
    check (snapshot_valid_from is null or public.g014_instant_valid(snapshot_valid_from)),
    check (snapshot_valid_to is null or public.g014_instant_valid(snapshot_valid_to)),
    check (snapshot_end_observed_at is null or public.g014_instant_valid(snapshot_end_observed_at)),
    primary key (tenant_id,entity_id,version),
    unique (tenant_id,command_id),
    check (change_kind in ('BIND','END')),
    check ((version=1) = (change_kind in ('CREATE','BIND'))),
    check (snapshot_valid_to is null or snapshot_valid_from < snapshot_valid_to),
    check (snapshot_end_observed_at is null or (snapshot_valid_to is not null and snapshot_valid_to >= snapshot_end_observed_at))
);

create table public.stage_equipment_revision (
    tenant_id uuid not null,
    entity_id uuid not null,
    version bigint not null,
    command_id uuid not null,
    change_kind varchar(16) collate "C" not null,
    occurred_at timestamptz(6) not null,
    actor_id uuid not null,
    snapshot_organization_id uuid not null,
    snapshot_created_at timestamptz(6) not null,
    snapshot_updated_at timestamptz(6) not null,
    snapshot_created_by uuid not null,
    snapshot_updated_by uuid not null,
    snapshot_stage_id uuid not null,
    snapshot_stage_version_at_bind bigint not null check (snapshot_stage_version_at_bind between 1 and 9007199254740991),
    snapshot_equipment_id uuid not null,
    snapshot_equipment_version_at_bind bigint not null check (snapshot_equipment_version_at_bind between 1 and 9007199254740991),
    snapshot_valid_from timestamptz(6) not null,
    snapshot_valid_to timestamptz(6),
    snapshot_end_observed_at timestamptz(6),
    check (version between 1 and 9007199254740991),
    check (occurred_at is null or public.g014_instant_valid(occurred_at)),
    check (snapshot_created_at is null or public.g014_instant_valid(snapshot_created_at)),
    check (snapshot_updated_at is null or public.g014_instant_valid(snapshot_updated_at)),
    check (snapshot_valid_from is null or public.g014_instant_valid(snapshot_valid_from)),
    check (snapshot_valid_to is null or public.g014_instant_valid(snapshot_valid_to)),
    check (snapshot_end_observed_at is null or public.g014_instant_valid(snapshot_end_observed_at)),
    primary key (tenant_id,entity_id,version),
    unique (tenant_id,command_id),
    check (change_kind in ('BIND','END')),
    check ((version=1) = (change_kind in ('CREATE','BIND'))),
    check (snapshot_valid_to is null or snapshot_valid_from < snapshot_valid_to),
    check (snapshot_end_observed_at is null or (snapshot_valid_to is not null and snapshot_valid_to >= snapshot_end_observed_at))
);

create table public.responsibility_binding_revision (
    tenant_id uuid not null,
    entity_id uuid not null,
    version bigint not null,
    command_id uuid not null,
    change_kind varchar(16) collate "C" not null,
    occurred_at timestamptz(6) not null,
    actor_id uuid not null,
    snapshot_organization_id uuid not null,
    snapshot_created_at timestamptz(6) not null,
    snapshot_updated_at timestamptz(6) not null,
    snapshot_created_by uuid not null,
    snapshot_updated_by uuid not null,
    snapshot_work_unit_id uuid not null,
    snapshot_org_id uuid not null,
    snapshot_position_id uuid,
    snapshot_duty varchar(32) collate "C" not null,
    snapshot_valid_from timestamptz(6) not null,
    snapshot_valid_to timestamptz(6),
    snapshot_end_observed_at timestamptz(6),
    snapshot_org_code_snapshot varchar(64) collate "C" not null,
    snapshot_org_name_snapshot varchar(200) collate "C" not null,
    snapshot_position_code_snapshot varchar(64) collate "C",
    snapshot_position_name_snapshot varchar(200) collate "C",
    snapshot_directory_observed_at timestamptz(6) not null,
    check (version between 1 and 9007199254740991),
    check (occurred_at is null or public.g014_instant_valid(occurred_at)),
    check (snapshot_created_at is null or public.g014_instant_valid(snapshot_created_at)),
    check (snapshot_updated_at is null or public.g014_instant_valid(snapshot_updated_at)),
    check (snapshot_duty in ('OPERATIONS','MAINTENANCE','SAFETY_COORDINATION')),
    check (snapshot_valid_from is null or public.g014_instant_valid(snapshot_valid_from)),
    check (snapshot_valid_to is null or public.g014_instant_valid(snapshot_valid_to)),
    check (snapshot_end_observed_at is null or public.g014_instant_valid(snapshot_end_observed_at)),
    check (snapshot_directory_observed_at is null or public.g014_instant_valid(snapshot_directory_observed_at)),
    primary key (tenant_id,entity_id,version),
    unique (tenant_id,command_id),
    check (change_kind in ('BIND','END')),
    check ((version=1) = (change_kind in ('CREATE','BIND'))),
    check (snapshot_valid_to is null or snapshot_valid_from < snapshot_valid_to),
    check (snapshot_end_observed_at is null or (snapshot_valid_to is not null and snapshot_valid_to >= snapshot_end_observed_at)),
    check ((snapshot_position_id is null and snapshot_position_code_snapshot is null and snapshot_position_name_snapshot is null) or (snapshot_position_id is not null and snapshot_position_code_snapshot is not null and snapshot_position_name_snapshot is not null))
);

create table public.asset_foundation_receipt (
    tenant_id uuid not null,
    command_id uuid not null,
    actor_id uuid not null,
    account_id uuid not null,
    member_id uuid not null,
    action_code varchar(128) collate "C" not null,
    idempotency_key uuid not null,
    fingerprint_version integer not null,
    payload_sha256 char(64) collate "C" not null,
    result_resource_type varchar(64) collate "C" not null,
    result_version bigint not null,
    result_occurred_at timestamptz(6) not null,
    audit_event_id uuid not null,
    created_at timestamptz(6) not null,
    result_workshop_id uuid,
    result_production_line_id uuid,
    result_process_stage_id uuid,
    result_equipment_id uuid,
    result_work_unit_id uuid,
    result_hazard_source_id uuid,
    result_line_workshop_id uuid,
    result_stage_equipment_id uuid,
    result_responsibility_binding_id uuid,
    change_kind varchar(16) collate "C" not null,
    trace_id uuid not null,
    check (result_version between 1 and 9007199254740991),
    check (result_occurred_at is null or public.g014_instant_valid(result_occurred_at)),
    check (created_at is null or public.g014_instant_valid(created_at)),
    primary key (tenant_id,command_id),
    unique (tenant_id,actor_id,action_code,idempotency_key),
    check (fingerprint_version=1),
    check (payload_sha256 collate "C" ~ '^[0-9a-f]{64}$'),
    check (created_at=result_occurred_at),
    check (num_nonnulls(result_workshop_id,result_production_line_id,result_process_stage_id,result_equipment_id,result_work_unit_id,result_hazard_source_id,result_line_workshop_id,result_stage_equipment_id,result_responsibility_binding_id)=1),
    check (
        (action_code='workshop.create' and result_resource_type='workshop' and result_workshop_id is not null and change_kind='CREATE')
        or (action_code='workshop.update' and result_resource_type='workshop' and result_workshop_id is not null and change_kind='UPDATE')
        or (action_code='workshop.archive' and result_resource_type='workshop' and result_workshop_id is not null and change_kind='ARCHIVE')
        or (action_code='production_line.create' and result_resource_type='production_line' and result_production_line_id is not null and change_kind='CREATE')
        or (action_code='production_line.update' and result_resource_type='production_line' and result_production_line_id is not null and change_kind='UPDATE')
        or (action_code='production_line.archive' and result_resource_type='production_line' and result_production_line_id is not null and change_kind='ARCHIVE')
        or (action_code='process_stage.create' and result_resource_type='process_stage' and result_process_stage_id is not null and change_kind='CREATE')
        or (action_code='process_stage.update' and result_resource_type='process_stage' and result_process_stage_id is not null and change_kind='UPDATE')
        or (action_code='process_stage.archive' and result_resource_type='process_stage' and result_process_stage_id is not null and change_kind='ARCHIVE')
        or (action_code='equipment.create' and result_resource_type='equipment' and result_equipment_id is not null and change_kind='CREATE')
        or (action_code='equipment.update' and result_resource_type='equipment' and result_equipment_id is not null and change_kind='UPDATE')
        or (action_code='equipment.archive' and result_resource_type='equipment' and result_equipment_id is not null and change_kind='ARCHIVE')
        or (action_code='work_unit.create' and result_resource_type='work_unit' and result_work_unit_id is not null and change_kind='CREATE')
        or (action_code='work_unit.update' and result_resource_type='work_unit' and result_work_unit_id is not null and change_kind='UPDATE')
        or (action_code='work_unit.archive' and result_resource_type='work_unit' and result_work_unit_id is not null and change_kind='ARCHIVE')
        or (action_code='hazard_source.create' and result_resource_type='hazard_source' and result_hazard_source_id is not null and change_kind='CREATE')
        or (action_code='hazard_source.update' and result_resource_type='hazard_source' and result_hazard_source_id is not null and change_kind='UPDATE')
        or (action_code='line_workshop.bind' and result_resource_type='line_workshop' and result_line_workshop_id is not null and change_kind='BIND')
        or (action_code='line_workshop.end' and result_resource_type='line_workshop' and result_line_workshop_id is not null and change_kind='END')
        or (action_code='stage_equipment.bind' and result_resource_type='stage_equipment' and result_stage_equipment_id is not null and change_kind='BIND')
        or (action_code='stage_equipment.end' and result_resource_type='stage_equipment' and result_stage_equipment_id is not null and change_kind='END')
        or (action_code='responsibility_binding.bind' and result_resource_type='responsibility_binding' and result_responsibility_binding_id is not null and change_kind='BIND')
        or (action_code='responsibility_binding.end' and result_resource_type='responsibility_binding' and result_responsibility_binding_id is not null and change_kind='END')),
    check ((result_version=1) = (change_kind in ('CREATE','BIND')))
);

alter table public.workshop add foreign key (tenant_id) references public."tenant"(id) on delete restrict;

alter table public.workshop add foreign key (tenant_id,organization_id) references public."org_unit"(tenant_id,id) on delete restrict;

alter table public.workshop add foreign key (created_by) references public."principal"(id) on delete restrict;

alter table public.workshop add foreign key (updated_by) references public."principal"(id) on delete restrict;

alter table public.workshop add constraint g014_workshop_last_command_fk foreign key (tenant_id,last_command_id) references public."asset_foundation_receipt"(tenant_id,command_id) on delete restrict deferrable initially deferred;

alter table public.production_line add foreign key (tenant_id) references public."tenant"(id) on delete restrict;

alter table public.production_line add foreign key (tenant_id,organization_id) references public."org_unit"(tenant_id,id) on delete restrict;

alter table public.production_line add foreign key (created_by) references public."principal"(id) on delete restrict;

alter table public.production_line add foreign key (updated_by) references public."principal"(id) on delete restrict;

alter table public.production_line add constraint g014_production_line_last_command_fk foreign key (tenant_id,last_command_id) references public."asset_foundation_receipt"(tenant_id,command_id) on delete restrict deferrable initially deferred;

alter table public.production_line add foreign key (tenant_id,workshop_id) references public."workshop"(tenant_id,id) on delete restrict;

alter table public.process_stage add foreign key (tenant_id) references public."tenant"(id) on delete restrict;

alter table public.process_stage add foreign key (tenant_id,organization_id) references public."org_unit"(tenant_id,id) on delete restrict;

alter table public.process_stage add foreign key (created_by) references public."principal"(id) on delete restrict;

alter table public.process_stage add foreign key (updated_by) references public."principal"(id) on delete restrict;

alter table public.process_stage add constraint g014_process_stage_last_command_fk foreign key (tenant_id,last_command_id) references public."asset_foundation_receipt"(tenant_id,command_id) on delete restrict deferrable initially deferred;

alter table public.process_stage add foreign key (tenant_id,line_id) references public."production_line"(tenant_id,id) on delete restrict;

alter table public.equipment add foreign key (tenant_id) references public."tenant"(id) on delete restrict;

alter table public.equipment add foreign key (tenant_id,organization_id) references public."org_unit"(tenant_id,id) on delete restrict;

alter table public.equipment add foreign key (created_by) references public."principal"(id) on delete restrict;

alter table public.equipment add foreign key (updated_by) references public."principal"(id) on delete restrict;

alter table public.equipment add constraint g014_equipment_last_command_fk foreign key (tenant_id,last_command_id) references public."asset_foundation_receipt"(tenant_id,command_id) on delete restrict deferrable initially deferred;

alter table public.work_unit add foreign key (tenant_id) references public."tenant"(id) on delete restrict;

alter table public.work_unit add foreign key (tenant_id,organization_id) references public."org_unit"(tenant_id,id) on delete restrict;

alter table public.work_unit add foreign key (created_by) references public."principal"(id) on delete restrict;

alter table public.work_unit add foreign key (updated_by) references public."principal"(id) on delete restrict;

alter table public.work_unit add constraint g014_work_unit_last_command_fk foreign key (tenant_id,last_command_id) references public."asset_foundation_receipt"(tenant_id,command_id) on delete restrict deferrable initially deferred;

alter table public.work_unit add foreign key (tenant_id,workshop_id) references public."workshop"(tenant_id,id) on delete restrict;

alter table public.work_unit add foreign key (tenant_id,line_id) references public."production_line"(tenant_id,id) on delete restrict;

alter table public.work_unit add foreign key (tenant_id,stage_id) references public."process_stage"(tenant_id,id) on delete restrict;

alter table public.work_unit add foreign key (tenant_id,equipment_id) references public."equipment"(tenant_id,id) on delete restrict;

alter table public.hazard_source add foreign key (tenant_id) references public."tenant"(id) on delete restrict;

alter table public.hazard_source add foreign key (tenant_id,organization_id) references public."org_unit"(tenant_id,id) on delete restrict;

alter table public.hazard_source add foreign key (created_by) references public."principal"(id) on delete restrict;

alter table public.hazard_source add foreign key (updated_by) references public."principal"(id) on delete restrict;

alter table public.hazard_source add constraint g014_hazard_source_last_command_fk foreign key (tenant_id,last_command_id) references public."asset_foundation_receipt"(tenant_id,command_id) on delete restrict deferrable initially deferred;

alter table public.hazard_source add foreign key (tenant_id,work_unit_id) references public."work_unit"(tenant_id,id) on delete restrict;

alter table public.line_workshop add constraint g014_line_workshop_line_label_revision_fk foreign key (tenant_id,line_id,line_version_at_bind) references public.production_line_revision(tenant_id,entity_id,version) on delete restrict deferrable initially deferred;

alter table public.line_workshop add constraint g014_line_workshop_workshop_label_revision_fk foreign key (tenant_id,workshop_id,workshop_version_at_bind) references public.workshop_revision(tenant_id,entity_id,version) on delete restrict deferrable initially deferred;

alter table public.line_workshop add foreign key (tenant_id) references public."tenant"(id) on delete restrict;

alter table public.line_workshop add foreign key (tenant_id,organization_id) references public."org_unit"(tenant_id,id) on delete restrict;

alter table public.line_workshop add foreign key (created_by) references public."principal"(id) on delete restrict;

alter table public.line_workshop add foreign key (updated_by) references public."principal"(id) on delete restrict;

alter table public.line_workshop add constraint g014_line_workshop_last_command_fk foreign key (tenant_id,last_command_id) references public."asset_foundation_receipt"(tenant_id,command_id) on delete restrict deferrable initially deferred;

alter table public.line_workshop add foreign key (tenant_id,line_id) references public."production_line"(tenant_id,id) on delete restrict;

alter table public.line_workshop add foreign key (tenant_id,workshop_id) references public."workshop"(tenant_id,id) on delete restrict;

alter table public.stage_equipment add constraint g014_stage_equipment_stage_label_revision_fk foreign key (tenant_id,stage_id,stage_version_at_bind) references public.process_stage_revision(tenant_id,entity_id,version) on delete restrict deferrable initially deferred;

alter table public.stage_equipment add constraint g014_stage_equipment_equipment_label_revision_fk foreign key (tenant_id,equipment_id,equipment_version_at_bind) references public.equipment_revision(tenant_id,entity_id,version) on delete restrict deferrable initially deferred;

alter table public.stage_equipment add foreign key (tenant_id) references public."tenant"(id) on delete restrict;

alter table public.stage_equipment add foreign key (tenant_id,organization_id) references public."org_unit"(tenant_id,id) on delete restrict;

alter table public.stage_equipment add foreign key (created_by) references public."principal"(id) on delete restrict;

alter table public.stage_equipment add foreign key (updated_by) references public."principal"(id) on delete restrict;

alter table public.stage_equipment add constraint g014_stage_equipment_last_command_fk foreign key (tenant_id,last_command_id) references public."asset_foundation_receipt"(tenant_id,command_id) on delete restrict deferrable initially deferred;

alter table public.stage_equipment add foreign key (tenant_id,stage_id) references public."process_stage"(tenant_id,id) on delete restrict;

alter table public.stage_equipment add foreign key (tenant_id,equipment_id) references public."equipment"(tenant_id,id) on delete restrict;

alter table public.responsibility_binding add foreign key (tenant_id) references public."tenant"(id) on delete restrict;

alter table public.responsibility_binding add foreign key (tenant_id,organization_id) references public."org_unit"(tenant_id,id) on delete restrict;

alter table public.responsibility_binding add foreign key (created_by) references public."principal"(id) on delete restrict;

alter table public.responsibility_binding add foreign key (updated_by) references public."principal"(id) on delete restrict;

alter table public.responsibility_binding add constraint g014_responsibility_binding_last_command_fk foreign key (tenant_id,last_command_id) references public."asset_foundation_receipt"(tenant_id,command_id) on delete restrict deferrable initially deferred;

alter table public.responsibility_binding add foreign key (tenant_id,work_unit_id) references public."work_unit"(tenant_id,id) on delete restrict;

alter table public.responsibility_binding add foreign key (tenant_id,org_id) references public."org_unit"(tenant_id,id) on delete restrict;

alter table public.responsibility_binding add foreign key (tenant_id,position_id) references public."position"(tenant_id,id) on delete restrict;

alter table public.workshop_revision add foreign key (tenant_id,entity_id) references public."workshop"(tenant_id,id) on delete restrict;

alter table public.workshop_revision add constraint g014_workshop_revision_receipt_fk foreign key (tenant_id,command_id) references public."asset_foundation_receipt"(tenant_id,command_id) on delete restrict deferrable initially deferred;

alter table public.workshop_revision add foreign key (actor_id) references public."principal"(id) on delete restrict;

alter table public.workshop_revision add foreign key (tenant_id,snapshot_organization_id) references public."org_unit"(tenant_id,id) on delete restrict;

alter table public.workshop_revision add foreign key (snapshot_created_by) references public."principal"(id) on delete restrict;

alter table public.workshop_revision add foreign key (snapshot_updated_by) references public."principal"(id) on delete restrict;

alter table public.workshop_revision add foreign key (tenant_id) references public.tenant(id) on delete restrict;

alter table public.production_line_revision add foreign key (tenant_id,entity_id) references public."production_line"(tenant_id,id) on delete restrict;

alter table public.production_line_revision add constraint g014_production_line_revision_receipt_fk foreign key (tenant_id,command_id) references public."asset_foundation_receipt"(tenant_id,command_id) on delete restrict deferrable initially deferred;

alter table public.production_line_revision add foreign key (actor_id) references public."principal"(id) on delete restrict;

alter table public.production_line_revision add foreign key (tenant_id,snapshot_organization_id) references public."org_unit"(tenant_id,id) on delete restrict;

alter table public.production_line_revision add foreign key (snapshot_created_by) references public."principal"(id) on delete restrict;

alter table public.production_line_revision add foreign key (snapshot_updated_by) references public."principal"(id) on delete restrict;

alter table public.production_line_revision add foreign key (tenant_id,snapshot_workshop_id) references public."workshop"(tenant_id,id) on delete restrict;

alter table public.production_line_revision add foreign key (tenant_id) references public.tenant(id) on delete restrict;

alter table public.process_stage_revision add foreign key (tenant_id,entity_id) references public."process_stage"(tenant_id,id) on delete restrict;

alter table public.process_stage_revision add constraint g014_process_stage_revision_receipt_fk foreign key (tenant_id,command_id) references public."asset_foundation_receipt"(tenant_id,command_id) on delete restrict deferrable initially deferred;

alter table public.process_stage_revision add foreign key (actor_id) references public."principal"(id) on delete restrict;

alter table public.process_stage_revision add foreign key (tenant_id,snapshot_organization_id) references public."org_unit"(tenant_id,id) on delete restrict;

alter table public.process_stage_revision add foreign key (snapshot_created_by) references public."principal"(id) on delete restrict;

alter table public.process_stage_revision add foreign key (snapshot_updated_by) references public."principal"(id) on delete restrict;

alter table public.process_stage_revision add foreign key (tenant_id,snapshot_line_id) references public."production_line"(tenant_id,id) on delete restrict;

alter table public.process_stage_revision add foreign key (tenant_id) references public.tenant(id) on delete restrict;

alter table public.equipment_revision add foreign key (tenant_id,entity_id) references public."equipment"(tenant_id,id) on delete restrict;

alter table public.equipment_revision add constraint g014_equipment_revision_receipt_fk foreign key (tenant_id,command_id) references public."asset_foundation_receipt"(tenant_id,command_id) on delete restrict deferrable initially deferred;

alter table public.equipment_revision add foreign key (actor_id) references public."principal"(id) on delete restrict;

alter table public.equipment_revision add foreign key (tenant_id,snapshot_organization_id) references public."org_unit"(tenant_id,id) on delete restrict;

alter table public.equipment_revision add foreign key (snapshot_created_by) references public."principal"(id) on delete restrict;

alter table public.equipment_revision add foreign key (snapshot_updated_by) references public."principal"(id) on delete restrict;

alter table public.equipment_revision add foreign key (tenant_id) references public.tenant(id) on delete restrict;

alter table public.work_unit_revision add foreign key (tenant_id,entity_id) references public."work_unit"(tenant_id,id) on delete restrict;

alter table public.work_unit_revision add constraint g014_work_unit_revision_receipt_fk foreign key (tenant_id,command_id) references public."asset_foundation_receipt"(tenant_id,command_id) on delete restrict deferrable initially deferred;

alter table public.work_unit_revision add foreign key (actor_id) references public."principal"(id) on delete restrict;

alter table public.work_unit_revision add foreign key (tenant_id,snapshot_organization_id) references public."org_unit"(tenant_id,id) on delete restrict;

alter table public.work_unit_revision add foreign key (snapshot_created_by) references public."principal"(id) on delete restrict;

alter table public.work_unit_revision add foreign key (snapshot_updated_by) references public."principal"(id) on delete restrict;

alter table public.work_unit_revision add foreign key (tenant_id,snapshot_workshop_id) references public."workshop"(tenant_id,id) on delete restrict;

alter table public.work_unit_revision add foreign key (tenant_id,snapshot_line_id) references public."production_line"(tenant_id,id) on delete restrict;

alter table public.work_unit_revision add foreign key (tenant_id,snapshot_stage_id) references public."process_stage"(tenant_id,id) on delete restrict;

alter table public.work_unit_revision add foreign key (tenant_id,snapshot_equipment_id) references public."equipment"(tenant_id,id) on delete restrict;

alter table public.work_unit_revision add foreign key (tenant_id) references public.tenant(id) on delete restrict;

alter table public.hazard_source_revision add foreign key (tenant_id,entity_id) references public."hazard_source"(tenant_id,id) on delete restrict;

alter table public.hazard_source_revision add constraint g014_hazard_source_revision_receipt_fk foreign key (tenant_id,command_id) references public."asset_foundation_receipt"(tenant_id,command_id) on delete restrict deferrable initially deferred;

alter table public.hazard_source_revision add foreign key (actor_id) references public."principal"(id) on delete restrict;

alter table public.hazard_source_revision add foreign key (tenant_id,snapshot_organization_id) references public."org_unit"(tenant_id,id) on delete restrict;

alter table public.hazard_source_revision add foreign key (snapshot_created_by) references public."principal"(id) on delete restrict;

alter table public.hazard_source_revision add foreign key (snapshot_updated_by) references public."principal"(id) on delete restrict;

alter table public.hazard_source_revision add foreign key (tenant_id,snapshot_work_unit_id) references public."work_unit"(tenant_id,id) on delete restrict;

alter table public.hazard_source_revision add foreign key (tenant_id) references public.tenant(id) on delete restrict;

alter table public.line_workshop_revision add foreign key (tenant_id,entity_id) references public."line_workshop"(tenant_id,id) on delete restrict;

alter table public.line_workshop_revision add constraint g014_line_workshop_revision_receipt_fk foreign key (tenant_id,command_id) references public."asset_foundation_receipt"(tenant_id,command_id) on delete restrict deferrable initially deferred;

alter table public.line_workshop_revision add foreign key (actor_id) references public."principal"(id) on delete restrict;

alter table public.line_workshop_revision add foreign key (tenant_id,snapshot_organization_id) references public."org_unit"(tenant_id,id) on delete restrict;

alter table public.line_workshop_revision add foreign key (snapshot_created_by) references public."principal"(id) on delete restrict;

alter table public.line_workshop_revision add foreign key (snapshot_updated_by) references public."principal"(id) on delete restrict;

alter table public.line_workshop_revision add foreign key (tenant_id,snapshot_line_id) references public."production_line"(tenant_id,id) on delete restrict;

alter table public.line_workshop_revision add foreign key (tenant_id,snapshot_workshop_id) references public."workshop"(tenant_id,id) on delete restrict;

alter table public.line_workshop_revision add foreign key (tenant_id) references public.tenant(id) on delete restrict;

alter table public.stage_equipment_revision add foreign key (tenant_id,entity_id) references public."stage_equipment"(tenant_id,id) on delete restrict;

alter table public.stage_equipment_revision add constraint g014_stage_equipment_revision_receipt_fk foreign key (tenant_id,command_id) references public."asset_foundation_receipt"(tenant_id,command_id) on delete restrict deferrable initially deferred;

alter table public.stage_equipment_revision add foreign key (actor_id) references public."principal"(id) on delete restrict;

alter table public.stage_equipment_revision add foreign key (tenant_id,snapshot_organization_id) references public."org_unit"(tenant_id,id) on delete restrict;

alter table public.stage_equipment_revision add foreign key (snapshot_created_by) references public."principal"(id) on delete restrict;

alter table public.stage_equipment_revision add foreign key (snapshot_updated_by) references public."principal"(id) on delete restrict;

alter table public.stage_equipment_revision add foreign key (tenant_id,snapshot_stage_id) references public."process_stage"(tenant_id,id) on delete restrict;

alter table public.stage_equipment_revision add foreign key (tenant_id,snapshot_equipment_id) references public."equipment"(tenant_id,id) on delete restrict;

alter table public.stage_equipment_revision add foreign key (tenant_id) references public.tenant(id) on delete restrict;

alter table public.responsibility_binding_revision add foreign key (tenant_id,entity_id) references public."responsibility_binding"(tenant_id,id) on delete restrict;

alter table public.responsibility_binding_revision add constraint g014_responsibility_binding_revision_receipt_fk foreign key (tenant_id,command_id) references public."asset_foundation_receipt"(tenant_id,command_id) on delete restrict deferrable initially deferred;

alter table public.responsibility_binding_revision add foreign key (actor_id) references public."principal"(id) on delete restrict;

alter table public.responsibility_binding_revision add foreign key (tenant_id,snapshot_organization_id) references public."org_unit"(tenant_id,id) on delete restrict;

alter table public.responsibility_binding_revision add foreign key (snapshot_created_by) references public."principal"(id) on delete restrict;

alter table public.responsibility_binding_revision add foreign key (snapshot_updated_by) references public."principal"(id) on delete restrict;

alter table public.responsibility_binding_revision add foreign key (tenant_id,snapshot_work_unit_id) references public."work_unit"(tenant_id,id) on delete restrict;

alter table public.responsibility_binding_revision add foreign key (tenant_id,snapshot_org_id) references public."org_unit"(tenant_id,id) on delete restrict;

alter table public.responsibility_binding_revision add foreign key (tenant_id,snapshot_position_id) references public."position"(tenant_id,id) on delete restrict;

alter table public.responsibility_binding_revision add foreign key (tenant_id) references public.tenant(id) on delete restrict;

alter table public.asset_foundation_receipt add foreign key (tenant_id) references public."tenant"(id) on delete restrict;

alter table public.asset_foundation_receipt add foreign key (actor_id) references public."principal"(id) on delete restrict;

alter table public.asset_foundation_receipt add foreign key (account_id) references public."account"(id) on delete restrict;

alter table public.asset_foundation_receipt add foreign key (tenant_id,member_id) references public."tenant_member"(tenant_id,id) on delete restrict;

alter table public.asset_foundation_receipt add foreign key (tenant_id,audit_event_id) references public."audit_event"(tenant_id,id) on delete restrict;

alter table public.asset_foundation_receipt add constraint g014_receipt_workshop_result_fk foreign key (tenant_id,result_workshop_id,result_version) references public."workshop_revision"(tenant_id,entity_id,version) on delete restrict deferrable initially deferred;

alter table public.asset_foundation_receipt add constraint g014_receipt_production_line_result_fk foreign key (tenant_id,result_production_line_id,result_version) references public."production_line_revision"(tenant_id,entity_id,version) on delete restrict deferrable initially deferred;

alter table public.asset_foundation_receipt add constraint g014_receipt_process_stage_result_fk foreign key (tenant_id,result_process_stage_id,result_version) references public."process_stage_revision"(tenant_id,entity_id,version) on delete restrict deferrable initially deferred;

alter table public.asset_foundation_receipt add constraint g014_receipt_equipment_result_fk foreign key (tenant_id,result_equipment_id,result_version) references public."equipment_revision"(tenant_id,entity_id,version) on delete restrict deferrable initially deferred;

alter table public.asset_foundation_receipt add constraint g014_receipt_work_unit_result_fk foreign key (tenant_id,result_work_unit_id,result_version) references public."work_unit_revision"(tenant_id,entity_id,version) on delete restrict deferrable initially deferred;

alter table public.asset_foundation_receipt add constraint g014_receipt_hazard_source_result_fk foreign key (tenant_id,result_hazard_source_id,result_version) references public."hazard_source_revision"(tenant_id,entity_id,version) on delete restrict deferrable initially deferred;

alter table public.asset_foundation_receipt add constraint g014_receipt_line_workshop_result_fk foreign key (tenant_id,result_line_workshop_id,result_version) references public."line_workshop_revision"(tenant_id,entity_id,version) on delete restrict deferrable initially deferred;

alter table public.asset_foundation_receipt add constraint g014_receipt_stage_equipment_result_fk foreign key (tenant_id,result_stage_equipment_id,result_version) references public."stage_equipment_revision"(tenant_id,entity_id,version) on delete restrict deferrable initially deferred;

alter table public.asset_foundation_receipt add constraint g014_receipt_responsibility_binding_result_fk foreign key (tenant_id,result_responsibility_binding_id,result_version) references public."responsibility_binding_revision"(tenant_id,entity_id,version) on delete restrict deferrable initially deferred;

alter table public.workshop enable row level security;
alter table public.workshop force row level security;
create policy g014_tenant_isolation on public.workshop
    using (tenant_id = nullif(current_setting('app.tenant_id',true),'')::uuid)
    with check (tenant_id = nullif(current_setting('app.tenant_id',true),'')::uuid);

alter table public.production_line enable row level security;
alter table public.production_line force row level security;
create policy g014_tenant_isolation on public.production_line
    using (tenant_id = nullif(current_setting('app.tenant_id',true),'')::uuid)
    with check (tenant_id = nullif(current_setting('app.tenant_id',true),'')::uuid);

alter table public.process_stage enable row level security;
alter table public.process_stage force row level security;
create policy g014_tenant_isolation on public.process_stage
    using (tenant_id = nullif(current_setting('app.tenant_id',true),'')::uuid)
    with check (tenant_id = nullif(current_setting('app.tenant_id',true),'')::uuid);

alter table public.equipment enable row level security;
alter table public.equipment force row level security;
create policy g014_tenant_isolation on public.equipment
    using (tenant_id = nullif(current_setting('app.tenant_id',true),'')::uuid)
    with check (tenant_id = nullif(current_setting('app.tenant_id',true),'')::uuid);

alter table public.work_unit enable row level security;
alter table public.work_unit force row level security;
create policy g014_tenant_isolation on public.work_unit
    using (tenant_id = nullif(current_setting('app.tenant_id',true),'')::uuid)
    with check (tenant_id = nullif(current_setting('app.tenant_id',true),'')::uuid);

alter table public.hazard_source enable row level security;
alter table public.hazard_source force row level security;
create policy g014_tenant_isolation on public.hazard_source
    using (tenant_id = nullif(current_setting('app.tenant_id',true),'')::uuid)
    with check (tenant_id = nullif(current_setting('app.tenant_id',true),'')::uuid);

alter table public.line_workshop enable row level security;
alter table public.line_workshop force row level security;
create policy g014_tenant_isolation on public.line_workshop
    using (tenant_id = nullif(current_setting('app.tenant_id',true),'')::uuid)
    with check (tenant_id = nullif(current_setting('app.tenant_id',true),'')::uuid);

alter table public.stage_equipment enable row level security;
alter table public.stage_equipment force row level security;
create policy g014_tenant_isolation on public.stage_equipment
    using (tenant_id = nullif(current_setting('app.tenant_id',true),'')::uuid)
    with check (tenant_id = nullif(current_setting('app.tenant_id',true),'')::uuid);

alter table public.responsibility_binding enable row level security;
alter table public.responsibility_binding force row level security;
create policy g014_tenant_isolation on public.responsibility_binding
    using (tenant_id = nullif(current_setting('app.tenant_id',true),'')::uuid)
    with check (tenant_id = nullif(current_setting('app.tenant_id',true),'')::uuid);

alter table public.workshop_revision enable row level security;
alter table public.workshop_revision force row level security;
create policy g014_tenant_isolation on public.workshop_revision
    using (tenant_id = nullif(current_setting('app.tenant_id',true),'')::uuid)
    with check (tenant_id = nullif(current_setting('app.tenant_id',true),'')::uuid);

alter table public.production_line_revision enable row level security;
alter table public.production_line_revision force row level security;
create policy g014_tenant_isolation on public.production_line_revision
    using (tenant_id = nullif(current_setting('app.tenant_id',true),'')::uuid)
    with check (tenant_id = nullif(current_setting('app.tenant_id',true),'')::uuid);

alter table public.process_stage_revision enable row level security;
alter table public.process_stage_revision force row level security;
create policy g014_tenant_isolation on public.process_stage_revision
    using (tenant_id = nullif(current_setting('app.tenant_id',true),'')::uuid)
    with check (tenant_id = nullif(current_setting('app.tenant_id',true),'')::uuid);

alter table public.equipment_revision enable row level security;
alter table public.equipment_revision force row level security;
create policy g014_tenant_isolation on public.equipment_revision
    using (tenant_id = nullif(current_setting('app.tenant_id',true),'')::uuid)
    with check (tenant_id = nullif(current_setting('app.tenant_id',true),'')::uuid);

alter table public.work_unit_revision enable row level security;
alter table public.work_unit_revision force row level security;
create policy g014_tenant_isolation on public.work_unit_revision
    using (tenant_id = nullif(current_setting('app.tenant_id',true),'')::uuid)
    with check (tenant_id = nullif(current_setting('app.tenant_id',true),'')::uuid);

alter table public.hazard_source_revision enable row level security;
alter table public.hazard_source_revision force row level security;
create policy g014_tenant_isolation on public.hazard_source_revision
    using (tenant_id = nullif(current_setting('app.tenant_id',true),'')::uuid)
    with check (tenant_id = nullif(current_setting('app.tenant_id',true),'')::uuid);

alter table public.line_workshop_revision enable row level security;
alter table public.line_workshop_revision force row level security;
create policy g014_tenant_isolation on public.line_workshop_revision
    using (tenant_id = nullif(current_setting('app.tenant_id',true),'')::uuid)
    with check (tenant_id = nullif(current_setting('app.tenant_id',true),'')::uuid);

alter table public.stage_equipment_revision enable row level security;
alter table public.stage_equipment_revision force row level security;
create policy g014_tenant_isolation on public.stage_equipment_revision
    using (tenant_id = nullif(current_setting('app.tenant_id',true),'')::uuid)
    with check (tenant_id = nullif(current_setting('app.tenant_id',true),'')::uuid);

alter table public.responsibility_binding_revision enable row level security;
alter table public.responsibility_binding_revision force row level security;
create policy g014_tenant_isolation on public.responsibility_binding_revision
    using (tenant_id = nullif(current_setting('app.tenant_id',true),'')::uuid)
    with check (tenant_id = nullif(current_setting('app.tenant_id',true),'')::uuid);

alter table public.asset_foundation_receipt enable row level security;
alter table public.asset_foundation_receipt force row level security;
create policy g014_tenant_isolation on public.asset_foundation_receipt
    using (tenant_id = nullif(current_setting('app.tenant_id',true),'')::uuid)
    with check (tenant_id = nullif(current_setting('app.tenant_id',true),'')::uuid);

create index production_line_workshop_id_g014_idx on public.production_line(tenant_id,workshop_id,lifecycle);

create index process_stage_line_id_g014_idx on public.process_stage(tenant_id,line_id,lifecycle);

create index work_unit_workshop_id_g014_idx on public.work_unit(tenant_id,workshop_id,lifecycle);

create index work_unit_line_id_g014_idx on public.work_unit(tenant_id,line_id,lifecycle);

create index work_unit_stage_id_g014_idx on public.work_unit(tenant_id,stage_id,lifecycle);

create index work_unit_equipment_id_g014_idx on public.work_unit(tenant_id,equipment_id,lifecycle);

create index hazard_source_work_unit_id_g014_idx on public.hazard_source(tenant_id,work_unit_id);

create index line_workshop_line_id_g014_idx on public.line_workshop(tenant_id,line_id,workshop_id,valid_from);

create index line_workshop_workshop_id_g014_idx on public.line_workshop(tenant_id,workshop_id,valid_to);

create index stage_equipment_stage_id_g014_idx on public.stage_equipment(tenant_id,stage_id,equipment_id,valid_from);

create index stage_equipment_equipment_id_g014_idx on public.stage_equipment(tenant_id,equipment_id,valid_to);

create index responsibility_binding_work_unit_id_g014_idx on public.responsibility_binding(tenant_id,work_unit_id,duty,org_id,position_id,valid_from);

-- Finite kind registry: line 10, workshop 20, stage 30, equipment 40, WorkUnit 50.
-- A real +1 write fences empty-set predicates even under RR; a stale writer aborts.
-- The pure-fence root branch checks ALL nontechnical columns and returns without recursion.
create function public.g014_fence_parent(parent_kind integer, tenant uuid, parent_id uuid, require_active boolean)
returns uuid language plpgsql volatile security invoker set search_path = pg_catalog, public
as $$
declare anchor uuid; parent_state text; affected bigint;
begin
    if tenant is null or parent_id is null or require_active is null
        or tenant is distinct from nullif(current_setting('app.tenant_id',true),'')::uuid then
        raise exception 'invalid parent fence' using errcode='23514';
    end if;
    case parent_kind

when 10 then
        update public.production_line set interval_mutex_revision=interval_mutex_revision+1
            where tenant_id=tenant and id=parent_id returning organization_id,lifecycle into anchor,parent_state;

when 20 then
        update public.workshop set interval_mutex_revision=interval_mutex_revision+1
            where tenant_id=tenant and id=parent_id returning organization_id,lifecycle into anchor,parent_state;

when 30 then
        update public.process_stage set interval_mutex_revision=interval_mutex_revision+1
            where tenant_id=tenant and id=parent_id returning organization_id,lifecycle into anchor,parent_state;

when 40 then
        update public.equipment set interval_mutex_revision=interval_mutex_revision+1
            where tenant_id=tenant and id=parent_id returning organization_id,lifecycle into anchor,parent_state;

when 50 then
        update public.work_unit set interval_mutex_revision=interval_mutex_revision+1
            where tenant_id=tenant and id=parent_id returning organization_id,lifecycle into anchor,parent_state;

else raise exception 'unregistered parent kind' using errcode='23514';
    end case;
    get diagnostics affected = row_count;
    if affected <> 1 or anchor is null or (require_active and parent_state is distinct from 'ACTIVE') then
        raise exception 'parent absent or inactive' using errcode='23514';
    end if;
    return anchor;
end $$;

create function public.g014_reject_mutation() returns trigger
language plpgsql security invoker set search_path = pg_catalog, public
as $$ begin raise exception 'G014 retained rows are immutable' using errcode='23514'; end $$;

create function public.g014_workshop_before_write() returns trigger
language plpgsql volatile security invoker set search_path = pg_catalog, public
as $$
declare anchor uuid; endpoint record;
begin
    if new.tenant_id is null or new.tenant_id is distinct from nullif(current_setting('app.tenant_id',true),'')::uuid then
        raise exception 'tenant context mismatch' using errcode='23514';
    end if;

if tg_op='UPDATE' then
        if (to_jsonb(new)-'interval_mutex_revision') = (to_jsonb(old)-'interval_mutex_revision') then
            if new.interval_mutex_revision is distinct from old.interval_mutex_revision+1 then
                raise exception 'technical fence must advance exactly one' using errcode='23514';
            end if;
            return new;
        end if;
        if new.interval_mutex_revision is distinct from old.interval_mutex_revision then
            raise exception 'business update cannot supply technical revision' using errcode='23514';
        end if;
        new.interval_mutex_revision := old.interval_mutex_revision+1;
    else

perform public.g014_require_natural_key('workshop',new.tenant_id,new.code,null);
        if new.interval_mutex_revision <> 0 then raise exception 'initial fence must be zero' using errcode='23514'; end if;
    end if;

perform public.g014_require_mutation_keys('workshop',new.tenant_id,new.id,new.version,new.last_command_id);

if tg_op='UPDATE' then
        if (to_jsonb(new)-array['name','boundary_description','lifecycle','version','updated_at','updated_by','last_command_id','interval_mutex_revision']::text[]) is distinct from (to_jsonb(old)-array['name','boundary_description','lifecycle','version','updated_at','updated_by','last_command_id','interval_mutex_revision']::text[]) then
            raise exception 'immutable identity anchor parent type or endpoint' using errcode='23514';
        end if;
        if new.version is distinct from old.version+1 or old.version>=9007199254740991
            or new.last_command_id is not distinct from old.last_command_id or new.updated_at<old.updated_at then
            raise exception 'invalid business revision' using errcode='23514';
        end if;

if old.lifecycle is distinct from 'ACTIVE' then raise exception 'archived root is terminal' using errcode='23514'; end if;
        if new.lifecycle='ARCHIVED' and
            (to_jsonb(new)-array['lifecycle','version','updated_at','updated_by','last_command_id','interval_mutex_revision']::text[])
            is distinct from (to_jsonb(old)-array['lifecycle','version','updated_at','updated_by','last_command_id','interval_mutex_revision']::text[]) then
            raise exception 'archive changes only lifecycle and revision metadata' using errcode='23514';
        end if;

else
        if new.version <> 1 or new.created_at is distinct from new.updated_at or new.created_by is distinct from new.updated_by then
            raise exception 'invalid initial business revision' using errcode='23514';
        end if;

if new.lifecycle is distinct from 'ACTIVE' then raise exception 'root must start active' using errcode='23514'; end if;

if not exists (select 1 from public.org_unit where tenant_id=new.tenant_id and id=new.organization_id and active) then raise exception 'creation anchor inactive' using errcode='23514'; end if;

end if;

return new;
end $$;
create trigger g014_workshop_before_write before insert or update on public.workshop
    for each row execute function public.g014_workshop_before_write();
create trigger g014_workshop_no_delete before delete on public.workshop
    for each row execute function public.g014_reject_mutation();

create function public.g014_workshop_archive_check() returns trigger
language plpgsql volatile security invoker set search_path = pg_catalog, public
as $$ begin
    if new.lifecycle='ARCHIVED' and old.lifecycle='ACTIVE' and (exists (select 1 from public.production_line where tenant_id=new.tenant_id and workshop_id=new.id and lifecycle='ACTIVE')
        or exists (select 1 from public.line_workshop where tenant_id=new.tenant_id and workshop_id=new.id and (valid_to is null or valid_to>clock_timestamp()))
        or exists (select 1 from public.work_unit where tenant_id=new.tenant_id and workshop_id=new.id and lifecycle='ACTIVE')) then
        raise exception 'archive has active or unexpired dependents' using errcode='23514';
    end if;
    return null;
end $$;
create trigger g014_workshop_archive_check after update on public.workshop
    for each row execute function public.g014_workshop_archive_check();

create function public.g014_production_line_before_write() returns trigger
language plpgsql volatile security invoker set search_path = pg_catalog, public
as $$
declare anchor uuid; endpoint record;
begin
    if new.tenant_id is null or new.tenant_id is distinct from nullif(current_setting('app.tenant_id',true),'')::uuid then
        raise exception 'tenant context mismatch' using errcode='23514';
    end if;

if tg_op='UPDATE' then
        if (to_jsonb(new)-'interval_mutex_revision') = (to_jsonb(old)-'interval_mutex_revision') then
            if new.interval_mutex_revision is distinct from old.interval_mutex_revision+1 then
                raise exception 'technical fence must advance exactly one' using errcode='23514';
            end if;
            return new;
        end if;
        if new.interval_mutex_revision is distinct from old.interval_mutex_revision then
            raise exception 'business update cannot supply technical revision' using errcode='23514';
        end if;
        new.interval_mutex_revision := old.interval_mutex_revision+1;
    else

perform public.g014_require_natural_key('production_line',new.tenant_id,new.code,null);
        if new.interval_mutex_revision <> 0 then raise exception 'initial fence must be zero' using errcode='23514'; end if;
    end if;

perform public.g014_require_mutation_keys('production_line',new.tenant_id,new.id,new.version,new.last_command_id);

if tg_op='UPDATE' then
        if (to_jsonb(new)-array['name','description','lifecycle','version','updated_at','updated_by','last_command_id','interval_mutex_revision']::text[]) is distinct from (to_jsonb(old)-array['name','description','lifecycle','version','updated_at','updated_by','last_command_id','interval_mutex_revision']::text[]) then
            raise exception 'immutable identity anchor parent type or endpoint' using errcode='23514';
        end if;
        if new.version is distinct from old.version+1 or old.version>=9007199254740991
            or new.last_command_id is not distinct from old.last_command_id or new.updated_at<old.updated_at then
            raise exception 'invalid business revision' using errcode='23514';
        end if;

if old.lifecycle is distinct from 'ACTIVE' then raise exception 'archived root is terminal' using errcode='23514'; end if;
        if new.lifecycle='ARCHIVED' and
            (to_jsonb(new)-array['lifecycle','version','updated_at','updated_by','last_command_id','interval_mutex_revision']::text[])
            is distinct from (to_jsonb(old)-array['lifecycle','version','updated_at','updated_by','last_command_id','interval_mutex_revision']::text[]) then
            raise exception 'archive changes only lifecycle and revision metadata' using errcode='23514';
        end if;

else
        if new.version <> 1 or new.created_at is distinct from new.updated_at or new.created_by is distinct from new.updated_by then
            raise exception 'invalid initial business revision' using errcode='23514';
        end if;

if new.lifecycle is distinct from 'ACTIVE' then raise exception 'root must start active' using errcode='23514'; end if;

if not exists (select 1 from public.org_unit where tenant_id=new.tenant_id and id=new.organization_id and active) then raise exception 'creation anchor inactive' using errcode='23514'; end if;

perform public.g014_fence_parent(20,new.tenant_id,new.workshop_id,true);

end if;

return new;
end $$;
create trigger g014_production_line_before_write before insert or update on public.production_line
    for each row execute function public.g014_production_line_before_write();
create trigger g014_production_line_no_delete before delete on public.production_line
    for each row execute function public.g014_reject_mutation();

create function public.g014_production_line_archive_check() returns trigger
language plpgsql volatile security invoker set search_path = pg_catalog, public
as $$ begin
    if new.lifecycle='ARCHIVED' and old.lifecycle='ACTIVE' and (exists (select 1 from public.process_stage where tenant_id=new.tenant_id and line_id=new.id and lifecycle='ACTIVE')
        or exists (select 1 from public.line_workshop where tenant_id=new.tenant_id and line_id=new.id and (valid_to is null or valid_to>clock_timestamp()))
        or exists (select 1 from public.work_unit where tenant_id=new.tenant_id and line_id=new.id and lifecycle='ACTIVE')) then
        raise exception 'archive has active or unexpired dependents' using errcode='23514';
    end if;
    return null;
end $$;
create trigger g014_production_line_archive_check after update on public.production_line
    for each row execute function public.g014_production_line_archive_check();

create function public.g014_process_stage_before_write() returns trigger
language plpgsql volatile security invoker set search_path = pg_catalog, public
as $$
declare anchor uuid; endpoint record;
begin
    if new.tenant_id is null or new.tenant_id is distinct from nullif(current_setting('app.tenant_id',true),'')::uuid then
        raise exception 'tenant context mismatch' using errcode='23514';
    end if;

if tg_op='UPDATE' then
        if (to_jsonb(new)-'interval_mutex_revision') = (to_jsonb(old)-'interval_mutex_revision') then
            if new.interval_mutex_revision is distinct from old.interval_mutex_revision+1 then
                raise exception 'technical fence must advance exactly one' using errcode='23514';
            end if;
            return new;
        end if;
        if new.interval_mutex_revision is distinct from old.interval_mutex_revision then
            raise exception 'business update cannot supply technical revision' using errcode='23514';
        end if;
        new.interval_mutex_revision := old.interval_mutex_revision+1;
    else

perform public.g014_require_natural_key('process_stage',new.tenant_id,new.code,new.line_id);
        if new.interval_mutex_revision <> 0 then raise exception 'initial fence must be zero' using errcode='23514'; end if;
    end if;

perform public.g014_require_mutation_keys('process_stage',new.tenant_id,new.id,new.version,new.last_command_id);

if tg_op='UPDATE' then
        if (to_jsonb(new)-array['name','ordinal','description','lifecycle','version','updated_at','updated_by','last_command_id','interval_mutex_revision']::text[]) is distinct from (to_jsonb(old)-array['name','ordinal','description','lifecycle','version','updated_at','updated_by','last_command_id','interval_mutex_revision']::text[]) then
            raise exception 'immutable identity anchor parent type or endpoint' using errcode='23514';
        end if;
        if new.version is distinct from old.version+1 or old.version>=9007199254740991
            or new.last_command_id is not distinct from old.last_command_id or new.updated_at<old.updated_at then
            raise exception 'invalid business revision' using errcode='23514';
        end if;

if old.lifecycle is distinct from 'ACTIVE' then raise exception 'archived root is terminal' using errcode='23514'; end if;
        if new.lifecycle='ARCHIVED' and
            (to_jsonb(new)-array['lifecycle','version','updated_at','updated_by','last_command_id','interval_mutex_revision']::text[])
            is distinct from (to_jsonb(old)-array['lifecycle','version','updated_at','updated_by','last_command_id','interval_mutex_revision']::text[]) then
            raise exception 'archive changes only lifecycle and revision metadata' using errcode='23514';
        end if;

else
        if new.version <> 1 or new.created_at is distinct from new.updated_at or new.created_by is distinct from new.updated_by then
            raise exception 'invalid initial business revision' using errcode='23514';
        end if;

if new.lifecycle is distinct from 'ACTIVE' then raise exception 'root must start active' using errcode='23514'; end if;

if not exists (select 1 from public.org_unit where tenant_id=new.tenant_id and id=new.organization_id and active) then raise exception 'creation anchor inactive' using errcode='23514'; end if;

perform public.g014_fence_parent(10,new.tenant_id,new.line_id,true);

end if;

return new;
end $$;
create trigger g014_process_stage_before_write before insert or update on public.process_stage
    for each row execute function public.g014_process_stage_before_write();
create trigger g014_process_stage_no_delete before delete on public.process_stage
    for each row execute function public.g014_reject_mutation();

create function public.g014_process_stage_archive_check() returns trigger
language plpgsql volatile security invoker set search_path = pg_catalog, public
as $$ begin
    if new.lifecycle='ARCHIVED' and old.lifecycle='ACTIVE' and (exists (select 1 from public.stage_equipment where tenant_id=new.tenant_id and stage_id=new.id and (valid_to is null or valid_to>clock_timestamp()))
        or exists (select 1 from public.work_unit where tenant_id=new.tenant_id and stage_id=new.id and lifecycle='ACTIVE')) then
        raise exception 'archive has active or unexpired dependents' using errcode='23514';
    end if;
    return null;
end $$;
create trigger g014_process_stage_archive_check after update on public.process_stage
    for each row execute function public.g014_process_stage_archive_check();

create function public.g014_equipment_before_write() returns trigger
language plpgsql volatile security invoker set search_path = pg_catalog, public
as $$
declare anchor uuid; endpoint record;
begin
    if new.tenant_id is null or new.tenant_id is distinct from nullif(current_setting('app.tenant_id',true),'')::uuid then
        raise exception 'tenant context mismatch' using errcode='23514';
    end if;

if tg_op='UPDATE' then
        if (to_jsonb(new)-'interval_mutex_revision') = (to_jsonb(old)-'interval_mutex_revision') then
            if new.interval_mutex_revision is distinct from old.interval_mutex_revision+1 then
                raise exception 'technical fence must advance exactly one' using errcode='23514';
            end if;
            return new;
        end if;
        if new.interval_mutex_revision is distinct from old.interval_mutex_revision then
            raise exception 'business update cannot supply technical revision' using errcode='23514';
        end if;
        new.interval_mutex_revision := old.interval_mutex_revision+1;
    else

perform public.g014_require_natural_key('equipment',new.tenant_id,new.code,null);
        if new.interval_mutex_revision <> 0 then raise exception 'initial fence must be zero' using errcode='23514'; end if;
    end if;

perform public.g014_require_mutation_keys('equipment',new.tenant_id,new.id,new.version,new.last_command_id);

if tg_op='UPDATE' then
        if (to_jsonb(new)-array['name','type_description','location_description','operational_state','lifecycle','version','updated_at','updated_by','last_command_id','interval_mutex_revision']::text[]) is distinct from (to_jsonb(old)-array['name','type_description','location_description','operational_state','lifecycle','version','updated_at','updated_by','last_command_id','interval_mutex_revision']::text[]) then
            raise exception 'immutable identity anchor parent type or endpoint' using errcode='23514';
        end if;
        if new.version is distinct from old.version+1 or old.version>=9007199254740991
            or new.last_command_id is not distinct from old.last_command_id or new.updated_at<old.updated_at then
            raise exception 'invalid business revision' using errcode='23514';
        end if;

if old.lifecycle is distinct from 'ACTIVE' then raise exception 'archived root is terminal' using errcode='23514'; end if;
        if new.lifecycle='ARCHIVED' and
            (to_jsonb(new)-array['lifecycle','version','updated_at','updated_by','last_command_id','interval_mutex_revision']::text[])
            is distinct from (to_jsonb(old)-array['lifecycle','version','updated_at','updated_by','last_command_id','interval_mutex_revision']::text[]) then
            raise exception 'archive changes only lifecycle and revision metadata' using errcode='23514';
        end if;

else
        if new.version <> 1 or new.created_at is distinct from new.updated_at or new.created_by is distinct from new.updated_by then
            raise exception 'invalid initial business revision' using errcode='23514';
        end if;

if new.lifecycle is distinct from 'ACTIVE' then raise exception 'root must start active' using errcode='23514'; end if;

if not exists (select 1 from public.org_unit where tenant_id=new.tenant_id and id=new.organization_id and active) then raise exception 'creation anchor inactive' using errcode='23514'; end if;

end if;

return new;
end $$;
create trigger g014_equipment_before_write before insert or update on public.equipment
    for each row execute function public.g014_equipment_before_write();
create trigger g014_equipment_no_delete before delete on public.equipment
    for each row execute function public.g014_reject_mutation();

create function public.g014_equipment_archive_check() returns trigger
language plpgsql volatile security invoker set search_path = pg_catalog, public
as $$ begin
    if new.lifecycle='ARCHIVED' and old.lifecycle='ACTIVE' and (exists (select 1 from public.stage_equipment where tenant_id=new.tenant_id and equipment_id=new.id and (valid_to is null or valid_to>clock_timestamp()))
        or exists (select 1 from public.work_unit where tenant_id=new.tenant_id and equipment_id=new.id and lifecycle='ACTIVE')) then
        raise exception 'archive has active or unexpired dependents' using errcode='23514';
    end if;
    return null;
end $$;
create trigger g014_equipment_archive_check after update on public.equipment
    for each row execute function public.g014_equipment_archive_check();

create function public.g014_work_unit_before_write() returns trigger
language plpgsql volatile security invoker set search_path = pg_catalog, public
as $$
declare anchor uuid; endpoint record;
begin
    if new.tenant_id is null or new.tenant_id is distinct from nullif(current_setting('app.tenant_id',true),'')::uuid then
        raise exception 'tenant context mismatch' using errcode='23514';
    end if;

if tg_op='UPDATE' then
        if (to_jsonb(new)-'interval_mutex_revision') = (to_jsonb(old)-'interval_mutex_revision') then
            if new.interval_mutex_revision is distinct from old.interval_mutex_revision+1 then
                raise exception 'technical fence must advance exactly one' using errcode='23514';
            end if;
            return new;
        end if;
        if new.interval_mutex_revision is distinct from old.interval_mutex_revision then
            raise exception 'business update cannot supply technical revision' using errcode='23514';
        end if;
        new.interval_mutex_revision := old.interval_mutex_revision+1;
    else

perform public.g014_require_natural_key('work_unit',new.tenant_id,new.code,null);
        if new.interval_mutex_revision <> 0 then raise exception 'initial fence must be zero' using errcode='23514'; end if;
    end if;

perform public.g014_require_mutation_keys('work_unit',new.tenant_id,new.id,new.version,new.last_command_id);

if tg_op='UPDATE' then
        if (to_jsonb(new)-array['name','boundary_description','lifecycle','version','updated_at','updated_by','last_command_id','interval_mutex_revision']::text[]) is distinct from (to_jsonb(old)-array['name','boundary_description','lifecycle','version','updated_at','updated_by','last_command_id','interval_mutex_revision']::text[]) then
            raise exception 'immutable identity anchor parent type or endpoint' using errcode='23514';
        end if;
        if new.version is distinct from old.version+1 or old.version>=9007199254740991
            or new.last_command_id is not distinct from old.last_command_id or new.updated_at<old.updated_at then
            raise exception 'invalid business revision' using errcode='23514';
        end if;

if old.lifecycle is distinct from 'ACTIVE' then raise exception 'archived root is terminal' using errcode='23514'; end if;
        if new.lifecycle='ARCHIVED' and
            (to_jsonb(new)-array['lifecycle','version','updated_at','updated_by','last_command_id','interval_mutex_revision']::text[])
            is distinct from (to_jsonb(old)-array['lifecycle','version','updated_at','updated_by','last_command_id','interval_mutex_revision']::text[]) then
            raise exception 'archive changes only lifecycle and revision metadata' using errcode='23514';
        end if;

else
        if new.version <> 1 or new.created_at is distinct from new.updated_at or new.created_by is distinct from new.updated_by then
            raise exception 'invalid initial business revision' using errcode='23514';
        end if;

if new.lifecycle is distinct from 'ACTIVE' then raise exception 'root must start active' using errcode='23514'; end if;

if not exists (select 1 from public.org_unit where tenant_id=new.tenant_id and id=new.organization_id and active) then raise exception 'creation anchor inactive' using errcode='23514'; end if;

case new.kind

when 'WORKSHOP' then perform public.g014_fence_parent(20,new.tenant_id,new.workshop_id,true);

when 'LINE' then perform public.g014_fence_parent(10,new.tenant_id,new.line_id,true);

when 'STAGE' then perform public.g014_fence_parent(30,new.tenant_id,new.stage_id,true);

when 'EQUIPMENT' then perform public.g014_fence_parent(40,new.tenant_id,new.equipment_id,true);

when 'STANDALONE' then null; else raise exception 'invalid WorkUnit kind' using errcode='23514'; end case;

end if;

return new;
end $$;
create trigger g014_work_unit_before_write before insert or update on public.work_unit
    for each row execute function public.g014_work_unit_before_write();
create trigger g014_work_unit_no_delete before delete on public.work_unit
    for each row execute function public.g014_reject_mutation();

create function public.g014_work_unit_archive_check() returns trigger
language plpgsql volatile security invoker set search_path = pg_catalog, public
as $$ begin
    if new.lifecycle='ARCHIVED' and old.lifecycle='ACTIVE' and (exists (select 1 from public.hazard_source where tenant_id=new.tenant_id and work_unit_id=new.id)
        or exists (select 1 from public.responsibility_binding where tenant_id=new.tenant_id and work_unit_id=new.id and (valid_to is null or valid_to>clock_timestamp()))) then
        raise exception 'archive has active or unexpired dependents' using errcode='23514';
    end if;
    return null;
end $$;
create trigger g014_work_unit_archive_check after update on public.work_unit
    for each row execute function public.g014_work_unit_archive_check();

create function public.g014_hazard_source_before_write() returns trigger
language plpgsql volatile security invoker set search_path = pg_catalog, public
as $$
declare anchor uuid; endpoint record;
begin
    if new.tenant_id is null or new.tenant_id is distinct from nullif(current_setting('app.tenant_id',true),'')::uuid then
        raise exception 'tenant context mismatch' using errcode='23514';
    end if;

if tg_op='UPDATE' then
        if (to_jsonb(new)-'interval_mutex_revision') = (to_jsonb(old)-'interval_mutex_revision') then
            if new.interval_mutex_revision is distinct from old.interval_mutex_revision+1 then
                raise exception 'technical fence must advance exactly one' using errcode='23514';
            end if;
            return new;
        end if;
        if new.interval_mutex_revision is distinct from old.interval_mutex_revision then
            raise exception 'business update cannot supply technical revision' using errcode='23514';
        end if;
        new.interval_mutex_revision := old.interval_mutex_revision+1;
    else

perform public.g014_require_natural_key('hazard_source',new.tenant_id,new.code,null);
        if new.interval_mutex_revision <> 0 then raise exception 'initial fence must be zero' using errcode='23514'; end if;
    end if;

perform public.g014_require_mutation_keys('hazard_source',new.tenant_id,new.id,new.version,new.last_command_id);

if tg_op='UPDATE' then
        if (to_jsonb(new)-array['description','identified_at','existence_state','version','updated_at','updated_by','last_command_id','interval_mutex_revision']::text[]) is distinct from (to_jsonb(old)-array['description','identified_at','existence_state','version','updated_at','updated_by','last_command_id','interval_mutex_revision']::text[]) then
            raise exception 'immutable identity anchor parent type or endpoint' using errcode='23514';
        end if;
        if new.version is distinct from old.version+1 or old.version>=9007199254740991
            or new.last_command_id is not distinct from old.last_command_id or new.updated_at<old.updated_at then
            raise exception 'invalid business revision' using errcode='23514';
        end if;

else
        if new.version <> 1 or new.created_at is distinct from new.updated_at or new.created_by is distinct from new.updated_by then
            raise exception 'invalid initial business revision' using errcode='23514';
        end if;

if not exists (select 1 from public.org_unit where tenant_id=new.tenant_id and id=new.organization_id and active) then raise exception 'creation anchor inactive' using errcode='23514'; end if;

perform public.g014_fence_parent(50,new.tenant_id,new.work_unit_id,true);

end if;

return new;
end $$;
create trigger g014_hazard_source_before_write before insert or update on public.hazard_source
    for each row execute function public.g014_hazard_source_before_write();
create trigger g014_hazard_source_no_delete before delete on public.hazard_source
    for each row execute function public.g014_reject_mutation();

create function public.g014_line_workshop_before_write() returns trigger
language plpgsql volatile security invoker set search_path = pg_catalog, public
as $$
declare anchor uuid; endpoint record;
begin
    if new.tenant_id is null or new.tenant_id is distinct from nullif(current_setting('app.tenant_id',true),'')::uuid then
        raise exception 'tenant context mismatch' using errcode='23514';
    end if;
    perform public.g014_require_mutation_keys('line_workshop',new.tenant_id,new.id,new.version,new.last_command_id);

    if tg_op='INSERT' and (new.line_version_at_bind is not null or new.workshop_version_at_bind is not null) then
        raise exception 'bind label anchors are database derived' using errcode='23514';
    end if;

-- Full old/new parent set, kind then UUID; even forbidden rebind/no-op attempts fence first.
    for endpoint in select distinct p.kind,p.id from (values (10,new.line_id),(10,case when tg_op='UPDATE' then old.line_id else null end),(20,new.workshop_id),(20,case when tg_op='UPDATE' then old.workshop_id else null end)) as p(kind,id)
        where p.id is not null order by p.kind,p.id loop
        anchor := public.g014_fence_parent(endpoint.kind,new.tenant_id,endpoint.id,tg_op='INSERT');
        if endpoint.kind=10 and endpoint.id=new.line_id and anchor is distinct from new.organization_id then
            raise exception 'relationship scope anchor mismatch' using errcode='23514';
        end if;
    end loop;

    -- Capture business versions only after the ENTIRE mandatory parent fence set.
    if tg_op='INSERT' then
        select version into strict new.line_version_at_bind from public.production_line
            where tenant_id=new.tenant_id and id=new.line_id;
        select version into strict new.workshop_version_at_bind from public.workshop
            where tenant_id=new.tenant_id and id=new.workshop_id;
    end if;

if tg_op='UPDATE' then
        if (to_jsonb(new)-array['valid_to','end_observed_at','version','updated_at','updated_by','last_command_id']::text[]) is distinct from (to_jsonb(old)-array['valid_to','end_observed_at','version','updated_at','updated_by','last_command_id']::text[]) then
            raise exception 'immutable identity anchor parent type or endpoint' using errcode='23514';
        end if;
        if new.version is distinct from old.version+1 or old.version>=9007199254740991
            or new.last_command_id is not distinct from old.last_command_id or new.updated_at<old.updated_at then
            raise exception 'invalid business revision' using errcode='23514';
        end if;

if new.valid_to is null or new.valid_to<=old.valid_from
            or (old.valid_to is not null and new.valid_to>=old.valid_to)
            or new.end_observed_at is distinct from new.updated_at or new.valid_to<new.end_observed_at then
            raise exception 'END must strictly shorten the interval' using errcode='23514';
        end if;

else
        if new.version <> 1 or new.created_at is distinct from new.updated_at or new.created_by is distinct from new.updated_by then
            raise exception 'invalid initial business revision' using errcode='23514';
        end if;

if new.end_observed_at is not null or (new.valid_to is not null and new.valid_to<=new.updated_at) then raise exception 'new binding already ended' using errcode='23514'; end if;

end if;

if exists (select 1 from public.production_line where tenant_id=new.tenant_id and id=new.line_id and workshop_id=new.workshop_id) then raise exception 'primary workshop duplicate' using errcode='23514'; end if;

-- Separate VOLATILE SQL command AFTER each actual parent UPDATE, never an application-only lock.
    if exists (select 1 from public.line_workshop x where x.tenant_id=new.tenant_id and x.id<>new.id and x.line_id=new.line_id and x.workshop_id=new.workshop_id
        and x.valid_from < coalesce(new.valid_to,'infinity'::timestamptz)
        and new.valid_from < coalesce(x.valid_to,'infinity'::timestamptz)) then
        raise exception 'overlapping effective relationship' using errcode='23P01';
    end if;

return new;
end $$;
create trigger g014_line_workshop_before_write before insert or update on public.line_workshop
    for each row execute function public.g014_line_workshop_before_write();
create trigger g014_line_workshop_no_delete before delete on public.line_workshop
    for each row execute function public.g014_reject_mutation();

create function public.g014_stage_equipment_before_write() returns trigger
language plpgsql volatile security invoker set search_path = pg_catalog, public
as $$
declare anchor uuid; endpoint record;
begin
    if new.tenant_id is null or new.tenant_id is distinct from nullif(current_setting('app.tenant_id',true),'')::uuid then
        raise exception 'tenant context mismatch' using errcode='23514';
    end if;
    perform public.g014_require_mutation_keys('stage_equipment',new.tenant_id,new.id,new.version,new.last_command_id);

    if tg_op='INSERT' and (new.stage_version_at_bind is not null or new.equipment_version_at_bind is not null) then
        raise exception 'bind label anchors are database derived' using errcode='23514';
    end if;

-- Full old/new parent set, kind then UUID; even forbidden rebind/no-op attempts fence first.
    for endpoint in select distinct p.kind,p.id from (values (30,new.stage_id),(30,case when tg_op='UPDATE' then old.stage_id else null end),(40,new.equipment_id),(40,case when tg_op='UPDATE' then old.equipment_id else null end)) as p(kind,id)
        where p.id is not null order by p.kind,p.id loop
        anchor := public.g014_fence_parent(endpoint.kind,new.tenant_id,endpoint.id,tg_op='INSERT');
        if endpoint.kind=30 and endpoint.id=new.stage_id and anchor is distinct from new.organization_id then
            raise exception 'relationship scope anchor mismatch' using errcode='23514';
        end if;
    end loop;

    -- Capture business versions only after the ENTIRE mandatory parent fence set.
    if tg_op='INSERT' then
        select version into strict new.stage_version_at_bind from public.process_stage
            where tenant_id=new.tenant_id and id=new.stage_id;
        select version into strict new.equipment_version_at_bind from public.equipment
            where tenant_id=new.tenant_id and id=new.equipment_id;
    end if;

if tg_op='UPDATE' then
        if (to_jsonb(new)-array['valid_to','end_observed_at','version','updated_at','updated_by','last_command_id']::text[]) is distinct from (to_jsonb(old)-array['valid_to','end_observed_at','version','updated_at','updated_by','last_command_id']::text[]) then
            raise exception 'immutable identity anchor parent type or endpoint' using errcode='23514';
        end if;
        if new.version is distinct from old.version+1 or old.version>=9007199254740991
            or new.last_command_id is not distinct from old.last_command_id or new.updated_at<old.updated_at then
            raise exception 'invalid business revision' using errcode='23514';
        end if;

if new.valid_to is null or new.valid_to<=old.valid_from
            or (old.valid_to is not null and new.valid_to>=old.valid_to)
            or new.end_observed_at is distinct from new.updated_at or new.valid_to<new.end_observed_at then
            raise exception 'END must strictly shorten the interval' using errcode='23514';
        end if;

else
        if new.version <> 1 or new.created_at is distinct from new.updated_at or new.created_by is distinct from new.updated_by then
            raise exception 'invalid initial business revision' using errcode='23514';
        end if;

if new.end_observed_at is not null or (new.valid_to is not null and new.valid_to<=new.updated_at) then raise exception 'new binding already ended' using errcode='23514'; end if;

end if;

-- Separate VOLATILE SQL command AFTER each actual parent UPDATE, never an application-only lock.
    if exists (select 1 from public.stage_equipment x where x.tenant_id=new.tenant_id and x.id<>new.id and x.stage_id=new.stage_id and x.equipment_id=new.equipment_id
        and x.valid_from < coalesce(new.valid_to,'infinity'::timestamptz)
        and new.valid_from < coalesce(x.valid_to,'infinity'::timestamptz)) then
        raise exception 'overlapping effective relationship' using errcode='23P01';
    end if;

return new;
end $$;
create trigger g014_stage_equipment_before_write before insert or update on public.stage_equipment
    for each row execute function public.g014_stage_equipment_before_write();
create trigger g014_stage_equipment_no_delete before delete on public.stage_equipment
    for each row execute function public.g014_reject_mutation();

create function public.g014_responsibility_binding_before_write() returns trigger
language plpgsql volatile security invoker set search_path = pg_catalog, public
as $$
declare anchor uuid; endpoint record;
begin
    if new.tenant_id is null or new.tenant_id is distinct from nullif(current_setting('app.tenant_id',true),'')::uuid then
        raise exception 'tenant context mismatch' using errcode='23514';
    end if;
    perform public.g014_require_mutation_keys('responsibility_binding',new.tenant_id,new.id,new.version,new.last_command_id);

-- Full old/new parent set, kind then UUID; even forbidden rebind/no-op attempts fence first.
    for endpoint in select distinct p.kind,p.id from (values (50,new.work_unit_id),(50,case when tg_op='UPDATE' then old.work_unit_id else null end)) as p(kind,id)
        where p.id is not null order by p.kind,p.id loop
        anchor := public.g014_fence_parent(endpoint.kind,new.tenant_id,endpoint.id,tg_op='INSERT');
        if endpoint.kind=50 and endpoint.id=new.work_unit_id and anchor is distinct from new.organization_id then
            raise exception 'relationship scope anchor mismatch' using errcode='23514';
        end if;
    end loop;

if tg_op='UPDATE' then
        if (to_jsonb(new)-array['valid_to','end_observed_at','version','updated_at','updated_by','last_command_id']::text[]) is distinct from (to_jsonb(old)-array['valid_to','end_observed_at','version','updated_at','updated_by','last_command_id']::text[]) then
            raise exception 'immutable identity anchor parent type or endpoint' using errcode='23514';
        end if;
        if new.version is distinct from old.version+1 or old.version>=9007199254740991
            or new.last_command_id is not distinct from old.last_command_id or new.updated_at<old.updated_at then
            raise exception 'invalid business revision' using errcode='23514';
        end if;

if new.valid_to is null or new.valid_to<=old.valid_from
            or (old.valid_to is not null and new.valid_to>=old.valid_to)
            or new.end_observed_at is distinct from new.updated_at or new.valid_to<new.end_observed_at then
            raise exception 'END must strictly shorten the interval' using errcode='23514';
        end if;

else
        if new.version <> 1 or new.created_at is distinct from new.updated_at or new.created_by is distinct from new.updated_by then
            raise exception 'invalid initial business revision' using errcode='23514';
        end if;

if new.end_observed_at is not null or (new.valid_to is not null and new.valid_to<=new.updated_at) then raise exception 'new binding already ended' using errcode='23514'; end if;

if new.directory_observed_at is distinct from new.updated_at or not exists (
            select 1 from public.org_unit o where o.tenant_id=new.tenant_id and o.id=new.org_id and o.active
                and o.code=new.org_code_snapshot and o.name=new.org_name_snapshot) then
            raise exception 'responsibility organization snapshot mismatch' using errcode='23514';
        end if;
        if new.position_id is not null and not exists (
            select 1 from public.position p where p.tenant_id=new.tenant_id and p.id=new.position_id and p.active
                and (p.org_id is null or p.org_id=new.org_id)
                and p.code=new.position_code_snapshot and p.name=new.position_name_snapshot) then
            raise exception 'responsibility position snapshot mismatch' using errcode='23514';
        end if;

end if;

-- Separate VOLATILE SQL command AFTER each actual parent UPDATE, never an application-only lock.
    if exists (select 1 from public.responsibility_binding x where x.tenant_id=new.tenant_id and x.id<>new.id and x.work_unit_id=new.work_unit_id and x.duty=new.duty and x.org_id=new.org_id and x.position_id is not distinct from new.position_id
        and x.valid_from < coalesce(new.valid_to,'infinity'::timestamptz)
        and new.valid_from < coalesce(x.valid_to,'infinity'::timestamptz)) then
        raise exception 'overlapping effective relationship' using errcode='23P01';
    end if;

return new;
end $$;
create trigger g014_responsibility_binding_before_write before insert or update on public.responsibility_binding
    for each row execute function public.g014_responsibility_binding_before_write();
create trigger g014_responsibility_binding_no_delete before delete on public.responsibility_binding
    for each row execute function public.g014_reject_mutation();

create function public.g014_workshop_revision_insert() returns trigger
language plpgsql volatile security invoker set search_path = pg_catalog, public
as $$ declare b public.workshop%rowtype;
begin
    perform public.g014_require_mutation_keys('workshop',new.tenant_id,new.entity_id,new.version,new.command_id);
    select * into b from public.workshop where tenant_id=new.tenant_id and id=new.entity_id;
    if not found or new.version is distinct from b.version or new.command_id is distinct from b.last_command_id
        or new.actor_id is distinct from b.updated_by or new.occurred_at is distinct from b.updated_at
        or new.change_kind is distinct from (case when b.version=1 then 'CREATE' when b.lifecycle='ARCHIVED' then 'ARCHIVE' else 'UPDATE' end) or row(new.snapshot_organization_id, new.snapshot_created_at, new.snapshot_updated_at, new.snapshot_created_by, new.snapshot_updated_by, new.snapshot_code, new.snapshot_name, new.snapshot_boundary_description, new.snapshot_lifecycle) is distinct from row(b.organization_id, b.created_at, b.updated_at, b.created_by, b.updated_by, b.code, b.name, b.boundary_description, b.lifecycle) then
        raise exception 'typed revision does not match current business mutation' using errcode='23514';
    end if;
    return new;
end $$;
create trigger g014_workshop_revision_insert before insert on public.workshop_revision
    for each row execute function public.g014_workshop_revision_insert();
create trigger g014_workshop_revision_append_only before update or delete on public.workshop_revision
    for each row execute function public.g014_reject_mutation();

create function public.g014_workshop_history_complete() returns trigger
language plpgsql volatile security invoker set search_path = pg_catalog, public
as $$ begin

if tg_op='UPDATE' and (to_jsonb(new)-'interval_mutex_revision')=(to_jsonb(old)-'interval_mutex_revision') then return null; end if;

if not exists (select 1 from public.workshop_revision r
        join public.asset_foundation_receipt c on c.tenant_id=r.tenant_id and c.command_id=r.command_id
        where r.tenant_id=new.tenant_id and r.entity_id=new.id and r.version=new.version
            and r.command_id=new.last_command_id and r.actor_id=new.updated_by and r.occurred_at=new.updated_at
            and row(r.snapshot_organization_id, r.snapshot_created_at, r.snapshot_updated_at, r.snapshot_created_by, r.snapshot_updated_by, r.snapshot_code, r.snapshot_name, r.snapshot_boundary_description, r.snapshot_lifecycle) is not distinct from row(new.organization_id, new.created_at, new.updated_at, new.created_by, new.updated_by, new.code, new.name, new.boundary_description, new.lifecycle)
            and c.result_resource_type='workshop' and c.result_workshop_id=new.id and c.result_version=new.version
            and c.actor_id=r.actor_id and c.result_occurred_at=r.occurred_at and c.change_kind=r.change_kind) then
        raise exception 'business history or complete receipt missing' using errcode='23514';
    end if;
    return null;
end $$;
create constraint trigger g014_workshop_history_complete after insert or update on public.workshop
    deferrable initially deferred for each row execute function public.g014_workshop_history_complete();

create function public.g014_production_line_revision_insert() returns trigger
language plpgsql volatile security invoker set search_path = pg_catalog, public
as $$ declare b public.production_line%rowtype;
begin
    perform public.g014_require_mutation_keys('production_line',new.tenant_id,new.entity_id,new.version,new.command_id);
    select * into b from public.production_line where tenant_id=new.tenant_id and id=new.entity_id;
    if not found or new.version is distinct from b.version or new.command_id is distinct from b.last_command_id
        or new.actor_id is distinct from b.updated_by or new.occurred_at is distinct from b.updated_at
        or new.change_kind is distinct from (case when b.version=1 then 'CREATE' when b.lifecycle='ARCHIVED' then 'ARCHIVE' else 'UPDATE' end) or row(new.snapshot_organization_id, new.snapshot_created_at, new.snapshot_updated_at, new.snapshot_created_by, new.snapshot_updated_by, new.snapshot_code, new.snapshot_name, new.snapshot_workshop_id, new.snapshot_description, new.snapshot_lifecycle) is distinct from row(b.organization_id, b.created_at, b.updated_at, b.created_by, b.updated_by, b.code, b.name, b.workshop_id, b.description, b.lifecycle) then
        raise exception 'typed revision does not match current business mutation' using errcode='23514';
    end if;
    return new;
end $$;
create trigger g014_production_line_revision_insert before insert on public.production_line_revision
    for each row execute function public.g014_production_line_revision_insert();
create trigger g014_production_line_revision_append_only before update or delete on public.production_line_revision
    for each row execute function public.g014_reject_mutation();

create function public.g014_production_line_history_complete() returns trigger
language plpgsql volatile security invoker set search_path = pg_catalog, public
as $$ begin

if tg_op='UPDATE' and (to_jsonb(new)-'interval_mutex_revision')=(to_jsonb(old)-'interval_mutex_revision') then return null; end if;

if not exists (select 1 from public.production_line_revision r
        join public.asset_foundation_receipt c on c.tenant_id=r.tenant_id and c.command_id=r.command_id
        where r.tenant_id=new.tenant_id and r.entity_id=new.id and r.version=new.version
            and r.command_id=new.last_command_id and r.actor_id=new.updated_by and r.occurred_at=new.updated_at
            and row(r.snapshot_organization_id, r.snapshot_created_at, r.snapshot_updated_at, r.snapshot_created_by, r.snapshot_updated_by, r.snapshot_code, r.snapshot_name, r.snapshot_workshop_id, r.snapshot_description, r.snapshot_lifecycle) is not distinct from row(new.organization_id, new.created_at, new.updated_at, new.created_by, new.updated_by, new.code, new.name, new.workshop_id, new.description, new.lifecycle)
            and c.result_resource_type='production_line' and c.result_production_line_id=new.id and c.result_version=new.version
            and c.actor_id=r.actor_id and c.result_occurred_at=r.occurred_at and c.change_kind=r.change_kind) then
        raise exception 'business history or complete receipt missing' using errcode='23514';
    end if;
    return null;
end $$;
create constraint trigger g014_production_line_history_complete after insert or update on public.production_line
    deferrable initially deferred for each row execute function public.g014_production_line_history_complete();

create function public.g014_process_stage_revision_insert() returns trigger
language plpgsql volatile security invoker set search_path = pg_catalog, public
as $$ declare b public.process_stage%rowtype;
begin
    perform public.g014_require_mutation_keys('process_stage',new.tenant_id,new.entity_id,new.version,new.command_id);
    select * into b from public.process_stage where tenant_id=new.tenant_id and id=new.entity_id;
    if not found or new.version is distinct from b.version or new.command_id is distinct from b.last_command_id
        or new.actor_id is distinct from b.updated_by or new.occurred_at is distinct from b.updated_at
        or new.change_kind is distinct from (case when b.version=1 then 'CREATE' when b.lifecycle='ARCHIVED' then 'ARCHIVE' else 'UPDATE' end) or row(new.snapshot_organization_id, new.snapshot_created_at, new.snapshot_updated_at, new.snapshot_created_by, new.snapshot_updated_by, new.snapshot_code, new.snapshot_name, new.snapshot_line_id, new.snapshot_ordinal, new.snapshot_description, new.snapshot_lifecycle) is distinct from row(b.organization_id, b.created_at, b.updated_at, b.created_by, b.updated_by, b.code, b.name, b.line_id, b.ordinal, b.description, b.lifecycle) then
        raise exception 'typed revision does not match current business mutation' using errcode='23514';
    end if;
    return new;
end $$;
create trigger g014_process_stage_revision_insert before insert on public.process_stage_revision
    for each row execute function public.g014_process_stage_revision_insert();
create trigger g014_process_stage_revision_append_only before update or delete on public.process_stage_revision
    for each row execute function public.g014_reject_mutation();

create function public.g014_process_stage_history_complete() returns trigger
language plpgsql volatile security invoker set search_path = pg_catalog, public
as $$ begin

if tg_op='UPDATE' and (to_jsonb(new)-'interval_mutex_revision')=(to_jsonb(old)-'interval_mutex_revision') then return null; end if;

if not exists (select 1 from public.process_stage_revision r
        join public.asset_foundation_receipt c on c.tenant_id=r.tenant_id and c.command_id=r.command_id
        where r.tenant_id=new.tenant_id and r.entity_id=new.id and r.version=new.version
            and r.command_id=new.last_command_id and r.actor_id=new.updated_by and r.occurred_at=new.updated_at
            and row(r.snapshot_organization_id, r.snapshot_created_at, r.snapshot_updated_at, r.snapshot_created_by, r.snapshot_updated_by, r.snapshot_code, r.snapshot_name, r.snapshot_line_id, r.snapshot_ordinal, r.snapshot_description, r.snapshot_lifecycle) is not distinct from row(new.organization_id, new.created_at, new.updated_at, new.created_by, new.updated_by, new.code, new.name, new.line_id, new.ordinal, new.description, new.lifecycle)
            and c.result_resource_type='process_stage' and c.result_process_stage_id=new.id and c.result_version=new.version
            and c.actor_id=r.actor_id and c.result_occurred_at=r.occurred_at and c.change_kind=r.change_kind) then
        raise exception 'business history or complete receipt missing' using errcode='23514';
    end if;
    return null;
end $$;
create constraint trigger g014_process_stage_history_complete after insert or update on public.process_stage
    deferrable initially deferred for each row execute function public.g014_process_stage_history_complete();

create function public.g014_equipment_revision_insert() returns trigger
language plpgsql volatile security invoker set search_path = pg_catalog, public
as $$ declare b public.equipment%rowtype;
begin
    perform public.g014_require_mutation_keys('equipment',new.tenant_id,new.entity_id,new.version,new.command_id);
    select * into b from public.equipment where tenant_id=new.tenant_id and id=new.entity_id;
    if not found or new.version is distinct from b.version or new.command_id is distinct from b.last_command_id
        or new.actor_id is distinct from b.updated_by or new.occurred_at is distinct from b.updated_at
        or new.change_kind is distinct from (case when b.version=1 then 'CREATE' when b.lifecycle='ARCHIVED' then 'ARCHIVE' else 'UPDATE' end) or row(new.snapshot_organization_id, new.snapshot_created_at, new.snapshot_updated_at, new.snapshot_created_by, new.snapshot_updated_by, new.snapshot_code, new.snapshot_name, new.snapshot_type_description, new.snapshot_location_description, new.snapshot_operational_state, new.snapshot_lifecycle) is distinct from row(b.organization_id, b.created_at, b.updated_at, b.created_by, b.updated_by, b.code, b.name, b.type_description, b.location_description, b.operational_state, b.lifecycle) then
        raise exception 'typed revision does not match current business mutation' using errcode='23514';
    end if;
    return new;
end $$;
create trigger g014_equipment_revision_insert before insert on public.equipment_revision
    for each row execute function public.g014_equipment_revision_insert();
create trigger g014_equipment_revision_append_only before update or delete on public.equipment_revision
    for each row execute function public.g014_reject_mutation();

create function public.g014_equipment_history_complete() returns trigger
language plpgsql volatile security invoker set search_path = pg_catalog, public
as $$ begin

if tg_op='UPDATE' and (to_jsonb(new)-'interval_mutex_revision')=(to_jsonb(old)-'interval_mutex_revision') then return null; end if;

if not exists (select 1 from public.equipment_revision r
        join public.asset_foundation_receipt c on c.tenant_id=r.tenant_id and c.command_id=r.command_id
        where r.tenant_id=new.tenant_id and r.entity_id=new.id and r.version=new.version
            and r.command_id=new.last_command_id and r.actor_id=new.updated_by and r.occurred_at=new.updated_at
            and row(r.snapshot_organization_id, r.snapshot_created_at, r.snapshot_updated_at, r.snapshot_created_by, r.snapshot_updated_by, r.snapshot_code, r.snapshot_name, r.snapshot_type_description, r.snapshot_location_description, r.snapshot_operational_state, r.snapshot_lifecycle) is not distinct from row(new.organization_id, new.created_at, new.updated_at, new.created_by, new.updated_by, new.code, new.name, new.type_description, new.location_description, new.operational_state, new.lifecycle)
            and c.result_resource_type='equipment' and c.result_equipment_id=new.id and c.result_version=new.version
            and c.actor_id=r.actor_id and c.result_occurred_at=r.occurred_at and c.change_kind=r.change_kind) then
        raise exception 'business history or complete receipt missing' using errcode='23514';
    end if;
    return null;
end $$;
create constraint trigger g014_equipment_history_complete after insert or update on public.equipment
    deferrable initially deferred for each row execute function public.g014_equipment_history_complete();

create function public.g014_work_unit_revision_insert() returns trigger
language plpgsql volatile security invoker set search_path = pg_catalog, public
as $$ declare b public.work_unit%rowtype;
begin
    perform public.g014_require_mutation_keys('work_unit',new.tenant_id,new.entity_id,new.version,new.command_id);
    select * into b from public.work_unit where tenant_id=new.tenant_id and id=new.entity_id;
    if not found or new.version is distinct from b.version or new.command_id is distinct from b.last_command_id
        or new.actor_id is distinct from b.updated_by or new.occurred_at is distinct from b.updated_at
        or new.change_kind is distinct from (case when b.version=1 then 'CREATE' when b.lifecycle='ARCHIVED' then 'ARCHIVE' else 'UPDATE' end) or row(new.snapshot_organization_id, new.snapshot_created_at, new.snapshot_updated_at, new.snapshot_created_by, new.snapshot_updated_by, new.snapshot_code, new.snapshot_name, new.snapshot_kind, new.snapshot_workshop_id, new.snapshot_line_id, new.snapshot_stage_id, new.snapshot_equipment_id, new.snapshot_boundary_description, new.snapshot_lifecycle) is distinct from row(b.organization_id, b.created_at, b.updated_at, b.created_by, b.updated_by, b.code, b.name, b.kind, b.workshop_id, b.line_id, b.stage_id, b.equipment_id, b.boundary_description, b.lifecycle) then
        raise exception 'typed revision does not match current business mutation' using errcode='23514';
    end if;
    return new;
end $$;
create trigger g014_work_unit_revision_insert before insert on public.work_unit_revision
    for each row execute function public.g014_work_unit_revision_insert();
create trigger g014_work_unit_revision_append_only before update or delete on public.work_unit_revision
    for each row execute function public.g014_reject_mutation();

create function public.g014_work_unit_history_complete() returns trigger
language plpgsql volatile security invoker set search_path = pg_catalog, public
as $$ begin

if tg_op='UPDATE' and (to_jsonb(new)-'interval_mutex_revision')=(to_jsonb(old)-'interval_mutex_revision') then return null; end if;

if not exists (select 1 from public.work_unit_revision r
        join public.asset_foundation_receipt c on c.tenant_id=r.tenant_id and c.command_id=r.command_id
        where r.tenant_id=new.tenant_id and r.entity_id=new.id and r.version=new.version
            and r.command_id=new.last_command_id and r.actor_id=new.updated_by and r.occurred_at=new.updated_at
            and row(r.snapshot_organization_id, r.snapshot_created_at, r.snapshot_updated_at, r.snapshot_created_by, r.snapshot_updated_by, r.snapshot_code, r.snapshot_name, r.snapshot_kind, r.snapshot_workshop_id, r.snapshot_line_id, r.snapshot_stage_id, r.snapshot_equipment_id, r.snapshot_boundary_description, r.snapshot_lifecycle) is not distinct from row(new.organization_id, new.created_at, new.updated_at, new.created_by, new.updated_by, new.code, new.name, new.kind, new.workshop_id, new.line_id, new.stage_id, new.equipment_id, new.boundary_description, new.lifecycle)
            and c.result_resource_type='work_unit' and c.result_work_unit_id=new.id and c.result_version=new.version
            and c.actor_id=r.actor_id and c.result_occurred_at=r.occurred_at and c.change_kind=r.change_kind) then
        raise exception 'business history or complete receipt missing' using errcode='23514';
    end if;
    return null;
end $$;
create constraint trigger g014_work_unit_history_complete after insert or update on public.work_unit
    deferrable initially deferred for each row execute function public.g014_work_unit_history_complete();

create function public.g014_hazard_source_revision_insert() returns trigger
language plpgsql volatile security invoker set search_path = pg_catalog, public
as $$ declare b public.hazard_source%rowtype;
begin
    perform public.g014_require_mutation_keys('hazard_source',new.tenant_id,new.entity_id,new.version,new.command_id);
    select * into b from public.hazard_source where tenant_id=new.tenant_id and id=new.entity_id;
    if not found or new.version is distinct from b.version or new.command_id is distinct from b.last_command_id
        or new.actor_id is distinct from b.updated_by or new.occurred_at is distinct from b.updated_at
        or new.change_kind is distinct from (case when b.version=1 then 'CREATE'  else 'UPDATE' end) or row(new.snapshot_organization_id, new.snapshot_created_at, new.snapshot_updated_at, new.snapshot_created_by, new.snapshot_updated_by, new.snapshot_code, new.snapshot_work_unit_id, new.snapshot_description, new.snapshot_identified_at, new.snapshot_existence_state, new.snapshot_factor_code_ref, new.snapshot_factor_version) is distinct from row(b.organization_id, b.created_at, b.updated_at, b.created_by, b.updated_by, b.code, b.work_unit_id, b.description, b.identified_at, b.existence_state, b.factor_code_ref, b.factor_version) then
        raise exception 'typed revision does not match current business mutation' using errcode='23514';
    end if;
    return new;
end $$;
create trigger g014_hazard_source_revision_insert before insert on public.hazard_source_revision
    for each row execute function public.g014_hazard_source_revision_insert();
create trigger g014_hazard_source_revision_append_only before update or delete on public.hazard_source_revision
    for each row execute function public.g014_reject_mutation();

create function public.g014_hazard_source_history_complete() returns trigger
language plpgsql volatile security invoker set search_path = pg_catalog, public
as $$ begin

if tg_op='UPDATE' and (to_jsonb(new)-'interval_mutex_revision')=(to_jsonb(old)-'interval_mutex_revision') then return null; end if;

if not exists (select 1 from public.hazard_source_revision r
        join public.asset_foundation_receipt c on c.tenant_id=r.tenant_id and c.command_id=r.command_id
        where r.tenant_id=new.tenant_id and r.entity_id=new.id and r.version=new.version
            and r.command_id=new.last_command_id and r.actor_id=new.updated_by and r.occurred_at=new.updated_at
            and row(r.snapshot_organization_id, r.snapshot_created_at, r.snapshot_updated_at, r.snapshot_created_by, r.snapshot_updated_by, r.snapshot_code, r.snapshot_work_unit_id, r.snapshot_description, r.snapshot_identified_at, r.snapshot_existence_state, r.snapshot_factor_code_ref, r.snapshot_factor_version) is not distinct from row(new.organization_id, new.created_at, new.updated_at, new.created_by, new.updated_by, new.code, new.work_unit_id, new.description, new.identified_at, new.existence_state, new.factor_code_ref, new.factor_version)
            and c.result_resource_type='hazard_source' and c.result_hazard_source_id=new.id and c.result_version=new.version
            and c.actor_id=r.actor_id and c.result_occurred_at=r.occurred_at and c.change_kind=r.change_kind) then
        raise exception 'business history or complete receipt missing' using errcode='23514';
    end if;
    return null;
end $$;
create constraint trigger g014_hazard_source_history_complete after insert or update on public.hazard_source
    deferrable initially deferred for each row execute function public.g014_hazard_source_history_complete();

create function public.g014_line_workshop_revision_insert() returns trigger
language plpgsql volatile security invoker set search_path = pg_catalog, public
as $$ declare b public.line_workshop%rowtype;
begin
    perform public.g014_require_mutation_keys('line_workshop',new.tenant_id,new.entity_id,new.version,new.command_id);
    select * into b from public.line_workshop where tenant_id=new.tenant_id and id=new.entity_id;
    if not found or new.version is distinct from b.version or new.command_id is distinct from b.last_command_id
        or new.actor_id is distinct from b.updated_by or new.occurred_at is distinct from b.updated_at
        or new.change_kind is distinct from (case when b.version=1 then 'BIND' else 'END' end) or row(new.snapshot_organization_id, new.snapshot_created_at, new.snapshot_updated_at, new.snapshot_created_by, new.snapshot_updated_by, new.snapshot_line_id, new.snapshot_line_version_at_bind, new.snapshot_workshop_id, new.snapshot_workshop_version_at_bind, new.snapshot_valid_from, new.snapshot_valid_to, new.snapshot_end_observed_at) is distinct from row(b.organization_id, b.created_at, b.updated_at, b.created_by, b.updated_by, b.line_id, b.line_version_at_bind, b.workshop_id, b.workshop_version_at_bind, b.valid_from, b.valid_to, b.end_observed_at) then
        raise exception 'typed revision does not match current business mutation' using errcode='23514';
    end if;
    return new;
end $$;
create trigger g014_line_workshop_revision_insert before insert on public.line_workshop_revision
    for each row execute function public.g014_line_workshop_revision_insert();
create trigger g014_line_workshop_revision_append_only before update or delete on public.line_workshop_revision
    for each row execute function public.g014_reject_mutation();

create function public.g014_line_workshop_history_complete() returns trigger
language plpgsql volatile security invoker set search_path = pg_catalog, public
as $$ begin

if not exists (select 1 from public.line_workshop_revision r
        join public.asset_foundation_receipt c on c.tenant_id=r.tenant_id and c.command_id=r.command_id
        where r.tenant_id=new.tenant_id and r.entity_id=new.id and r.version=new.version
            and r.command_id=new.last_command_id and r.actor_id=new.updated_by and r.occurred_at=new.updated_at
            and row(r.snapshot_organization_id, r.snapshot_created_at, r.snapshot_updated_at, r.snapshot_created_by, r.snapshot_updated_by, r.snapshot_line_id, r.snapshot_line_version_at_bind, r.snapshot_workshop_id, r.snapshot_workshop_version_at_bind, r.snapshot_valid_from, r.snapshot_valid_to, r.snapshot_end_observed_at) is not distinct from row(new.organization_id, new.created_at, new.updated_at, new.created_by, new.updated_by, new.line_id, new.line_version_at_bind, new.workshop_id, new.workshop_version_at_bind, new.valid_from, new.valid_to, new.end_observed_at)
            and c.result_resource_type='line_workshop' and c.result_line_workshop_id=new.id and c.result_version=new.version
            and c.actor_id=r.actor_id and c.result_occurred_at=r.occurred_at and c.change_kind=r.change_kind) then
        raise exception 'business history or complete receipt missing' using errcode='23514';
    end if;
    return null;
end $$;
create constraint trigger g014_line_workshop_history_complete after insert or update on public.line_workshop
    deferrable initially deferred for each row execute function public.g014_line_workshop_history_complete();

create function public.g014_stage_equipment_revision_insert() returns trigger
language plpgsql volatile security invoker set search_path = pg_catalog, public
as $$ declare b public.stage_equipment%rowtype;
begin
    perform public.g014_require_mutation_keys('stage_equipment',new.tenant_id,new.entity_id,new.version,new.command_id);
    select * into b from public.stage_equipment where tenant_id=new.tenant_id and id=new.entity_id;
    if not found or new.version is distinct from b.version or new.command_id is distinct from b.last_command_id
        or new.actor_id is distinct from b.updated_by or new.occurred_at is distinct from b.updated_at
        or new.change_kind is distinct from (case when b.version=1 then 'BIND' else 'END' end) or row(new.snapshot_organization_id, new.snapshot_created_at, new.snapshot_updated_at, new.snapshot_created_by, new.snapshot_updated_by, new.snapshot_stage_id, new.snapshot_stage_version_at_bind, new.snapshot_equipment_id, new.snapshot_equipment_version_at_bind, new.snapshot_valid_from, new.snapshot_valid_to, new.snapshot_end_observed_at) is distinct from row(b.organization_id, b.created_at, b.updated_at, b.created_by, b.updated_by, b.stage_id, b.stage_version_at_bind, b.equipment_id, b.equipment_version_at_bind, b.valid_from, b.valid_to, b.end_observed_at) then
        raise exception 'typed revision does not match current business mutation' using errcode='23514';
    end if;
    return new;
end $$;
create trigger g014_stage_equipment_revision_insert before insert on public.stage_equipment_revision
    for each row execute function public.g014_stage_equipment_revision_insert();
create trigger g014_stage_equipment_revision_append_only before update or delete on public.stage_equipment_revision
    for each row execute function public.g014_reject_mutation();

create function public.g014_stage_equipment_history_complete() returns trigger
language plpgsql volatile security invoker set search_path = pg_catalog, public
as $$ begin

if not exists (select 1 from public.stage_equipment_revision r
        join public.asset_foundation_receipt c on c.tenant_id=r.tenant_id and c.command_id=r.command_id
        where r.tenant_id=new.tenant_id and r.entity_id=new.id and r.version=new.version
            and r.command_id=new.last_command_id and r.actor_id=new.updated_by and r.occurred_at=new.updated_at
            and row(r.snapshot_organization_id, r.snapshot_created_at, r.snapshot_updated_at, r.snapshot_created_by, r.snapshot_updated_by, r.snapshot_stage_id, r.snapshot_stage_version_at_bind, r.snapshot_equipment_id, r.snapshot_equipment_version_at_bind, r.snapshot_valid_from, r.snapshot_valid_to, r.snapshot_end_observed_at) is not distinct from row(new.organization_id, new.created_at, new.updated_at, new.created_by, new.updated_by, new.stage_id, new.stage_version_at_bind, new.equipment_id, new.equipment_version_at_bind, new.valid_from, new.valid_to, new.end_observed_at)
            and c.result_resource_type='stage_equipment' and c.result_stage_equipment_id=new.id and c.result_version=new.version
            and c.actor_id=r.actor_id and c.result_occurred_at=r.occurred_at and c.change_kind=r.change_kind) then
        raise exception 'business history or complete receipt missing' using errcode='23514';
    end if;
    return null;
end $$;
create constraint trigger g014_stage_equipment_history_complete after insert or update on public.stage_equipment
    deferrable initially deferred for each row execute function public.g014_stage_equipment_history_complete();

create function public.g014_responsibility_binding_revision_insert() returns trigger
language plpgsql volatile security invoker set search_path = pg_catalog, public
as $$ declare b public.responsibility_binding%rowtype;
begin
    perform public.g014_require_mutation_keys('responsibility_binding',new.tenant_id,new.entity_id,new.version,new.command_id);
    select * into b from public.responsibility_binding where tenant_id=new.tenant_id and id=new.entity_id;
    if not found or new.version is distinct from b.version or new.command_id is distinct from b.last_command_id
        or new.actor_id is distinct from b.updated_by or new.occurred_at is distinct from b.updated_at
        or new.change_kind is distinct from (case when b.version=1 then 'BIND' else 'END' end) or row(new.snapshot_organization_id, new.snapshot_created_at, new.snapshot_updated_at, new.snapshot_created_by, new.snapshot_updated_by, new.snapshot_work_unit_id, new.snapshot_org_id, new.snapshot_position_id, new.snapshot_duty, new.snapshot_valid_from, new.snapshot_valid_to, new.snapshot_end_observed_at, new.snapshot_org_code_snapshot, new.snapshot_org_name_snapshot, new.snapshot_position_code_snapshot, new.snapshot_position_name_snapshot, new.snapshot_directory_observed_at) is distinct from row(b.organization_id, b.created_at, b.updated_at, b.created_by, b.updated_by, b.work_unit_id, b.org_id, b.position_id, b.duty, b.valid_from, b.valid_to, b.end_observed_at, b.org_code_snapshot, b.org_name_snapshot, b.position_code_snapshot, b.position_name_snapshot, b.directory_observed_at) then
        raise exception 'typed revision does not match current business mutation' using errcode='23514';
    end if;
    return new;
end $$;
create trigger g014_responsibility_binding_revision_insert before insert on public.responsibility_binding_revision
    for each row execute function public.g014_responsibility_binding_revision_insert();
create trigger g014_responsibility_binding_revision_append_only before update or delete on public.responsibility_binding_revision
    for each row execute function public.g014_reject_mutation();

create function public.g014_responsibility_binding_history_complete() returns trigger
language plpgsql volatile security invoker set search_path = pg_catalog, public
as $$ begin

if not exists (select 1 from public.responsibility_binding_revision r
        join public.asset_foundation_receipt c on c.tenant_id=r.tenant_id and c.command_id=r.command_id
        where r.tenant_id=new.tenant_id and r.entity_id=new.id and r.version=new.version
            and r.command_id=new.last_command_id and r.actor_id=new.updated_by and r.occurred_at=new.updated_at
            and row(r.snapshot_organization_id, r.snapshot_created_at, r.snapshot_updated_at, r.snapshot_created_by, r.snapshot_updated_by, r.snapshot_work_unit_id, r.snapshot_org_id, r.snapshot_position_id, r.snapshot_duty, r.snapshot_valid_from, r.snapshot_valid_to, r.snapshot_end_observed_at, r.snapshot_org_code_snapshot, r.snapshot_org_name_snapshot, r.snapshot_position_code_snapshot, r.snapshot_position_name_snapshot, r.snapshot_directory_observed_at) is not distinct from row(new.organization_id, new.created_at, new.updated_at, new.created_by, new.updated_by, new.work_unit_id, new.org_id, new.position_id, new.duty, new.valid_from, new.valid_to, new.end_observed_at, new.org_code_snapshot, new.org_name_snapshot, new.position_code_snapshot, new.position_name_snapshot, new.directory_observed_at)
            and c.result_resource_type='responsibility_binding' and c.result_responsibility_binding_id=new.id and c.result_version=new.version
            and c.actor_id=r.actor_id and c.result_occurred_at=r.occurred_at and c.change_kind=r.change_kind) then
        raise exception 'business history or complete receipt missing' using errcode='23514';
    end if;
    return null;
end $$;
create constraint trigger g014_responsibility_binding_history_complete after insert or update on public.responsibility_binding
    deferrable initially deferred for each row execute function public.g014_responsibility_binding_history_complete();

create function public.g014_receipt_insert() returns trigger
language plpgsql volatile security invoker set search_path = pg_catalog, public
as $$ declare matches boolean; result_id uuid;
begin
    perform public.g014_require_receipt_key(new.tenant_id,new.actor_id,new.action_code,new.idempotency_key);
    result_id := case new.result_resource_type
        when 'workshop' then new.result_workshop_id
        when 'production_line' then new.result_production_line_id
        when 'process_stage' then new.result_process_stage_id
        when 'equipment' then new.result_equipment_id
        when 'work_unit' then new.result_work_unit_id
        when 'hazard_source' then new.result_hazard_source_id
        when 'line_workshop' then new.result_line_workshop_id
        when 'stage_equipment' then new.result_stage_equipment_id
        when 'responsibility_binding' then new.result_responsibility_binding_id
        else null end;
    -- BEFORE triggers run before CHECK constraints; do not derive a generic coalesced target.
    if new.result_resource_type is null or result_id is null or num_nonnulls(new.result_workshop_id,new.result_production_line_id,new.result_process_stage_id,new.result_equipment_id,new.result_work_unit_id,new.result_hazard_source_id,new.result_line_workshop_id,new.result_stage_equipment_id,new.result_responsibility_binding_id)<>1 then
        raise exception 'invalid receipt target shape' using errcode='23514';
    end if;
    perform public.g014_require_mutation_keys(new.result_resource_type,new.tenant_id,result_id,new.result_version,new.command_id);
    case new.result_resource_type

when 'workshop' then
        select exists (select 1 from public.workshop_revision r where r.tenant_id=new.tenant_id
            and r.entity_id=new.result_workshop_id and r.version=new.result_version and r.command_id=new.command_id
            and r.actor_id=new.actor_id and r.occurred_at=new.result_occurred_at and r.change_kind=new.change_kind) into matches;

when 'production_line' then
        select exists (select 1 from public.production_line_revision r where r.tenant_id=new.tenant_id
            and r.entity_id=new.result_production_line_id and r.version=new.result_version and r.command_id=new.command_id
            and r.actor_id=new.actor_id and r.occurred_at=new.result_occurred_at and r.change_kind=new.change_kind) into matches;

when 'process_stage' then
        select exists (select 1 from public.process_stage_revision r where r.tenant_id=new.tenant_id
            and r.entity_id=new.result_process_stage_id and r.version=new.result_version and r.command_id=new.command_id
            and r.actor_id=new.actor_id and r.occurred_at=new.result_occurred_at and r.change_kind=new.change_kind) into matches;

when 'equipment' then
        select exists (select 1 from public.equipment_revision r where r.tenant_id=new.tenant_id
            and r.entity_id=new.result_equipment_id and r.version=new.result_version and r.command_id=new.command_id
            and r.actor_id=new.actor_id and r.occurred_at=new.result_occurred_at and r.change_kind=new.change_kind) into matches;

when 'work_unit' then
        select exists (select 1 from public.work_unit_revision r where r.tenant_id=new.tenant_id
            and r.entity_id=new.result_work_unit_id and r.version=new.result_version and r.command_id=new.command_id
            and r.actor_id=new.actor_id and r.occurred_at=new.result_occurred_at and r.change_kind=new.change_kind) into matches;

when 'hazard_source' then
        select exists (select 1 from public.hazard_source_revision r where r.tenant_id=new.tenant_id
            and r.entity_id=new.result_hazard_source_id and r.version=new.result_version and r.command_id=new.command_id
            and r.actor_id=new.actor_id and r.occurred_at=new.result_occurred_at and r.change_kind=new.change_kind) into matches;

when 'line_workshop' then
        select exists (select 1 from public.line_workshop_revision r where r.tenant_id=new.tenant_id
            and r.entity_id=new.result_line_workshop_id and r.version=new.result_version and r.command_id=new.command_id
            and r.actor_id=new.actor_id and r.occurred_at=new.result_occurred_at and r.change_kind=new.change_kind) into matches;

when 'stage_equipment' then
        select exists (select 1 from public.stage_equipment_revision r where r.tenant_id=new.tenant_id
            and r.entity_id=new.result_stage_equipment_id and r.version=new.result_version and r.command_id=new.command_id
            and r.actor_id=new.actor_id and r.occurred_at=new.result_occurred_at and r.change_kind=new.change_kind) into matches;

when 'responsibility_binding' then
        select exists (select 1 from public.responsibility_binding_revision r where r.tenant_id=new.tenant_id
            and r.entity_id=new.result_responsibility_binding_id and r.version=new.result_version and r.command_id=new.command_id
            and r.actor_id=new.actor_id and r.occurred_at=new.result_occurred_at and r.change_kind=new.change_kind) into matches;

else raise exception 'unregistered receipt resource' using errcode='23514';
    end case;
    if matches is distinct from true then
        raise exception 'receipt must match exact staged typed revision' using errcode='23514';
    end if;
    -- Audit FK below proves existence ONLY. Exact successful audit binding is enforced by
    -- the audit owner's opaque same-connection append receipt; no audit SELECT privilege.
    return new;
end $$;
create trigger g014_receipt_insert before insert on public.asset_foundation_receipt
    for each row execute function public.g014_receipt_insert();
create trigger g014_receipt_append_only before update or delete on public.asset_foundation_receipt
    for each row execute function public.g014_reject_mutation();

-- Capability catalog only. This does not assign any capability to a role/member.
do $$
declare expected record; actual record;
begin
    for expected in select * from (values
        ('4fac3659-4bcd-571a-a5e0-2548e275175f','workshop.read','workshop','read'),
        ('e37ed5fb-5248-54d3-9dfe-c605f00d2890','workshop.create','workshop','create'),
        ('acb01595-cebe-5329-9b15-857f95db7da4','workshop.update','workshop','update'),
        ('ec04e0e3-04c3-51f4-8ce4-52d1b5acc9fb','workshop.archive','workshop','archive'),
        ('f388bea9-91bb-51f0-8804-b566e2d7e82f','production_line.read','production_line','read'),
        ('b4f61e69-1781-53f6-a5b6-239d8728cf92','production_line.create','production_line','create'),
        ('e705e2c5-c828-56a3-9403-850142dfcd49','production_line.update','production_line','update'),
        ('c2cf3cf2-dc94-5f60-b647-ab1fdda2d537','production_line.archive','production_line','archive'),
        ('f21b620d-4dfc-5237-924d-2c22dda6b7a4','process_stage.read','process_stage','read'),
        ('b0d1adf1-821d-5f35-8ce8-f715e7ee9727','process_stage.create','process_stage','create'),
        ('65435009-de6f-5651-9679-073613e4dd99','process_stage.update','process_stage','update'),
        ('798b6948-b3b6-5969-b44f-ead0b05b2838','process_stage.archive','process_stage','archive'),
        ('72455795-41d9-58cb-86fc-652686c35612','equipment.read','equipment','read'),
        ('35100e98-4b56-503f-874e-7ac06566b0d6','equipment.create','equipment','create'),
        ('2602c0c1-e24a-56b5-a723-cf95d6f1f5ac','equipment.update','equipment','update'),
        ('eb5d320f-f2fa-542c-9521-c9258e582898','equipment.archive','equipment','archive'),
        ('8e1b746f-f649-534a-8aea-2e54c17ef96a','work_unit.read','work_unit','read'),
        ('969c12dd-0de5-57d1-a838-ef9732da086f','work_unit.create','work_unit','create'),
        ('019216a2-592c-5c36-8d57-053d5d9fc231','work_unit.update','work_unit','update'),
        ('1acc3193-be21-5f14-9ef4-04ab2e13cf3a','work_unit.archive','work_unit','archive'),
        ('ab689e70-455e-5a5a-8c7c-54d66b1a9611','hazard_source.read','hazard_source','read'),
        ('540c3465-d28f-5b19-8bae-685503c89413','hazard_source.create','hazard_source','create'),
        ('c7d5450b-72d0-5269-aae4-271b216bbac4','hazard_source.update','hazard_source','update'),
        ('89b68d86-947b-5628-9605-67e17c0befe1','line_workshop.bind','line_workshop','bind'),
        ('da029de8-7bea-59b9-be57-cb1da69001d3','line_workshop.end','line_workshop','end'),
        ('3d42fc27-3eea-5380-9315-8de8295fa1b2','stage_equipment.bind','stage_equipment','bind'),
        ('438c9560-dd81-52b0-8680-b828710d2605','stage_equipment.end','stage_equipment','end'),
        ('54058b17-7b09-56a2-85b6-19da70a850bb','responsibility_binding.bind','responsibility_binding','bind'),
        ('ccaa78c2-9f61-50fc-bcc5-ae8491df49ac','responsibility_binding.end','responsibility_binding','end')) as catalog(id,code,resource_type,action) loop
        select resource_type,action into actual from public.permission where code=expected.code;
        if found then
            if actual.resource_type is distinct from expected.resource_type or actual.action is distinct from expected.action then
                raise exception 'existing permission triple mismatch: %',expected.code using errcode='23514';
            end if;
        else
            insert into public.permission(id,code,resource_type,action)
                values(expected.id::uuid,expected.code,expected.resource_type,expected.action);
        end if;
    end loop;
end $$;

revoke execute on function public.g014_instant_valid(timestamptz) from public;

revoke execute on function public.g014_text_valid(text,integer,boolean) from public;

revoke execute on function public.g014_lock_natural_key(text,uuid,text,uuid) from public;

revoke execute on function public.g014_fence_parent(integer,uuid,uuid,boolean) from public;

revoke execute on function public.g014_reject_mutation() from public;

revoke execute on function public.g014_receipt_insert() from public;

revoke execute on function public.g014_workshop_before_write() from public;

revoke execute on function public.g014_workshop_revision_insert() from public;

revoke execute on function public.g014_workshop_history_complete() from public;

revoke execute on function public.g014_production_line_before_write() from public;

revoke execute on function public.g014_production_line_revision_insert() from public;

revoke execute on function public.g014_production_line_history_complete() from public;

revoke execute on function public.g014_process_stage_before_write() from public;

revoke execute on function public.g014_process_stage_revision_insert() from public;

revoke execute on function public.g014_process_stage_history_complete() from public;

revoke execute on function public.g014_equipment_before_write() from public;

revoke execute on function public.g014_equipment_revision_insert() from public;

revoke execute on function public.g014_equipment_history_complete() from public;

revoke execute on function public.g014_work_unit_before_write() from public;

revoke execute on function public.g014_work_unit_revision_insert() from public;

revoke execute on function public.g014_work_unit_history_complete() from public;

revoke execute on function public.g014_hazard_source_before_write() from public;

revoke execute on function public.g014_hazard_source_revision_insert() from public;

revoke execute on function public.g014_hazard_source_history_complete() from public;

revoke execute on function public.g014_line_workshop_before_write() from public;

revoke execute on function public.g014_line_workshop_revision_insert() from public;

revoke execute on function public.g014_line_workshop_history_complete() from public;

revoke execute on function public.g014_stage_equipment_before_write() from public;

revoke execute on function public.g014_stage_equipment_revision_insert() from public;

revoke execute on function public.g014_stage_equipment_history_complete() from public;

revoke execute on function public.g014_responsibility_binding_before_write() from public;

revoke execute on function public.g014_responsibility_binding_revision_insert() from public;

revoke execute on function public.g014_responsibility_binding_history_complete() from public;

revoke execute on function public.g014_workshop_archive_check() from public;

revoke execute on function public.g014_production_line_archive_check() from public;

revoke execute on function public.g014_process_stage_archive_check() from public;

revoke execute on function public.g014_equipment_archive_check() from public;

revoke execute on function public.g014_work_unit_archive_check() from public;

-- Revoke only this migration's new helper signatures; runtime EXECUTE is a separate reviewed proposal.
revoke execute on function public.g014_natural_key_bytes(text,uuid,text,uuid) from public;
revoke execute on function public.g014_receipt_key_bytes(uuid,uuid,text,uuid) from public;
revoke execute on function public.g014_mutation_key_plan(text,uuid,uuid,bigint,uuid) from public;
revoke execute on function public.g014_require_natural_key(text,uuid,text,uuid) from public;
revoke execute on function public.g014_lock_receipt_key(uuid,uuid,text,uuid) from public;
revoke execute on function public.g014_require_receipt_key(uuid,uuid,text,uuid) from public;
revoke execute on function public.g014_lock_mutation_keys(text,uuid,uuid,bigint,uuid) from public;
revoke execute on function public.g014_require_mutation_keys(text,uuid,uuid,bigint,uuid) from public;
