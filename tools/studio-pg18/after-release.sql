begin read only;
set local app.tenant_id='10000000-0000-4000-8000-000000000001';
do $$
begin
    if (select count(*) from public.studio_publication_stream where screen_key='floor' and current_version=1) <> 1
       or (select count(*) from public.studio_published_version) <> 1
       or (select count(*) from public.studio_published_version where screen_key='floor' and version=1) <> 1
       or (select count(*) from public.audit_event) <> 1
       or (select count(*) from public.audit_event where id='10000000-0000-4000-8000-000000000011') <> 1
    then raise exception 'Commit failure did not preserve version, alias and audit baseline'; end if;
end $$;
rollback;
