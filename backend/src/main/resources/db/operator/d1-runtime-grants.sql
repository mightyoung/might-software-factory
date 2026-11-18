-- Operator-applied D1 delta after the D0 legacy and runtime scripts. No tenant entitlement or role grants.
-- psql -X -v ON_ERROR_STOP=1 -v runtime_role=... -v target_database=... \
--   -v d1_ack=d1-runtime-direct-grants-v1 -f d1-runtime-grants.sql
\set ON_ERROR_STOP on
begin;
select set_config('d1.runtime_role', :'runtime_role', true),
       set_config('d1.target_database', :'target_database', true),
       set_config('d1.ack', :'d1_ack', true);
do $$
declare runtime record; role_name text:=current_setting('d1.runtime_role');
begin
    if current_setting('d1.ack')<>'d1-runtime-direct-grants-v1'
       or current_database()<>current_setting('d1.target_database')
       or current_database() !~ '^msf_d0_d1_[a-z0-9_]+_test$' then
        raise exception 'D1 target acknowledgement mismatch';
    end if;
    select * into strict runtime from pg_roles where rolname=role_name;
    if runtime.rolsuper or runtime.rolbypassrls or runtime.rolcreaterole or runtime.rolcreatedb
       or runtime.rolreplication or runtime.rolinherit or not runtime.rolcanlogin or role_name=session_user
       or exists(select 1 from pg_auth_members where member=runtime.oid) then
        raise exception 'D1 requires a separate non-elevated NOINHERIT login role';
    end if;
    if exists(select 1 from pg_class where relnamespace='public'::regnamespace and relowner=runtime.oid)
       or exists(select 1 from pg_proc where pronamespace='public'::regnamespace and proowner=runtime.oid)
       or exists(select 1 from pg_database where datname=current_database() and datdba=runtime.oid)
       or has_database_privilege(role_name,current_database(),'CREATE,TEMP')
       or has_schema_privilege(role_name,'public','CREATE') then
        raise exception 'D1 runtime ownership or effective DDL privilege is forbidden';
    end if;
    if not exists(select 1 from public.flyway_schema_history_app where version='27' and success) then
        raise exception 'D1 migration V27 is required';
    end if;
end $$;
create temporary table d1_runtime_tables(table_name text primary key,readable boolean,insertable boolean,insert_columns text[],update_columns text[]) on commit drop;
insert into d1_runtime_tables values
('tool_custody_scope',true,false,ARRAY[]::text[],ARRAY[]::text[]),
('tool_custodian',true,false,ARRAY[]::text[],ARRAY[]::text[]),
('tool',true,false,ARRAY['tenant_id','id','version','created_at','updated_at','created_by_actor_id','updated_by_actor_id','code','name','model','description','custody_scope_id','organization_id','item_state'],ARRAY['name','model','description','version','updated_at','updated_by_actor_id']),
('ledger_record_revision',true,true,ARRAY[]::text[],ARRAY[]::text[]),
('ledger_command_receipt',true,true,ARRAY[]::text[],ARRAY[]::text[]);
do $$
declare spec record; rel regclass; column_list text; operation text; col record; expected boolean;
        role_name text:=current_setting('d1.runtime_role');
begin
    for spec in select * from d1_runtime_tables loop
        rel:=to_regclass('public.'||quote_ident(spec.table_name));
        if rel is null then raise exception 'Missing D1 table %',spec.table_name; end if;
        if not exists(select 1 from pg_class where oid=rel and relrowsecurity and relforcerowsecurity and relowner<>(select oid from pg_roles where rolname=role_name)) then
            raise exception 'D1 table must be non-runtime-owned with FORCE RLS';
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
                raise exception 'Unexpected D1 effective table privilege % on %',operation,spec.table_name;
            end if;
        end loop;
        for col in select attname from pg_attribute where attrelid=rel and attnum>0 and not attisdropped loop
            foreach operation in array array['SELECT','INSERT','UPDATE','REFERENCES'] loop
                expected:=case operation when 'SELECT' then spec.readable when 'INSERT' then spec.insertable or col.attname=any(spec.insert_columns)
                    when 'UPDATE' then col.attname=any(spec.update_columns) else false end;
                if has_column_privilege(role_name,rel,col.attname,operation) is distinct from expected then
                    raise exception 'Unexpected D1 effective column privilege % on %.%',operation,spec.table_name,col.attname;
                end if;
            end loop;
        end loop;
    end loop;
end $$;

-- Neutral ledger human-control reads the complete persisted activation row, including its database issuer.
-- This read-only D1 consumer delta neither activates authority nor widens legacy D0 profiles.
grant select(activated_by_database_role) on public.session_authority_activation to :"runtime_role";
do $$
declare role_name text:=current_setting('d1.runtime_role'); col record; operation text;
begin
    if has_table_privilege(role_name,'public.session_authority_activation','SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER,MAINTAIN') then
        raise exception 'D1 activation control permits only explicit column SELECT';
    end if;
    if (select count(*) from pg_attribute where attrelid='public.session_authority_activation'::regclass and attnum>0 and not attisdropped)<>7 then
        raise exception 'D1 activation control column manifest differs';
    end if;
    for col in select attname from pg_attribute where attrelid='public.session_authority_activation'::regclass and attnum>0 and not attisdropped loop
        foreach operation in array array['SELECT','INSERT','UPDATE','REFERENCES'] loop
            if has_column_privilege(role_name,'public.session_authority_activation',col.attname,operation) is distinct from (operation='SELECT') then
                raise exception 'D1 activation control requires exact read-only column rights';
            end if;
        end loop;
    end loop;
end $$;
select count(*) as reconciled_d1_tables from d1_runtime_tables;
commit;
