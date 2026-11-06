-- Source-only tenant audit query capability. Apply only through a separately reviewed migration role.
-- Transactional maintenance migration: table locks and ordinary index creation may block writers.
lock table public.permission in access exclusive mode;
lock table public.scope_rule in access exclusive mode;
lock table public.role_permission in access exclusive mode;
lock table public.scope_org in access exclusive mode;
lock table public.audit_event in share mode;

-- A filtered preflight must never pass by observing an empty tenant slice.
do $preflight$
declare expected record; actual record;
begin
    if pg_catalog.row_security_active('public.permission'::regclass)
       or pg_catalog.row_security_active('public.scope_rule'::regclass)
       or pg_catalog.row_security_active('public.role_permission'::regclass)
       or pg_catalog.row_security_active('public.scope_org'::regclass) then
        raise exception 'audit migration requires complete preflight visibility' using errcode='23514';
    end if;
    if exists(select 1 from pg_catalog.pg_class c where c.oid in (
        'public.scope_rule'::regclass,'public.role_permission'::regclass,'public.scope_org'::regclass,'public.audit_event'::regclass)
        and (not c.relrowsecurity or not c.relforcerowsecurity))
       or exists(select 1 from pg_catalog.pg_class c where c.oid='public.permission'::regclass
        and (c.relrowsecurity or c.relforcerowsecurity)) then
        raise exception 'audit migration row security baseline has drifted' using errcode='23514';
    end if;
    for expected in select * from (values
        ('permission','id'),('permission','code'),('permission','resource_type'),('permission','action'),
        ('scope_rule','tenant_id'),('scope_rule','id'),('scope_rule','kind'),('scope_rule','version_no'),('scope_rule','definition'),
        ('role_permission','tenant_id'),('role_permission','role_id'),('role_permission','permission_id'),
        ('scope_org','tenant_id'),('scope_org','scope_rule_id'),('scope_org','org_id')
    ) as columns(table_name,column_name) loop
        if not exists(select 1 from pg_catalog.pg_attribute a
            where a.attrelid=pg_catalog.to_regclass('public.'||expected.table_name)
              and a.attname=expected.column_name and a.attnum>0 and a.attnotnull and not a.attisdropped
              and exists(select 1 from pg_catalog.pg_constraint n where n.conrelid=a.attrelid and n.contype='n'
                  and n.conkey=array[a.attnum]::smallint[] and n.convalidated and n.conenforced)) then
            raise exception 'audit migration requires validated non-null columns' using errcode='23514';
        end if;
    end loop;
    for expected in select * from (values
        ('scope_rule','scope_rule_kind_check'),('scope_rule','scope_rule_definition_empty')
    ) as checks(table_name,constraint_name) loop
        if not exists(select 1 from pg_catalog.pg_constraint c
            where c.conrelid=pg_catalog.to_regclass('public.'||expected.table_name)
              and c.conname=expected.constraint_name and c.contype='c' and c.convalidated and c.conenforced) then
            raise exception 'audit migration requires validated scope checks' using errcode='23514';
        end if;
    end loop;
    if not exists(select 1 from pg_catalog.pg_constraint c
        where c.conrelid='public.scope_rule'::regclass and c.conname='scope_rule_definition_empty'
          and pg_catalog.pg_get_expr(c.conbin,c.conrelid,false)='(definition = ''{}''::jsonb)') then
        raise exception 'scope definition constraint has drifted' using errcode='23514';
    end if;
    for expected in select * from (values
        ('role_permission','role_permission_permission_id_fkey','permission',array['permission_id'],array['id']),
        ('role_permission','role_permission_scope_fkey','scope_rule',array['tenant_id','scope_rule_id'],array['tenant_id','id']),
        ('role_permission','role_permission_role_fkey','role',array['tenant_id','role_id'],array['tenant_id','id']),
        ('scope_org','scope_org_rule_fkey','scope_rule',array['tenant_id','scope_rule_id'],array['tenant_id','id']),
        ('scope_org','scope_org_org_fkey','org_unit',array['tenant_id','org_id'],array['tenant_id','id'])
    ) as fkeys(table_name,constraint_name,target_name,source_columns,target_columns) loop
        select c.* into actual from pg_catalog.pg_constraint c
         where c.conrelid=pg_catalog.to_regclass('public.'||expected.table_name) and c.conname=expected.constraint_name;
        if not found then raise exception 'audit migration requires existing foreign keys' using errcode='23514'; end if;
        if actual.contype is distinct from 'f' or not actual.convalidated or not actual.conenforced or actual.condeferrable
           or actual.confdeltype is distinct from 'r'
           or actual.confrelid is distinct from pg_catalog.to_regclass('public.'||pg_catalog.quote_ident(expected.target_name))
           or (select array_agg(a.attname::text order by k.ordinality) from unnest(actual.conkey) with ordinality k(attnum,ordinality)
                join pg_catalog.pg_attribute a on a.attrelid=actual.conrelid and a.attnum=k.attnum) is distinct from expected.source_columns
           or (select array_agg(a.attname::text order by k.ordinality) from unnest(actual.confkey) with ordinality k(attnum,ordinality)
                join pg_catalog.pg_attribute a on a.attrelid=actual.confrelid and a.attnum=k.attnum) is distinct from expected.target_columns then
            raise exception 'audit migration foreign key shape requires review' using errcode='23514';
        end if;
    end loop;
    if exists(select 1 from public.scope_rule s where s.kind not in ('SELF','DEPT','DEPT_TREE','EXPLICIT','RELATION','IDENTITY_TENANT','AUDIT_TENANT')
        or s.definition is distinct from '{}'::jsonb) then
        raise exception 'existing scope definition requires review' using errcode='23514';
    end if;
    if exists(select 1 from public.scope_org o left join public.scope_rule s on s.tenant_id=o.tenant_id and s.id=o.scope_rule_id
        where s.id is null or s.kind in ('IDENTITY_TENANT','AUDIT_TENANT')) then
        raise exception 'existing tenant capability organization binding requires review' using errcode='23514';
    end if;
    if exists(select 1 from public.permission p where
        (p.code='audit_event.read' or p.id='8c987ea3-631b-5512-bd41-ced3e29eee46'::uuid)
        and (p.code is distinct from 'audit_event.read' or p.resource_type is distinct from 'audit_event' or p.action is distinct from 'read')) then
        raise exception 'reserved audit permission collision requires review' using errcode='23514';
    end if;
    if exists(select 1 from public.role_permission rp
        left join public.permission p on p.id=rp.permission_id
        left join public.scope_rule s on s.tenant_id=rp.tenant_id and s.id=rp.scope_rule_id
        where p.id is null or (rp.scope_rule_id is not null and s.id is null)
          or ((p.code is not distinct from 'audit_event.read') is distinct from (s.kind is not distinct from 'AUDIT_TENANT'))
          or ((p.code in ('identity.invitation.create','identity.invitation.revoke')) is distinct from (s.kind is not distinct from 'IDENTITY_TENANT'))) then
        raise exception 'existing capability scope pairing requires review' using errcode='23514';
    end if;
    if exists(select 1 from public.permission p where p.code in ('identity.invitation.create','identity.invitation.revoke') and not (
        (p.code='identity.invitation.create' and p.resource_type='account-invitation' and p.action='create')
        or (p.code='identity.invitation.revoke' and p.resource_type='account-invitation' and p.action='revoke'))) then
        raise exception 'existing identity tuple requires review' using errcode='23514';
    end if;
    for expected in select * from (values
        ('permission','guard_audit_query_permission'),('scope_rule','guard_audit_tenant_scope'),
        ('scope_org','guard_audit_tenant_scope_org'),('role_permission','guard_audit_query_role_permission')
    ) as objects(table_name,object_name) loop
        if pg_catalog.to_regprocedure('public.'||expected.object_name||'()') is not null
           or exists(select 1 from pg_catalog.pg_trigger t where t.tgrelid=pg_catalog.to_regclass('public.'||expected.table_name) and t.tgname=expected.object_name) then
            raise exception 'audit migration object collision requires review' using errcode='23514';
        end if;
    end loop;
    if pg_catalog.to_regclass('public.audit_event_tenant_time_id_idx') is not null then
        raise exception 'audit query index collision requires review' using errcode='23514';
    end if;
