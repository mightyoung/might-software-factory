-- Pass marker, loan and second from browser.mjs. Read-only assertions for that run.
\set ON_ERROR_STOP on
begin read only;
select set_config('test.d2_marker', :'marker', true),
       set_config('test.d2_loan', :'loan', true),
       set_config('test.d2_second', :'second', true);
do $$
declare
    first_id uuid := current_setting('test.d2_loan')::uuid;
    second_id uuid := current_setting('test.d2_second')::uuid;
    marker text := current_setting('test.d2_marker');
    line_counts int[];
begin
    if current_database() <> 'msf_d2_loans_test' or first_id = second_id then
        raise exception 'Invalid acceptance target';
    end if;
    if (select count(*) from tool_loan where purpose like marker || '%') <> 2
        or not exists(select from tool_loan where id = first_id and state = 'WITHDRAWN' and version = 3)
        or not exists(select from tool_loan where id = second_id and state = 'PENDING_ISSUE' and version = 1)
        or (select count(distinct applicant_member_id) from tool_loan where id in (first_id,second_id)) <> 2 then
        raise exception 'Unexpected applications, owners or states';
    end if;
    select array_agg(n order by version) into line_counts from (
        select version, count(*)::int n from tool_loan_item where loan_id = first_id group by version
    ) counts;
    if line_counts is distinct from array[3,2,2] or
        (select array_agg(tool_id order by line_no) from tool_loan_item where loan_id = first_id and version = 1)
        is distinct from
        (select array_agg(tool_id order by line_no) from tool_loan_item where loan_id = second_id and version = 1)
        or exists(select from tool_loan_item i join tool t on t.tenant_id = i.tenant_id and t.id = i.tool_id
            where i.loan_id in (first_id,second_id) and t.item_state <> 'NORMAL') then
        raise exception 'Unexpected detail history or tool condition';
    end if;
    if (select count(*) from tool_loan_revision where loan_id in (first_id,second_id)) <> 4
        or (select count(*) from tool_loan_command_receipt r join audit_event a on a.tenant_id = r.tenant_id
            and a.id = r.event_id and a.resource_id = r.loan_id and a.result = 'SUCCESS'
            join tool_loan_revision v on v.command_id = r.command_id and v.loan_id = r.loan_id
            and v.version = r.result_version where r.loan_id in (first_id,second_id)) <> 4 then
        raise exception 'Missing or extra successful commands';
    end if;
end $$;
rollback;
