-- Pass loan, marker and tool from the browser evidence. No writes are performed.
\set ON_ERROR_STOP on
begin read only;
select set_config('test.tl10_loan', :'loan', true),
       set_config('test.tl10_marker', :'marker', true),
       set_config('test.tl10_tool', :'tool', true);
do $$
declare target_id uuid := current_setting('test.tl10_loan')::uuid;
        target_tool uuid := current_setting('test.tl10_tool')::uuid;
        marker text := current_setting('test.tl10_marker');
        target_tenant uuid;
begin
    if current_database() <> 'msf_d2_issue_test'
       or (inet_server_addr() is distinct from '127.0.0.1'::inet and (
           nullif(current_setting('test.tl10_owned_instance', true), '') is null or
           current_setting('msf.ci_instance', true) is distinct from current_setting('test.tl10_owned_instance', true)
       ))
       or marker !~ '^TL10-ISSUE-FIRST-[0-9a-f-]{36}$' then
        raise exception 'Dedicated loopback TL-10 fixture required';
    end if;
    select tenant_id into strict target_tenant from public.tool_loan
     where id=target_id and purpose=marker and state='ISSUED' and version=2;
    if (select count(*) from public.tool_loan_revision
         where tenant_id=target_tenant and loan_id=target_id) <> 2
       or not exists(select from public.tool_loan_revision
         where tenant_id=target_tenant and loan_id=target_id and version=2 and state='ISSUED' and action='ISSUE')
       or exists(select from public.tool_loan_revision
         where tenant_id=target_tenant and loan_id=target_id and action='WITHDRAW') then
        raise exception 'Unexpected issue/withdrawal revision effects';
    end if;
    if (select count(*) from public.tool_loan_item
         where tenant_id=target_tenant and loan_id=target_id and issued_at is not null and settled_at is null) <> 1
       or not exists(select from public.tool_loan_item
         where tenant_id=target_tenant and loan_id=target_id and version=2 and tool_id=target_tool
           and issued_at is not null and settled_at is null) then
        raise exception 'Missing or duplicate tool occupancy';
    end if;
    if (select count(*) from public.tool_loan_command_receipt r join public.audit_event a
          on a.tenant_id=r.tenant_id and a.id=r.event_id and a.resource_id=r.loan_id and a.result='SUCCESS'
         where r.tenant_id=target_tenant and r.loan_id=target_id and r.action='ISSUE'
           and r.result_version=2 and a.action='tool_loan.issue') <> 1
       or exists(select from public.tool_loan_command_receipt
         where tenant_id=target_tenant and loan_id=target_id and action='WITHDRAW')
       or exists(select from public.audit_event
         where tenant_id=target_tenant and resource_id=target_id and result='SUCCESS' and action='tool_loan.update') then
        raise exception 'Unexpected command receipt or successful audit';
    end if;
    if (select count(*) from public.workflow_binding
         where tenant_id=target_tenant and business_id=target_id) <> 1 then
        raise exception 'Expected precisely one workflow binding';
    end if;
end $$;
select 'PASS: issue preserved; no successful withdrawal or duplicate occupancy' as database_evidence;
rollback;
