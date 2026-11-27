-- Fixed ephemeral role only; no grants to any live database or existing principal.
grant select on public.studio_ci_marker,public.studio_screen,public.studio_tenant_scene_admin,
    public.studio_screen_player_reader,public.studio_publication_stream,public.studio_published_version,
    public.audit_event to msf_ci_studio_reader;
grant select on public.studio_publication_stream to msf_ci_studio_writer;
grant insert on public.studio_published_version,public.audit_event to msf_ci_studio_writer;
grant update(current_version) on public.studio_publication_stream to msf_ci_studio_writer;
alter table public.studio_publication_stream add foreign key(tenant_id,screen_key)
    references public.studio_screen(tenant_id,screen_key);
insert into public.tenant values ('10000000-0000-4000-8000-000000000001'),('20000000-0000-4000-8000-000000000001');
insert into public.tenant_member values
    ('10000000-0000-4000-8000-000000000001','10000000-0000-4000-8000-000000000002'),
    ('10000000-0000-4000-8000-000000000001','10000000-0000-4000-8000-000000000003'),
    ('20000000-0000-4000-8000-000000000001','20000000-0000-4000-8000-000000000002');
insert into public.studio_screen values
    ('10000000-0000-4000-8000-000000000001','floor',null),
    ('10000000-0000-4000-8000-000000000001','other',null),
    ('20000000-0000-4000-8000-000000000001','floor',null);
insert into public.studio_tenant_scene_admin values ('10000000-0000-4000-8000-000000000001','10000000-0000-4000-8000-000000000003');
insert into public.studio_screen_player_reader values
    ('10000000-0000-4000-8000-000000000001','floor','10000000-0000-4000-8000-000000000002'),
    ('20000000-0000-4000-8000-000000000001','floor','20000000-0000-4000-8000-000000000002');
insert into public.studio_publication_stream values
    ('10000000-0000-4000-8000-000000000001','floor','10000000-0000-4000-8000-000000000010',null);
insert into public.audit_event values
    ('10000000-0000-4000-8000-000000000001','10000000-0000-4000-8000-000000000011','10000000-0000-4000-8000-000000000020');
insert into public.studio_published_version values
    ('10000000-0000-4000-8000-000000000001','floor',1,convert_to('{}','UTF8'),
     encode(sha256(convert_to('{}','UTF8')),'hex'),
     '10000000-0000-4000-8000-000000000003','10000000-0000-4000-8000-000000000011',now());
update public.studio_publication_stream set current_version=1 where tenant_id='10000000-0000-4000-8000-000000000001' and screen_key='floor';
