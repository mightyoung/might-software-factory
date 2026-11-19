-- D1 standard ledger history and the first independent tool catalog. No tenant/app/role grants are seeded.
create table public.ledger_record_revision (
    tenant_id uuid not null references public.tenant(id) on delete restrict,
    app_key varchar(64) not null check(app_key ~ '^[a-z][a-z0-9_]{0,63}$'),
    entity_key varchar(64) not null check(entity_key ~ '^[a-z][a-z0-9_]{0,63}$'),
    record_id uuid not null,
    version bigint not null check(version>=1),
    command_id uuid not null,
    event_id uuid not null,
    definition_version varchar(64) not null check(length(definition_version)>0),
    action varchar(16) not null check(action in ('CREATE','UPDATE')),
    audit_event_id uuid not null,
    actor_id uuid not null references public.principal(id) on delete restrict,
    account_id uuid not null references public.account(id) on delete restrict,
    member_id uuid not null,
    occurred_at timestamptz not null,
    snapshot jsonb not null check(jsonb_typeof(snapshot)='object'),
    change_fact jsonb not null check(jsonb_typeof(change_fact)='object'),
    primary key(tenant_id,app_key,entity_key,record_id,version),
    unique(tenant_id,command_id),
    unique(tenant_id,event_id),
    unique(tenant_id,app_key,entity_key,record_id,version,command_id,actor_id,account_id,member_id,action,definition_version),
    foreign key(tenant_id,audit_event_id) references public.audit_event(tenant_id,id) on delete restrict,
    foreign key(tenant_id,member_id) references public.tenant_member(tenant_id,id) on delete restrict
);
create table public.ledger_command_receipt (
    tenant_id uuid not null references public.tenant(id) on delete restrict,
    actor_id uuid not null references public.principal(id) on delete restrict,
    account_id uuid not null references public.account(id) on delete restrict,
    member_id uuid not null,
    app_key varchar(64) not null,
    entity_key varchar(64) not null,
    action varchar(16) not null check(action in ('CREATE','UPDATE')),
    idempotency_key uuid not null,
    command_id uuid not null,
    record_id uuid not null,
    result_version bigint not null check(result_version>=1),
    request_hash char(64) not null check(request_hash ~ '^[0-9a-f]{64}$'),
    definition_version varchar(64) not null,
    result jsonb not null check(jsonb_typeof(result)='object'),
    completed_at timestamptz not null,
    primary key(tenant_id,actor_id,account_id,member_id,app_key,entity_key,action,idempotency_key),
    unique(tenant_id,command_id),
    foreign key(tenant_id,member_id) references public.tenant_member(tenant_id,id) on delete restrict,
    constraint ledger_receipt_exact_revision_fk foreign key
      (tenant_id,app_key,entity_key,record_id,result_version,command_id,actor_id,account_id,member_id,action,definition_version)
      references public.ledger_record_revision
      (tenant_id,app_key,entity_key,record_id,version,command_id,actor_id,account_id,member_id,action,definition_version)
      on delete restrict
);

create function public.reject_ledger_history_mutation() returns trigger language plpgsql as $$
begin raise exception 'ledger history is append-only' using errcode='23514'; end $$;
create trigger ledger_revision_append_only before update or delete on public.ledger_record_revision
    for each row execute function public.reject_ledger_history_mutation();
create trigger ledger_revision_no_truncate before truncate on public.ledger_record_revision
    for each statement execute function public.reject_ledger_history_mutation();
create trigger ledger_receipt_append_only before update or delete on public.ledger_command_receipt
    for each row execute function public.reject_ledger_history_mutation();
create trigger ledger_receipt_no_truncate before truncate on public.ledger_command_receipt
    for each statement execute function public.reject_ledger_history_mutation();

create function public.guard_ledger_revision_fact() returns trigger language plpgsql as $$
declare fact_time timestamptz;
begin
    if (select count(*) from jsonb_object_keys(new.change_fact))<>16
       or new.change_fact->>'eventId' is distinct from new.event_id::text
       or new.change_fact->>'eventType' is distinct from 'ledger.record.changed'
       or new.change_fact->'schemaVersion' is distinct from '1'::jsonb
       or new.change_fact->>'appKey' is distinct from new.app_key
       or new.change_fact->>'entityKey' is distinct from new.entity_key
       or new.change_fact->>'tenantId' is distinct from new.tenant_id::text
       or new.change_fact->>'recordId' is distinct from new.record_id::text
       or new.change_fact->'version' is distinct from to_jsonb(new.version)
       or new.change_fact->>'definitionVersion' is distinct from new.definition_version
       or new.change_fact->>'action' is distinct from new.action
       or new.change_fact->>'commandId' is distinct from new.command_id::text
       or new.change_fact->>'actorId' is distinct from new.actor_id::text
       or new.change_fact->>'accountId' is distinct from new.account_id::text
       or new.change_fact->>'memberId' is distinct from new.member_id::text
       or new.change_fact->'snapshot' is distinct from new.snapshot then
        raise exception 'ledger fact does not match its revision' using errcode='23514';
    end if;
    begin fact_time := (new.change_fact->>'occurredAt')::timestamptz;
    exception when invalid_datetime_format or datetime_field_overflow then
        raise exception 'ledger fact timestamp is invalid' using errcode='23514';
    end;
    if fact_time is distinct from new.occurred_at then
        raise exception 'ledger fact timestamp does not match its revision' using errcode='23514';
    end if;
    return new;
end $$;
create trigger ledger_revision_fact before insert on public.ledger_record_revision
    for each row execute function public.guard_ledger_revision_fact();

