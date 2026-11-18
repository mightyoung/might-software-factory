-- V27 preserved compatible legacy permission IDs but left a NULL application out of the admin catalog.
-- Converge catalog metadata only: preserve every existing ID and role association, and grant nothing.
lock table public.permission in share row exclusive mode;
do $$
begin
    if exists(select 1 from public.permission where code in ('tool.read','tool.create','tool.update')
      and (resource_type is distinct from 'tool' or action is distinct from substring(code from 6)
           or application_key is not null and application_key<>'tooling')) then
        raise exception 'tool permission definitions conflict with the registered application' using errcode='23514';
    end if;
    insert into public.permission(id,code,resource_type,action,application_key,label,sort_order) values
      (gen_random_uuid(),'tool.read','tool','read','tooling','查看工装档案',400),
      (gen_random_uuid(),'tool.create','tool','create','tooling','新增工装档案',410),
      (gen_random_uuid(),'tool.update','tool','update','tooling','修改工装档案',420)
    on conflict(code) do nothing;
    update public.permission p
       set application_key='tooling',
           label=case when p.label is null or btrim(p.label)='' then canonical.label else p.label end,
           -- Zero is V25's default for legacy rows without a catalog order; retain explicit nonzero order.
           sort_order=case when p.sort_order=0 then canonical.sort_order else p.sort_order end
      from (values ('tool.read','查看工装档案',400),('tool.create','新增工装档案',410),
                   ('tool.update','修改工装档案',420)) as canonical(code,label,sort_order)
     where p.code=canonical.code
       and (p.application_key is null or p.label is null or btrim(p.label)='' or p.sort_order=0);
end $$;
