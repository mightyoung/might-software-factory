begin read only;
do $$
begin
  perform set_config('app.tenant_id','10000000-0000-4000-8000-000000000001',true);
  if exists(select from public.studio_screen_player_reader g join public.studio_screen s using(tenant_id,screen_key)
      where g.tenant_id='10000000-0000-4000-8000-000000000001' and g.screen_key='floor'
        and g.member_id='10000000-0000-4000-8000-000000000002' and s.retired_at is null)
    then raise exception 'Revoked assignment remains visible'; end if;
  perform set_config('app.tenant_id','20000000-0000-4000-8000-000000000001',true);
  if not exists(select from public.studio_screen_player_reader where tenant_id='20000000-0000-4000-8000-000000000001'
      and screen_key='floor' and member_id='20000000-0000-4000-8000-000000000002')
    then raise exception 'Independent B assignment was lost'; end if;
end $$;
rollback;
