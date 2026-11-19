-- Operator-applied D2 scope transfer delta after the D0, D1, D2 return and loss runtime scripts. No tenant entitlement or role grants.
-- psql -X -v ON_ERROR_STOP=1 -v runtime_role=... -v target_database=... \
--   -v d2_transfer_ack=d2-transfer-runtime-direct-grants-v1 -f d2-transfer-runtime-grants.sql
\set ON_ERROR_STOP on
begin;
select set_config('d2t.runtime_role', :'runtime_role', true),
       set_config('d2t.target_database', :'target_database', true),
       set_config('d2t.ack', :'d2_transfer_ack', true);
do $$
declare runtime record; role_name text:=current_setting('d2t.runtime_role');
begin
    if current_setting('d2t.ack')<>'d2-transfer-runtime-direct-grants-v1'
       or current_database()<>current_setting('d2t.target_database')
       or current_database() !~ '^msf_(d0_d2|d2)_[a-z0-9_]+_test$' then
        raise exception 'D2 scope transfer target acknowledgement mismatch';
    end if;
    select * into strict runtime from pg_roles where rolname=role_name;
    if runtime.rolsuper or runtime.rolbypassrls or runtime.rolcreaterole or runtime.rolcreatedb
       or runtime.rolreplication or runtime.rolinherit or not runtime.rolcanlogin or role_name=session_user
       or exists(select 1 from pg_auth_members where member=runtime.oid) then
        raise exception 'D2 scope transfer requires a separate non-elevated NOINHERIT login role';
    end if;
    if exists(select 1 from pg_class where relnamespace='public'::regnamespace and relowner=runtime.oid)
       or exists(select 1 from pg_proc where pronamespace='public'::regnamespace and proowner=runtime.oid)
       or exists(select 1 from pg_database where datname=current_database() and datdba=runtime.oid)
       or has_database_privilege(role_name,current_database(),'CREATE,TEMP')
       or has_schema_privilege(role_name,'public','CREATE') then
        raise exception 'D2 scope transfer runtime ownership or effective DDL privilege is forbidden';
    end if;
    if not exists(select 1 from public.flyway_schema_history_app where version='34' and success) then
        raise exception 'D2 scope transfer migration V34 is required';
    end if;
end $$;
do $$
declare rel regclass:='public.tool'::regclass;
begin
    if not exists(select from pg_class where oid=rel and relrowsecurity and relforcerowsecurity)
       or not exists(select from pg_trigger where tgrelid=rel and tgname='tool_identity' and tgenabled='O'
            and tgfoid='public.guard_tool_identity()'::regprocedure) then
        raise exception 'Tool scope transfer requires its protected RLS table and active identity trigger';
    end if;
end $$;
grant update(custody_scope_id,organization_id) on public.tool to :"runtime_role";
commit;
