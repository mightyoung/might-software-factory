-- Operator-applied D2 definition version delta after the D0, D1, D2 return and loss runtime scripts. No tenant entitlement or role grants.
-- psql -X -v ON_ERROR_STOP=1 -v runtime_role=... -v target_database=... \
--   -v d2_version_ack=d2-version-runtime-direct-grants-v1 -f d2-version-runtime-grants.sql
\set ON_ERROR_STOP on
begin;
select set_config('d2v.runtime_role', :'runtime_role', true),
       set_config('d2v.target_database', :'target_database', true),
       set_config('d2v.ack', :'d2_version_ack', true);
do $$
declare runtime record; role_name text:=current_setting('d2v.runtime_role');
begin
    if current_setting('d2v.ack')<>'d2-version-runtime-direct-grants-v1'
       or current_database()<>current_setting('d2v.target_database')
       or current_database() !~ '^msf_(d0_d2|d2)_[a-z0-9_]+_test$' then
        raise exception 'D2 definition version target acknowledgement mismatch';
    end if;
    select * into strict runtime from pg_roles where rolname=role_name;
    if runtime.rolsuper or runtime.rolbypassrls or runtime.rolcreaterole or runtime.rolcreatedb
       or runtime.rolreplication or runtime.rolinherit or not runtime.rolcanlogin or role_name=session_user
       or exists(select 1 from pg_auth_members where member=runtime.oid) then
        raise exception 'D2 definition version requires a separate non-elevated NOINHERIT login role';
    end if;
    if exists(select 1 from pg_class where relnamespace='public'::regnamespace and relowner=runtime.oid)
       or exists(select 1 from pg_proc where pronamespace='public'::regnamespace and proowner=runtime.oid)
       or exists(select 1 from pg_database where datname=current_database() and datdba=runtime.oid)
       or has_database_privilege(role_name,current_database(),'CREATE,TEMP')
       or has_schema_privilege(role_name,'public','CREATE') then
        raise exception 'D2 definition version runtime ownership or effective DDL privilege is forbidden';
    end if;
    if not exists(select 1 from public.flyway_schema_history_app where version='35' and success) then
        raise exception 'D2 definition version migration V35 is required';
    end if;
end $$;
grant update(usage_location) on public.tool_loan to :"runtime_role";
commit;