end $preflight$;

-- Fail on drift of the identity guards this migration must preserve.
do $identity_baseline$
declare expected record; existing_function record;
begin
    for expected in select * from (values
        ('scope_rule','guard_identity_tenant_scope',23,$identity_body$
begin
    if tg_op='UPDATE' and (old.kind='IDENTITY_TENANT' or new.kind='IDENTITY_TENANT')
       and (new.kind is distinct from old.kind or new.tenant_id is distinct from old.tenant_id or new.id is distinct from old.id) then
        raise exception 'identity tenant scope identity is immutable' using errcode='23514';
    end if;
    if new.kind='IDENTITY_TENANT' and new.definition<>'{}'::jsonb then
        raise exception 'identity tenant scope has no organization definition' using errcode='23514';
    end if;
    return new;
end $identity_body$),
        ('scope_org','guard_identity_tenant_scope_org',23,$identity_body$
begin
    if exists(select 1 from public.scope_rule s where s.tenant_id=new.tenant_id and s.id=new.scope_rule_id and s.kind='IDENTITY_TENANT') then
        raise exception 'identity tenant scopes cannot have organization rows' using errcode='23514';
    end if;
    return new;
end $identity_body$),
        ('permission','guard_identity_invitation_permission',31,$identity_body$
begin
    if tg_op='DELETE' then
        if old.code in ('identity.invitation.create','identity.invitation.revoke') then
            raise exception 'identity permission definitions are immutable' using errcode='23514';
        end if;
        return old;
    end if;
    if tg_op='UPDATE' and (old.code in ('identity.invitation.create','identity.invitation.revoke')
          or new.code in ('identity.invitation.create','identity.invitation.revoke')) and new is distinct from old then
        raise exception 'reserved identity permission tuples require fresh definitions' using errcode='23514';
    end if;
    if new.code in ('identity.invitation.create','identity.invitation.revoke') and not (
        (new.code='identity.invitation.create' and new.resource_type='account-invitation' and new.action='create')
        or (new.code='identity.invitation.revoke' and new.resource_type='account-invitation' and new.action='revoke')) then
        raise exception 'invalid identity invitation permission tuple' using errcode='23514';
    end if;
    return new;
end $identity_body$),
        ('role_permission','guard_identity_invitation_role_permission',23,$identity_body$
declare scope_kind text; permission_code text; resource_kind text; permission_action text;
begin
    select s.kind into scope_kind from public.scope_rule s where s.tenant_id=new.tenant_id and s.id=new.scope_rule_id;
    select p.code,p.resource_type,p.action into permission_code,resource_kind,permission_action from public.permission p where p.id=new.permission_id;
    if scope_kind='IDENTITY_TENANT' or permission_code in ('identity.invitation.create','identity.invitation.revoke') then
        if scope_kind is distinct from 'IDENTITY_TENANT' or not (
            (permission_code='identity.invitation.create' and resource_kind='account-invitation' and permission_action='create')
            or (permission_code='identity.invitation.revoke' and resource_kind='account-invitation' and permission_action='revoke')) then
            raise exception 'identity invitation permission requires its exact tenant capability scope' using errcode='23514';
        end if;
    end if;
    return new;
end $identity_body$)
    ) as guards(table_name,function_name,trigger_type,function_body) loop
        select p.* into existing_function from pg_catalog.pg_proc p
         where p.oid=pg_catalog.to_regprocedure('public.'||expected.function_name||'()');
        if not found then raise exception 'required identity guard is absent' using errcode='23514'; end if;
        if existing_function.prosrc is distinct from expected.function_body or existing_function.prosecdef
           or existing_function.proconfig is not null or existing_function.provolatile is distinct from 'v'
           or existing_function.prokind is distinct from 'f' or existing_function.proretset
           or existing_function.proparallel is distinct from 'u' or existing_function.prorettype is distinct from 'trigger'::regtype
           or existing_function.prolang is distinct from (select oid from pg_catalog.pg_language where lanname='plpgsql')
           or not exists(select 1 from pg_catalog.pg_trigger t
                where t.tgrelid=pg_catalog.to_regclass('public.'||expected.table_name) and t.tgname=expected.function_name
                  and t.tgfoid=existing_function.oid and t.tgtype=expected.trigger_type and t.tgenabled in ('O','A')
                  and not t.tgisinternal and t.tgnargs=0 and t.tgqual is null and t.tgattr=''::int2vector) then
            raise exception 'required identity guard has drifted' using errcode='23514';
        end if;
    end loop;
