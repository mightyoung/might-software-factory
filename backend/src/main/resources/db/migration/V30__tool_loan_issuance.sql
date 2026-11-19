-- Issued revision items are the occupancy facts. Earlier application revisions stay unoccupied.
alter table public.tool_loan drop constraint tool_loan_state_check;
alter table public.tool_loan add constraint tool_loan_state_check
    check (state in ('PENDING_ISSUE', 'WITHDRAWN', 'ISSUED'));
alter table public.tool_loan_revision drop constraint tool_loan_revision_state_check;
alter table public.tool_loan_revision add constraint tool_loan_revision_state_check
    check (state in ('PENDING_ISSUE', 'WITHDRAWN', 'ISSUED'));
alter table public.tool_loan_revision drop constraint tool_loan_revision_action_check;
alter table public.tool_loan_revision add constraint tool_loan_revision_action_check
    check (action in ('CREATE', 'UPDATE', 'WITHDRAW', 'ISSUE'));
alter table public.tool_loan_command_receipt drop constraint tool_loan_command_receipt_action_check;
alter table public.tool_loan_command_receipt add constraint tool_loan_command_receipt_action_check
    check (action in ('CREATE', 'UPDATE', 'WITHDRAW', 'ISSUE'));
alter table public.tool_loan_item
    add column issued_at timestamptz,
    add column settled_at timestamptz,
    add constraint tool_loan_item_settlement_time_check
        check (settled_at is null or issued_at is not null and settled_at >= issued_at);
create unique index tool_loan_item_active_occupancy
    on public.tool_loan_item (tenant_id, tool_id)
    where issued_at is not null and settled_at is null;

lock table public.permission in share row exclusive mode;
do $$
begin
    if exists(select 1 from public.permission where code = 'tool_loan.issue'
       and (resource_type is distinct from 'tool_loan' or action is distinct from 'issue'
            or application_key is not null and application_key <> 'tooling')) then
        raise exception 'loan issue permission conflicts with tooling application' using errcode = '23514';
    end if;
    insert into public.permission(id, code, resource_type, action, application_key, label, sort_order)
        values (gen_random_uuid(), 'tool_loan.issue', 'tool_loan', 'issue', 'tooling', '发放工装', 460)
    on conflict(code) do nothing;
    update public.permission
       set application_key = 'tooling',
           label = case when label is null or btrim(label) = '' then '发放工装' else label end,
           sort_order = case when sort_order = 0 then 460 else sort_order end
     where code = 'tool_loan.issue'
       and (application_key is null or label is null or btrim(label) = '' or sort_order = 0);
end $$;
