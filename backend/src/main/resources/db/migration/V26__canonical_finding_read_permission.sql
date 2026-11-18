-- Fill the read capability omitted by V5. Keep any existing permission identity and grants.
-- A conflicting tuple is an operator-visible failure, never silently repaired.
do $$
begin
    if exists (select 1 from public.permission where code = 'finding.read'
               and (resource_type is distinct from 'finding' or action is distinct from 'read')) then
        raise exception 'finding.read has an incompatible definition' using errcode = '23514';
    end if;
    insert into public.permission (id, code, resource_type, action, application_key, label, sort_order)
    values (gen_random_uuid(), 'finding.read', 'finding', 'read', 'safety', '查看隐患', 10)
    on conflict (code) do nothing;
    update public.permission set application_key = 'safety', label = '查看隐患', sort_order = 10
     where code = 'finding.read';
end;
$$;
