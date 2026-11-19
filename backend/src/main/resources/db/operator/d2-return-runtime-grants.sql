-- Operator-applied D2 return delta after the D0, D1 and D2 runtime scripts. No tenant entitlement or role grants.
-- psql -X -v ON_ERROR_STOP=1 -v runtime_role=... -v target_database=... \
--   -v d2_return_ack=d2-return-runtime-direct-grants-v1 -f d2-return-runtime-grants.sql
\set ON_ERROR_STOP on
begin;
select set_config('d2r.runtime_role', :'runtime_role', true),
       set_config('d2r.target_database', :'target_database', true),
       set_config('d2r.ack', :'d2_return_ack', true);
do $$
declare runtime record; role_name text:=current_setting('d2r.runtime_role');
begin
    if current_setting('d2r.ack')<>'d2-return-runtime-direct-grants-v1'
       or current_database()<>current_setting('d2r.target_database')
       or current_database() !~ '^msf_(d0_d2|d2)_[a-z0-9_]+_test$' then
        raise exception 'D2 return target acknowledgement mismatch';
    end if;
    select * into strict runtime from pg_roles where rolname=role_name;
    if runtime.rolsuper or runtime.rolbypassrls or runtime.rolcreaterole or runtime.rolcreatedb
       or runtime.rolreplication or runtime.rolinherit or not runtime.rolcanlogin or role_name=session_user
       or exists(select 1 from pg_auth_members where member=runtime.oid) then
        raise exception 'D2 return requires a separate non-elevated NOINHERIT login role';
    end if;
    if exists(select 1 from pg_class where relnamespace='public'::regnamespace and relowner=runtime.oid)
       or exists(select 1 from pg_proc where pronamespace='public'::regnamespace and proowner=runtime.oid)
       or exists(select 1 from pg_database where datname=current_database() and datdba=runtime.oid)
       or has_database_privilege(role_name,current_database(),'CREATE,TEMP')
       or has_schema_privilege(role_name,'public','CREATE') then
        raise exception 'D2 return runtime ownership or effective DDL privilege is forbidden';
    end if;
    if not exists(select 1 from public.flyway_schema_history_app where version='31' and success) then
        raise exception 'D2 return migration V31 is required';
    end if;
end $$;
create temporary table d2_return_runtime_tables(table_name text primary key,readable boolean,insertable boolean,insert_columns text[],update_columns text[]) on commit drop;
insert into d2_return_runtime_tables values
('tool_return_request',true,true,ARRAY[]::text[],ARRAY[]::text[]),
('tool_return_request_item',true,true,ARRAY[]::text[],ARRAY['received_at','outcome','reason','received_by_member_id','command_id']),
('tool_loan_item',true,true,ARRAY[]::text[],ARRAY['settled_at']);
do $$
declare spec record; rel regclass; column_list text; operation text; col record; expected boolean;
        role_name text:=current_setting('d2r.runtime_role');
begin
    for spec in select * from d2_return_runtime_tables loop
        rel:=to_regclass('public.'||quote_ident(spec.table_name));
        if rel is null then raise exception 'Missing D2 return table %',spec.table_name; end if;
        if not exists(select 1 from pg_class where oid=rel and relrowsecurity and relforcerowsecurity and relowner<>(select oid from pg_roles where rolname=role_name)) then
            raise exception 'D2 return table must be non-runtime-owned with FORCE RLS';
        end if;
        execute format('revoke select,insert,update,delete,truncate,references,trigger,maintain on table %s from %I',rel,role_name);
        select string_agg(quote_ident(attname),',') into column_list from pg_attribute where attrelid=rel and attnum>0 and not attisdropped;
        foreach operation in array array['select','insert','update','references'] loop
            execute format('revoke %s(%s) on table %s from %I',operation,column_list,rel,role_name);
        end loop;
        execute format('grant select on table %s to %I',rel,role_name);
        if spec.insertable then execute format('grant insert on table %s to %I',rel,role_name); end if;
        if cardinality(spec.insert_columns)>0 then
            select string_agg(quote_ident(name),',') into column_list from unnest(spec.insert_columns) name;
            execute format('grant insert(%s) on table %s to %I',column_list,rel,role_name);
        end if;
        if cardinality(spec.update_columns)>0 then
            select string_agg(quote_ident(name),',') into column_list from unnest(spec.update_columns) name;
            execute format('grant update(%s) on table %s to %I',column_list,rel,role_name);
        end if;
        foreach operation in array array['SELECT','INSERT','UPDATE','DELETE','TRUNCATE','REFERENCES','TRIGGER','MAINTAIN'] loop
            expected:=case operation when 'SELECT' then spec.readable when 'INSERT' then spec.insertable else false end;
            if has_table_privilege(role_name,rel,operation) is distinct from expected then
                raise exception 'Unexpected D2 return effective table privilege % on %',operation,spec.table_name;
            end if;
        end loop;
        for col in select attname from pg_attribute where attrelid=rel and attnum>0 and not attisdropped loop
            foreach operation in array array['SELECT','INSERT','UPDATE','REFERENCES'] loop
                expected:=case operation when 'SELECT' then spec.readable when 'INSERT' then spec.insertable or col.attname=any(spec.insert_columns)
                    when 'UPDATE' then col.attname=any(spec.update_columns) else false end;
                if has_column_privilege(role_name,rel,col.attname,operation) is distinct from expected then
                    raise exception 'Unexpected D2 return effective column privilege % on %.%',operation,spec.table_name,col.attname;
                end if;
            end loop;
        end loop;
    end loop;
end $$;

select count(*) as reconciled_d2_return_tables from d2_return_runtime_tables;
grant update(item_state) on public.tool to :"runtime_role";
grant execute on function public.mark_received_tool_damaged(uuid,uuid,timestamptz,uuid) to :"runtime_role";
commit;
