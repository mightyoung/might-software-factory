-- Return requests retain occupancy until the keeper records a receipt for each selected item.
alter table public.tool_loan drop constraint tool_loan_state_check;
alter table public.tool_loan add constraint tool_loan_state_check
    check (state in ('PENDING_ISSUE', 'WITHDRAWN', 'ISSUED', 'SETTLED'));
alter table public.tool_loan_revision drop constraint tool_loan_revision_state_check;
alter table public.tool_loan_revision add constraint tool_loan_revision_state_check
    check (state in ('PENDING_ISSUE', 'WITHDRAWN', 'ISSUED', 'SETTLED'));
alter table public.tool_loan_revision drop constraint tool_loan_revision_action_check;
alter table public.tool_loan_revision add constraint tool_loan_revision_action_check
    check (action in ('CREATE', 'UPDATE', 'WITHDRAW', 'ISSUE', 'RETURN', 'RECEIVE'));
alter table public.tool_loan_command_receipt drop constraint tool_loan_command_receipt_action_check;
alter table public.tool_loan_command_receipt add constraint tool_loan_command_receipt_action_check
    check (action in ('CREATE', 'UPDATE', 'WITHDRAW', 'ISSUE', 'RETURN', 'RECEIVE'));


create or replace function public.guard_tool_loan_application_update() returns trigger language plpgsql as $$
begin
    if row(new.tenant_id,new.id,new.applicant_member_id,new.organization_id,new.custody_scope_id,
           new.definition_version,new.created_at)
       is distinct from row(old.tenant_id,old.id,old.applicant_member_id,old.organization_id,old.custody_scope_id,
           old.definition_version,old.created_at)
       or new.version <> old.version + 1
       or old.state not in ('PENDING_ISSUE','ISSUED')
       or old.state='ISSUED' and (new.state not in ('ISSUED','SETTLED') or new.purpose is distinct from old.purpose) then
        raise exception 'loan identity and completed revisions are protected' using errcode='23514';
    end if;
    return new;
end $$;

create table public.tool_return_request (
    tenant_id uuid not null,
    id uuid not null,
    loan_id uuid not null,
    created_by_member_id uuid not null,
    created_at timestamptz not null,
    primary key (tenant_id, id),
    unique (tenant_id, id, loan_id),
    foreign key (tenant_id, loan_id) references public.tool_loan(tenant_id, id) on delete restrict,
    foreign key (tenant_id, created_by_member_id) references public.tenant_member(tenant_id, id) on delete restrict
);
create table public.tool_return_request_item (
    tenant_id uuid not null,
    request_id uuid not null,
    loan_id uuid not null,
    tool_id uuid not null,
    received_at timestamptz,
    outcome varchar(16) check (outcome in ('NORMAL', 'DAMAGED')),
    reason varchar(4000),
    received_by_member_id uuid,
    command_id uuid,
    primary key (tenant_id, request_id, tool_id),
    foreign key (tenant_id, request_id, loan_id) references public.tool_return_request(tenant_id, id, loan_id) on delete restrict,
    foreign key (tenant_id, tool_id) references public.tool(tenant_id, id) on delete restrict,
    foreign key (tenant_id, received_by_member_id) references public.tenant_member(tenant_id, id) on delete restrict,
    constraint tool_return_receipt_complete check (
        (received_at is null and outcome is null and reason is null and received_by_member_id is null and command_id is null)
        or (received_at is not null and outcome is not null and received_by_member_id is not null and command_id is not null
            and (outcome <> 'DAMAGED' or reason is not null and length(btrim(reason)) > 0))
    )
);
create unique index tool_return_item_pending
    on public.tool_return_request_item(tenant_id, loan_id, tool_id) where received_at is null;

create function public.guard_tool_return_receipt() returns trigger language plpgsql as $$
begin
    if tg_op = 'DELETE' then
        raise exception 'return receipt history is protected' using errcode = '23514';
    end if;
    if old.received_at is not null or new.received_at is null
       or row(new.tenant_id,new.request_id,new.loan_id,new.tool_id)
          is distinct from row(old.tenant_id,old.request_id,old.loan_id,old.tool_id) then
        raise exception 'return receipt can only be recorded once' using errcode = '23514';
    end if;
    return new;
end $$;
create trigger tool_return_item_receipt before update or delete on public.tool_return_request_item
    for each row execute function public.guard_tool_return_receipt();
create trigger tool_return_request_append_only before update or delete on public.tool_return_request
    for each row execute function public.reject_ledger_history_mutation();
create trigger tool_return_request_no_truncate before truncate on public.tool_return_request
    for each statement execute function public.reject_ledger_history_mutation();
create trigger tool_return_item_no_truncate before truncate on public.tool_return_request_item
    for each statement execute function public.reject_ledger_history_mutation();

