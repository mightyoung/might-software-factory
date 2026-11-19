-- Independent grants keep ordinary borrower read access limited to their own applications.
lock table public.permission in share row exclusive mode;
do $$
declare resource text;
begin
    foreach resource in array array['tool_loan_department','tool_loan_custody'] loop
        if exists(select 1 from public.permission where code=resource||'.read'
            and (resource_type is distinct from resource or action is distinct from 'read'
                or application_key is not null and application_key<>'tooling')) then
            raise exception 'loan read scope permission conflicts with tooling application' using errcode='23514';
        end if;
    end loop;
    insert into public.permission(id,code,resource_type,action,application_key,label,sort_order) values
        (gen_random_uuid(),'tool_loan_department.read','tool_loan_department','read','tooling','查看授权部门工装借用单',490),
        (gen_random_uuid(),'tool_loan_custody.read','tool_loan_custody','read','tooling','查看负责保管范围借用单',500)
        on conflict(code) do nothing;
    update public.permission set application_key='tooling'
        where code in ('tool_loan_department.read','tool_loan_custody.read') and application_key is null;
end $$;
