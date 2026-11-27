begin;
do $$
begin
    delete from public.studio_screen_player_reader
    where tenant_id='10000000-0000-4000-8000-000000000001' and screen_key='floor'
      and member_id='10000000-0000-4000-8000-000000000002';
    if not found then raise exception 'A read assignment missing before revoke'; end if;
end $$;
commit;
