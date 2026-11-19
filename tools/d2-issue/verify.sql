-- Supply winner, loser and triple from issue-concurrency.mjs; run against the same dedicated database.
\set ON_ERROR_STOP on
begin read only;
select set_config('test.issue_winner', :'winner', true),
       set_config('test.issue_loser', :'loser', true),
       set_config('test.issue_triple', :'triple', true);
do $$
declare winner uuid := current_setting('test.issue_winner')::uuid;
        loser uuid := current_setting('test.issue_loser')::uuid;
        triple uuid := current_setting('test.issue_triple')::uuid;
begin
    if current_database()<>'msf_d2_issue_test' or winner=loser or winner=triple or loser=triple then
        raise exception 'Invalid issuance acceptance target';
    end if;
    if not exists(select from tool_loan where id=winner and state='ISSUED' and version=2)
       or (select count(*) from tool_loan where id in (loser,triple) and state='PENDING_ISSUE' and version=1)<>2 then
        raise exception 'Unexpected winner or rejected application state';
    end if;
    if (select count(*) from tool_loan_item where loan_id=winner and issued_at is not null and settled_at is null)<>1
       or exists(select from tool_loan_item where loan_id in (loser,triple) and issued_at is not null)
       or exists(select from tool_loan_revision where loan_id in (loser,triple) and action='ISSUE') then
        raise exception 'Partial or duplicate occupancy';
    end if;
    if (select count(*) from tool_loan_command_receipt r join audit_event a
            on a.tenant_id=r.tenant_id and a.id=r.event_id and a.resource_id=r.loan_id and a.result='SUCCESS'
            where r.loan_id=winner and r.action='ISSUE')<>1
       or exists(select from tool_loan_command_receipt where loan_id in (loser,triple) and action='ISSUE') then
        raise exception 'Unexpected issuance receipt or successful audit';
    end if;
    if (select count(*) from workflow_binding where business_id=winner)<>1
       or exists(select from workflow_binding where business_id in (loser,triple)) then
        raise exception 'Unexpected workflow binding';
    end if;
    if (select count(*) from workflow_binding b join act_ru_task task on task.proc_inst_id_=b.process_instance_id
            join tool_loan l on l.id=b.business_id and l.tenant_id=b.tenant_id
            where b.business_id=winner and task.task_def_key_='returnRequest'
              and task.assignee_=l.applicant_member_id::text)<>1
       or exists(select from workflow_binding b join act_ru_task task on task.proc_inst_id_=b.process_instance_id
            where b.business_id=winner and task.task_def_key_<>'returnRequest') then
        raise exception 'Issue did not reach the applicant return-request task';
    end if;
end $$;
rollback;