create function public.guard_ledger_receipt_result() returns trigger language plpgsql as $$
declare revision public.ledger_record_revision%rowtype; result_time timestamptz;
begin
    select * into revision from public.ledger_record_revision where tenant_id=new.tenant_id
       and app_key=new.app_key and entity_key=new.entity_key and record_id=new.record_id and version=new.result_version;
    if not found then raise exception 'ledger receipt revision is unavailable' using errcode='23503'; end if;
    if (select count(*) from jsonb_object_keys(new.result))<>7
       or new.result->>'commandId' is distinct from revision.command_id::text
       or new.result->>'id' is distinct from revision.record_id::text
       or new.result->'version' is distinct from to_jsonb(revision.version)
       or new.result->>'definitionVersion' is distinct from revision.definition_version
       or new.result->>'eventId' is distinct from revision.event_id::text
       or new.result->'replay' is distinct from 'false'::jsonb then
        raise exception 'ledger command result does not match its revision' using errcode='23514';
    end if;
    begin result_time := (new.result->>'occurredAt')::timestamptz;
    exception when invalid_datetime_format or datetime_field_overflow then
        raise exception 'ledger result timestamp is invalid' using errcode='23514';
    end;
    if result_time is distinct from revision.occurred_at or new.completed_at is distinct from revision.occurred_at then
        raise exception 'ledger result timestamp does not match its revision' using errcode='23514';
    end if;
    return new;
end $$;
create trigger ledger_receipt_result before insert on public.ledger_command_receipt
    for each row execute function public.guard_ledger_receipt_result();

create table public.tool_custody_scope (
    tenant_id uuid not null references public.tenant(id) on delete restrict,
    id uuid not null,
    organization_id uuid not null,
    code varchar(64) not null check(length(btrim(code))>0),
    name varchar(200) not null check(length(btrim(name))>0),
    active boolean not null default true,
    primary key(tenant_id,id), unique(tenant_id,code), unique(tenant_id,id,organization_id),
    foreign key(tenant_id,organization_id) references public.org_unit(tenant_id,id) on delete restrict
);
create table public.tool_custodian (
    tenant_id uuid not null,
    custody_scope_id uuid not null,
    member_id uuid not null,
    valid_from timestamptz not null,
    valid_to timestamptz,
    primary key(tenant_id,custody_scope_id,member_id,valid_from),
    check(valid_to is null or valid_to>valid_from),
    foreign key(tenant_id,custody_scope_id) references public.tool_custody_scope(tenant_id,id) on delete restrict,
    foreign key(tenant_id,member_id) references public.tenant_member(tenant_id,id) on delete restrict
);
create index tool_custodian_current_idx on public.tool_custodian(tenant_id,member_id,custody_scope_id,valid_from,valid_to);
create table public.tool (
    tenant_id uuid not null references public.tenant(id) on delete restrict,
    id uuid not null,
    version bigint not null check(version>=1),
    created_at timestamptz not null,
    updated_at timestamptz not null,
    created_by_actor_id uuid not null references public.principal(id) on delete restrict,
    updated_by_actor_id uuid not null references public.principal(id) on delete restrict,
    code varchar(64) not null check(length(btrim(code))>0 and code=btrim(code)),
    name varchar(200) not null check(length(btrim(name))>0),
    model varchar(200),
    description varchar(4000),
    custody_scope_id uuid not null,
    organization_id uuid not null,
    item_state varchar(16) not null default 'NORMAL' check(item_state in ('NORMAL','DAMAGED','LOST','RETIRED')),
    primary key(tenant_id,id), unique(tenant_id,code),
    foreign key(tenant_id,custody_scope_id,organization_id)
       references public.tool_custody_scope(tenant_id,id,organization_id) on delete restrict
);
create index tool_organization_list_idx on public.tool(tenant_id,organization_id,id);
create index tool_custody_scope_idx on public.tool(tenant_id,custody_scope_id,id);
create function public.guard_tool_identity() returns trigger language plpgsql as $$
begin
    if row(new.tenant_id,new.id,new.code,new.custody_scope_id,new.organization_id,new.item_state,new.created_at,new.created_by_actor_id)
       is distinct from row(old.tenant_id,old.id,old.code,old.custody_scope_id,old.organization_id,old.item_state,old.created_at,old.created_by_actor_id)
       or new.version<>old.version+1 then
        raise exception 'tool identity/state is protected and versions must advance' using errcode='23514';
    end if;
    return new;
end $$;
create trigger tool_identity before update on public.tool for each row execute function public.guard_tool_identity();

do $$
declare relation text;
begin
    foreach relation in array array['ledger_record_revision','ledger_command_receipt','tool_custody_scope','tool_custodian','tool'] loop
        execute format('alter table public.%I enable row level security',relation);
        execute format('alter table public.%I force row level security',relation);
        execute format('create policy %I on public.%I using (tenant_id=nullif(current_setting(''app.tenant_id'',true),'''')::uuid) with check (tenant_id=nullif(current_setting(''app.tenant_id'',true),'''')::uuid)',relation||'_tenant_isolation',relation);
    end loop;
end $$;

do $$
begin
    if exists(select 1 from public.permission where code in ('tool.read','tool.create','tool.update')
      and (resource_type is distinct from 'tool' or action is distinct from substring(code from 6)
           or application_key is not null and application_key<>'tooling')) then
        raise exception 'tool permission definitions conflict with the registered application' using errcode='23514';
    end if;
    insert into public.permission(id,code,resource_type,action,application_key,label,sort_order) values
      (gen_random_uuid(),'tool.read','tool','read','tooling','查看工装档案',400),
      (gen_random_uuid(),'tool.create','tool','create','tooling','新增工装档案',410),
      (gen_random_uuid(),'tool.update','tool','update','tooling','修改工装档案',420)
    on conflict(code) do nothing;
end $$;