-- Revision contents remain immutable. Only the issued item's settlement timestamp may be completed once.
drop trigger tool_loan_item_append_only on public.tool_loan_item;
create function public.guard_tool_loan_item_settlement() returns trigger language plpgsql as $$
begin
    if tg_op = 'DELETE' then
        raise exception 'loan item history is protected' using errcode = '23514';
    end if;
    if old.issued_at is null or old.settled_at is not null or new.settled_at is null
       or row(new.tenant_id,new.loan_id,new.version,new.line_no,new.tool_id,new.issued_at)
          is distinct from row(old.tenant_id,old.loan_id,old.version,old.line_no,old.tool_id,old.issued_at)
       or not exists(select from public.tool_return_request_item r where r.tenant_id=new.tenant_id
          and r.loan_id=new.loan_id and r.tool_id=new.tool_id and r.received_at=new.settled_at) then
        raise exception 'loan item requires a matching immutable receipt' using errcode = '23514';
    end if;
    return new;
end $$;
create trigger tool_loan_item_settlement before update or delete on public.tool_loan_item
    for each row execute function public.guard_tool_loan_item_settlement();

create or replace function public.guard_tool_identity() returns trigger language plpgsql as $$
begin
    if row(new.tenant_id,new.id,new.code,new.custody_scope_id,new.organization_id,new.created_at,new.created_by_actor_id)
       is distinct from row(old.tenant_id,old.id,old.code,old.custody_scope_id,old.organization_id,old.created_at,old.created_by_actor_id)
       or new.version <> old.version + 1 then
        raise exception 'tool identity is protected and versions must advance' using errcode = '23514';
    end if;
    if new.item_state is distinct from old.item_state and (
       old.item_state <> 'NORMAL' or new.item_state <> 'DAMAGED'
       or not exists(select from public.tool_return_request_item r
          join public.tool_loan_item i on i.tenant_id=r.tenant_id and i.loan_id=r.loan_id and i.tool_id=r.tool_id
          where r.tenant_id=new.tenant_id and r.tool_id=new.id and r.outcome='DAMAGED'
            and r.received_at=new.updated_at and i.settled_at=r.received_at and i.issued_at is not null)) then
        raise exception 'tool state change requires a matching damaged receipt' using errcode = '23514';
    end if;
    return new;
end $$;
create function public.mark_received_tool_damaged(p_tenant uuid,p_tool uuid,p_at timestamptz,p_actor uuid)
    returns void language plpgsql security invoker as $$
begin
    if not exists(select from public.tool_return_request_item r
        join public.tool_loan_item i on i.tenant_id=r.tenant_id and i.loan_id=r.loan_id and i.tool_id=r.tool_id
        where r.tenant_id=p_tenant and r.tool_id=p_tool and r.outcome='DAMAGED'
          and r.received_at=p_at and i.settled_at=p_at and i.issued_at is not null) then
        raise exception 'damaged tool update requires a matching receipt' using errcode = '23514';
    end if;
    perform 1 from public.tool where tenant_id=p_tenant and id=p_tool and item_state in ('NORMAL','DAMAGED') for update;
    if not found then
        raise exception 'tool is unavailable for damaged receipt' using errcode = '23514';
    end if;
    update public.tool set item_state='DAMAGED',version=version+1,updated_at=p_at,updated_by_actor_id=p_actor
      where tenant_id=p_tenant and id=p_tool and item_state='NORMAL';
end $$;
revoke all on function public.mark_received_tool_damaged(uuid,uuid,timestamptz,uuid) from public;

do $$
declare relation text;
begin
    foreach relation in array array['tool_return_request','tool_return_request_item'] loop
        execute format('alter table public.%I enable row level security',relation);
        execute format('alter table public.%I force row level security',relation);
        execute format('create policy %I on public.%I using (tenant_id=nullif(current_setting(''app.tenant_id'',true),'''')::uuid) with check (tenant_id=nullif(current_setting(''app.tenant_id'',true),'''')::uuid)',relation||'_tenant_isolation',relation);
    end loop;
end $$;
lock table public.permission in share row exclusive mode;
do $$
begin
    if exists(select from public.permission where code='tool_loan.receive'
       and (resource_type is distinct from 'tool_loan' or action is distinct from 'receive'
            or application_key is not null and application_key <> 'tooling')) then
        raise exception 'loan receive permission conflicts with tooling application' using errcode='23514';
    end if;
    insert into public.permission(id,code,resource_type,action,application_key,label,sort_order)
        values(gen_random_uuid(),'tool_loan.receive','tool_loan','receive','tooling','接收归还工装',470)
        on conflict(code) do nothing;
    update public.permission set application_key='tooling',
       label=case when label is null or btrim(label)='' then '接收归还工装' else label end,
       sort_order=case when sort_order=0 then 470 else sort_order end
     where code='tool_loan.receive' and (application_key is null or label is null or btrim(label)='' or sort_order=0);
end $$;