end $identity_baseline$;

alter table public.scope_rule drop constraint scope_rule_kind_check;
alter table public.scope_rule add constraint scope_rule_kind_check
    check (kind in ('SELF','DEPT','DEPT_TREE','EXPLICIT','RELATION','IDENTITY_TENANT','AUDIT_TENANT'));

create function public.guard_audit_query_permission() returns trigger
language plpgsql set search_path = pg_catalog, public as $$
begin
    if tg_op='DELETE' then
        if old.code='audit_event.read' or old.id='8c987ea3-631b-5512-bd41-ced3e29eee46'::uuid then
            raise exception 'audit permission definition is immutable' using errcode='23514';
        end if;
        return old;
    end if;
    if tg_op='UPDATE' and (old.code='audit_event.read' or new.code='audit_event.read'
        or old.id='8c987ea3-631b-5512-bd41-ced3e29eee46'::uuid or new.id='8c987ea3-631b-5512-bd41-ced3e29eee46'::uuid)
        and new is distinct from old then
        raise exception 'reserved audit permission requires a fresh definition' using errcode='23514';
    end if;
    if (new.code='audit_event.read' or new.id='8c987ea3-631b-5512-bd41-ced3e29eee46'::uuid)
        and (new.code is distinct from 'audit_event.read' or new.resource_type is distinct from 'audit_event' or new.action is distinct from 'read') then
        raise exception 'invalid audit permission tuple' using errcode='23514';
    end if;
    return new;
