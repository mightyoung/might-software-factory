-- Dormant, read-only probe, not a workflow, launcher, or migration.
-- Future caller owns and proves the fresh CI database before any setup DDL.
-- The existing CiPg18EphemeralTarget has no Studio lane; do not borrow another lane.
begin transaction read only;
set local statement_timeout = '5s';

do $probe$
declare
    a constant uuid := '10000000-0000-4000-8000-000000000001';
    b constant uuid := '20000000-0000-4000-8000-000000000001';
    a_reader constant uuid := '10000000-0000-4000-8000-000000000002';
    a_admin constant uuid := '10000000-0000-4000-8000-000000000003';
    b_reader constant uuid := '20000000-0000-4000-8000-000000000002';
    observed integer;
begin
    if current_database() <> 'msf_ci_studio_authority'
       or inet_server_addr() is distinct from '127.0.0.1'::inet
       or current_setting('server_version_num')::integer / 10000 <> 18
       or current_setting('msf.studio_probe_ack',true) is distinct from 'studio-authority-fixture-only'
       or current_user <> 'msf_ci_studio_reader'
       or current_user <> session_user
       or not exists (select 1 from pg_roles where rolname=current_user and not rolsuper and not rolbypassrls)
       or (select count(*) from pg_class c join pg_namespace n on n.oid=c.relnamespace
           where n.nspname='public' and c.relname in
             ('studio_screen','studio_tenant_scene_admin','studio_screen_player_reader')
             and c.relrowsecurity and c.relforcerowsecurity and not pg_has_role(current_user,c.relowner,'MEMBER')) <> 3
    then raise exception 'Studio synthetic probe target is not verified'; end if;

    perform set_config('app.tenant_id',a::text,true);
    select count(*) into observed from public.studio_screen s where s.screen_key='floor';
    if observed <> 1 then raise exception 'Expected one same-key registry row under tenant A'; end if;

    select count(*) into observed
    from (values
        (a,a_reader,'floor',true), (a,a_reader,'other',false),
        (b,b_reader,'floor',false), (a,a_admin,'floor',false)
    ) as cases(tenant_id,member_id,screen_key,expected)
    where exists (
        select from public.studio_screen_player_reader as g
        join public.studio_screen as s using (tenant_id,screen_key)
        where g.tenant_id=cases.tenant_id and g.member_id=cases.member_id
            and g.screen_key=cases.screen_key and s.retired_at is null
    ) is distinct from cases.expected;
    if observed <> 0 then raise exception 'Tenant A screen grant matrix mismatch'; end if;

    if not exists (select from public.studio_tenant_scene_admin where tenant_id=a and member_id=a_admin)
       or exists (select from public.studio_tenant_scene_admin where tenant_id=a and member_id=a_reader)
    then raise exception 'Tenant A scene-admin capability mismatch'; end if;

    perform set_config('app.tenant_id',b::text,true);
    if not exists (select from public.studio_screen_player_reader g
        join public.studio_screen s using (tenant_id,screen_key)
        where g.tenant_id=b and g.member_id=b_reader and g.screen_key='floor' and s.retired_at is null)
        then raise exception 'Expected tenant B positive control'; end if;
end
$probe$;
rollback;
