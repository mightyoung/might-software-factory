\set VERBOSITY verbose
begin;
set local app.tenant_id='10000000-0000-4000-8000-000000000001';
insert into public.studio_published_version values
    ('10000000-0000-4000-8000-000000000001','floor',2,convert_to('{"test":2}','UTF8'),
     encode(sha256(convert_to('{"test":2}','UTF8')),'hex'),
     '10000000-0000-4000-8000-000000000003','10000000-0000-4000-8000-000000000012',now());
-- A genuine transaction write, intentionally not the required audit reference.
insert into public.audit_event values
    ('10000000-0000-4000-8000-000000000001','10000000-0000-4000-8000-000000000013','10000000-0000-4000-8000-000000000020');
do $$
begin
    update public.studio_publication_stream set current_version=2
    where tenant_id='10000000-0000-4000-8000-000000000001' and screen_key='floor' and current_version=1;
    if not found then raise exception 'Head did not move inside failing fixture transaction'; end if;
    raise notice 'studio fixture reached deferred commit';
end $$;
commit;