end $$;
create trigger guard_audit_query_permission before insert or update or delete on public.permission
    for each row execute function public.guard_audit_query_permission();

create function public.guard_audit_tenant_scope() returns trigger
language plpgsql set search_path = pg_catalog, public as $$
begin
    if tg_op='UPDATE' and (old.kind='AUDIT_TENANT' or new.kind='AUDIT_TENANT')
        and (new.kind is distinct from old.kind or new.tenant_id is distinct from old.tenant_id or new.id is distinct from old.id) then
        raise exception 'audit tenant scope identity is immutable' using errcode='23514';
    end if;
    if new.kind='AUDIT_TENANT' and new.definition is distinct from '{}'::jsonb then
        raise exception 'audit tenant scope has no organization definition' using errcode='23514';
    end if;
    return new;
end $$;
create trigger guard_audit_tenant_scope before insert or update on public.scope_rule
    for each row execute function public.guard_audit_tenant_scope();

-- Lock existing parents against delete/reinsert while checking the semantic binding.
-- Missing parents are refused rather than classified as non-audit.
-- Writer activation requires reviewed effective SELECT and at least one UPDATE column
-- on each locked parent table. This migration grants nothing; query-only roles do not write these associations.
create function public.guard_audit_tenant_scope_org() returns trigger
language plpgsql set search_path = pg_catalog, public as $$
declare scope_kind text;
begin
    select s.kind into scope_kind from public.scope_rule s where s.tenant_id=new.tenant_id and s.id=new.scope_rule_id for key share;
    if not found then raise exception 'scope parent is unavailable' using errcode='23514'; end if;
    if scope_kind is not distinct from 'AUDIT_TENANT' then
        raise exception 'audit tenant scopes cannot have organization rows' using errcode='23514';
    end if;
    return new;
end $$;
create trigger guard_audit_tenant_scope_org before insert or update on public.scope_org
    for each row execute function public.guard_audit_tenant_scope_org();

create function public.guard_audit_query_role_permission() returns trigger
language plpgsql set search_path = pg_catalog, public as $$
declare scope_kind text; permission_code text; resource_kind text; permission_action text;
begin
    select p.code,p.resource_type,p.action into permission_code,resource_kind,permission_action
      from public.permission p where p.id=new.permission_id for key share;
    if not found then raise exception 'permission parent is unavailable' using errcode='23514'; end if;
    if new.scope_rule_id is not null then
        select s.kind into scope_kind from public.scope_rule s where s.tenant_id=new.tenant_id and s.id=new.scope_rule_id for key share;
        if not found then raise exception 'scope parent is unavailable' using errcode='23514'; end if;
    end if;
    if (permission_code is not distinct from 'audit_event.read') is distinct from (scope_kind is not distinct from 'AUDIT_TENANT') then
        raise exception 'audit permission requires its exact tenant capability scope' using errcode='23514';
    end if;
    if permission_code='audit_event.read' and (resource_kind is distinct from 'audit_event' or permission_action is distinct from 'read') then
        raise exception 'invalid audit permission tuple' using errcode='23514';
    end if;
    return new;
end $$;
create trigger guard_audit_query_role_permission before insert or update on public.role_permission
    for each row execute function public.guard_audit_query_role_permission();

-- UUIDv5(DNS,"might-software-factory/capability/audit_event.read").
-- Equivalent legacy definitions keep their IDs; this seed does not grant any role access.
do $capability_seed$
begin
    if not exists(select 1 from public.permission where code='audit_event.read') then
        insert into public.permission(id,code,resource_type,action)
            values('8c987ea3-631b-5512-bd41-ced3e29eee46','audit_event.read','audit_event','read');
    end if;
end $capability_seed$;

create index audit_event_tenant_time_id_idx on public.audit_event(tenant_id,occurred_at desc,id desc);
